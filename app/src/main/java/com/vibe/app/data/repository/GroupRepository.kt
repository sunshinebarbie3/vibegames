package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.Group
import com.vibe.app.domain.GroupMember
import com.vibe.app.domain.GroupRole
import com.vibe.app.domain.Ids
import com.vibe.app.domain.InviteCode
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Groups, membership and the invite code.
 *
 * Groups are written directly to Firestore and cached in Room for offline access.
 * Creating a group while offline is allowed: the client generates the group UUID,
 * the row is cached straight away and written to Firestore when the connection
 * returns (Firestore persistence handles the replay automatically).
 */
class GroupRepository(
    private val firestore: FirestoreProvider,
    private val database: VibeDatabase,
    private val settings: SettingsStore,
    private val time: TimeProvider,
) {

    val groups: Flow<List<Group>> =
        database.groupDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeGroup(groupId: String): Flow<Group?> = combine(
        database.groupDao().observeById(groupId),
        database.groupDao().observeMembers(groupId),
        database.activityDao().observeByGroup(groupId),
    ) { entity, members, activities ->
        entity?.toDomain(members)?.copy(
            memberCount = members.size.coerceAtLeast(1),
            activityCount = activities.size,
        )
    }

    suspend fun refresh(): VibeResult<Int> {
        val uid = settings.currentUserId() ?: return VibeResult.Ok(0)
        return runCatching {
            val serverGroups = firestore.getGroupsForUser(uid)
            withContext(Dispatchers.IO) {
                serverGroups.forEach { dto ->
                    database.groupDao().upsert(dto.toEntity(time.nowMillis()))
                    database.groupDao().upsertMembers(dto.members.map { it.toEntity(dto.id) })
                }
            }
            VibeResult.Ok(serverGroups.size)
        }.getOrElse { VibeResult.Ok(0) }
    }

    suspend fun refreshGroup(groupId: String): VibeResult<Group> =
        runCatching {
            val group = firestore.getGroup(groupId, settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in.")) ?: return VibeResult.Problem(detail = "Group not found.")
            withContext(Dispatchers.IO) {
                database.groupDao().upsert(group.toEntity(time.nowMillis()))
                database.groupDao().upsertMembers(group.members.map { it.toEntity(group.id) })
            }
            VibeResult.Ok(group)
        }.getOrElse { VibeResult.Problem(detail = "Could not refresh group.") }

    suspend fun createGroup(name: String, icon: String): VibeResult<Group> {
        Validators.groupName(name)?.let { return VibeResult.Problem(error = it) }
        val ownerId = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in")
        val ownerName = database.userDao().byId(ownerId)?.displayName.orEmpty()
        val now = time.nowMillis()
        val groupId = Ids.newId()

        val local = Group(
            id = groupId,
            name = name.trim(),
            icon = icon,
            inviteCode = InviteCode.generate(),
            ownerId = ownerId,
            createdAt = now,
            role = GroupRole.OWNER,
            memberCount = 1,
            activityCount = 0,
            members = listOf(
                GroupMember(
                    userId = ownerId,
                    displayName = ownerName,
                    role = GroupRole.OWNER,
                    joinedAt = now,
                ),
            ),
            syncState = SyncState.PENDING,
        )
        return runCatching {
            val saved = firestore.createGroup(local).copy(syncState = SyncState.SYNCED)
            database.groupDao().upsert(saved.toEntity(now))
            database.groupDao().upsertMembers(saved.members.map { it.toEntity(groupId) })
            VibeResult.Ok(saved)
        }.getOrElse {
            VibeResult.Problem(detail = it.message ?: "Could not save the group.")
        }
    }

    suspend fun joinGroup(inviteCode: String): VibeResult<Group> {
        Validators.inviteCode(inviteCode)?.let { return VibeResult.Problem(error = FieldError.INVITE_CODE_INVALID) }
        val code = InviteCode.normalise(inviteCode)
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in")
        val user = database.userDao().byId(uid)
        val displayName = user?.displayName.orEmpty()

        // Look up the invite code locally first for instant feedback.
        val cached = database.groupDao().byInviteCode(code)
        if (cached != null) {
            return runCatching {
                VibeResult.Ok(firestore.joinGroup(cached.id, uid, displayName))
            }.getOrElse {
                VibeResult.Problem(detail = it.message ?: "Could not join the group.")
            }
        }

        return VibeResult.Problem(detail = "Invite code not found locally. Go online and try again.")
    }

    /** Looks up a code in the local cache so the join screen can confirm first. */
    suspend fun peekInviteCode(inviteCode: String): Group? {
        if (!InviteCode.isValid(inviteCode)) return null
        return database.groupDao().byInviteCode(InviteCode.normalise(inviteCode))?.toDomain()
    }

    suspend fun deleteGroup(groupId: String): VibeResult<Unit> =
        runCatching {
            firestore.deleteGroup(groupId)
            withContext(Dispatchers.IO) {
                database.activityDao().deleteByGroup(groupId)
                database.groupDao().delete(groupId)
            }
            VibeResult.Ok(Unit)
        }.getOrElse { VibeResult.Problem(detail = it.message ?: "Could not delete group.") }

    suspend fun leaveGroup(groupId: String): VibeResult<Unit> {
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in")
        return runCatching {
            firestore.leaveGroup(groupId, uid)
            withContext(Dispatchers.IO) {
                database.activityDao().deleteByGroup(groupId)
                database.groupDao().delete(groupId)
            }
            VibeResult.Ok(Unit)
        }.getOrElse { VibeResult.Problem(detail = it.message ?: "Could not leave group.") }
    }
}
