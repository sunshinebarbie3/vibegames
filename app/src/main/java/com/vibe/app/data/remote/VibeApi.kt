package com.vibe.app.data.remote

import com.vibe.app.domain.Activity
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.AppLanguage
import com.vibe.app.domain.Decision
import com.vibe.app.domain.DecisionState
import com.vibe.app.domain.Group
import com.vibe.app.domain.GroupMember
import com.vibe.app.domain.GroupRole
import com.vibe.app.domain.Memory
import com.vibe.app.domain.NotificationType
import com.vibe.app.domain.Participation
import com.vibe.app.domain.Plan
import com.vibe.app.domain.RoundOutcome
import com.vibe.app.domain.Tally
import com.vibe.app.domain.ThemeMode
import com.vibe.app.domain.User
import com.vibe.app.domain.VibeNotification
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Multipart
import retrofit2.http.Part
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Path

/**
 * The Firebase-backed REST API contract (design document, section 5).
 *
 * Conventions, mirrored by `api-container/`:
 *  - JSON, camelCase field names, all timestamps are epoch milliseconds (UTC);
 *  - bearer tokens in `Authorization: Bearer <token>`;
 *  - 401 unauthenticated, 403 role check failed, 409 conflict between an
 *    offline change and the server copy, 422 validation.
 */
interface VibeApi {

    // ----- Authentication -----

    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    /** PoE: Google Sign-In returns a verified ID token that the API validates. */
    @POST("api/v1/auth/google")
    suspend fun googleSignIn(@Body body: GoogleAuthRequest): AuthResponse

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @POST("api/v1/auth/password-reset")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequest): Response<Unit>

    // ----- Account and settings -----

    @retrofit2.http.HTTP(method="DELETE",path="api/v1/users/me",hasBody=true)
    suspend fun deleteAccount(@Body body: DeleteAccountRequest): Response<Unit>

    @GET("api/v1/users/me")
    suspend fun me(): UserDto

    @PATCH("api/v1/users/me")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): UserDto

    @POST("api/v1/users/me/password")
    suspend fun changePassword(@Body body: ChangePasswordRequest): AuthResponse

    @GET("api/v1/auth/providers")
    suspend fun providers(): ProvidersDto

    @POST("api/v1/auth/google/link")
    suspend fun linkGoogle(@Body body: GoogleAuthRequest): UserDto

    @retrofit2.http.HTTP(method = "DELETE", path = "api/v1/auth/google/link", hasBody = false)
    suspend fun unlinkGoogle(): Response<Unit>

    @POST("api/v1/devices/unregister")
    suspend fun unregisterDevice(@Body body: RegisterDeviceRequest): Response<Unit>

    @Multipart
    @POST("api/v1/media")
    suspend fun uploadPhoto(@Part photo: MultipartBody.Part, @Part("groupId") groupId: RequestBody?): MediaDto

    // ----- Groups and the Vibe List -----

    @GET("api/v1/groups")
    suspend fun groups(): List<GroupDto>

    @GET("api/v1/groups/{groupId}")
    suspend fun group(@Path("groupId") groupId: String): GroupDto

    @POST("api/v1/groups")
    suspend fun createGroup(@Body body: CreateGroupRequest): GroupDto

    @POST("api/v1/groups/join")
    suspend fun joinGroup(@Body body: JoinGroupRequest): GroupDto

    @DELETE("api/v1/groups/{groupId}")
    suspend fun deleteGroup(@Path("groupId") id: String): Response<Unit>

    @POST("api/v1/groups/{groupId}/leave")
    suspend fun leaveGroup(@Path("groupId") groupId: String): Response<Unit>

    @GET("api/v1/groups/{groupId}/activities")
    suspend fun activities(@Path("groupId") groupId: String): List<ActivityDto>

    @GET("api/v1/users/me/activities")
    suspend fun myActivities(): List<ActivityDto>

    @POST("api/v1/groups/{groupId}/activities")
    suspend fun addActivity(
        @Path("groupId") groupId: String,
        @Body body: UpsertActivityRequest,
    ): ActivityDto

    @PATCH("api/v1/activities/{activityId}")
    suspend fun updateActivity(
        @Path("activityId") activityId: String,
        @Body body: UpsertActivityRequest,
    ): ActivityDto

    @DELETE("api/v1/activities/{activityId}")
    suspend fun deleteActivity(@Path("activityId") activityId: String): Response<Unit>

    // ----- Decide For Us -----

    @POST("api/v1/groups/{groupId}/decisions")
    suspend fun startDecision(
        @Path("groupId") groupId: String,
        @Body body: StartDecisionRequest,
    ): DecisionDto

    @GET("api/v1/decisions/{decisionId}")
    suspend fun decision(@Path("decisionId") decisionId: String): DecisionDto

    @POST("api/v1/decisions/{decisionId}/votes")
    suspend fun castVote(
        @Path("decisionId") decisionId: String,
        @Body body: CastVoteRequest,
    ): VoteAckDto

    @POST("api/v1/decisions/{decisionId}/close")
    suspend fun closeDecision(@Path("decisionId") decisionId: String): DecisionDto

    @POST("api/v1/decisions/{decisionId}/surprise")
    suspend fun surprise(
        @Path("decisionId") decisionId: String,
        @Body body: SurpriseRequest,
    ): ActivityDto

    // ----- Plans and memories -----

    @GET("api/v1/groups/{groupId}/plans")
    suspend fun plans(@Path("groupId") groupId: String): List<PlanDto>

    @POST("api/v1/plans")
    suspend fun savePlan(@Body body: SavePlanRequest): PlanDto

    @POST("api/v1/plans/{planId}/complete")
    suspend fun completePlan(
        @Path("planId") planId: String,
        @Body body: CompletePlanRequest,
    ): MemoryDto

    @GET("api/v1/groups/{groupId}/memories")
    suspend fun memories(@Path("groupId") groupId: String): List<MemoryDto>

    @PATCH("api/v1/memories/{memoryId}")
    suspend fun updateMemory(@Path("memoryId") id: String, @Body body: CompletePlanRequest): MemoryDto

    // ----- Alerts, devices and offline sync -----

    @GET("api/v1/notifications")
    suspend fun notifications(): List<NotificationDto>

    @POST("api/v1/notifications/{notificationId}/read")
    suspend fun markNotificationRead(@Path("notificationId") notificationId: String): Response<Unit>

    @POST("api/v1/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<Unit>

    @POST("api/v1/devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): Response<Unit>

    /** Drains the offline queue in one request; the API answers per item. */
    @POST("api/v1/sync/batch")
    suspend fun syncBatch(@Body body: SyncBatchRequest): SyncBatchResponse
}

// ---------------------------------------------------------------- request DTOs

data class RegisterRequest(val displayName: String, val email: String, val password: String)
data class LoginRequest(val email: String, val password: String)
data class DeleteAccountRequest(val currentPassword: String? = null)
data class GoogleAuthRequest(val idToken: String)
data class RefreshRequest(val refreshToken: String)
data class PasswordResetRequest(val email: String)
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

data class UpdateProfileRequest(
    val displayName: String? = null,
    val username: String? = null,
    val language: String? = null,
    val themeMode: String? = null,
    val notificationsEnabled: Boolean? = null,
    val privacyMembersOnly: Boolean? = null,
    val photoUri: String? = null,
)

data class CreateGroupRequest(val name: String, val icon: String, val clientId: String? = null)
data class JoinGroupRequest(val inviteCode: String)

data class UpsertActivityRequest(
    val title: String,
    val description: String = "",
    val icon: String = "🎉",
    val favourite: Boolean? = null,
    val status: String? = null,
    /** Client-generated id: retrying a queued action can never duplicate a row. */
    val clientId: String? = null,
    /** The group the idea belongs to; carried so a queued add can be applied. */
    val groupId: String? = null,
)

data class StartDecisionRequest(
    val activityIds: List<String> = emptyList(),
    val deadlineEpochMillis: Long? = null,
    /** Idempotency key so a queued "start round" is not started twice. */
    val clientId: String? = null,
    /** The group the round runs in; carried so a queued start can be applied. */
    val groupId: String? = null,
    val roundNumber: Int = 1,
)

data class CastVoteRequest(
    val activityId: String,
    val choice: Boolean,
    /** When the member actually voted, even if the vote was queued offline. */
    val clientTimestampEpochMillis: Long,
    val clientId: String,
)

data class SurpriseRequest(val rejectedIds: List<String> = emptyList())
data class SavePlanRequest(val groupId: String, val activityId: String, val scheduledAtEpochMillis: Long?, val clientId: String? = null)
data class CompletePlanRequest(
    val rating: Int,
    val caption: String,
    val photoUris: List<String> = emptyList(),
    /** Carried so a memory queued offline can be attached to its plan. */
    val planId: String? = null,
    val groupId: String? = null,
    val clientId: String? = null,
)
data class RegisterDeviceRequest(val fcmToken: String, val platform: String = "android")

data class SyncItemDto(
    val clientId: String,
    val action: String,
    val entityId: String,
    val payloadJson: String,
    val queuedAtEpochMillis: Long,
)

data class SyncBatchRequest(val items: List<SyncItemDto>)

// --------------------------------------------------------------- response DTOs

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val user: UserDto,
)

data class ProvidersDto(val providers: List<String> = emptyList(), val hasPassword: Boolean = false)
data class MediaDto(val id: String, val uri: String)

data class UserDto(
    val id: String,
    val displayName: String,
    val username: String,
    val email: String,
    val language: String = "en",
    val themeMode: String = "DARK",
    val notificationsEnabled: Boolean = true,
    val privacyMembersOnly: Boolean = true,
    val photoUri: String? = null,
    val createdAtEpochMillis: Long = 0L,
)

data class MemberDto(
    val userId: String,
    val displayName: String,
    val role: String = "MEMBER",
    val photoUri: String? = null,
    val joinedAtEpochMillis: Long = 0L,
)

data class GroupDto(
    val id: String,
    val name: String,
    val icon: String = "🎉",
    val inviteCode: String,
    val ownerId: String,
    val createdAtEpochMillis: Long = 0L,
    val role: String = "MEMBER",
    val memberCount: Int = 1,
    val activityCount: Int = 0,
    val members: List<MemberDto> = emptyList(),
)

data class ActivityDto(
    val id: String,
    val groupId: String,
    val title: String,
    val description: String = "",
    val icon: String = "🎉",
    val status: String = "SUGGESTED",
    val createdBy: String = "",
    val createdByName: String = "",
    val createdAtEpochMillis: Long = 0L,
    val favourite: Boolean = false,
    val yesVotes: Int = 0,
    val participantCount: Int = 0,
)

data class TallyDto(val activityId: String, val yes: Int, val no: Int, val pending: Int)

data class ParticipationDto(
    val memberCount: Int = 0,
    val votedCount: Int = 0,
    val pendingMemberIds: List<String> = emptyList(),
)

data class DecisionDto(
    val id: String,
    val groupId: String,
    val roundNumber: Int,
    val state: String = "OPEN",
    val deadlineEpochMillis: Long? = null,
    val winnerActivityId: String? = null,
    val startedBy: String = "",
    val startedAtEpochMillis: Long = 0L,
    val participation: ParticipationDto = ParticipationDto(),
    val tallies: List<TallyDto> = emptyList(),
    val survivorIds: List<String> = emptyList(),
    val eliminatedIds: List<String> = emptyList(),
    val votesRevealed: Boolean = false,
    val canCloseEarly: Boolean = false,
    /** "inProgress" | "winner" | "continue" | "tie" | "none" */
    val outcome: String? = null,
    val activityIds: List<String> = emptyList(),
    val myVotes: List<MyVoteDto> = emptyList(),
)

data class MyVoteDto(val activityId: String, val choice: Boolean, val epochMillis: Long)

data class VoteAckDto(val voteId: String, val decision: DecisionDto)

data class PlanDto(
    val id: String,
    val groupId: String,
    val activityId: String,
    val activityTitle: String = "",
    val activityIcon: String = "🎉",
    val scheduledAtEpochMillis: Long? = null,
    val completed: Boolean = false,
    val createdBy: String = "",
)

data class MemoryDto(
    val id: String,
    val groupId: String,
    val planId: String,
    val activityId: String,
    val activityTitle: String = "",
    val activityIcon: String = "🎉",
    val completedAtEpochMillis: Long = 0L,
    val caption: String = "",
    val rating: Int = 0,
    val photoUris: List<String> = emptyList(),
    val createdBy: String = "",
)

data class NotificationDto(
    val id: String,
    val userId: String,
    val title: String,
    val body: String,
    val type: String = "NEW_ACTIVITY",
    val read: Boolean = false,
    val createdAtEpochMillis: Long = 0L,
    val groupId: String? = null,
)

data class SyncResultDto(
    val clientId: String,
    /** "applied" | "conflict" | "rejected" */
    val status: String,
    val serverPayload: String? = null,
    val message: String? = null,
)

data class SyncBatchResponse(val results: List<SyncResultDto> = emptyList())

// -------------------------------------------------------------- DTO -> domain

internal inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fallback

fun UserDto.toDomain() = User(
    id = id,
    displayName = displayName,
    username = username,
    email = email,
    language = AppLanguage.fromTag(language),
    themeMode = enumOr(themeMode, ThemeMode.DARK),
    notificationsEnabled = notificationsEnabled,
    privacyMembersOnly = privacyMembersOnly,
    photoUri = photoUri,
    createdAt = createdAtEpochMillis,
)

fun MemberDto.toDomain() = GroupMember(
    userId = userId,
    displayName = displayName,
    role = enumOr(role, GroupRole.MEMBER),
    photoUri = photoUri,
    joinedAt = joinedAtEpochMillis,
)

fun GroupDto.toDomain() = Group(
    id = id,
    name = name,
    icon = icon,
    inviteCode = inviteCode,
    ownerId = ownerId,
    createdAt = createdAtEpochMillis,
    role = enumOr(role, GroupRole.MEMBER),
    memberCount = memberCount,
    activityCount = activityCount,
    members = members.map { it.toDomain() },
)

fun ActivityDto.toDomain() = Activity(
    id = id,
    groupId = groupId,
    title = title,
    description = description,
    icon = icon,
    status = enumOr(status, ActivityStatus.SUGGESTED),
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = createdAtEpochMillis,
    favourite = favourite,
    yesVotes = yesVotes,
    participantCount = participantCount,
)

fun TallyDto.toDomain() = Tally(activityId = activityId, yes = yes, no = no, pending = pending)

fun ParticipationDto.toDomain() = Participation(
    memberCount = memberCount,
    votedCount = votedCount,
    pendingMemberIds = pendingMemberIds,
)

fun DecisionDto.toDomain() = Decision(
    id = id,
    groupId = groupId,
    roundNumber = roundNumber,
    state = enumOr(state, DecisionState.OPEN),
    deadlineEpochMillis = deadlineEpochMillis,
    winnerActivityId = winnerActivityId,
    startedBy = startedBy,
    startedAt = startedAtEpochMillis,
)

fun DecisionDto.outcomeOrNull(): RoundOutcome? = when (outcome) {
    "inProgress" -> RoundOutcome.InProgress(participation.pendingMemberIds)
    "winner" -> winnerActivityId?.let { RoundOutcome.Winner(it) }
        ?: RoundOutcome.ContinueRounds(survivorIds, roundNumber + 1)
    "continue" -> RoundOutcome.ContinueRounds(survivorIds, roundNumber + 1)
    "tie" -> RoundOutcome.Tie(survivorIds)
    "none" -> RoundOutcome.NoWinner
    else -> null
}

fun PlanDto.toDomain() = Plan(
    id = id,
    groupId = groupId,
    activityId = activityId,
    activityTitle = activityTitle,
    activityIcon = activityIcon,
    scheduledAtEpochMillis = scheduledAtEpochMillis,
    completed = completed,
    createdBy = createdBy,
)

fun MemoryDto.toDomain() = Memory(
    id = id,
    groupId = groupId,
    planId = planId,
    activityId = activityId,
    activityTitle = activityTitle,
    activityIcon = activityIcon,
    completedAtEpochMillis = completedAtEpochMillis,
    caption = caption,
    rating = rating,
    photoUris = photoUris,
    createdBy = createdBy,
)

fun NotificationDto.toDomain() = VibeNotification(
    id = id,
    userId = userId,
    title = title,
    body = body,
    type = enumOr(type, NotificationType.NEW_ACTIVITY),
    read = read,
    createdAt = createdAtEpochMillis,
    groupId = groupId,
)

/** The round payload the decision screens render. */
fun DecisionDto.toRound() = com.vibe.app.domain.DecisionRound(
    decision = toDomain(),
    participation = participation.toDomain(),
    tallies = tallies.map { it.toDomain() },
    survivorIds = survivorIds,
    eliminatedIds = eliminatedIds,
    votesRevealed = votesRevealed || state.equals("CLOSED", true) || state.equals("SETTLED", true),
    canCloseEarly = canCloseEarly,
    outcome = outcomeOrNull(),
)
