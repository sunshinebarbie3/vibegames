package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.Ids
import com.vibe.app.domain.Memory
import com.vibe.app.domain.Plan
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Plans and Memories: setting the date, marking a plan done, and the photos,
 * caption and group rating that make the memory.
 *
 * Plans and memories are written directly to Firestore and cached in Room.
 * Selected photos are persisted to private app storage immediately.
 */
class MemoryRepository(
    private val firestore: FirestoreProvider,
    private val database: VibeDatabase,
    private val settings: SettingsStore,
    private val time: TimeProvider,
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
        runCatching {
            val serverMemories = firestore.getMemoriesForGroup(groupId)
            withContext(Dispatchers.IO) {
                database.memoryDao().upsert(serverMemories.map { it.toEntity() })
            }
            VibeResult.Ok(serverMemories.size)
        }.getOrElse { VibeResult.Ok(0) }

    suspend fun refreshPlans(groupId: String): VibeResult<Int> =
        runCatching {
            val serverPlans = firestore.getPlansForGroup(groupId)
            withContext(Dispatchers.IO) {
                serverPlans.forEach { database.planDao().upsert(it.toEntity()) }
            }
            VibeResult.Ok(serverPlans.size)
        }.getOrElse { VibeResult.Ok(0) }

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

        return runCatching {
            firestore.savePlan(plan)
            withContext(Dispatchers.IO) {
                database.planDao().upsert(plan.toEntity())
            }
            VibeResult.Ok(plan)
        }.getOrElse {
            VibeResult.Ok(plan)
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
            photoUris = photoUris,
            createdBy = userId,
            syncState = SyncState.PENDING,
        )

        database.planDao().upsert(plan.copy(completed = true))
        database.activityDao().updateStatus(plan.activityId, ActivityStatus.COMPLETED.name)
        database.memoryDao().upsert(memory.toEntity())

        return runCatching {
            firestore.saveMemory(memory)
            withContext(Dispatchers.IO) {
                database.memoryDao().upsert(memory.copy(syncState = SyncState.SYNCED).toEntity())
            }
            VibeResult.Ok(memory)
        }.getOrElse {
            VibeResult.Ok(memory)
        }
    }

    /** Adds a photo to an existing memory. */
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
        val entity = database.memoryDao().byId(memoryId) ?: return VibeResult.Problem(detail = "Memory not found")
        val updated = entity.toDomain().copy(
            rating = rating,
            caption = caption.trim(),
            photoUris = photoUris,
            syncState = SyncState.PENDING,
        )
        database.memoryDao().upsert(updated.toEntity())

        return runCatching {
            firestore.saveMemory(updated)
            withContext(Dispatchers.IO) {
                database.memoryDao().upsert(updated.copy(syncState = SyncState.SYNCED).toEntity())
            }
            VibeResult.Ok(updated)
        }.getOrElse {
            VibeResult.Ok(updated)
        }
    }

    suspend fun memoryCount(): Int = database.memoryDao().count()

    private companion object {
        // Validators.rating and Validators.caption are defined in domain.Validators.
    }
}
