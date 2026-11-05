package com.vibe.app.core

import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.local.SyncItemEntity
import com.vibe.app.domain.FieldError

/**
 * The result type every repository returns.
 *
 * Two kinds of failure are surfaced separately on purpose:
 *  - [error] is a rule the member can fix in the form (invalid email, password
 *    too short, round needs two ideas, owner-only);
 *  - [kind] explains the transport or server answer, including
 *    [ApiErrorKind.CONFLICT] for "somebody else changed this while you were
 *    offline" and [ApiErrorKind.NETWORK] for a queued offline change.
 *
 * The UI turns either into a concrete next action, which is one of the PoE's
 * non-functional requirements ("errors must explain the next action").
 */
sealed interface VibeResult<out T> {

    data class Ok<T>(val value: T) : VibeResult<T>

    data class Problem(
        val error: FieldError? = null,
        val kind: ApiErrorKind? = null,
        val queuedOffline: Boolean = false,
        val detail: String? = null,
    ) : VibeResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun valueOrNull(): T? = (this as? Ok)?.value
}

inline fun <T> VibeResult<T>.onOk(block: (T) -> Unit): VibeResult<T> {
    if (this is VibeResult.Ok) block(value)
    return this
}

/** Maps an API failure onto the UI result, optionally attaching a form error. */
fun ApiResult.Failure.toProblem(error: FieldError? = null): VibeResult.Problem =
    VibeResult.Problem(error = error, kind = kind, detail = message)

/** A change that was written locally and queued because the device is offline. */
fun queuedOfflineResult(): VibeResult.Problem =
    VibeResult.Problem(kind = ApiErrorKind.NETWORK, queuedOffline = true)
