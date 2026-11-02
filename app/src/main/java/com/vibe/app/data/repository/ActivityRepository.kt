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
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.UpsertActivityRequest
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.remote.apiResult
import com.vibe.app.data.remote.toDomain
import com.vibe.app.data.sync.SyncQueue
import com.vibe.app.domain.Activity
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.Ids
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The shared Vibe List: the idea library every member contributes to.
 *
 * Additions and edits are optimistic - RoomDB first, then the API - so the list
 * keeps working in a lecture hall with no signal, and the queued action carries
 * the timestamp of when the member actually typed the idea.
 */
class ActivityRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val queue: SyncQueue,
    private val settings: SettingsStore,
    private val time: TimeProvider,
) {

    fun observeForGroup(groupId: String): Flow<List<Activity>> =
        database.activityDao().observeByGroup(groupId).map { rows -> rows.map { it.toDomain() } }

    fun observeMine(userId: String): Flow<List<Activity>> =
        database.activityDao().observeMine(userId).map { rows -> rows.map { it.toDomain() } }

    fun observeAll(): Flow<List<Activity>> =
        database.activityDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeById(activityId: String): Flow<Activity?> =
        database.activityDao().observeById(activityId).map { it?.toDomain() }

    suspend fun refresh(groupId: String): VibeResult<Int> =
        when (val result = apiResult { api.activities(groupId) }) {
            is ApiResult.Success -> {
                if (database.syncQueueDao().count() == 0) database.activityDao().upsert(result.data.map { it.toDomain().toEntity() })
                VibeResult.Ok(result.data.size)
            }

            is ApiResult.Failure -> result.toProblem()
        }

    suspend fun refreshMine(): VibeResult<Int> =
        when (val result = apiResult { api.myActivities() }) {
            is ApiResult.Success -> {
                if (database.syncQueueDao().count() == 0) database.activityDao().upsert(result.data.map { it.toDomain().toEntity() })
                VibeResult.Ok(result.data.size)
            }

            is ApiResult.Failure -> result.toProblem()
        }

    suspend fun addActivity(
        groupId: String,
        title: String,
        description: String,
        icon: String,
    ): VibeResult<Activity> {
        Validators.activityTitle(title)?.let { return VibeResult.Problem(error = it) }
        val userId = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in")
        val user = database.userDao().byId(userId)
        val now = time.nowMillis()

        val local = Activity(
            id = Ids.newId(),
            groupId = groupId,
            title = title.trim(),
            description = description.trim(),
            icon = icon,
            status = ActivityStatus.SUGGESTED,
            createdBy = userId,
            createdByName = user?.displayName.orEmpty(),
            createdAt = now,
            syncState = SyncState.PENDING,
        )
        database.activityDao().upsert(local.toEntity())
        refreshGroupCounts(groupId)

        val payload = UpsertActivityRequest(
            title = local.title,
            description = local.description,
            icon = local.icon,
            clientId = local.id,
            groupId = local.groupId,
        )
        val queued = queue.enqueue(SyncAction.ADD_ACTIVITY, local.id, payload, UpsertActivityRequest::class.java)

        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.addActivity(groupId, payload) }) {
            is ApiResult.Success -> {
                val saved = result.data.toDomain()
                database.activityDao().upsert(saved.toEntity())
                VibeResult.Ok(saved)
            }

            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK || result.kind == ApiErrorKind.SERVER) VibeResult.Ok(local) else result.toProblem()
        }
    }

    suspend fun updateActivity(
        activity: Activity,
        title: String,
        description: String,
        icon: String,
    ): VibeResult<Activity> {
        Validators.activityTitle(title)?.let { return VibeResult.Problem(error = it) }
        val updated = activity.copy(
            title = title.trim(),
            description = description.trim(),
            icon = icon,
            syncState = SyncState.PENDING,
        )
        database.activityDao().upsert(updated.toEntity())

        val payload = UpsertActivityRequest(
            title = updated.title,
            description = updated.description,
            icon = updated.icon,
            favourite = updated.favourite,
            status = updated.status.name,
            clientId = updated.id,
            groupId = updated.groupId,
        )
        val queued = queue.enqueue(SyncAction.UPDATE_ACTIVITY, updated.id, payload, UpsertActivityRequest::class.java)

        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.updateActivity(updated.id, payload) }) {
            is ApiResult.Success -> {
                val saved = result.data.toDomain()
                database.activityDao().upsert(saved.toEntity())
                VibeResult.Ok(saved)
            }

            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK) {
                VibeResult.Ok(updated)
            } else {
                result.toProblem()
            }
        }
    }

    suspend fun setFavourite(activity: Activity, favourite: Boolean): VibeResult<Activity> {
        database.activityDao().updateFavourite(activity.id, favourite)
        return updateActivity(activity.copy(favourite = favourite), activity.title, activity.description, activity.icon)
    }

    /** Used by the "Add to Decide For Us" action on the activity detail screen. */
    suspend fun markForDecision(activity: Activity): VibeResult<Activity> =
        updateActivity(
            activity.copy(status = ActivityStatus.ACTIVE),
            activity.title,
            activity.description,
            activity.icon,
        )

    suspend fun deleteActivity(activityId: String): VibeResult<Unit> {
        database.activityDao().delete(activityId)
        val payload = UpsertActivityRequest(title = "", icon = "")
        val queued = queue.enqueue(SyncAction.DELETE_ACTIVITY, activityId, payload, UpsertActivityRequest::class.java)
        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.deleteActivity(activityId) }) {
            is ApiResult.Success -> VibeResult.Ok(Unit)
            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK) {
                queuedOfflineResult()
            } else {
                result.toProblem()
            }
        }
    }

    suspend fun eligibleForRound(groupId: String): List<Activity> =
        database.activityDao().eligibleForRound(groupId).map { it.toDomain() }

    private suspend fun refreshGroupCounts(groupId: String) {
        database.groupDao().updateCounts(
            groupId,
            members = database.groupDao().memberCount(groupId),
            activities = database.activityDao().countByGroup(groupId),
        )
    }
}
