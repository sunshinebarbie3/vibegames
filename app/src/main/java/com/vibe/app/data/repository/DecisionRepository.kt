package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.Codecs
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.Decision
import com.vibe.app.domain.DecisionRound
import com.vibe.app.domain.DecisionState
import com.vibe.app.domain.RoundOutcome
import com.vibe.app.domain.RoundState
import com.vibe.app.domain.SyncState
import com.vibe.app.domain.Tally
import com.vibe.app.domain.Vote
import com.vibe.app.domain.VoteChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Decide For Us: YES/NO decision rounds.
 *
 * Decisions and votes are stored directly in Firestore. Vote document IDs are
 * predictable: `{decisionId}:{activityId}:{userId}`, which makes it easy to
 * enforce "one vote per member per idea" in Firestore Security Rules and to
 * detect duplicate votes client-side.
 *
 * The DecisionEngine (pure Kotlin) evaluates tallies and outcomes from the
 * stored votes, so the client and any other client can never disagree about
 * who won.
 */
class DecisionRepository(
    private val firestore: FirestoreProvider,
    private val database: VibeDatabase,
    private val settings: SettingsStore,
    private val time: TimeProvider,
) {

    fun observeRound(id: String): Flow<DecisionRound?> =
        combine(
            database.decisionDao().observeById(id),
            database.voteDao().observeByDecision(id),
            database.activityDao().observeAll(),
        ) { decisionEntity, voteEntities, activityEntities ->
            decisionEntity?.let { buildRound(it, voteEntities, activityEntities) }
        }.flatMapLatest { flowOf(it) }

    fun observeCurrentRound(groupId: String) =
        database.decisionDao().observeLatestOpen(groupId).flatMapLatest {
            if (it == null) flowOf(null) else observeRound(it.id)
        }

    fun observeLatestWinner(groupId: String) =
        database.decisionDao().observeLatestSettled(groupId).flatMapLatest {
            if (it == null) flowOf(null) else observeRound(it.id)
        }

    fun observeHistory(groupId: String) =
        database.decisionDao().observeByGroup(groupId).map { rows -> rows.map { it.toDomain() } }

    suspend fun currentRoundId(groupId: String) = database.decisionDao().latestOpenId(groupId)

    suspend fun startRound(groupId: String, activityIds: List<String> = emptyList(), roundNumber: Int = 1): VibeResult<String> {
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in")
        val group = database.groupDao().byId(groupId) ?: return VibeResult.Problem(detail = "Group not found")
        if (group.ownerId != uid) return VibeResult.Problem(detail = "Only the group owner can start a round.")
        val eligible = database.activityDao().eligibleForRound(groupId).filter { activityIds.isEmpty() || it.id in activityIds }
        if (eligible.size < 2) return VibeResult.Problem(detail = "Add at least two eligible ideas before starting a round.")
        database.decisionDao().latestOpenId(groupId)?.let { return VibeResult.Ok(it) }

        val id = Ids.newId()
        val ids = eligible.map { it.id }
        val now = time.nowMillis()

        val decision = Decision(
            id = id,
            groupId = groupId,
            roundNumber = roundNumber,
            state = DecisionState.OPEN,
            deadlineEpochMillis = null,
            winnerActivityId = null,
            startedBy = uid,
            startedAt = now,
        )

        // Write to Room immediately for offline-first, then to Firestore.
        database.decisionDao().upsert(decision.toEntity(
            tallies = emptyList(),
            survivorIds = emptyList(),
            eliminatedIds = emptyList(),
            roundActivityIds = ids,
        ))

        return runCatching {
            firestore.createDecision(decision)
            VibeResult.Ok(id)
        }.getOrElse {
            // Already in Room; will sync when online via Firestore persistence.
            VibeResult.Ok(id)
        }
    }

    suspend fun castVote(decisionId: String, activityId: String, choice: Boolean): VibeResult<String> {
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in")
        val decision = database.decisionDao().byId(decisionId) ?: return VibeResult.Problem(detail = "Round not found")
        if (decision.state != DecisionState.OPEN) return VibeResult.Problem(detail = "This round is closed.")
        val ids = Codecs.decodeList(decision.roundActivityIdsCsv)
        if (activityId !in ids) return VibeResult.Problem(detail = "This idea is not in this round.")

        // Prevent duplicate votes: one vote per member per idea.
        val existing = database.voteDao().find(decisionId, activityId, uid)
        if (existing != null) {
            if (existing.choice == choice) return VibeResult.Ok(decisionId)
            return VibeResult.Problem(detail = "Your vote is already recorded.")
        }

        val timestamp = time.nowMillis()
        val voteId = "$decisionId:$activityId:$uid"

        // Write to Room immediately.
        val vote = Vote(
            id = voteId,
            decisionId = decisionId,
            activityId = activityId,
            userId = uid,
            choice = choice,
            epochMillis = timestamp,
            syncState = SyncState.PENDING,
        )
        database.voteDao().upsert(vote.toEntity())

        return runCatching {
            firestore.castVote(decisionId, activityId, uid, choice, timestamp)
            withContext(Dispatchers.IO) {
                database.voteDao().updateSyncState(decisionId, activityId, uid, SyncState.SYNCED.name)
            }
            VibeResult.Ok(decisionId)
        }.getOrElse {
            // Keep the pending vote in Room; it will sync when online.
            VibeResult.Ok(decisionId)
        }
    }

    suspend fun closeRound(id: String): VibeResult<String> {
        val d = database.decisionDao().byId(id) ?: return VibeResult.Problem(detail = "Round not found")
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in")
        val group = database.groupDao().byId(d.groupId)
        if (group == null || group.ownerId != uid) return VibeResult.Problem(detail = "Only the group owner can close a round.")

        // Evaluate the outcome from stored votes.
        val votes = database.voteDao().byDecision(id)
        val activities = database.activityDao().byGroup(d.groupId)
        val memberIds = group.members.map { it.userId }
        val eligible = activities.filter { it.status.isEligibleForRounds }
        val input = com.vibe.app.domain.RoundInput(
            roundNumber = d.roundNumber,
            state = RoundState.OPEN,
            nowEpochMillis = time.nowMillis(),
            memberIds = memberIds,
            activities = eligible.map { it.toDomain().toIdea() },
            votes = votes.map { VoteChoice(it.activityId, it.userId, it.choice, it.epochMillis) },
            deadlineEpochMillis = d.deadlineEpochMillis,
        )
        val evaluation = com.vibe.app.domain.DecisionEngine.evaluate(input)

        val newState = when {
            evaluation.outcome is com.vibe.app.domain.RoundOutcome.ContinueRounds -> DecisionState.CLOSED
            else -> DecisionState.SETTLED
        }
        val winnerId = when (evaluation.outcome) {
            is com.vibe.app.domain.RoundOutcome.Winner -> evaluation.outcome.activityId
            else -> null
        }

        val updated = d.copy(state = newState, winnerActivityId = winnerId)
        database.decisionDao().upsert(updated.toEntity(
            tallies = evaluation.tallies,
            survivorIds = evaluation.survivorIds,
            eliminatedIds = evaluation.eliminatedIds,
            roundActivityIds = Codecs.decodeList(d.roundActivityIdsCsv),
        ))

        // Update activity statuses
        for (activity in activities) {
            val newStatus = when {
                winnerId == activity.id -> ActivityStatus.WINNER
                evaluation.eliminatedIds.contains(activity.id) -> ActivityStatus.ELIMINATED
                else -> activity.status
            }
            database.activityDao().updateStatus(activity.id, newStatus.name)
        }

        return runCatching {
            firestore.updateDecision(updated)
            VibeResult.Ok(id)
        }.getOrElse { VibeResult.Ok(id) }
    }

    suspend fun refreshRound(id: String): VibeResult<DecisionRound> {
        val decision = database.decisionDao().byId(id) ?: return VibeResult.Problem(detail = "Round not found")
        return runCatching {
            val votes = firestore.getVotesForDecision(id)
            val activities = database.activityDao().byGroup(decision.groupId)
            withContext(Dispatchers.IO) {
                for (vote in votes) {
                    val existing = database.voteDao().find(id, vote.activityId, vote.userId)
                    if (existing == null || existing.syncState != SyncState.PENDING.name) {
                        database.voteDao().upsert(vote.toEntity())
                    }
                }
            }
            val voteEntities = database.voteDao().byDecision(id)
            val activityEntities = database.activityDao().byGroup(decision.groupId)
            val round = buildRound(decision, voteEntities, activityEntities)
            VibeResult.Ok(round)
        }.getOrElse { VibeResult.Problem(detail = "Could not refresh round.") }
    }

    suspend fun surprise(id: String, rejected: List<String>): VibeResult<Activity> {
        val decision = database.decisionDao().byId(id) ?: return VibeResult.Problem(detail = "Round not found")
        val activities = database.activityDao().byGroup(decision.groupId)
        val ids = Codecs.decodeList(decision.roundActivityIdsCsv)
        val eligible = activities.filter { it.id in ids && it.id !in rejected && it.status.isEligibleForRounds }
        if (eligible.isEmpty()) return VibeResult.Problem(detail = "No eligible ideas remain.")
        val chosen = eligible.random()
        return VibeResult.Ok(chosen.toDomain())
    }

    private suspend fun buildRound(
        entity: com.vibe.app.data.local.DecisionEntity,
        votes: List<com.vibe.app.data.local.VoteEntity>,
        activities: List<com.vibe.app.data.local.ActivityEntity>,
    ): DecisionRound {
        val ids = Codecs.decodeList(entity.roundActivityIdsCsv)
        val ideas = activities.filter { it.id in ids }.map { it.toDomain().toIdea().copy(status = ActivityStatus.ACTIVE) }
        val members = database.groupDao().members(entity.groupId).map { it.userId }
        val voteChoices = votes.map { VoteChoice(it.activityId, it.userId, it.choice, it.epochMillis) }
        val input = com.vibe.app.domain.RoundInput(
            roundNumber = entity.roundNumber,
            state = if (entity.state == DecisionState.OPEN.name) RoundState.OPEN else RoundState.SETTLED,
            nowEpochMillis = time.nowMillis(),
            memberIds = members,
            activities = ideas,
            votes = voteChoices,
            deadlineEpochMillis = entity.deadlineEpochMillis,
        )
        val eval = com.vibe.app.domain.DecisionEngine.evaluate(input)

        val tallies = if (entity.state != DecisionState.OPEN) eval.tallies else emptyList()
        val survivors = if (entity.state != DecisionState.OPEN) eval.survivorIds else emptyList()
        val eliminated = if (entity.state != DecisionState.OPEN) eval.eliminatedIds else emptyList()

        val outcome = when (entity.state) {
            DecisionState.OPEN -> com.vibe.app.domain.RoundOutcome.InProgress(eval.participation.pendingMemberIds)
            DecisionState.CLOSED -> when {
                eval.survivorIds.size > 1 -> com.vibe.app.domain.RoundOutcome.Tie(eval.survivorIds)
                else -> com.vibe.app.domain.RoundOutcome.NoWinner
            }
            DecisionState.SETTLED -> when {
                entity.winnerActivityId != null -> com.vibe.app.domain.RoundOutcome.Winner(entity.winnerActivityId!!)
                eval.survivorIds.size > 1 -> com.vibe.app.domain.RoundOutcome.Tie(eval.survivorIds)
                else -> com.vibe.app.domain.RoundOutcome.NoWinner
            }
        }

        return DecisionRound(
            decision = entity.toDomain(),
            participation = eval.participation,
            tallies = tallies,
            survivorIds = survivors,
            eliminatedIds = eliminated,
            votesRevealed = entity.state != DecisionState.OPEN,
            canCloseEarly = eval.canCloseEarly,
            outcome = outcome,
        )
    }

    fun isRoundDecided(round: DecisionRound) =
        round.outcome is RoundOutcome.Winner || round.outcome is RoundOutcome.Tie || round.outcome is RoundOutcome.NoWinner
}
