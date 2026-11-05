package com.vibe.app.core

import com.vibe.app.domain.FieldError

/**
 * The result type every repository returns.
 *
 * Two kinds of failure are surfaced separately on purpose:
 *  - [error] is a rule the member can fix in the form (invalid email, password
 *    too short, round needs two ideas, owner-only);
 *  - [kind] explains the transport or server answer (network, unauthorised,
 *    server error).
 *
 * The UI turns either into a concrete next action.
 */
sealed interface VibeResult<out T> {

    data class Ok<T>(val value: T) : VibeResult<T>

    data class Problem(
        val error: FieldError? = null,
        val kind: ProblemKind? = null,
        val queuedOffline: Boolean = false,
        val detail: String? = null,
    ) : VibeResult<Nothing>

    val isOk: Boolean get() = this is Ok

    fun valueOrNull(): T? = (this as? Ok)?.value
}

enum class ProblemKind { NETWORK, UNAUTHORISED, SERVER, UNKNOWN }

inline fun <T> VibeResult<T>.onOk(block: (T) -> Unit): VibeResult<T> {
    if (this is VibeResult.Ok) block(value)
    return this
}

/** A change that was written locally and queued because the device is offline. */
fun queuedOfflineResult(): VibeResult.Problem =
    VibeResult.Problem(kind = ProblemKind.NETWORK, queuedOffline = true)
