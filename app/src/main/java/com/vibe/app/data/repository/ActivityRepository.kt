package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.domain.Activity
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.Ids
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * The shared Vibe List: the idea library every member contributes to.
 *
 * Activities are written directly to Firestore and cached in Room. Additions
 * are written to Room immediately for offline-first behavior and synced to
 * Firestore via its built-in persistence when the connection returns.
 */
class ActivityRepository(
    private val firestore: FirestoreProvider,
    private val database: VibeDatabase,
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
        runCatching {
            val serverActivities = firestore.getActivitiesForGroup(groupId)
            withContext(Dispatchers.IO) {
                database.activityDao().upsert(serverActivities.map { it.toEntity() })
            }
            VibeResult.Ok(serverActivities.size)
        }.getOrElse { VibeResult.Ok(0) }

    suspend fun refreshMine(): VibeResult<Int> =
        runCatching { VibeResult.Ok(0) }.getOrElse { VibeResult.Ok(0) }

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

        return runCatching {
            firestore.createActivity(local)
            withContext(Dispatchers.IO) {
                database.activityDao().upsert(local.copy(syncState = SyncState.SYNCED).toEntity())
            }
            VibeResult.Ok(local)
        }.getOrElse {
            VibeResult.Ok(local.copy(syncState = SyncState.PENDING))
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

        return runCatching {
            firestore.updateActivity(updated)
            withContext(Dispatchers.IO) {
                database.activityDao().upsert(updated.copy(syncState = SyncState.SYNCED).toEntity())
            }
            VibeResult.Ok(updated)
        }.getOrElse {
            VibeResult.Ok(updated)
        }
    }

    suspend fun setFavourite(activity: Activity, favourite: Boolean): VibeResult<Activity> =
        updateActivity(activity.copy(favourite = favourite), activity.title, activity.description, activity.icon)

    suspend fun markForDecision(activity: Activity): VibeResult<Activity> =
        updateActivity(
            activity.copy(status = ActivityStatus.ACTIVE),
            activity.title,
            activity.description,
            activity.icon,
        )

    suspend fun deleteActivity(activityId: String): VibeResult<Unit> {
        database.activityDao().delete(activityId)
        return runCatching {
            firestore.deleteActivity(activityId)
            VibeResult.Ok(Unit)
        }.getOrElse { VibeResult.Ok(Unit) }
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

    private companion object {
        // Validators.activityTitle is defined in domain.Validators.
    }
}
