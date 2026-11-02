package com.vibe.app.data.sync

import com.squareup.moshi.Moshi
import com.vibe.app.core.TimeProvider
import com.vibe.app.data.local.SyncItemEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.domain.Ids
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.SyncState

/**
 * The offline action queue.
 *
 * Every mutation that a member performs while disconnected is written to RoomDB
 * and then appended here with the time it happened ([TimeProvider]) and a stable
 * client id, so retrying a batch can never apply the same action twice.
 */
class SyncQueue(
    private val database: VibeDatabase,
    private val moshi: Moshi,
    private val time: TimeProvider,
) {
    var isOnline: suspend () -> Boolean = { true }
    var changed: suspend () -> Unit = {}

    suspend fun <T : Any> enqueue(action: SyncAction, entityId: String, payload: T, type: Class<T>): SyncItemEntity {
        val queuedAt = time.nowMillis()
        val item = SyncItemEntity(
            id = Ids.syncClientId(action, entityId, queuedAt),
            action = action.wire,
            entityId = entityId,
            payloadJson = moshi.adapter(type).toJson(payload),
            queuedAt = queuedAt,
            state = SyncState.PENDING.name,
            attempts = 0,
            lastError = null,
            serverPayloadJson = null,
        )
        database.syncQueueDao().upsert(item)
        changed()
        return item
    }

    fun <T : Any> decode(payloadJson: String?, type: Class<T>): T? {
        if (payloadJson.isNullOrBlank()) return null
        return runCatching { moshi.adapter(type).fromJson(payloadJson) }.getOrNull()
    }

    suspend fun markSynced(id: String) = database.syncQueueDao().delete(id)

    suspend fun markFailed(id: String, message: String?) =
        database.syncQueueDao().markState(id, SyncState.FAILED.name, message)

    suspend fun markConflict(id: String, serverPayload: String?) =
        database.syncQueueDao().markConflict(id, serverPayload)

    /** "Keep mine": put the change back at the front of the queue. */
    suspend fun requeue(id: String) {
        val item = database.syncQueueDao().byId(id) ?: return
        database.syncQueueDao().update(
            item.copy(
                state = SyncState.PENDING.name,
                attempts = 0,
                lastError = null,
                serverPayloadJson = null,
                queuedAt = time.nowMillis(),
            ),
        )
    }

    /** "Keep theirs": drop the local change; the next refresh pulls the server copy. */
    suspend fun acceptServerVersion(id: String) = database.syncQueueDao().delete(id)
}
