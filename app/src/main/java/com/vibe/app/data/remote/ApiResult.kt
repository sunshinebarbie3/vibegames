package com.vibe.app.data.remote

import retrofit2.HttpException
import java.io.IOException

/**
 * Every API call is funnelled through [apiResult] so the repositories can treat
 * transport, authorisation, validation and conflict outcomes in one place.
 *
 * Conflicts are deliberately their own kind: the PoE requires that a clash
 * between an offline change and somebody else's change is *reported* rather than
 * silently overwritten, so the sync layer needs to tell 409 apart from a
 * transient network failure.
 */
enum class ApiErrorKind { NETWORK, UNAUTHORISED, FORBIDDEN, CONFLICT, VALIDATION, SERVER, UNKNOWN }

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    data class Failure(
        val kind: ApiErrorKind,
        val message: String,
        val httpCode: Int? = null,
        val serverPayload: String? = null,
    ) : ApiResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun dataOrNull(): T? = (this as? Success)?.data
}

internal fun errorKindFor(code: Int): ApiErrorKind = when {
    code == 401 -> ApiErrorKind.UNAUTHORISED
    code == 403 -> ApiErrorKind.FORBIDDEN
    code == 409 -> ApiErrorKind.CONFLICT
    code in 400..499 -> ApiErrorKind.VALIDATION
    code >= 500 -> ApiErrorKind.SERVER
    else -> ApiErrorKind.UNKNOWN
}

suspend fun <T> apiResult(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (failure: HttpException) {
    val body = runCatching { failure.response()?.errorBody()?.string() }.getOrNull()
    ApiResult.Failure(
        kind = errorKindFor(failure.code()),
        message = failure.message(),
        httpCode = failure.code(),
        serverPayload = if (failure.code() == 409) body else null,
    )
} catch (failure: IOException) {
    // No connectivity, DNS failure, timeout: the caller keeps the queued change.
    ApiResult.Failure(ApiErrorKind.NETWORK, failure.message ?: "network unavailable")
} catch (failure: Exception) {
    ApiResult.Failure(ApiErrorKind.UNKNOWN, failure.message ?: failure.javaClass.simpleName)
}
