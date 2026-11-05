package com.vibe.app.data.sync

import com.vibe.app.data.local.SyncItemEntity
import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.apiResult
import com.vibe.app.domain.SyncState

/** Immediate path and replay share one receipt. A validation error is never 'saved offline'. */
suspend fun <T> onlineMutation(queue: SyncQueue, item: SyncItemEntity, call: suspend () -> T): ApiResult<T> {
    if (!queue.isOnline()) return ApiResult.Failure(ApiErrorKind.NETWORK, "Saved offline")
    val result = apiResult(call)
    when (result) {
        is ApiResult.Success -> queue.markSynced(item.id)
        is ApiResult.Failure -> when (result.kind) {
            ApiErrorKind.CONFLICT -> queue.markConflict(item.id, result.serverPayload)
            ApiErrorKind.VALIDATION, ApiErrorKind.FORBIDDEN, ApiErrorKind.UNAUTHORISED -> queue.markFailed(item.id, result.message)
            else -> SyncWorkerBridge.schedule()
        }
    }
    return result
}
object SyncWorkerBridge { var schedule: () -> Unit = {} }
