import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { rateLimit } from 'express-rate-limit';
import multer from 'multer';
import { fileTypeFromBuffer } from 'file-type';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { ApiError, assert, idValid } from './errors.js';
import { VibeStore, now } from './store.js';

const password = z.string().min(8).max(128).regex(/\d/, 'Include at least one digit.');
const email = z.email().max(254).transform(s => s.trim().toLowerCase());
const profile = z.object({ displayName: z.string().trim().min(1).max(40).optional(), username: z.string().trim().min(1).max(30).regex(/^[a-zA-Z0-9_]+$/).optional(), language: z.enum(['en','zu','st']).optional(), themeMode: z.enum(['DARK','LIGHT','SYSTEM']).optional(), notificationsEnabled: z.boolean().optional(), privacyMembersOnly: z.boolean().optional(), photoUri: z.string().max(200).nullable().optional() }).strict();
const groupSchema = z.object({ name: z.string().trim().min(1).max(60), icon: z.string().min(1).max(16), clientId: z.string().optional(), inviteCode: z.string().regex(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/).optional() });
const activitySchema = z.object({ title: z.string().trim().min(1).max(80), description: z.string().max(500).default(''), icon: z.string().max(16).default('🎉'), favourite: z.boolean().nullable().optional(), status: z.enum(['SUGGESTED','ACTIVE']).optional(), clientId: z.string().optional(), groupId: z.string().optional() });
const voteSchema = z.object({ activityId: z.string(), choice: z.boolean(), clientTimestampEpochMillis: z.number().int().nonnegative(), clientId: z.string() });
const memorySchema = z.object({ rating: z.number().int().min(0).max(5), caption: z.string().max(280), photoUris: z.array(z.string().max(200)).max(10).default([]), clientId: z.string().optional(), planId: z.string().optional(), groupId: z.string().optional() });

export function createApp(services, env = process.env) {
  const app = express(), store = new VibeStore(services);
  app.disable('x-powered-by');
  if (env.TRUST_PROXY === '1') app.set('trust proxy', 1);
  app.use(helmet());
  app.use(cors({ origin: env.ALLOWED_ORIGIN?.split(',') || false }));
  app.use(express.json({ limit: '256kb' }));
  app.use('/api', rateLimit({ windowMs: 60000, limit: 240, standardHeaders: 'draft-8', legacyHeaders: false }));
  const authLimit = rateLimit({ windowMs: 15 * 60000, limit: 30, standardHeaders: 'draft-8', legacyHeaders: false });
  const route = fn => (req, res, next) => Promise.resolve(fn(req, res)).catch(next);
  const middleware = async (req, res, next) => {
    try {
      const token = req.headers.authorization?.match(/^Bearer (.+)$/)?.[1];
      assert(token, 401, 'Please sign in.');
      const decoded = await services.verify(token);
      req.uid = decoded.uid; req.token = decoded; next();
    } catch (e) { next(e.status ? e : new ApiError(401, 'Your session expired. Please sign in again.')); }
  };
  const authResponse = async (uid, tokens, user) => ({ ...tokens, user: user || await store.user(uid) });
  const paramId = req => { assert(idValid(req.params.id), 422, 'Invalid resource ID.'); return req.params.id; };

  app.get('/health', (req, res) => res.json({ status: 'ok', service: 'vibe-firebase-api', database: 'firestore', storage: 'firebase-storage' }));
  app.post('/api/v1/auth/register', authLimit, route(async (req, res) => {
    const p = z.object({ displayName: z.string().trim().min(1).max(40), email, password }).parse(req.body);
    const account = await services.create(p.email, p.password, p.displayName);
    const user = await store.ensureUser(account.uid, p.displayName, p.email);
    res.status(201).json(await authResponse(account.uid, await services.sessionFor(account.uid), user));
  }));
  app.post('/api/v1/auth/login', authLimit, route(async (req, res) => {
    const p = z.object({ email, password: z.string().min(1).max(128) }).parse(req.body);
    const tokens = await services.login(p.email, p.password);
    const account = await services.verify(tokens.idToken);
    const user = await store.ensureUser(account.uid, account.name || p.email.split('@')[0], p.email);
    res.json({ accessToken: tokens.idToken, refreshToken: tokens.refreshToken, expiresAtEpochMillis: now() + Number(tokens.expiresIn) * 1000, user });
  }));
  app.post('/api/v1/auth/google', authLimit, route(async (req, res) => {
    const { idToken } = z.object({ idToken: z.string().min(20).max(12000) }).parse(req.body);
    const tokens = await services.googleLogin(idToken), identity = await services.verify(tokens.accessToken);
    const user = await store.ensureUser(tokens.uid, identity.name || identity.email?.split('@')[0] || 'Member', identity.email || '');
    res.json({ ...tokens, user });
  }));
  app.post('/api/v1/auth/refresh', authLimit, route(async (req, res) => {
    const p = z.object({ refreshToken: z.string().min(1).max(8192) }).parse(req.body);
    const tokens = await services.refresh(p.refreshToken);
    const identity = await services.verify(tokens.accessToken);
    res.json(await authResponse(identity.uid, tokens));
  }));
  app.post('/api/v1/auth/password-reset', authLimit, route(async (req, res) => {
    const p = z.object({ email }).parse(req.body); await services.reset(p.email); res.status(202).json({ message: 'If that account exists, a reset email has been sent.' });
  }));
  app.post('/api/v1/auth/logout', middleware, route(async (req, res) => { await services.auth.revokeRefreshTokens(req.uid); res.status(204).end(); }));
  app.post('/api/v1/auth/google/link', middleware, route(async (req, res) => {
    const { idToken } = z.object({ idToken: z.string().min(20).max(12000) }).parse(req.body);
    await services.linkGoogle(req.headers.authorization.slice(7), idToken); res.json(await store.user(req.uid));
  }));
  app.delete('/api/v1/auth/google/link', middleware, route(async (req, res) => {
    const current = await services.auth.getUser(req.uid);
    assert(current.passwordHash || current.providerData.some(p => p.providerId === 'password'), 409, 'Set a password before unlinking Google.');
    await services.unlinkGoogle(req.headers.authorization.slice(7)); res.status(204).end();
  }));
  app.get('/api/v1/auth/providers', middleware, route(async (req, res) => {
    const u = await services.auth.getUser(req.uid); res.json({ providers: u.providerData.map(p => p.providerId), hasPassword: Boolean(u.passwordHash) });
  }));

  app.use('/api/v1', middleware);
  app.get('/api/v1/users/me', route(async (req, res) => res.json(await store.user(req.uid))));
  app.delete('/api/v1/users/me', route(async (req, res) => { const { currentPassword } = z.object({ currentPassword: z.string().max(128).optional() }).parse(req.body || {}); await store.deleteAccount(req.uid, currentPassword); res.status(204).end(); }));
  app.patch('/api/v1/users/me', route(async (req, res) => res.json(await store.updateProfile(req.uid, profile.parse(req.body)))));
  app.post('/api/v1/users/me/password', authLimit, route(async (req, res) => {
    const p = z.object({ currentPassword: z.string().min(1).max(128), newPassword: password }).parse(req.body);
    const u = await services.auth.getUser(req.uid); assert(u.email, 422, 'No email account found.');
    await services.login(u.email, p.currentPassword);
    await services.auth.updateUser(req.uid, { password: p.newPassword });
    await services.auth.revokeRefreshTokens(req.uid);
    res.json(await authResponse(req.uid, await services.sessionFor(req.uid)));
  }));
  app.get('/api/v1/groups', route(async (req, res) => res.json(await store.groups(req.uid))));
  app.post('/api/v1/groups', route(async (req, res) => res.status(201).json(await store.createGroup(req.uid, groupSchema.parse(req.body)))));
  app.post('/api/v1/groups/join', route(async (req, res) => {
    const { inviteCode } = z.object({ inviteCode: z.string().regex(/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/) }).parse(req.body);
    res.json(await store.join(req.uid, inviteCode));
  }));
  app.get('/api/v1/groups/:id', route(async (req, res) => { const g = await store.member(paramId(req), req.uid); res.json(await store.groupDto(g, req.uid)); }));
  app.delete('/api/v1/groups/:id', route(async (req, res) => { await store.deleteGroup(req.uid, paramId(req)); res.status(204).end(); }));
  app.post('/api/v1/groups/:id/leave', route(async (req, res) => { await store.leave(req.uid, paramId(req)); res.status(204).end(); }));
  app.get('/api/v1/groups/:id/activities', route(async (req, res) => res.json(await store.activities(paramId(req), req.uid))));
  app.get('/api/v1/users/me/activities', route(async (req, res) => {
    const groups = await store.groups(req.uid); const rows = (await Promise.all(groups.map(g => store.activities(g.id, req.uid)))).flat(); res.json(rows.filter(a => a.createdBy === req.uid));
  }));
  app.post('/api/v1/groups/:id/activities', route(async (req, res) => {
    const p = activitySchema.parse(req.body), id = p.clientId || randomUUID(); assert(idValid(id), 422, 'Invalid idea ID.');
    await store.member(paramId(req), req.uid); res.status(201).json(await store.saveActivity(req.uid, id, { ...p, groupId: req.params.id }, true));
  }));
  app.patch('/api/v1/activities/:id', route(async (req, res) => res.json(await store.saveActivity(req.uid, paramId(req), activitySchema.partial().parse(req.body)))));
  app.delete('/api/v1/activities/:id', route(async (req, res) => { await store.deleteActivity(req.uid, paramId(req)); res.status(204).end(); }));
  app.post('/api/v1/groups/:id/decisions', route(async (req, res) => {
    const p = z.object({ activityIds: z.array(z.string()).max(100).default([]), deadlineEpochMillis: z.number().int().positive().nullable().optional(), clientId: z.string().optional(), groupId: z.string().optional(), roundNumber: z.number().int().min(1).max(5).optional() }).parse(req.body);
    res.status(201).json(await store.start(req.uid, paramId(req), p));
  }));
  app.get('/api/v1/decisions/:id', route(async (req, res) => res.json(await store.round(paramId(req), req.uid))));
  app.post('/api/v1/decisions/:id/votes', route(async (req, res) => res.json(await store.vote(req.uid, paramId(req), voteSchema.parse(req.body)))));
  app.post('/api/v1/decisions/:id/close', route(async (req, res) => res.json(await store.close(req.uid, paramId(req)))));
  app.post('/api/v1/decisions/:id/surprise', route(async (req, res) => {
    const p = z.object({ rejectedIds: z.array(z.string()).max(100).default([]) }).parse(req.body);
    const d = await store.round(paramId(req), req.uid); const choices = (await store.activities(d.groupId, req.uid)).filter(a => ['ACTIVE','SUGGESTED'].includes(a.status) && !p.rejectedIds.includes(a.id));
    assert(choices.length, 404, 'No eligible ideas remain.'); res.json(choices[Math.floor(Math.random() * choices.length)]);
  }));
  app.get('/api/v1/groups/:id/plans', route(async (req, res) => { await store.member(paramId(req), req.uid); res.json(await store.list('plans', 'groupId', req.params.id)); }));
  app.post('/api/v1/plans', route(async (req, res) => {
    const p = z.object({ groupId: z.string(), activityId: z.string(), scheduledAtEpochMillis: z.number().int().positive().nullable(), clientId: z.string().optional() }).parse(req.body);
    res.json(await store.savePlan(req.uid, p, p.clientId));
  }));
  app.post('/api/v1/plans/:id/complete', route(async (req, res) => res.json(await store.complete(req.uid, paramId(req), memorySchema.parse(req.body)))));
  app.patch('/api/v1/memories/:id', route(async (req, res) => { const m = await store.get('memories', paramId(req)); assert(m, 404, 'Memory not found.'); res.json(await store.complete(req.uid, m.planId, { ...memorySchema.parse(req.body), clientId: m.id })); }));
  app.get('/api/v1/groups/:id/memories', route(async (req, res) => { await store.member(paramId(req), req.uid); res.json(await store.list('memories', 'groupId', req.params.id)); }));
  app.get('/api/v1/notifications', route(async (req, res) => {
    await store.flush(req.uid); res.json((await store.list('notifications', 'userId', req.uid)).sort((a,b) => b.createdAtEpochMillis - a.createdAtEpochMillis).slice(0,100).map(({ pushPending, ...n }) => n));
  }));
  app.post('/api/v1/notifications/read-all', route(async (req, res) => { const rows = await store.list('notifications', 'userId', req.uid); await Promise.all(rows.map(n => store.put('notifications', n.id, { read: true }))); res.status(204).end(); }));
  app.post('/api/v1/notifications/:id/read', route(async (req, res) => { const n = await store.get('notifications', paramId(req)); assert(n?.userId === req.uid, 404, 'Notification not found.'); await store.put('notifications', n.id, { read: true }); res.status(204).end(); }));
  app.post('/api/v1/devices/register', route(async (req, res) => {
    const p = z.object({ fcmToken: z.string().min(20).max(4096), platform: z.literal('android') }).parse(req.body);
    const deviceId = (await import('node:crypto')).createHash('sha256').update(p.fcmToken).digest('hex');
    await store.put('devices', deviceId, { id: deviceId, token: p.fcmToken, userId: req.uid, platform: p.platform, updatedAtEpochMillis: now() }); res.status(204).end();
  }));
  app.post('/api/v1/devices/unregister', route(async (req, res) => { const p = z.object({ fcmToken: z.string().min(20).max(4096) }).parse(req.body); const deviceId = (await import('node:crypto')).createHash('sha256').update(p.fcmToken).digest('hex'); const d = await store.get('devices', deviceId); if (d?.userId === req.uid) await store.ref('devices', d.id).delete(); res.status(204).end(); }));

  const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 10 * 1024 * 1024, files: 1 } });
  app.post('/api/v1/media', upload.single('photo'), route(async (req, res) => {
    assert(req.file, 422, 'Choose a photo.');
    const type = await fileTypeFromBuffer(req.file.buffer);
    assert(type && ['image/jpeg','image/png','image/webp'].includes(type.mime), 415, 'Only JPEG, PNG and WebP photos are supported.');
    const groupId = req.body.groupId || null;
    if (groupId) await store.member(groupId, req.uid);
    const id = randomUUID(), object = `private/${req.uid}/${groupId || 'profile'}/${id}.${type.ext}`;
    const file = services.bucket.file(object);
    await file.save(req.file.buffer, { resumable: false, contentType: type.mime, metadata: { cacheControl: 'private, max-age=300', metadata: { ownerId: req.uid, groupId: groupId || '' } } });
    await store.put('blobs', id, { id, ownerId: req.uid, groupId, object, contentType: type.mime, createdAtEpochMillis: now() });
    res.status(201).json({ id, uri: `vibe-blob://${id}` });
  }));
  app.get('/api/v1/media/:id', route(async (req, res) => {
    const blob = await store.get('blobs', paramId(req)); assert(blob, 404, 'Photo not found.');
    if (blob.groupId) await store.member(blob.groupId, req.uid);
    else if (blob.ownerId !== req.uid) {
      const owner = await store.user(blob.ownerId);
      const viewerGroups = await store.groups(req.uid);
      const ownerGroups = await store.groups(blob.ownerId);
      assert(!owner.privacyMembersOnly || viewerGroups.some(g => ownerGroups.some(o => o.id === g.id)), 403, 'This profile photo is private.');
    }
    res.setHeader('Content-Type', blob.contentType); res.setHeader('Cache-Control', 'private, max-age=300');
    const stream = services.bucket.file(blob.object).createReadStream(); stream.on('error', () => { if (!res.headersSent) res.status(404).json({ error: 'Photo unavailable.' }); else res.end(); }); stream.pipe(res);
  }));
  app.post('/api/v1/sync/batch', route(async (req, res) => {
    const p = z.object({ items: z.array(z.object({ clientId: z.string().max(250), action: z.string().max(50), entityId: z.string().max(250), payloadJson: z.string().max(60000), queuedAtEpochMillis: z.number().int().nonnegative() })).max(50) }).parse(req.body);
    const results = [];
    for (const item of p.items) {
      try { const { appliedAtEpochMillis, ...result } = await store.sync(req.uid, item); results.push(result); }
      catch (e) {
        if (!e.status || e.status >= 500) throw e;
        results.push({ clientId: item.clientId, status: 'rejected', message: e.status === 422 ? e.message : 'This change is no longer allowed.', serverPayload: null });
      }
    }
    res.json({ results });
  }));
  app.use((req, res) => res.status(404).json({ error: 'Endpoint not found.' }));
  app.use((error, req, res, next) => {
    if (res.headersSent) return next(error);
    if (error instanceof z.ZodError) return res.status(422).json({ error: 'Please check the highlighted field.', field: error.issues[0]?.path?.[0], details: error.issues.map(i => ({ field: i.path.join('.'), message: i.message })) });
    if (error instanceof multer.MulterError) return res.status(413).json({ error: 'Photo must be smaller than 10 MB.' });
    const status = error.status || (error.code === 6 ? 409 : 500);
    if (status >= 500) console.error('API request failed:', error.stack || error, req.path);
    res.status(status).json({ error: status >= 500 ? 'The service is temporarily unavailable. Your changes remain saved on this device.' : error.message, ...(error.field ? { field: error.field } : {}) });
  });
  return app;
}
