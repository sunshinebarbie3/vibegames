package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.core.toProblem
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.CompletePlanRequest
import com.vibe.app.data.remote.SavePlanRequest
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.remote.apiResult
import com.vibe.app.data.remote.toDomain
import com.vibe.app.data.sync.SyncQueue
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.Ids
import com.vibe.app.domain.Memory
import com.vibe.app.domain.Plan
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Plans and Memories: setting the date, marking a plan done, and the photos,
 * caption and group rating that make the memory.
 *
 * Selected photos are copied into private app storage immediately. The sync queue
 * uploads them to private Firebase Storage through the REST API before saving
 * their opaque blob references in Firestore.
 */
class MemoryRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val queue: SyncQueue,
    private val settings: SettingsStore,
    private val time: TimeProvider,
    private val media: MediaRepository? = null,
) {

    val memories: Flow<List<Memory>> =
        database.memoryDao().observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeForGroup(groupId: String): Flow<List<Memory>> =
        database.memoryDao().observeByGroup(groupId).map { rows -> rows.map { it.toDomain() } }

    fun observeMemory(memoryId: String): Flow<Memory?> =
        database.memoryDao().observeById(memoryId).map { it?.toDomain() }

    fun observePlans(groupId: String): Flow<List<Plan>> =
        database.planDao().observeByGroup(groupId).map { rows -> rows.map { it.toDomain() } }

    suspend fun refreshMemories(groupId: String): VibeResult<Int> =
        when (val result = apiResult { api.memories(groupId) }) {
            is ApiResult.Success -> {
                if (database.syncQueueDao().count() == 0) database.memoryDao().upsert(result.data.map { it.toDomain().toEntity() })
                VibeResult.Ok(result.data.size)
            }

            is ApiResult.Failure -> result.toProblem()
        }

    suspend fun refreshPlans(groupId: String): VibeResult<Int> =
        when (val result = apiResult { api.plans(groupId) }) {
            is ApiResult.Success -> {
                if (database.syncQueueDao().count() == 0) result.data.forEach { database.planDao().upsert(it.toDomain().toEntity()) }
                VibeResult.Ok(result.data.size)
            }

            is ApiResult.Failure -> result.toProblem()
        }

    /** "Plan it": the winner gets a date and time. */
    suspend fun savePlan(groupId: String, activityId: String, scheduledAtEpochMillis: Long?): VibeResult<Plan> {
        val userId = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in")
        val activity = database.activityDao().byId(activityId)
            ?: return VibeResult.Problem(detail = "Activity not found")
        val existing = database.planDao().latestForActivity(activityId)

        val plan = Plan(
            id = existing?.id ?: Ids.newId(),
            groupId = groupId,
            activityId = activityId,
            activityTitle = activity.title,
            activityIcon = activity.icon,
            scheduledAtEpochMillis = scheduledAtEpochMillis,
            completed = existing?.completed ?: false,
            createdBy = userId,
        )
        database.planDao().upsert(plan.toEntity())

        val payload = SavePlanRequest(groupId, activityId, scheduledAtEpochMillis, plan.id)
        val queued = queue.enqueue(SyncAction.SAVE_PLAN, plan.id, payload, SavePlanRequest::class.java)

        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.savePlan(payload) }) {
            is ApiResult.Success -> {
                val saved = result.data.toDomain()
                database.planDao().upsert(saved.toEntity())
                VibeResult.Ok(saved)
            }

            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK || result.kind == ApiErrorKind.SERVER) VibeResult.Ok(plan) else result.toProblem()
        }
    }

    /** "Mark as done" plus caption, rating and photos: this creates the memory. */
    suspend fun completePlan(
        planId: String,
        rating: Int,
        caption: String,
        photoUris: List<String> = emptyList(),
    ): VibeResult<Memory> {
        Validators.rating(rating)?.let { return VibeResult.Problem(error = it) }
        Validators.caption(caption)?.let { return VibeResult.Problem(error = it) }
        val userId = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in")
        val persistedPhotos = photoUris.map { media?.photos?.persist(it) ?: it }
        val plan = database.planDao().byId(planId) ?: return VibeResult.Problem(detail = "Plan not found")

        val now = time.nowMillis()
        val memory = Memory(
            id = Ids.newId(),
            groupId = plan.groupId,
            planId = plan.id,
            activityId = plan.activityId,
            activityTitle = plan.activityTitle,
            activityIcon = plan.activityIcon,
            completedAtEpochMillis = now,
            caption = caption.trim(),
            rating = rating,
            photoUris = persistedPhotos,
            createdBy = userId,
            syncState = SyncState.PENDING,
        )

        database.planDao().upsert(plan.copy(completed = true))
        database.activityDao().updateStatus(plan.activityId, ActivityStatus.COMPLETED.name)
        database.memoryDao().upsert(memory.toEntity())

        val payload = CompletePlanRequest(
            rating = rating,
            caption = memory.caption,
            photoUris = persistedPhotos,
            planId = planId,
            groupId = memory.groupId,
            clientId = memory.id,
        )
        val queued = queue.enqueue(SyncAction.COMPLETE_PLAN, planId, payload, CompletePlanRequest::class.java)
        val requestPayload = if (queue.isOnline()) payload.copy(photoUris = media?.uploadAll(persistedPhotos, memory.groupId) ?: persistedPhotos) else payload

        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.completePlan(planId, requestPayload) }) {
            is ApiResult.Success -> {
                val saved = result.data.toDomain()
                database.memoryDao().upsert(saved.toEntity())
                VibeResult.Ok(saved)
            }

            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK || result.kind == ApiErrorKind.SERVER) {
                VibeResult.Ok(memory)
            } else {
                result.toProblem()
            }
        }
    }

    /** Adds a photo to an existing memory (device URI in the PoE build). */
    suspend fun addPhoto(memoryId: String, photoUri: String): VibeResult<Memory> {
        val memory = database.memoryDao().byId(memoryId)?.toDomain() ?: return VibeResult.Problem(detail = "Memory not found")
        return updateMemory(memoryId, memory.rating, memory.caption, memory.photoUris + photoUri)
    }

    /** Edits an existing memory (caption, rating, photos). */
    suspend fun updateMemory(
        memoryId: String,
        rating: Int,
        caption: String,
        photoUris: List<String>,
    ): VibeResult<Memory> {
        Validators.rating(rating)?.let { return VibeResult.Problem(error = it) }
        Validators.caption(caption)?.let { return VibeResult.Problem(error = it) }
        val persistedPhotos = photoUris.map { media?.photos?.persist(it) ?: it }
        val entity = database.memoryDao().byId(memoryId) ?: return VibeResult.Problem(detail = "Memory not found")
        val updated = entity.toDomain().copy(
            rating = rating,
            caption = caption.trim(),
            photoUris = persistedPhotos,
            syncState = SyncState.PENDING,
        )
        database.memoryDao().upsert(updated.toEntity())

        val payload = CompletePlanRequest(
            rating = rating,
            caption = updated.caption,
            photoUris = persistedPhotos,
            planId = entity.planId,
            groupId = updated.groupId,
        )
        val queued = queue.enqueue(SyncAction.SAVE_MEMORY, memoryId, payload, CompletePlanRequest::class.java)
        val requestPayload = if (queue.isOnline()) payload.copy(photoUris = media?.uploadAll(persistedPhotos, updated.groupId) ?: persistedPhotos) else payload
        return when (val result = com.vibe.app.data.sync.onlineMutation(queue, queued) { api.updateMemory(memoryId, requestPayload) }) {
            is ApiResult.Success -> {
                val saved = result.data.toDomain()
                database.memoryDao().upsert(saved.toEntity())
                VibeResult.Ok(saved)
            }

            is ApiResult.Failure -> if (result.kind == ApiErrorKind.NETWORK || result.kind == ApiErrorKind.SERVER) VibeResult.Ok(updated) else result.toProblem()
        }
    }

    suspend fun memoryCount(): Int = database.memoryDao().count()
}
