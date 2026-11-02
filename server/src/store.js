import { randomUUID, createHash } from 'node:crypto';
import { ApiError, assert, idValid } from './errors.js';
import { evaluate, publicDecision } from './domain.js';

export const now = () => Date.now();
export const hash = value => createHash('sha256').update(value).digest('hex');
const clean = obj => Object.fromEntries(Object.entries(obj).filter(([,v]) => v !== undefined));

/** All persistent reads and writes here use Firestore, never a relational database. */
export class VibeStore {
  constructor(services) { this.s = services; this.db = services.db; }
  ref(collection, id) { assert(idValid(id), 422, 'Invalid resource ID.'); return this.db.collection(collection).doc(id); }
  async get(collection, id) { const s = await this.ref(collection, id).get(); return s.exists ? s.data() : null; }
  async put(collection, id, data) { await this.ref(collection, id).set(clean(data), { merge: true }); return this.get(collection, id); }
  async list(collection, field, value, op = '==') { const s = await this.db.collection(collection).where(field, op, value).get(); return s.docs.map(d => d.data()); }
  async transaction(fn) { return this.db.runTransaction(async tx => { const helper = {
    get: async (c, id) => { const s = await tx.get(this.ref(c, id)); return s.exists ? s.data() : null; },
    set: (c, id, data) => tx.set(this.ref(c, id), clean(data), { merge: true }),
    delete: (c, id) => tx.delete(this.ref(c, id)),
  }; return fn(helper); }); }
  async user(id) { const u = await this.get('users', id); assert(u, 404, 'Profile not found.'); return u; }
  async ensureUser(uid, displayName, email, photoUri = null) {
    const ref = this.ref('users', uid);
    return this.db.runTransaction(async tx => {
      const old = await tx.get(ref);
      if (old.exists) return old.data();
      const u = { id: uid, displayName: displayName || email.split('@')[0], username: email.split('@')[0].replace(/[^a-zA-Z0-9_]/g, '').slice(0, 30), email, language: 'en', themeMode: 'DARK', notificationsEnabled: true, privacyMembersOnly: true, photoUri, createdAtEpochMillis: now(), updatedAtEpochMillis: now() };
      tx.set(ref, u); return u;
    });
  }
  async member(groupId, uid, tx = null) {
    const g = tx ? await tx.get('groups', groupId) : await this.get('groups', groupId);
    assert(g, 404, 'Group not found.');
    assert(g.memberIds.includes(uid), 403, 'Join this group first.');
    return g;
  }
  async groupDto(g, uid) {
    const users = await Promise.all(g.memberIds.map(id => this.get('users', id)));
    const activities = await this.list('activities', 'groupId', g.id);
    return { id: g.id, name: g.name, icon: g.icon, inviteCode: g.inviteCode, ownerId: g.ownerId, createdAtEpochMillis: g.createdAtEpochMillis, role: g.ownerId === uid ? 'OWNER' : 'MEMBER', memberCount: g.memberIds.length, activityCount: activities.length,
      members: g.memberIds.map((id, i) => ({ userId: id, displayName: users[i]?.displayName || 'Member', role: id === g.ownerId ? 'OWNER' : 'MEMBER', photoUri: users[i]?.photoUri, joinedAtEpochMillis: g.members?.[id]?.joinedAtEpochMillis || 0 })) };
  }
  async groups(uid) { const rows = await this.list('groups', 'memberIds', uid, 'array-contains'); return Promise.all(rows.map(g => this.groupDto(g, uid))); }
  async createGroup(uid, payload) {
    const id = payload.clientId || randomUUID(); assert(idValid(id), 422, 'Invalid client ID.');
    const code = payload.inviteCode || Array.from({ length: 6 }, () => 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'[Math.floor(Math.random() * 32)]).join('');
    const result = await this.transaction(async tx => {
      const existing = await tx.get('groups', id);
      if (existing) { assert(existing.ownerId === uid, 403, 'This group belongs to another member.'); return existing; }
      const collision = await tx.get('invites', code); assert(!collision, 503, 'Invite code collision; retry this request.');
      const g = { id, name: payload.name.trim(), icon: payload.icon || '🎉', inviteCode: code, ownerId: uid, memberIds: [uid], members: { [uid]: { joinedAtEpochMillis: now() } }, createdAtEpochMillis: now(), updatedAtEpochMillis: now() };
      tx.set('groups', id, g); tx.set('invites', code, { groupId: id }); return g;
    });
    return this.groupDto(result, uid);
  }
  async join(uid, code) {
    const invite = await this.get('invites', code); assert(invite, 404, 'Invite code not found.');
    const g = await this.transaction(async tx => {
      const g = await tx.get('groups', invite.groupId); assert(g, 404, 'Group no longer exists.');
      if (!g.memberIds.includes(uid)) { g.memberIds.push(uid); g.members[uid] = { joinedAtEpochMillis: now() }; g.updatedAtEpochMillis = now(); tx.set('groups', g.id, g); }
      return g;
    });
    await this.notifyGroup(g.id, 'INVITE', uid, `${g.name}: a new member joined`);
    return this.groupDto(g, uid);
  }
  async activityDto(a, uid) { return { ...a, createdByName: (await this.user(a.createdBy)).displayName, favourite: (a.favouriteBy || []).includes(uid) }; }
  async activities(groupId, uid) { await this.member(groupId, uid); return Promise.all((await this.list('activities', 'groupId', groupId)).map(a => this.activityDto(a, uid))); }
  async saveActivity(uid, id, payload, create = false) {
    const a = await this.transaction(async tx => {
      const old = await tx.get('activities', id);
      const groupId = old?.groupId || payload.groupId;
      const g = await this.member(groupId, uid, tx);
      if (old) assert(old.createdBy === uid || g.ownerId === uid, 403, 'Only the contributor or owner can edit this idea.');
      const favouriteBy = new Set(old?.favouriteBy || []);
      if (payload.favourite !== undefined && payload.favourite !== null) payload.favourite ? favouriteBy.add(uid) : favouriteBy.delete(uid);
      const next = { ...(old || {}), id, groupId, title: payload.title?.trim() ?? old?.title, description: payload.description ?? old?.description ?? '', icon: payload.icon || old?.icon || '🎉', status: old?.status || 'SUGGESTED', createdBy: old?.createdBy || uid, createdAtEpochMillis: old?.createdAtEpochMillis || now(), updatedAtEpochMillis: now(), favouriteBy: [...favouriteBy], yesVotes: old?.yesVotes || 0, participantCount: g.memberIds.length };
      tx.set('activities', id, next); return next;
    });
    if (create) await this.notifyGroup(a.groupId, 'NEW_ACTIVITY', uid, a.title);
    return this.activityDto(a, uid);
  }
  async deleteActivity(uid, id) {
    await this.transaction(async tx => { const a = await tx.get('activities', id); if (!a) return; const g = await this.member(a.groupId, uid, tx); assert(a.createdBy === uid || g.ownerId === uid, 403, 'Only the contributor or owner can delete this idea.'); tx.delete('activities', id); });
  }
  async round(id, uid) {
    const d = await this.get('decisions', id); assert(d, 404, 'Round not found.');
    const g = await this.member(d.groupId, uid);
    const all = await this.list('activities', 'groupId', d.groupId);
    const activities = all.filter(a => d.activityIds.includes(a.id));
    const e = evaluate({ ...d, activities, memberIds: g.memberIds, votes: Object.values(d.votes || {}).flat(), now: now() });
    if (d.state === 'OPEN' && e.votesRevealed) await this.close(uid, id);
    const saved = await this.get('decisions', id);
    const result = publicDecision(saved, saved.state === d.state ? e : evaluate({ ...saved, activities: all.filter(a => saved.activityIds.includes(a.id)), memberIds: g.memberIds, votes: Object.values(saved.votes || {}).flat(), now: now() }));
    return { ...result, myVotes: saved.votes?.[uid] || [] };
  }
  async start(uid, groupId, payload) {
    const id = payload.clientId || randomUUID();
    await this.transaction(async tx => {
      if (await tx.get('decisions', id)) return;
      const g = await this.member(groupId, uid, tx); assert(g.ownerId === uid, 403, 'Only the group owner can start a round.');
      const activities = (await this.list('activities', groupId)).filter(a => ['SUGGESTED', 'ACTIVE'].includes(a.status) && (!payload.activityIds?.length || payload.activityIds.includes(a.id)));
      assert(activities.length >= 2, 422, 'Add at least two eligible ideas.');
      tx.set('decisions', id, { id, groupId, roundNumber: payload.roundNumber || 1, state: 'OPEN', deadlineEpochMillis: payload.deadlineEpochMillis || null, winnerActivityId: null, startedBy: uid, startedAtEpochMillis: now(), updatedAtEpochMillis: now(), activityIds: activities.map(a => a.id), votes: {} });
    });
    await this.notifyGroup(groupId, 'DEADLINE', uid, 'A new voting round is open.');
    return this.round(id, uid);
  }
  async vote(uid, id, payload) {
    await this.transaction(async tx => {
      const d = await tx.get('decisions', id); assert(d, 404, 'Round not found.');
      await this.member(d.groupId, uid, tx);
      assert(d.state === 'OPEN', 409, 'This round is closed.');
      assert(d.activityIds.includes(payload.activityId), 422, 'This idea is not in this round.');
      const previous = (d.votes?.[uid] || []).find(v => v.activityId === payload.activityId); if (previous) { assert(previous.choice === payload.choice, 409, 'Your vote is already recorded.'); return; }
      const timestamp = Math.min(payload.clientTimestampEpochMillis || now(), now());
      d.votes ||= {}; d.votes[uid] ||= [];
      d.votes[uid].push({ activityId: payload.activityId, choice: payload.choice, epochMillis: timestamp, userId: uid });
      d.updatedAtEpochMillis = now(); tx.set('decisions', id, d);
    });
    const r = await this.round(id, uid);
    return { voteId: `${id}:${payload.activityId}:${uid}`, decision: r };
  }
  async close(uid, id) {
    const groupId = await this.transaction(async tx => {
      const d = await tx.get('decisions', id); assert(d, 404, 'Round not found.');
      const g = await this.member(d.groupId, uid, tx);
      const activities = (await this.list('activities', d.groupId)).filter(a => d.activityIds.includes(a.id));
      const e = evaluate({ ...d, activities, memberIds: g.memberIds, votes: Object.values(d.votes || {}).flat(), now: now() });
      // A member may trigger automatic settlement, but only the owner may close early.
      if (!e.votesRevealed) assert(g.ownerId === uid, 403, 'Wait for every vote, or ask the owner to close the round.');
      d.state = e.outcome === 'continue' ? 'CLOSED' : 'SETTLED';
      d.winnerActivityId = e.winnerActivityId; d.updatedAtEpochMillis = now(); tx.set('decisions', id, d);
      for (const a of activities) {
        const status = e.winnerActivityId === a.id ? 'WINNER' : e.eliminatedIds.includes(a.id) ? 'ELIMINATED' : a.status === 'COMPLETED' ? 'COMPLETED' : 'ACTIVE';
        tx.set('activities', a.id, { ...a, status, yesVotes: e.tallies.find(t => t.activityId === a.id)?.yes || 0, updatedAtEpochMillis: now() });
      }
      return d.groupId;
    });
    const r = await this.round(id, uid);
    if (r.outcome === 'winner') await this.notifyGroup(groupId, 'WINNER', null, 'Your group has a winner. Plan it!');
    return r;
  }
  async savePlan(uid, payload, requestedId) {
    const id = payload.clientId || requestedId || randomUUID();
    const p = await this.transaction(async tx => {
      const a = await tx.get('activities', payload.activityId); assert(a, 404, 'Idea not found.');
      await this.member(a.groupId, uid, tx);
      const old = await tx.get('plans', id);
      const next = { ...(old || {}), id, groupId: a.groupId, activityId: a.id, activityTitle: a.title, activityIcon: a.icon, scheduledAtEpochMillis: payload.scheduledAtEpochMillis ?? old?.scheduledAtEpochMillis ?? null, completed: old?.completed || false, createdBy: old?.createdBy || uid, updatedAtEpochMillis: now() };
      tx.set('plans', id, next); return next;
    });
    return p;
  }
  async verifyPhotos(uid, groupId, uris) {
    for (const uri of uris || []) {
      assert(typeof uri === 'string' && uri.startsWith('vibe-blob://'), 422, 'Upload photos to blob storage before saving.');
      const blob = await this.get('blobs', uri.slice(12));
      assert(blob && blob.ownerId === uid && blob.groupId === groupId, 403, 'This photo does not belong to this group.');
    }
  }
  async complete(uid, planId, payload) {
    const plan = await this.get('plans', planId); assert(plan, 404, 'Plan not found.');
    await this.member(plan.groupId, uid); await this.verifyPhotos(uid, plan.groupId, payload.photoUris);
    const id = payload.clientId || hash(`${uid}:${planId}:memory`).slice(0, 32);
    const m = await this.transaction(async tx => {
      const old = await tx.get('memories', id);
      const next = { ...(old || {}), id, groupId: plan.groupId, planId, activityId: plan.activityId, activityTitle: plan.activityTitle, activityIcon: plan.activityIcon, completedAtEpochMillis: old?.completedAtEpochMillis || now(), caption: payload.caption ?? old?.caption ?? '', rating: payload.rating ?? old?.rating ?? 0, photoUris: payload.photoUris ?? old?.photoUris ?? [], createdBy: old?.createdBy || uid, updatedAtEpochMillis: now() };
      tx.set('memories', id, next); tx.set('plans', planId, { ...plan, completed: true, updatedAtEpochMillis: now() }); tx.set('activities', plan.activityId, { status: 'COMPLETED', updatedAtEpochMillis: now() }); return next;
    });
    if (payload.notify === true) await this.notifyGroup(m.groupId, 'MEMORY', uid, m.activityTitle);
    return m;
  }
  async notifyGroup(groupId, type, excludeUid, body) {
    const g = await this.get('groups', groupId); if (!g) return;
    for (const uid of g.memberIds.filter(id => id !== excludeUid)) {
      const u = await this.get('users', uid); if (!u?.notificationsEnabled) continue;
      const id = randomUUID();
      const n = { id, userId: uid, groupId, type, title: 'VIBE', body, read: false, createdAtEpochMillis: now(), pushPending: true };
      await this.put('notifications', id, n);
      try { await this.push(n); } catch (e) { console.error('Push delivery failed; inbox preserved:', e.code || e.name); }
    }
  }
  async push(n) {
    const devices = await this.list('devices', 'userId', n.userId);
    if (!devices.length) return;
    for (let offset=0;offset<devices.length;offset+=500) {
      const batch=devices.slice(offset,offset+500);
      const result = await this.s.messaging.sendEachForMulticast({ tokens: batch.map(d => d.id), data: { id: n.id, type: n.type, groupId: n.groupId || '', title: n.title, body: n.body } });
      await Promise.all(result.responses.map(async (r, i) => { if (!r.success && ['messaging/registration-token-not-registered', 'messaging/invalid-registration-token'].includes(r.error?.code)) await this.ref('devices', batch[i].id).delete(); }));
    }
    await this.put('notifications', n.id, { pushPending: false });
  }
  async flush(uid) {
    const rows = (await this.list('notifications', 'userId', uid)).filter(n => n.pushPending).slice(0, 20);
    for (const n of rows) { try { await this.push(n); } catch { /* retried on next inbox refresh */ } }
  }
  async sync(uid, item) {
    assert(idValid(item.clientId), 422, 'Invalid sync ID.');
    const receiptId = hash(`${uid}:${item.clientId}`);
    let payload;
    try { payload = JSON.parse(item.payloadJson); } catch { throw new ApiError(422, 'Invalid sync payload.'); }
    const result = await this.transaction(async tx => {
      const receipt = await tx.get('receipts', receiptId); if (receipt) return receipt;
      // Immutable votes and role-checked operations are validated inside the operation.
      const mutable = ['activity.update', 'plan.save', 'memory.save', 'profile.update'];
      if (mutable.includes(item.action) && !payload.force) {
        const [c, id] = item.action === 'profile.update' ? ['users', uid] : item.action === 'activity.update' ? ['activities', item.entityId] : item.action === 'memory.save' ? ['memories', item.entityId] : ['plans', item.entityId];
        const old = await tx.get(c, id);
        if (old?.updatedAtEpochMillis > item.queuedAtEpochMillis) return { clientId: item.clientId, status: 'conflict', serverPayload: JSON.stringify(old), message: 'Changed on another device.' };
      }
      // Delegate to the same validated operations, outside this receipt transaction.
      return null;
    });
    if (result) return result;
    let data;
    switch (item.action) {
      case 'group.create': data = await this.createGroup(uid, payload); break;
      case 'group.join': data = await this.join(uid, payload.inviteCode); break;
      case 'group.leave': data = await this.leave(uid, item.entityId); break;
      case 'activity.add': data = await this.saveActivity(uid, payload.clientId || item.entityId, payload, true); break;
      case 'activity.update': data = await this.saveActivity(uid, item.entityId, payload); break;
      case 'activity.delete': await this.deleteActivity(uid, item.entityId); data = { id: item.entityId, deleted: true }; break;
      case 'decision.start': data = await this.start(uid, payload.groupId, payload); break;
      case 'decision.vote': data = await this.vote(uid, item.entityId.split(':')[0], payload); break;
      case 'decision.close': data = await this.close(uid, item.entityId); break;
      case 'plan.save': data = await this.savePlan(uid, payload, item.entityId); break;
      case 'plan.complete': data = await this.complete(uid, payload.planId || item.entityId, { ...payload, clientId: payload.clientId || hash(item.clientId) }); break;
      case 'memory.save': data = await this.complete(uid, payload.planId, { ...payload, clientId: item.entityId, notify: false }); break;
      case 'profile.update': data = await this.updateProfile(uid, payload); break;
      default: throw new ApiError(422, 'Unknown sync action.');
    }
    const receipt = { id: receiptId, userId: uid, clientId: item.clientId, status: 'applied', serverPayload: JSON.stringify(data), appliedAtEpochMillis: now() };
    // Another retry may have applied the same idempotent operation concurrently.
    await this.ref('receipts', receiptId).create(receipt).catch(async e => { if (e.code !== 6) throw e; });
    return receipt;
  }
  async leave(uid, groupId) {
    await this.transaction(async tx => { const g = await this.member(groupId, uid, tx); assert(g.ownerId !== uid, 422, 'The owner cannot leave. Delete or transfer the group first.'); g.memberIds = g.memberIds.filter(id => id !== uid); delete g.members[uid]; tx.set('groups', groupId, g); }); return { id: groupId };
  }

  async deleteGroup(uid, groupId) {
    const g = await this.member(groupId, uid); assert(g.ownerId === uid, 403, 'Only the owner may delete a group.');
    for (const c of ['activities','decisions','plans','memories','notifications']) {
      const rows = await this.list(c, 'groupId', groupId);
      for (let i = 0; i < rows.length; i += 400) { const batch = this.db.batch(); rows.slice(i,i+400).forEach(row => batch.delete(this.ref(c,row.id))); await batch.commit(); }
    }
    const blobs = await this.list('blobs','groupId',groupId);
    for (const b of blobs) await this.s.bucket.file(b.object).delete({ ignoreNotFound: true });
    for (let i=0;i<blobs.length;i+=400) { const batch=this.db.batch(); blobs.slice(i,i+400).forEach(b=>batch.delete(this.ref('blobs',b.id))); await batch.commit(); }
    await this.ref('invites',g.inviteCode).delete(); await this.ref('groups',groupId).delete();
  }
  async deleteAccount(uid, currentPassword) {
    const account = await this.s.auth.getUser(uid);
    if (account.passwordHash) { assert(currentPassword,401,'Enter your current password.'); await this.s.login(account.email,currentPassword); }
    else assert(Date.now() - Date.parse(account.metadata.lastSignInTime) < 5*60*1000,401,'Sign in again before deleting your account.');
    const owned = await this.list('groups','ownerId',uid);
    assert(!owned.length,409,'Delete the groups you own first. Leave all other groups before deleting your account.');
    const groups = await this.list('groups','memberIds',uid,'array-contains');
    assert(!groups.length,409,'Leave your groups before deleting your account.');
    for (const c of ['notifications','devices']) {
      const rows = await this.list(c,'userId',uid);
      for (let i=0;i<rows.length;i+=400) { const batch=this.db.batch(); rows.slice(i,i+400).forEach(row=>batch.delete(this.ref(c,row.id))); await batch.commit(); }
    }
    await this.s.bucket.deleteFiles({ prefix: `private/${uid}/`, force: true });
    const blobs=await this.list('blobs','ownerId',uid);
    for (let i=0;i<blobs.length;i+=400) { const batch=this.db.batch(); blobs.slice(i,i+400).forEach(b=>batch.delete(this.ref('blobs',b.id))); await batch.commit(); }
    const receipts=await this.list('receipts','userId',uid);
    for (let i=0;i<receipts.length;i+=400) { const batch=this.db.batch(); receipts.slice(i,i+400).forEach(r=>batch.delete(this.ref('receipts',r.id))); await batch.commit(); }
    await this.ref('users',uid).delete(); await this.s.auth.deleteUser(uid);
  }

  async updateProfile(uid, p) {
    const old = await this.user(uid);
    const allowed = Object.fromEntries(Object.entries(p).filter(([k]) => ['displayName','username','language','themeMode','notificationsEnabled','privacyMembersOnly','photoUri'].includes(k)));
    if (allowed.photoUri) {
      assert(allowed.photoUri.startsWith('vibe-blob://'), 422, 'Upload the profile photo first.');
      const b = await this.get('blobs', allowed.photoUri.slice(12)); assert(b?.ownerId === uid && !b.groupId, 403, 'Not your profile photo.');
    }
    return this.put('users', uid, { ...old, ...allowed, updatedAtEpochMillis: now() });
  }
}
