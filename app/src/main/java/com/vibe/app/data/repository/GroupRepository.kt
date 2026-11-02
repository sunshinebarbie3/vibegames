package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.core.queuedOfflineResult
import com.vibe.app.core.toProblem
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.CreateGroupRequest
import com.vibe.app.data.remote.JoinGroupRequest
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.remote.apiResult
import com.vibe.app.data.remote.toDomain
import com.vibe.app.data.sync.SyncQueue
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.Group
import com.vibe.app.domain.GroupRole
import com.vibe.app.domain.Ids
import com.vibe.app.domain.InviteCode
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Groups, membership and the invite code.
 *
 * Groups are read from RoomDB (so the Home screen paints immediately, online or
 * not) and refreshed from the API in the background. Creating a group while
 * offline is allowed: the client generates the group UUID, the row is cached
 * straight away and the action is queued. Because the same UUID is sent to the
 * API, replaying the queue cannot create a duplicate group.
 */
class GroupRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val queue: SyncQueue,
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

    suspend fun refresh(): VibeResult<Int> =
        when (val result = apiResult { api.groups() }) {
            is ApiResult.Success -> {
                val now = time.nowMillis()
                if (database.syncQueueDao().count() > 0) return VibeResult.Ok(0)
                    result.data.forEach { dto ->
                    val group = dto.toDomain()
                    database.groupDao().upsert(group.toEntity(now))
                    database.groupDao().upsertMembers(group.members.map { it.toEntity(group.id) })
                }
                VibeResult.Ok(result.data.size)
            }

            is ApiResult.Failure -> result.toProblem()
        }

    suspend fun refreshGroup(groupId: String): VibeResult<Group> =
        when (val result = apiResult { api.group(groupId) }) {
            is ApiResult.Success -> {
                val group = result.data.toDomain()
                database.groupDao().upsert(group.toEntity(time.nowMillis()))
                database.groupDao().upsertMembers(group.members.map { it.toEntity(group.id) })
                VibeResult.Ok(group)
            }

            is ApiResult.Failure -> result.toProblem()
        }

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
                com.vibe.app.domain.GroupMember(
                    userId = ownerId,
                    displayName = ownerName,
                    role = GroupRole.OWNER,
                    joinedAt = now,
                ),
            ),
            syncState = SyncState.PENDING,
        )
        database.groupDao().upsert(local.toEntity(now))
        database.groupDao().upsertMembers(local.members.map { it.toEntity(groupId) })

        val payload = CreateGroupRequest(name = local.name, icon = local.icon, clientId = groupId, inviteCode = local.inviteCode)
        val queued = queue.enqueue(SyncAction.CREATE_GROUP, groupId, payload, CreateGroupRequest::class.java)

        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.createGroup(payload) }) {
            is ApiResult.Success -> {
                val server = result.data.toDomain()
                database.groupDao().upsert(server.toEntity(time.nowMillis()))
                database.groupDao().upsertMembers(server.members.map { it.toEntity(server.id) })
                VibeResult.Ok(server)
            }

            // Offline: the group exists locally and will be created on the API later.
            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK || result.kind == ApiErrorKind.SERVER) VibeResult.Ok(local.copy(syncState = SyncState.PENDING)) else { database.groupDao().delete(groupId); result.toProblem() }
        }
    }

    suspend fun joinGroup(inviteCode: String): VibeResult<Group> {
        Validators.inviteCode(inviteCode)?.let { return VibeResult.Problem(error = FieldError.INVITE_CODE_INVALID) }
        val code = InviteCode.normalise(inviteCode)
        return when (val result = apiResult { api.joinGroup(JoinGroupRequest(code)) }) {
            is ApiResult.Success -> {
                val group = result.data.toDomain()
                database.groupDao().upsert(group.toEntity(time.nowMillis()))
                database.groupDao().upsertMembers(group.members.map { it.toEntity(group.id) })
                VibeResult.Ok(group)
            }

            is ApiResult.Failure -> when (result.httpCode) {
                404 -> VibeResult.Problem(error = FieldError.INVITE_CODE_INVALID, kind = result.kind)
                else -> result.toProblem()
            }
        }
    }

    /** Looks up a code in the local cache so the join screen can confirm first. */
    suspend fun peekInviteCode(inviteCode: String): Group? {
        if (!InviteCode.isValid(inviteCode)) return null
        return database.groupDao().byInviteCode(InviteCode.normalise(inviteCode))?.toDomain()
    }

    suspend fun deleteGroup(groupId: String): VibeResult<Unit> = when (val r = apiResult { api.deleteGroup(groupId) }) {
        is ApiResult.Success -> { removeLocally(groupId); VibeResult.Ok(Unit) }
        is ApiResult.Failure -> r.toProblem()
    }

    suspend fun leaveGroup(groupId: String): VibeResult<Unit> {
        val payload = CreateGroupRequest(name = "", icon = "", clientId = groupId)
        val queued = queue.enqueue(SyncAction.LEAVE_GROUP, groupId, payload, CreateGroupRequest::class.java)
        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.leaveGroup(groupId) }) {
            is ApiResult.Success -> {
                removeLocally(groupId)
                VibeResult.Ok(Unit)
            }

            is ApiResult.Failure -> if (result.kind == com.vibe.app.data.remote.ApiErrorKind.NETWORK) {
                removeLocally(groupId)
                queuedOfflineResult()
            } else {
                result.toProblem()
            }
        }
    }

    private suspend fun removeLocally(groupId: String) {
        database.activityDao().deleteByGroup(groupId)
        database.groupDao().delete(groupId)
    }

}
