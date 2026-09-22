package com.vibe.app.data.remote

import com.vibe.app.core.TimeProvider
import com.vibe.app.data.local.ActivityEntity
import com.vibe.app.data.local.Codecs
import com.vibe.app.data.local.DecisionEntity
import com.vibe.app.data.local.GroupEntity
import com.vibe.app.data.local.GroupMemberEntity
import com.vibe.app.data.local.MemoryEntity
import com.vibe.app.data.local.NotificationEntity
import com.vibe.app.data.local.PlanEntity
import com.vibe.app.data.local.UserEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.VoteEntity
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.DecisionEngine
import com.vibe.app.domain.DecisionState
import com.vibe.app.domain.GroupRole
import com.vibe.app.domain.Ids
import com.vibe.app.domain.InviteCode
import com.vibe.app.domain.NotificationType
import com.vibe.app.domain.RoundEvaluation
import com.vibe.app.domain.RoundOutcome
import com.vibe.app.domain.RoundState
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Validators
import com.vibe.app.domain.VoteChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.util.Locale
import kotlin.random.Random

/**
 * Bundled demo backend (`BuildConfig.USE_DEMO_BACKEND = true`).
 *
 * It implements the same [VibeApi] contract as the ASP.NET Core service, so the
 * app, the repositories and the offline queue behave identically whether the
 * marker is running against a real HTTPS deployment or without a network at all.
 * Three things make it useful for a demo or a PoE session:
 *
 *  1. it is Room-backed, so whatever you create survives process death;
 *  2. it refuses what the real API refuses (403 for non-owners, 422 for a round
 *     with fewer than two ideas, 404 for an unknown invite code, 409 for a
 *     duplicate email), which keeps the error paths honest;
 *  3. after your own vote it lets the other members answer, one at a time, so
 *     the participation counter and the auto-close rule can be seen working in a
 *     group that has more than one member.
 *
 * It starts with **no content at all**: no groups, no ideas, no plans, no memories
 * and no notifications. The only seeded row is the demo account below, so the demo
 * sign-in works; everything else is created by the member.
 *
 * Passwords are never persisted here either: the demo login simply resolves the
 * account by email, exactly as documented in docs/SETUP.md.
 */
class DemoVibeApi(
    private val db: VibeDatabase,
    private val time: TimeProvider,
    private val scope: CoroutineScope,
    private val currentUserId: suspend () -> String?,
    private val simulatedVoteDelayMillis: Long = SIMULATED_VOTE_DELAY_MS,
) : VibeApi {

    companion object {
        const val DEMO_USER_ID = "demo-user-0001"
        const val DEMO_DISPLAY_NAME = "Lizzy 🌸"
        const val DEMO_EMAIL = "lizzy@vibe.app"
        const val SIMULATED_VOTE_DELAY_MS = 1_600L
        private const val ROUND_DEADLINE_HOURS = 24L
    }

    private val accountLock = Mutex()

    @Volatile
    private var demoAccountReady = false

    // ------------------------------------------------------------- auth

    override suspend fun register(body: RegisterRequest): AuthResponse {
        ensureDemoAccount()
        val email = body.email.trim().lowercase(Locale.ROOT)
        if (db.userDao().byEmail(email) != null) {
            throw httpError(409, "An account already exists for this email.")
        }
        val id = Ids.newId()
        val entity = UserEntity(
            id = id,
            displayName = body.displayName.trim(),
            username = usernameFrom(email),
            email = email,
            language = "en",
            themeMode = "DARK",
            notificationsEnabled = true,
            privacyMembersOnly = true,
            photoUri = null,
            createdAt = time.nowMillis(),
        )
        db.userDao().upsert(entity)
        // The demo backend never stores the password: only a real deployment
        // hands the password to the PBKDF2 hasher in the API.
        return authResponse(entity)
    }

    override suspend fun login(body: LoginRequest): AuthResponse {
        ensureDemoAccount()
        val email = body.email.trim().lowercase(Locale.ROOT)
        val user = db.userDao().byEmail(email)
            ?: throw httpError(401, "Email or password is incorrect.")
        return authResponse(user)
    }

    override suspend fun googleSignIn(body: GoogleAuthRequest): AuthResponse {
        ensureDemoAccount()
        // A real deployment posts the ID token to Google's tokeninfo endpoint and
        // checks aud/iss/exp before issuing a VIBE session (see API docs).
        val user = db.userDao().byId(DEMO_USER_ID) ?: throw httpError(401, "Google sign-in failed.")
        return authResponse(user)
    }

    override suspend fun refresh(body: RefreshRequest): AuthResponse {
        ensureDemoAccount()
        val user = db.userDao().byId(DEMO_USER_ID) ?: throw httpError(401, "Session expired.")
        return authResponse(user)
    }

    override suspend fun logout(): Response<Unit> = Response.success(Unit)

    override suspend fun requestPasswordReset(body: PasswordResetRequest): Response<Unit> =
        Response.success(Unit)

    override suspend fun me(): UserDto = currentUser().toDto()

    override suspend fun updateProfile(body: UpdateProfileRequest): UserDto {
        val current = currentUser()
        val updated = current.copy(
            displayName = body.displayName?.trim().takeUnless { it.isNullOrBlank() } ?: current.displayName,
            username = body.username?.takeUnless { it.isBlank() } ?: current.username,
            language = body.language ?: current.language,
            themeMode = body.themeMode ?: current.themeMode,
            notificationsEnabled = body.notificationsEnabled ?: current.notificationsEnabled,
            privacyMembersOnly = body.privacyMembersOnly ?: current.privacyMembersOnly,
            photoUri = body.photoUri ?: current.photoUri,
        )
        db.userDao().upsert(updated)
        return updated.toDto()
    }

    override suspend fun changePassword(body: ChangePasswordRequest): Response<Unit> {
        Validators.password(body.newPassword)?.let { throw httpError(422, "Password does not meet the policy.") }
        return Response.success(Unit)
    }

    // ------------------------------------------------------------ groups

    override suspend fun groups(): List<GroupDto> {
        ensureDemoAccount()
        val userId = currentUser().id
        val memberships = db.groupDao().membershipsOf(userId).map { it.groupId }.toSet()
        return db.groupDao().all()
            .filter { it.id in memberships }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .map { groupDto(it, userId) }
    }

    override suspend fun group(groupId: String): GroupDto {
        ensureDemoAccount()
        val userId = currentUser().id
        val entity = db.groupDao().byId(groupId) ?: throw httpError(404, "Group not found.")
        return groupDto(entity, userId)
    }

    override suspend fun createGroup(body: CreateGroupRequest): GroupDto {
        ensureDemoAccount()
        val userId = currentUser().id
        val now = time.nowMillis()
        val groupId = body.clientId?.takeIf { it.isNotBlank() } ?: Ids.newId()
        val code = generateUniqueInviteCode()
        val entity = GroupEntity(
            id = groupId,
            name = body.name.trim(),
            icon = body.icon,
            inviteCode = code,
            ownerId = userId,
            createdAt = now,
            role = GroupRole.OWNER.name,
            memberCount = 1,
            activityCount = 0,
            syncState = SyncState.SYNCED.name,
            cachedAt = now,
        )
        db.groupDao().upsert(entity)
        db.groupDao().upsertMembers(
            listOf(
                GroupMemberEntity(
                    groupId = groupId,
                    userId = userId,
                    displayName = currentUser().displayName,
                    role = GroupRole.OWNER.name,
                    photoUri = null,
                    joinedAt = now,
                ),
            ),
        )
        return groupDto(entity, userId)
    }

    override suspend fun joinGroup(body: JoinGroupRequest): GroupDto {
        ensureDemoAccount()
        val userId = currentUser().id
        val code = InviteCode.normalise(body.inviteCode)
        if (!InviteCode.isValid(code)) throw httpError(404, "That invite code does not match a group.")
        val entity = db.groupDao().byInviteCode(code)
            ?: throw httpError(404, "That invite code does not match a group.")
        val existing = db.groupDao().members(entity.id).any { it.userId == userId }
        if (!existing) {
            val member = GroupMemberEntity(
                groupId = entity.id,
                userId = userId,
                displayName = currentUser().displayName,
                role = GroupRole.MEMBER.name,
                photoUri = null,
                joinedAt = time.nowMillis(),
            )
            db.groupDao().upsertMembers(listOf(member))
            db.groupDao().updateCounts(
                entity.id,
                members = db.groupDao().memberCount(entity.id),
                activities = db.activityDao().countByGroup(entity.id),
            )
            notifyMembers(
                groupId = entity.id,
                type = NotificationType.INVITE,
                title = "${currentUser().displayName} joined ${entity.name}",
                body = "Say hi and add an idea to the Vibe List.",
                excludeUserId = userId,
            )
        }
        return groupDto(entity, userId)
    }

    override suspend fun leaveGroup(groupId: String): Response<Unit> {
        ensureDemoAccount()
        val userId = currentUser().id
        val entity = db.groupDao().byId(groupId) ?: throw httpError(404, "Group not found.")
        if (entity.ownerId == userId) {
            throw httpError(409, "Group owners must hand over the group before leaving.")
        }
        db.groupDao().deleteMember(groupId, userId)
        db.groupDao().updateCounts(groupId, db.groupDao().memberCount(groupId), db.activityDao().countByGroup(groupId))
        return Response.success(Unit)
    }

    // -------------------------------------------------------- activities

    override suspend fun activities(groupId: String): List<ActivityDto> {
        ensureDemoAccount()
        return db.activityDao().byGroup(groupId)
            .sortedByDescending { it.createdAt }
            .map { it.toDto() }
    }

    override suspend fun myActivities(): List<ActivityDto> {
        ensureDemoAccount()
        val userId = currentUser().id
        return db.activityDao().mine(userId).map { it.toDto() }
    }

    override suspend fun addActivity(groupId: String, body: UpsertActivityRequest): ActivityDto {
        ensureDemoAccount()
        val user = currentUser()
        val now = time.nowMillis()
        val entity = ActivityEntity(
            id = body.clientId?.takeIf { it.isNotBlank() } ?: Ids.newId(),
            groupId = groupId,
            title = body.title.trim(),
            description = body.description.trim(),
            icon = body.icon,
            status = ActivityStatus.SUGGESTED.name,
            createdBy = user.id,
            createdByName = user.displayName,
            createdAt = now,
            favourite = body.favourite ?: false,
            yesVotes = 0,
            participantCount = 0,
            syncState = SyncState.SYNCED.name,
        )
        db.activityDao().upsert(entity)
        db.groupDao().updateCounts(groupId, db.groupDao().memberCount(groupId), db.activityDao().countByGroup(groupId))
        notifyMembers(
            groupId = groupId,
            type = NotificationType.NEW_ACTIVITY,
            title = "${user.displayName} added ${entity.title}",
            body = "It is on the Vibe List and ready for the next decision.",
            excludeUserId = user.id,
        )
        return entity.toDto()
    }

    override suspend fun updateActivity(activityId: String, body: UpsertActivityRequest): ActivityDto {
        ensureDemoAccount()
        val existing = db.activityDao().byId(activityId) ?: throw httpError(404, "Activity not found.")
        val updated = existing.copy(
            title = body.title.trim(),
            description = body.description.trim(),
            icon = body.icon,
            favourite = body.favourite ?: existing.favourite,
            status = body.status ?: existing.status,
        )
        db.activityDao().upsert(updated)
        return updated.toDto()
    }

    override suspend fun deleteActivity(activityId: String): Response<Unit> {
        ensureDemoAccount()
        db.activityDao().delete(activityId)
        return Response.success(Unit)
    }

    // --------------------------------------------------------- decisions

    override suspend fun startDecision(groupId: String, body: StartDecisionRequest): DecisionDto {
        ensureDemoAccount()
        val user = currentUser()
        val group = db.groupDao().byId(groupId) ?: throw httpError(404, "Group not found.")
        if (group.ownerId != user.id) throw httpError(403, "Only the group owner can start a round.")

        val eligible = db.activityDao().eligibleForRound(groupId)
            .filter { body.activityIds.isEmpty() || it.id in body.activityIds }
        Validators.roundActivities(eligible.size)
            ?.let { throw httpError(422, "Add at least two activities before starting a round.") }

        eligible.forEach { db.activityDao().updateStatus(it.id, ActivityStatus.ACTIVE.name) }

        val now = time.nowMillis()
        val decision = DecisionEntity(
            id = body.clientId?.takeIf { it.isNotBlank() } ?: Ids.newId(),
            groupId = groupId,
            roundNumber = 1,
            state = DecisionState.OPEN.name,
            deadlineEpochMillis = body.deadlineEpochMillis ?: (now + ROUND_DEADLINE_HOURS * 60L * 60L * 1000L),
            winnerActivityId = null,
            startedBy = user.id,
            startedAt = now,
            talliesCsv = "",
            survivorIdsCsv = Codecs.encodeList(eligible.map { it.id }),
            eliminatedIdsCsv = "",
            roundActivityIdsCsv = Codecs.encodeList(eligible.map { it.id }),
        )
        db.decisionDao().upsert(decision)
        notifyMembers(
            groupId = groupId,
            type = NotificationType.DEADLINE,
            title = "Round 1 is open in ${group.name}",
            body = "Cast your YES or NO vote on ${eligible.size} ideas.",
            excludeUserId = user.id,
        )
        return decisionDto(decision)
    }

    override suspend fun decision(decisionId: String): DecisionDto {
        ensureDemoAccount()
        val entity = db.decisionDao().byId(decisionId) ?: throw httpError(404, "Decision not found.")
        return decisionDto(entity)
    }

    override suspend fun castVote(decisionId: String, body: CastVoteRequest): VoteAckDto {
        ensureDemoAccount()
        val user = currentUser()
        val decision = db.decisionDao().byId(decisionId) ?: throw httpError(404, "Decision not found.")
        if (decision.state != DecisionState.OPEN.name) {
            throw httpError(409, "This round is already closed.")
        }
        val existing = db.voteDao().find(decisionId, body.activityId, user.id)
        val vote = VoteEntity(
            decisionId = decisionId,
            activityId = body.activityId,
            userId = user.id,
            voteId = existing?.voteId ?: Ids.newId(),
            choice = body.choice,
            epochMillis = if (body.clientTimestampEpochMillis > 0) {
                body.clientTimestampEpochMillis
            } else {
                time.nowMillis()
            },
            syncState = SyncState.SYNCED.name,
        )
        db.voteDao().upsert(vote)
        val current = settleIfRoundFinished(decision)
        // Follow the group into the next round when this one just advanced.
        simulateRemainingMembers(current.id)
        return VoteAckDto(voteId = vote.voteId, decision = decisionDto(current))
    }

    override suspend fun closeDecision(decisionId: String): DecisionDto {
        ensureDemoAccount()
        val decision = db.decisionDao().byId(decisionId) ?: throw httpError(404, "Decision not found.")
        val settled = settle(decision)
        return decisionDto(settled)
    }

    override suspend fun surprise(decisionId: String, body: SurpriseRequest): ActivityDto {
        ensureDemoAccount()
        val decision = db.decisionDao().byId(decisionId) ?: throw httpError(404, "Decision not found.")
        val candidates = db.activityDao().byGroup(decision.groupId).map { it.toDomainIdea() }
        val pick = DecisionEngine.surpriseMe(candidates, body.rejectedIds.toSet())
            ?: throw httpError(404, "No eligible activity yet.")
        return db.activityDao().byId(pick.id)!!.toDto()
    }

    // -------------------------------------------------------------- plans

    override suspend fun plans(groupId: String): List<PlanDto> {
        ensureDemoAccount()
        return db.planDao().observeByGroup(groupId).firstValue().map { it.toDto() }
    }

    override suspend fun savePlan(body: SavePlanRequest): PlanDto {
        ensureDemoAccount()
        val user = currentUser()
        val activity = db.activityDao().byId(body.activityId) ?: throw httpError(404, "Activity not found.")
        val existing = db.planDao().latestForActivity(body.activityId)
        val plan = PlanEntity(
            id = existing?.id ?: Ids.newId(),
            groupId = body.groupId,
            activityId = body.activityId,
            activityTitle = activity.title,
            activityIcon = activity.icon,
            scheduledAtEpochMillis = body.scheduledAtEpochMillis,
            completed = existing?.completed ?: false,
            createdBy = user.id,
        )
        db.planDao().upsert(plan)
        return plan.toDto()
    }

    override suspend fun completePlan(planId: String, body: CompletePlanRequest): MemoryDto {
        ensureDemoAccount()
        val user = currentUser()
        val plan = db.planDao().byId(planId) ?: throw httpError(404, "Plan not found.")
        db.planDao().upsert(plan.copy(completed = true))
        db.activityDao().updateStatus(plan.activityId, ActivityStatus.COMPLETED.name)
        val memory = MemoryEntity(
            id = Ids.newId(),
            groupId = plan.groupId,
            planId = plan.id,
            activityId = plan.activityId,
            activityTitle = plan.activityTitle,
            activityIcon = plan.activityIcon,
            completedAtEpochMillis = time.nowMillis(),
            caption = body.caption,
            rating = body.rating,
            photoUrisCsv = Codecs.encodeList(body.photoUris),
            createdBy = user.id,
            syncState = SyncState.SYNCED.name,
        )
        db.memoryDao().upsert(memory)
        notifyMembers(
            groupId = plan.groupId,
            type = NotificationType.MEMORY,
            title = "${plan.activityTitle} is now a memory",
            body = "${user.displayName} rated it ${body.rating}/5.",
            excludeUserId = user.id,
        )
        return memory.toDto()
    }

    override suspend fun memories(groupId: String): List<MemoryDto> {
        ensureDemoAccount()
        return db.memoryDao().observeByGroup(groupId).firstValue().map { it.toDto() }
    }

    // ------------------------------------------- notifications and devices

    override suspend fun notifications(): List<NotificationDto> {
        ensureDemoAccount()
        val userId = currentUser().id
        return db.notificationDao().observeAll().firstValue()
            .filter { it.userId == userId }
            .map { it.toDto() }
    }

    override suspend fun markNotificationRead(notificationId: String): Response<Unit> {
        db.notificationDao().markRead(notificationId)
        return Response.success(Unit)
    }

    override suspend fun markAllNotificationsRead(): Response<Unit> {
        db.notificationDao().markAllRead()
        return Response.success(Unit)
    }

    override suspend fun registerDevice(body: RegisterDeviceRequest): Response<Unit> = Response.success(Unit)

    /**
     * The demo backend *is* the local store, so a queued action is applied by
     * definition. Conflict reporting is exercised by `SyncManagerTest` against a
     * fake API and by the real service in `api-container/`.
     */
    override suspend fun syncBatch(body: SyncBatchRequest): SyncBatchResponse =
        SyncBatchResponse(results = body.items.map { SyncResultDto(clientId = it.clientId, status = "applied") })

    // ------------------------------------------------------------- helpers

    private suspend fun currentUser(): UserEntity {
        val id = currentUserId()
        val fromStore = id?.let { db.userDao().byId(it) }
        return fromStore ?: db.userDao().byId(DEMO_USER_ID) ?: demoUserEntity()
    }

    private fun demoUserEntity() = UserEntity(
        id = DEMO_USER_ID,
        displayName = DEMO_DISPLAY_NAME,
        username = "lizzy_vibes",
        email = DEMO_EMAIL,
        language = "en",
        themeMode = "LIGHT",
        notificationsEnabled = true,
        privacyMembersOnly = true,
        photoUri = null,
        createdAt = time.nowMillis(),
    )

    private suspend fun authResponse(user: UserEntity): AuthResponse {
        val now = time.nowMillis()
        return AuthResponse(
            accessToken = "demo-access-${user.id}",
            refreshToken = "demo-refresh-${user.id}",
            expiresAtEpochMillis = now + 60L * 60L * 1000L,
            user = user.toDto(),
        )
    }

    private suspend fun generateUniqueInviteCode(): String {
        repeat(10) {
            val code = InviteCode.generate(Random(time.nowMillis() + it))
            if (db.groupDao().byInviteCode(code) == null) return code
        }
        return InviteCode.generate()
    }

    private suspend fun groupDto(entity: GroupEntity, userId: String): GroupDto {
        val members = db.groupDao().members(entity.id)
        return GroupDto(
            id = entity.id,
            name = entity.name,
            icon = entity.icon,
            inviteCode = entity.inviteCode,
            ownerId = entity.ownerId,
            createdAtEpochMillis = entity.createdAt,
            role = members.firstOrNull { it.userId == userId }?.role ?: GroupRole.MEMBER.name,
            memberCount = members.size.coerceAtLeast(1),
            activityCount = db.activityDao().countByGroup(entity.id),
            members = members.map {
                MemberDto(
                    userId = it.userId,
                    displayName = it.displayName,
                    role = it.role,
                    photoUri = it.photoUri,
                    joinedAtEpochMillis = it.joinedAt,
                )
            },
        )
    }

    private suspend fun decisionDto(entity: DecisionEntity): DecisionDto {
        val members = db.groupDao().members(entity.groupId).map { it.userId }
        val roundIds = Codecs.decodeList(entity.roundActivityIdsCsv)
        val ideas = db.activityDao().byGroup(entity.groupId)
            .filter { it.id in roundIds }
            .map { it.toDomainIdea() }
        val votes = db.voteDao().byDecision(entity.id).map {
            VoteChoice(activityId = it.activityId, userId = it.userId, choice = it.choice, epochMillis = it.epochMillis)
        }
        val evaluation = DecisionEngine.evaluate(
            com.vibe.app.domain.RoundInput(
                roundNumber = entity.roundNumber,
                state = when (entity.state) {
                    DecisionState.OPEN.name -> RoundState.OPEN
                    DecisionState.CLOSED.name -> RoundState.CLOSED
                    else -> RoundState.SETTLED
                },
                nowEpochMillis = time.nowMillis(),
                memberIds = members,
                activities = ideas,
                votes = votes,
                deadlineEpochMillis = entity.deadlineEpochMillis,
            ),
        )
        val revealed = evaluation.votesRevealed
        return DecisionDto(
            id = entity.id,
            groupId = entity.groupId,
            roundNumber = entity.roundNumber,
            state = entity.state,
            deadlineEpochMillis = entity.deadlineEpochMillis,
            winnerActivityId = entity.winnerActivityId,
            startedBy = entity.startedBy,
            startedAtEpochMillis = entity.startedAt,
            participation = ParticipationDto(
                memberCount = evaluation.participation.memberCount,
                votedCount = evaluation.participation.votedCount,
                pendingMemberIds = evaluation.participation.pendingMemberIds,
            ),
            // Never leak the score while the round is still open.
            tallies = if (revealed) {
                evaluation.tallies.map { TallyDto(it.activityId, it.yes, it.no, it.pending) }
            } else {
                emptyList()
            },
            survivorIds = if (revealed) evaluation.survivorIds else emptyList(),
            eliminatedIds = if (revealed) evaluation.eliminatedIds else emptyList(),
            votesRevealed = revealed,
            canCloseEarly = evaluation.canCloseEarly,
            outcome = when (val outcome = evaluation.outcome) {
                is RoundOutcome.InProgress -> "inProgress"
                is RoundOutcome.Winner -> "winner"
                is RoundOutcome.ContinueRounds -> "continue"
                is RoundOutcome.Tie -> "tie"
                is RoundOutcome.NoWinner -> "none"
            },
        )
    }

    /** Closes and settles the round when the members have spoken (or can no longer change it). */
    private suspend fun settleIfRoundFinished(decision: DecisionEntity): DecisionEntity {
        val evaluation = evaluate(decision)
        val finished = evaluation.votesRevealed || evaluation.canCloseEarly
        return if (finished) settle(decision, evaluation) else decision
    }

    private suspend fun evaluate(decision: DecisionEntity): RoundEvaluation {
        val members = db.groupDao().members(decision.groupId).map { it.userId }
        val roundIds = Codecs.decodeList(decision.roundActivityIdsCsv)
        val ideas = db.activityDao().byGroup(decision.groupId).filter { it.id in roundIds }.map { it.toDomainIdea() }
        val votes = db.voteDao().byDecision(decision.id).map {
            VoteChoice(it.activityId, it.userId, it.choice, it.epochMillis)
        }
        return DecisionEngine.evaluate(
            com.vibe.app.domain.RoundInput(
                roundNumber = decision.roundNumber,
                state = if (decision.state == DecisionState.OPEN.name) RoundState.OPEN else RoundState.CLOSED,
                nowEpochMillis = time.nowMillis(),
                memberIds = members,
                activities = ideas,
                votes = votes,
                deadlineEpochMillis = decision.deadlineEpochMillis,
            ),
        )
    }

    private suspend fun settle(decision: DecisionEntity, evaluation: RoundEvaluation? = null): DecisionEntity {
        val eval = evaluation ?: evaluate(decision)
        val now = time.nowMillis()
        val roundIds = Codecs.decodeList(decision.roundActivityIdsCsv)
        fun settled(state: DecisionState, winner: String?) = decision.copy(
            state = state.name,
            winnerActivityId = winner,
            talliesCsv = Codecs.encodeTallies(eval.tallies),
            survivorIdsCsv = Codecs.encodeList(eval.survivorIds),
            eliminatedIdsCsv = Codecs.encodeList(eval.eliminatedIds),
            roundActivityIdsCsv = Codecs.encodeList(roundIds),
        )

        return when (val outcome = eval.outcome) {
            is RoundOutcome.Winner -> {
                db.activityDao().updateStatus(outcome.activityId, ActivityStatus.WINNER.name)
                eval.eliminatedIds.forEach { db.activityDao().updateStatus(it, ActivityStatus.ELIMINATED.name) }
                val updated = settled(DecisionState.SETTLED, outcome.activityId)
                db.decisionDao().upsert(updated)
                val title = db.activityDao().byId(outcome.activityId)?.title ?: "your plan"
                notifyMembers(
                    groupId = decision.groupId,
                    type = NotificationType.WINNER,
                    title = "Winner: $title",
                    body = "The group agreed. Open the plan and lock in a date.",
                    excludeUserId = null,
                )
                updated
            }

            is RoundOutcome.ContinueRounds -> {
                eval.eliminatedIds.forEach { db.activityDao().updateStatus(it, ActivityStatus.ELIMINATED.name) }
                outcome.survivorIds.forEach { db.activityDao().updateStatus(it, ActivityStatus.ACTIVE.name) }
                db.decisionDao().upsert(settled(DecisionState.CLOSED, null))
                val next = DecisionEntity(
                    id = Ids.newId(),
                    groupId = decision.groupId,
                    roundNumber = outcome.nextRoundNumber,
                    state = DecisionState.OPEN.name,
                    deadlineEpochMillis = now + ROUND_DEADLINE_HOURS * 60L * 60L * 1000L,
                    winnerActivityId = null,
                    startedBy = decision.startedBy,
                    startedAt = now,
                    talliesCsv = "",
                    survivorIdsCsv = Codecs.encodeList(outcome.survivorIds),
                    eliminatedIdsCsv = Codecs.encodeList(eval.eliminatedIds),
                    roundActivityIdsCsv = Codecs.encodeList(outcome.survivorIds),
                )
                db.decisionDao().upsert(next)
                notifyMembers(
                    groupId = decision.groupId,
                    type = NotificationType.DEADLINE,
                    title = "Round ${outcome.nextRoundNumber} is open",
                    body = "${outcome.survivorIds.size} ideas are still in the running.",
                    excludeUserId = null,
                )
                next
            }

            is RoundOutcome.Tie, is RoundOutcome.NoWinner -> {
                eval.eliminatedIds.forEach { db.activityDao().updateStatus(it, ActivityStatus.ELIMINATED.name) }
                val updated = settled(DecisionState.CLOSED, null)
                db.decisionDao().upsert(updated)
                notifyMembers(
                    groupId = decision.groupId,
                    type = NotificationType.DEADLINE,
                    title = "Round ended level",
                    body = "Add a fresh idea, or let Surprise Me break the tie.",
                    excludeUserId = null,
                )
                updated
            }

            is RoundOutcome.InProgress -> decision
        }
    }

    /**
     * Lets the other members answer one at a time so the participation counter is
     * visible. Votes are deterministic per member and activity, so a demo run is
     * repeatable.
     */
    private fun simulateRemainingMembers(decisionId: String) {
        scope.launch {
            repeat(12) {
                delay(simulatedVoteDelayMillis)
                val decision = db.decisionDao().byId(decisionId) ?: return@launch
                if (decision.state != DecisionState.OPEN.name) return@launch
                val roundIds = Codecs.decodeList(decision.roundActivityIdsCsv)
                val activities = db.activityDao().byGroup(decision.groupId).filter { it.id in roundIds }
                val votes = db.voteDao().byDecision(decisionId)
                val members = db.groupDao().members(decision.groupId).map { it.userId }
                val pendingMember = members.firstOrNull { member ->
                    activities.any { activity -> votes.none { it.activityId == activity.id && it.userId == member } }
                } ?: return@launch

                activities.forEach { activity ->
                    val already = db.voteDao().find(decisionId, activity.id, pendingMember)
                    if (already == null) {
                        val seed = (pendingMember + activity.id).hashCode()
                        db.voteDao().upsert(
                            VoteEntity(
                                decisionId = decisionId,
                                activityId = activity.id,
                                userId = pendingMember,
                                voteId = Ids.newId(),
                                choice = Random(seed).nextInt(100) < 58,
                                epochMillis = time.nowMillis(),
                                syncState = SyncState.SYNCED.name,
                            ),
                        )
                    }
                }
                settleIfRoundFinished(decision)
            }
        }
    }

    private suspend fun notifyMembers(
        groupId: String,
        type: NotificationType,
        title: String,
        body: String,
        excludeUserId: String?,
    ) {
        val now = time.nowMillis()
        val recipients = db.groupDao().members(groupId).map { it.userId }.filter { it != excludeUserId }
        if (recipients.isEmpty()) return
        db.notificationDao().upsert(
            recipients.map { userId ->
                NotificationEntity(
                    id = Ids.newId(),
                    userId = userId,
                    title = title,
                    body = body,
                    type = type.name,
                    read = false,
                    createdAt = now,
                    groupId = groupId,
                )
            },
        )
    }

    private fun httpError(code: Int, message: String): HttpException =
        HttpException(Response.error<Unit>(code, message.toResponseBody("text/plain".toMediaType())))

    private fun usernameFrom(email: String): String =
        email.substringBefore('@').filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT).ifBlank { "viber" }

    // ------------------------------------------------------- demo account

    /**
     * The bundled backend starts empty: the only thing it makes sure of is that the
     * demo account exists, so "log in as lerato@vibe.app" works before registration.
     * Groups, ideas, plans, memories and notifications are never created here - the
     * member creates them, and they live in RoomDB exactly like the real API's rows.
     */
    private suspend fun ensureDemoAccount() {
        if (demoAccountReady) return
        accountLock.withLock {
            if (demoAccountReady) return
            if (db.userDao().byId(DEMO_USER_ID) == null) {
                db.userDao().upsert(demoUserEntity())
            }
            demoAccountReady = true
        }
    }
}

private fun UserEntity.toDto() = UserDto(
    id = id,
    displayName = displayName,
    username = username,
    email = email,
    language = language,
    themeMode = themeMode,
    notificationsEnabled = notificationsEnabled,
    privacyMembersOnly = privacyMembersOnly,
    photoUri = photoUri,
    createdAtEpochMillis = createdAt,
)

private fun ActivityEntity.toDto() = ActivityDto(
    id = id,
    groupId = groupId,
    title = title,
    description = description,
    icon = icon,
    status = status,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAtEpochMillis = createdAt,
    favourite = favourite,
    yesVotes = yesVotes,
    participantCount = participantCount,
)

private fun ActivityEntity.toDomainIdea() = com.vibe.app.domain.ActivityIdea(
    id = id,
    title = title,
    icon = icon,
    status = runCatching { ActivityStatus.valueOf(status) }.getOrDefault(ActivityStatus.SUGGESTED),
    createdAt = createdAt,
    createdBy = createdBy,
)

private fun PlanEntity.toDto() = PlanDto(
    id = id,
    groupId = groupId,
    activityId = activityId,
    activityTitle = activityTitle,
    activityIcon = activityIcon,
    scheduledAtEpochMillis = scheduledAtEpochMillis,
    completed = completed,
    createdBy = createdBy,
)

private fun MemoryEntity.toDto() = MemoryDto(
    id = id,
    groupId = groupId,
    planId = planId,
    activityId = activityId,
    activityTitle = activityTitle,
    activityIcon = activityIcon,
    completedAtEpochMillis = completedAtEpochMillis,
    caption = caption,
    rating = rating,
    photoUris = Codecs.decodeList(photoUrisCsv),
    createdBy = createdBy,
)

private fun NotificationEntity.toDto() = NotificationDto(
    id = id,
    userId = userId,
    title = title,
    body = body,
    type = type,
    read = read,
    createdAtEpochMillis = createdAt,
    groupId = groupId,
)

/** Reads the single value of a Room `Flow` for one-shot API-style calls. */
private suspend fun <T> Flow<T>.firstValue(): T = first()
