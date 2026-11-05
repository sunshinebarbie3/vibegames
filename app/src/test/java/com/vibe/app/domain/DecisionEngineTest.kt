package com.vibe.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The decision algorithm is the innovation of the app ("Decide For Us"), so it is
 * tested directly rather than through the UI: rounds, eliminations, the fairness
 * rule of one equal YES/NO vote per member, hidden results and the early close.
 */
class DecisionEngineTest {

    /** The same round, one state later. */
    private fun roundClosed() = round(
        state = RoundState.CLOSED,
        members = listOf(lerato, thandi, sipho, naledi),
        activities = listOf(idea("pizza"), idea("bowling"), idea("movie")),
        votes = listOf(
            yes("pizza", lerato), yes("pizza", thandi), yes("pizza", sipho),
            no("bowling", lerato), no("bowling", thandi), no("bowling", sipho),
            yes("movie", lerato), no("movie", thandi), no("movie", sipho),
        ),
    )

    private val lerato = "u-lerato"
    private val thandi = "u-thandi"
    private val sipho = "u-sipho"
    private val naledi = "u-naledi"

    private fun idea(id: String, status: ActivityStatus = ActivityStatus.ACTIVE) =
        ActivityIdea(id = id, title = id, status = status)

    private fun yes(activityId: String, userId: String, at: Long = 0L) =
        VoteChoice(activityId = activityId, userId = userId, choice = true, epochMillis = at)

    private fun no(activityId: String, userId: String, at: Long = 0L) =
        VoteChoice(activityId = activityId, userId = userId, choice = false, epochMillis = at)

    private fun round(
        roundNumber: Int = 1,
        state: RoundState = RoundState.OPEN,
        deadline: Long? = null,
        now: Long = 1_000L,
        members: List<String> = listOf(lerato, thandi, sipho),
        activities: List<ActivityIdea> = listOf(idea("pizza"), idea("bowling")),
        votes: List<VoteChoice> = emptyList(),
    ) = RoundInput(
        roundNumber = roundNumber,
        state = state,
        deadlineEpochMillis = deadline,
        nowEpochMillis = now,
        memberIds = members,
        activities = activities,
        votes = votes,
    )

    @Test
    fun `results stay hidden while the round is open and members are still voting`() {
        val evaluation = DecisionEngine.evaluate(round(votes = listOf(yes("pizza", lerato))))

        assertTrue(evaluation.outcome is RoundOutcome.InProgress)
        assertFalse(evaluation.votesRevealed)
        assertEquals(listOf(lerato, thandi, sipho).sorted(), (evaluation.outcome as RoundOutcome.InProgress).waitingForMemberIds.sorted())
        assertEquals(0, evaluation.participation.votedCount)
        assertEquals(3, evaluation.participation.memberCount)
    }

    @Test
    fun `the option with more yes than no wins once everyone votes on every idea`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                votes = listOf(
                    yes("pizza", lerato),
                    yes("pizza", thandi),
                    no("pizza", sipho),
                    no("bowling", lerato),
                    no("bowling", thandi),
                    yes("bowling", sipho),
                ),
            ),
        )

        assertEquals(RoundOutcome.Winner("pizza"), evaluation.outcome)
        assertTrue(evaluation.votesRevealed)
        assertEquals(listOf("bowling"), evaluation.eliminatedIds)
        assertEquals(3, evaluation.participation.votedCount)
        assertTrue(evaluation.participation.everyoneVoted)
    }

    @Test
    fun `an option that cannot progress is eliminated and survivors go to the next round`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                activities = listOf(idea("pizza"), idea("bowling"), idea("movie")),
                votes = listOf(
                    yes("pizza", lerato), yes("pizza", thandi), yes("pizza", sipho),
                    yes("bowling", lerato), yes("bowling", thandi), no("bowling", sipho),
                    no("movie", lerato), no("movie", thandi), no("movie", sipho),
                ),
            ),
        )

        assertEquals(RoundOutcome.ContinueRounds(listOf("pizza", "bowling"), nextRoundNumber = 2), evaluation.outcome)
        assertEquals(listOf("movie"), evaluation.eliminatedIds)
        assertTrue(evaluation.votesRevealed)
    }

    @Test
    fun `a tie on every activity means nobody can progress`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                activities = listOf(idea("pizza"), idea("bowling")),
                votes = listOf(
                    yes("pizza", lerato), no("pizza", thandi), no("pizza", sipho),
                    yes("bowling", lerato), no("bowling", thandi), no("bowling", sipho),
                ),
            ),
        )

        assertEquals(RoundOutcome.NoWinner, evaluation.outcome)
        assertEquals(listOf("pizza", "bowling"), evaluation.eliminatedIds)
    }

    @Test
    fun `the final comparison reports a tie instead of inventing a winner`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                roundNumber = DecisionEngine.MAX_ROUNDS,
                activities = listOf(idea("pizza"), idea("bowling")),
                votes = listOf(
                    yes("pizza", lerato), yes("pizza", thandi), no("pizza", sipho),
                    yes("bowling", lerato), yes("bowling", thandi), no("bowling", sipho),
                ),
            ),
        )

        assertEquals(RoundOutcome.Tie(listOf("pizza", "bowling")), evaluation.outcome)
    }

    @Test
    fun `the round can close early when only one option can still win`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                members = listOf(lerato, thandi, sipho, naledi),
                activities = listOf(idea("pizza"), idea("bowling"), idea("movie")),
                votes = listOf(
                    yes("pizza", lerato), yes("pizza", thandi), yes("pizza", sipho),
                    no("bowling", lerato), no("bowling", thandi), no("bowling", sipho),
                    yes("movie", lerato), no("movie", thandi), no("movie", sipho),
                ),
            ),
        )

        assertTrue(evaluation.canCloseEarly)
        // Still an open round: no winner is claimed and no tally is revealed
        // until the round actually closes.
        assertTrue(evaluation.outcome is RoundOutcome.InProgress)
        assertFalse(evaluation.votesRevealed)

        // Close it (state = CLOSED) and the same votes decide it.
        val closed = DecisionEngine.evaluate(roundClosed())
        assertEquals(RoundOutcome.Winner("pizza"), closed.outcome)
        assertTrue(closed.votesRevealed)
    }

    @Test
    fun `a passed deadline closes the round without every vote`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                deadline = 500L,
                now = 900L,
                votes = listOf(
                    yes("pizza", lerato),
                    no("pizza", thandi),
                    yes("bowling", lerato),
                    no("bowling", thandi),
                ),
            ),
        )

        assertTrue(evaluation.votesRevealed)
        assertEquals(RoundOutcome.NoWinner, evaluation.outcome)
        assertFalse(evaluation.canCloseEarly)
    }

    @Test
    fun `one incomplete ballot does not end the round and leaves that idea pending`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                votes = listOf(
                    yes("pizza", lerato),
                    no("pizza", thandi),
                    // sipho only voted on bowling
                    yes("bowling", sipho),
                ),
            ),
        )

        val pizza = evaluation.tallies.first { it.activityId == "pizza" }
        assertEquals(1, pizza.yes)
        assertEquals(1, pizza.no)
        assertEquals(1, pizza.pending)
        assertTrue(pizza.couldStillWin)
        assertFalse(evaluation.votesRevealed)
        assertEquals(0, evaluation.participation.votedCount)
        assertEquals(3, evaluation.participation.pendingMemberIds.size)
    }

    @Test
    fun `a member's later vote replaces the earlier one`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                members = listOf(lerato),
                activities = listOf(idea("pizza")),
                votes = listOf(
                    no("pizza", lerato, at = 10L),
                    yes("pizza", lerato, at = 20L),
                ),
            ),
        )

        assertEquals(1, evaluation.tallies.single().yes)
        assertEquals(0, evaluation.tallies.single().no)
        assertEquals(RoundOutcome.Winner("pizza"), evaluation.outcome)
    }

    @Test
    fun `a vote from outside the group never counts`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                members = listOf(lerato),
                activities = listOf(idea("pizza")),
                votes = listOf(yes("pizza", lerato), yes("pizza", "u-ghost")),
            ),
        )

        assertEquals(1, evaluation.tallies.single().yes)
    }

    @Test
    fun `completed activities never enter a round`() {
        val evaluation = DecisionEngine.evaluate(
            round(
                members = listOf(lerato),
                activities = listOf(idea("pizza"), idea("old", ActivityStatus.COMPLETED), idea("dropped", ActivityStatus.ELIMINATED)),
                votes = listOf(yes("pizza", lerato)),
            ),
        )

        assertEquals(listOf("pizza"), evaluation.tallies.map { it.activityId })
        assertEquals(RoundOutcome.Winner("pizza"), evaluation.outcome)
    }

    @Test
    fun `winner tally falls back to an empty tally for an unknown activity`() {
        val evaluation = DecisionEngine.evaluate(round(votes = listOf(yes("pizza", lerato))))
        val tally = DecisionEngine.winnerTally(evaluation, "not-in-this-round")

        assertEquals(0, tally.yes)
        assertEquals(3, tally.pending)
    }

    @Test
    fun `surprise me only proposes eligible activities and honours rejections`() {
        val pool = listOf(
            idea("pizza"),
            idea("bowling", ActivityStatus.SUGGESTED),
            idea("finished", ActivityStatus.COMPLETED),
            idea("dropped", ActivityStatus.ELIMINATED),
        )

        val seeded = Random(7)
        repeat(20) {
            val pick = DecisionEngine.surpriseMe(pool, random = seeded)
            assertTrue(pick != null && pick.id in listOf("pizza", "bowling"))
        }

        val withoutPizza = DecisionEngine.surpriseMe(pool, rejectedIds = setOf("pizza"), random = Random(1))
        assertEquals("bowling", withoutPizza?.id)

        assertNull(DecisionEngine.surpriseMe(pool.filter { !it.status.isEligibleForRounds }, random = Random(1)))
    }

    @Test
    fun `approval ratio and progress numbers are what the UI shows`() {
        val tally = Tally(activityId = "pizza", yes = 3, no = 1, pending = 2)
        assertEquals(4, tally.cast)
        assertEquals(0.75, tally.approvalRatio, 0.0001)
        assertTrue(tally.couldStillWin)

        val participation = Participation(memberCount = 4, votedCount = 3, pendingMemberIds = listOf(thandi))
        assertFalse(participation.everyoneVoted)
        assertEquals(0.75f, participation.progress, 0.0001f)
    }
}
