package com.vibe.app.domain

import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Validation shared by the forms and the API layer.
 *
 * Errors are returned as [FieldError] values rather than strings so the domain
 * stays free of Android resources: the UI maps them to localised copy in
 * English, isiZulu or Sesotho (see `ui/screens/ErrorText.kt`).
 */
enum class FieldError {
    NAME_REQUIRED,
    NAME_TOO_LONG,
    EMAIL_INVALID,
    PASSWORD_SHORT,
    PASSWORD_DIGIT,
    PASSWORD_MISMATCH,
    CURRENT_PASSWORD_REQUIRED,
    GROUP_NAME_REQUIRED,
    ACTIVITY_TITLE_REQUIRED,
    INVITE_CODE_INVALID,
    CAPTION_TOO_LONG,
    RATING_OUT_OF_RANGE,
    NOT_ENOUGH_ACTIVITIES,
    OWNER_ONLY,
}

object Validators {

    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_NAME_LENGTH = 40
    const val MAX_CAPTION_LENGTH = 280
    const val MIN_ROUND_ACTIVITIES = 2

    /**
     * Deliberately close to the server-side rule and to
     * `EmailAddressAttribute` in the Firebase-backed REST API, so a value that passes
     * on the device is not rejected by the API.
     */
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun displayName(raw: String): FieldError? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> FieldError.NAME_REQUIRED
            name.length > MAX_NAME_LENGTH -> FieldError.NAME_TOO_LONG
            else -> null
        }
    }

    fun email(raw: String): FieldError? =
        if (EMAIL_REGEX.matches(raw.trim())) null else FieldError.EMAIL_INVALID

    /** Mirrors the OWASP-aligned policy the API enforces when hashing. */
    fun password(raw: String): FieldError? = when {
        raw.length < MIN_PASSWORD_LENGTH -> FieldError.PASSWORD_SHORT
        raw.none { it.isDigit() } -> FieldError.PASSWORD_DIGIT
        else -> null
    }

    fun passwordConfirmation(password: String, confirmation: String): FieldError? =
        if (password == confirmation) null else FieldError.PASSWORD_MISMATCH

    fun groupName(raw: String): FieldError? =
        if (raw.trim().isEmpty()) FieldError.GROUP_NAME_REQUIRED else null

    fun activityTitle(raw: String): FieldError? =
        if (raw.trim().isEmpty()) FieldError.ACTIVITY_TITLE_REQUIRED else null

    fun inviteCode(raw: String): FieldError? =
        if (InviteCode.isValid(raw)) null else FieldError.INVITE_CODE_INVALID

    fun caption(raw: String): FieldError? =
        if (raw.length <= MAX_CAPTION_LENGTH) null else FieldError.CAPTION_TOO_LONG

    fun rating(value: Int): FieldError? =
        if (value in 0..5) null else FieldError.RATING_OUT_OF_RANGE

    /** A decision round needs at least two ideas to be worth running. */
    fun roundActivities(count: Int): FieldError? =
        if (count >= MIN_ROUND_ACTIVITIES) null else FieldError.NOT_ENOUGH_ACTIVITIES

    /** Role check used before showing "Start decision" or member management. */
    fun canStartRound(role: GroupRole): FieldError? =
        if (role == GroupRole.OWNER) null else FieldError.OWNER_ONLY
}

/**
 * Invite codes: six characters from an alphabet without I, O, 0 or 1 so codes
 * can be read aloud or typed from a screenshot without guesswork.
 */
object InviteCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun normalise(raw: String): String =
        raw.trim().uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun isValid(raw: String): Boolean {
        val code = normalise(raw)
        return code.length == LENGTH && code.all { it in ALPHABET }
    }
}

object Ids {
    fun newId(): String = UUID.randomUUID().toString()

    /** Stable id for an offline action so a retry is never applied twice. */
    fun syncClientId(action: SyncAction, entityId: String, queuedAt: Long): String =
        "${action.wire}:$entityId:$queuedAt"
}
