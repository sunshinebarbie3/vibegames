package com.vibe.app.data.sync

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.vibe.app.core.ConnectivityObserver
import com.vibe.app.core.VibeResult
import com.vibe.app.data.local.SyncItemEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.*
import com.vibe.app.data.repository.MediaRepository
import com.vibe.app.domain.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordered, single-flight, idempotent replay. WorkManager retries transient failures. */
class SyncManager(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val connectivity: ConnectivityObserver,
    private val scope: CoroutineScope,
    private val settings: SettingsStore? = null,
    private val moshi: Moshi? = null,
    private val media: MediaRepository? = null,
) {
    private val mutex = Mutex()
    val pendingCount = database.syncQueueDao().observePendingCount()
    val conflicts = database.syncQueueDao().observeConflicts().map { rows -> rows.map { it.toDomain() } }
    val queuedItems = database.syncQueueDao().observeAll()
    var afterSync: suspend () -> Unit = {}
    fun start() { scope.launch { connectivity.isOnline.collect { if (it) syncNow() } } }

    suspend fun syncNow(): VibeResult<Int> = mutex.withLock {
        if (settings != null && settings.currentUserId() == null) return VibeResult.Ok(0)
        if (!connectivity.isOnline.first()) return VibeResult.Problem(kind = ApiErrorKind.NETWORK)
        var applied = 0
        try {
            while (true) {
                val pending = database.syncQueueDao().pending().take(50)
                if (pending.isEmpty()) break
                val prepared = pending.map { preparePhotos(it) }
                val response = apiResult { api.syncBatch(SyncBatchRequest(prepared.map { SyncItemDto(it.id, it.action, it.entityId, it.payloadJson, it.queuedAt) })) }
                if (response !is ApiResult.Success) return (response as ApiResult.Failure).toProblem()
                val receipts = response.data.results.associateBy { it.clientId }
                for (item in prepared) {
                    val receipt = receipts[item.id] ?: continue
                    when (receipt.status) {
                        "applied" -> { accept(item, receipt.serverPayload); database.syncQueueDao().deleteIfPending(item.id); applied++ }
                        "conflict" -> database.syncQueueDao().markConflict(item.id, receipt.serverPayload)
                        else -> database.syncQueueDao().markState(item.id, SyncState.FAILED.name, receipt.message ?: "Change rejected")
                    }
                }
                if (prepared.none { receipts[it.id]?.status == "applied" }) break
            }
            if (applied > 0) afterSync()
            VibeResult.Ok(applied)
        } catch (e: Exception) {
            VibeResult.Problem(kind = ApiErrorKind.UNKNOWN, detail = e.message)
        }
    }

    /** Upload private blobs first; replace local file references in the durable JSON. */
    private suspend fun preparePhotos(item: SyncItemEntity): SyncItemEntity {
        val parser = moshi ?: return item
        if (!item.payloadJson.contains("file://") && !item.payloadJson.contains("content://")) return item
        val adapter = parser.adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
        val map = adapter.fromJson(item.payloadJson)?.toMutableMap() ?: return item
        val groupId = map["groupId"] as? String
        suspend fun convert(value: Any?): Any? = when (value) {
            is String -> if (value.startsWith("file://") || value.startsWith("content://")) media?.upload(value, groupId) ?: value else value
            is List<*> -> value.map { convert(it) }
            is Map<*, *> -> value.entries.associate { it.key.toString() to convert(it.value) }
            else -> value
        }
        val converted = map.mapValues { convert(it.value) }
        val updated = item.copy(payloadJson = adapter.toJson(converted))
        database.syncQueueDao().update(updated)
        return updated
    }

    private suspend fun accept(item: SyncItemEntity, json: String?) {
        val action = item.action
        if (action == "activity.update" || action == "activity.add") {
            parse(json, ActivityDto::class.java)?.let { database.activityDao().upsert(it.toDomain().toEntity()) }
        }
        if (action == "profile.update") parse(json, UserDto::class.java)?.let { database.userDao().upsert(it.toDomain().toEntity()) }
        if (action == "plan.save") parse(json, PlanDto::class.java)?.let { database.planDao().upsert(it.toDomain().toEntity()) }
        if (action == "memory.save" || action == "plan.complete") parse(json, MemoryDto::class.java)?.let {
            database.memoryDao().upsert(it.toDomain().toEntity())
            database.planDao().byId(it.planId)?.let { p -> database.planDao().upsert(p.copy(completed = true)) }
        }
        if (action == "group.create") parse(json, GroupDto::class.java)?.let {
            val group = it.toDomain(); database.groupDao().upsert(group.toEntity(System.currentTimeMillis()))
            database.groupDao().upsertMembers(group.members.map { m -> m.toEntity(group.id) })
        }
        if (action.startsWith("decision.")) {
            val dto = if (action == "decision.vote") parse(json, VoteAckDto::class.java)?.decision else parse(json, DecisionDto::class.java)
            dto?.let { d ->
                val existing = database.decisionDao().byId(d.id)
                database.decisionDao().upsert(d.toDomain().toEntity(d.tallies.map { it.toDomain() }, d.survivorIds, d.eliminatedIds, d.activityIds.ifEmpty { existing?.let { e -> com.vibe.app.data.local.Codecs.decodeList(e.roundActivityIdsCsv) }.orEmpty() }))
                if (action == "decision.vote") {
                    val parts = item.entityId.split(":")
                    if (parts.size == 2) database.voteDao().updateSyncState(parts[0], parts[1], settings?.currentUserId().orEmpty(), SyncState.SYNCED.name)
                }
            }
        }
        if (action == "activity.delete") database.activityDao().delete(item.entityId)
        if (action == "group.leave") { database.activityDao().deleteByGroup(item.entityId); database.groupDao().delete(item.entityId) }
    }
    private fun <T> parse(json: String?, type: Class<T>): T? = runCatching { json?.let { moshi?.adapter(type)?.fromJson(it) } }.getOrNull()

    suspend fun resolveKeepingLocal(item: com.vibe.app.domain.SyncItem) {
        val entity = database.syncQueueDao().byId(item.id) ?: return
        val adapter = moshi?.adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
        val payload = adapter?.fromJson(entity.payloadJson)?.plus("force" to true)
        database.syncQueueDao().update(entity.copy(state = SyncState.PENDING.name, attempts = 0, lastError = null, serverPayloadJson = null, payloadJson = payload?.let { adapter.toJson(it) } ?: entity.payloadJson))
        syncNow()
    }
    suspend fun resolveKeepingServer(item: com.vibe.app.domain.SyncItem) { database.syncQueueDao().delete(item.id); afterSync() }
    suspend fun retry(item: com.vibe.app.domain.SyncItem) {
        database.syncQueueDao().byId(item.id)?.let { database.syncQueueDao().update(it.copy(state = SyncState.PENDING.name, attempts = 0)); syncNow() }
    }
    fun isTransient(kind: ApiErrorKind?) = kind == null || kind == ApiErrorKind.NETWORK || kind == ApiErrorKind.SERVER
}
