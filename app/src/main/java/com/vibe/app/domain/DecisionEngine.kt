package com.vibe.app.domain

import kotlin.random.Random

/**
 * "Decide For Us": the progressive YES/NO decision game.
 *
 * Rules implemented here (design document, section 2 and 3):
 *  - every eligible activity enters a round; each member casts one YES/NO vote
 *    per activity, so every voice carries exactly the same weight;
 *  - an activity progresses when it has more YES than NO votes;
 *  - an activity that can no longer reach a majority drops out ("weak options
 *    are removed");
 *  - nothing is revealed to the group until the round closes;
 *  - survivors go into the next round until one winner remains, and the final
 *    comparison is the last round that still has more than one survivor;
 *  - a round can be closed early the moment only one activity could still win.
 *
 * The engine is pure: hand it the round, the members and the votes, and it
 * returns the outcome. The same rules are mirrored by the Firebase-backed REST API
 * (`server/src/domain.js`) so the server and the offline
 * cache can never disagree about who won.
 */
object DecisionEngine {

    /** A group cannot keep voting forever: after this many rounds it is a tie. */
    const val MAX_ROUNDS = 5

    fun evaluate(input: RoundInput): RoundEvaluation {
        val eligible = input.activities.filter { it.status.isEligibleForRounds }
        val members = input.memberIds.distinct()
        val memberSet = members.toSet()
        val votesByActivity = input.votes.groupBy { it.activityId }

        val tallies = eligible.map { idea ->
            // One YES/NO vote per member per activity: a later vote replaces an
            // earlier one, and a vote from outside the group never counts.
            val votes = votesByActivity[idea.id].orEmpty()
                .filter { it.userId in memberSet }
                .groupBy { it.userId }
                .map { (_, perMember) -> perMember.maxBy { it.epochMillis } }
            val yes = votes.count { it.choice }
            val no = votes.count { !it.choice }
            val votedMembers = votes.map { it.userId }.toSet()
            Tally(
                activityId = idea.id,
                yes = yes,
                no = no,
                pending = memberSet.count { it !in votedMembers },
            )
        }

        val votedMemberIds = input.votes.map { it.userId }.filter { it in memberSet }.toSet()
        val participation = Participation(
            memberCount = members.size,
            votedCount = votedMemberIds.size,
            pendingMemberIds = members.filter { it !in votedMemberIds },
        )

        val deadlinePassed = input.deadlineEpochMillis?.let { input.nowEpochMillis >= it } ?: false
        val roundOver = input.state != RoundState.OPEN || participation.everyoneVoted || deadlinePassed
        val votesRevealed = roundOver

        // While a round is open, waiting for the last votes can still be worth it -
        // unless at most one option could still win, in which case the group may
        // close the round now. Nothing is revealed and no winner is claimed before
        // the round closes: the flag only tells the caller to close it.
        val stillPossible = tallies.count { it.couldStillWin }
        val canCloseEarly = !roundOver && stillPossible <= 1

        if (!roundOver) {
            return RoundEvaluation(
                roundNumber = input.roundNumber,
                outcome = RoundOutcome.InProgress(participation.pendingMemberIds),
                tallies = tallies,
                survivors = eligible,
                eliminated = emptyList(),
                participation = participation,
                votesRevealed = false,
                canCloseEarly = canCloseEarly,
            )
        }

        val tallyById = tallies.associateBy { it.activityId }
        val survivors = eligible.filter { tallyById.getValue(it.id).canProgress }
        val eliminated = eligible.filterNot { it in survivors }

        val outcome: RoundOutcome = when {
            survivors.size == 1 -> RoundOutcome.Winner(survivors.first().id)
            survivors.isEmpty() -> RoundOutcome.NoWinner
            input.roundNumber >= input.maxRounds -> RoundOutcome.Tie(survivors.map { it.id })
            else -> RoundOutcome.ContinueRounds(survivors.map { it.id }, input.roundNumber + 1)
        }

        return RoundEvaluation(
            roundNumber = input.roundNumber,
            outcome = outcome,
            tallies = tallies,
            survivors = survivors,
            eliminated = eliminated,
            participation = participation,
            votesRevealed = votesRevealed,
            canCloseEarly = canCloseEarly,
        )
    }

    /**
     * The vote summary the winner screen shows. Tallies are only handed to the
     * UI after the round closed, which is what keeps the game fair.
     */
    fun winnerTally(evaluation: RoundEvaluation, winnerActivityId: String): Tally =
        evaluation.tallies.firstOrNull { it.activityId == winnerActivityId }
            ?: Tally(winnerActivityId, yes = 0, no = 0, pending = evaluation.participation.memberCount)

    /**
     * "Surprise Me": propose one eligible activity at random. Callers pass the
     * ids they already rejected so the group is not shown the same idea twice.
     */
    fun surpriseMe(
        candidates: List<ActivityIdea>,
        rejectedIds: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): ActivityIdea? {
        val pool = candidates.filter { it.status.isEligibleForRounds && it.id !in rejectedIds }
        if (pool.isEmpty()) return null
        return pool[random.nextInt(pool.size)]
    }
}

enum class RoundState { OPEN, CLOSED, SETTLED }

data class ActivityIdea(
    val id: String,
    val title: String,
    val icon: String = "🎉",
    val status: ActivityStatus = ActivityStatus.SUGGESTED,
    val createdAt: Long = 0L,
    val createdBy: String = "",
)

data class VoteChoice(
    val activityId: String,
    val userId: String,
    val choice: Boolean,
    val epochMillis: Long = 0L,
)

data class RoundInput(
    val roundNumber: Int,
    val state: RoundState,
    val nowEpochMillis: Long,
    val memberIds: List<String>,
    val activities: List<ActivityIdea>,
    val votes: List<VoteChoice>,
    val deadlineEpochMillis: Long? = null,
    val maxRounds: Int = DecisionEngine.MAX_ROUNDS,
)

data class Tally(
    val activityId: String,
    val yes: Int,
    val no: Int,
    val pending: Int,
) {
    val cast: Int get() = yes + no

    /** More YES than NO: this option stays in the running. */
    val canProgress: Boolean get() = yes > no

    /** Could still win once every pending member has voted. */
    val couldStillWin: Boolean get() = yes + pending > no

    val approvalRatio: Double get() = if (cast == 0) 0.0 else yes.toDouble() / cast.toDouble()

    fun withPending(pending: Int) = copy(pending = pending)
}

data class Participation(
    val memberCount: Int,
    val votedCount: Int,
    val pendingMemberIds: List<String>,
) {
    val everyoneVoted: Boolean get() = memberCount > 0 && votedCount >= memberCount
    val progress: Float get() = if (memberCount == 0) 0f else votedCount.toFloat() / memberCount
}

sealed interface RoundOutcome {
    /** Round is still open and only a few votes are missing. */
    data class InProgress(val waitingForMemberIds: List<String>) : RoundOutcome

    /** One option survived: the plan is decided. */
    data class Winner(val activityId: String) : RoundOutcome

    /** Survivors go into the next YES/NO round. */
    data class ContinueRounds(val survivorIds: List<String>, val nextRoundNumber: Int) : RoundOutcome

    /** Final comparison ended level, or the round limit was reached. */
    data class Tie(val survivorIds: List<String>) : RoundOutcome

    /** Nothing could progress: the group should add fresh ideas. */
    data object NoWinner : RoundOutcome
}

data class RoundEvaluation(
    val roundNumber: Int,
    val outcome: RoundOutcome,
    val tallies: List<Tally>,
    val survivors: List<ActivityIdea>,
    val eliminated: List<ActivityIdea>,
    val participation: Participation,
    val votesRevealed: Boolean,
    val canCloseEarly: Boolean,
) {
    val eliminatedIds: List<String> get() = eliminated.map { it.id }
    val survivorIds: List<String> get() = survivors.map { it.id }
}
