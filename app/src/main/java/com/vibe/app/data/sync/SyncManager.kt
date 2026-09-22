package com.vibe.app.data.sync

import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.core.ConnectivityObserver
import com.vibe.app.core.VibeResult
import com.vibe.app.data.local.SyncItemEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.remote.SyncBatchRequest
import com.vibe.app.data.remote.SyncItemDto
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.apiResult
import com.vibe.app.domain.SyncItem
import com.vibe.app.domain.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drains the offline queue.
 *
 * Flow of events:
 *  1. the member works offline; each change is written to RoomDB and queued;
 *  2. [AndroidConnectivityObserver][com.vibe.app.core.AndroidConnectivityObserver]
 *     reports the device is back online and [start] triggers [syncNow];
 *  3. the API answers per item: `applied`, `conflict` or `rejected`;
 *  4. a conflict is **reported**, not overwritten - the row stays in the queue in
 *     [SyncState.CONFLICT] with the server copy attached, and the member chooses
 *     "Keep mine" or "Keep theirs" in Settings.
 */
class SyncManager(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val connectivity: ConnectivityObserver,
    private val scope: CoroutineScope,
) {

    val pendingCount: Flow<Int> = database.syncQueueDao().observePendingCount()

    val conflicts: Flow<List<SyncItem>> =
        database.syncQueueDao().observeConflicts().map { items -> items.map { it.toDomain() } }

    val queuedItems: Flow<List<SyncItemEntity>> = database.syncQueueDao().observeAll()

    /** Called once from the application container. */
    fun start() {
        scope.launch {
            connectivity.isOnline.collect { online ->
                if (online) syncNow()
            }
        }
    }

    suspend fun syncNow(): VibeResult<Int> {
        val pending = database.syncQueueDao().pending()
        if (pending.isEmpty()) return VibeResult.Ok(0)

        val request = SyncBatchRequest(
            items = pending.map {
                SyncItemDto(
                    clientId = it.id,
                    action = it.action,
                    entityId = it.entityId,
                    payloadJson = it.payloadJson,
                    queuedAtEpochMillis = it.queuedAt,
                )
            },
        )

        return when (val response = apiResult { api.syncBatch(request) }) {
            is ApiResult.Success -> {
                var applied = 0
                response.data.results.forEach { result ->
                    when (result.status.lowercase()) {
                        "applied" -> {
                            database.syncQueueDao().delete(result.clientId)
                            applied++
                        }

                        "conflict" -> database.syncQueueDao().markConflict(result.clientId, result.serverPayload)

                        else -> database.syncQueueDao().markState(
                            result.clientId,
                            SyncState.FAILED.name,
                            result.message ?: "rejected by the server",
                        )
                    }
                }
                VibeResult.Ok(applied)
            }

            is ApiResult.Failure -> VibeResult.Problem(kind = response.kind, detail = response.message)
        }
    }

    /** "Keep mine" from the conflict dialog. */
    suspend fun resolveKeepingLocal(item: SyncItem) {
        database.syncQueueDao().update(item.toEntity().copy(state = SyncState.PENDING.name, attempts = 0))
        syncNow()
    }

    /** "Keep theirs": the queued change is dropped and the server copy wins. */
    suspend fun resolveKeepingServer(item: SyncItem) {
        database.syncQueueDao().delete(item.id)
    }

    fun isTransient(kind: ApiErrorKind?): Boolean =
        kind == ApiErrorKind.NETWORK || kind == ApiErrorKind.SERVER || kind == null
}
