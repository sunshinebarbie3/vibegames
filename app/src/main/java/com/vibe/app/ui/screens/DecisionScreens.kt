@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.data.repository.ActivityRepository
import com.vibe.app.data.repository.DecisionRepository
import com.vibe.app.data.repository.MemoryRepository
import com.vibe.app.domain.Activity
import com.vibe.app.domain.DecisionEngine
import com.vibe.app.domain.DecisionRound
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.RoundOutcome
import com.vibe.app.domain.Tally
import com.vibe.app.ui.components.EmptyState
import com.vibe.app.ui.components.ErrorText
import com.vibe.app.ui.components.ProgressRow
import com.vibe.app.ui.components.PrimaryButton
import com.vibe.app.ui.components.SecondaryButton
import com.vibe.app.ui.components.SectionHeader
import com.vibe.app.ui.components.VibeCard
import com.vibe.app.ui.components.VibeTopBar
import com.vibe.app.ui.theme.VibeCoral
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint
import com.vibe.app.ui.formatDateTime
import com.vibe.app.ui.vibeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

class DecisionViewModel(
    private val decisionRepository: DecisionRepository,
    private val activityRepository: ActivityRepository,
    private val memoryRepository: MemoryRepository,
) : ViewModel() {

    var error: FieldError? by mutableStateOf(null)
        private set

    var message: DecisionMessage? by mutableStateOf(null)
        private set

    /** Which idea of the round is on screen. One card at a time, as designed. */
    var cardIndex: Int by mutableStateOf(0)
        private set

    private var currentUserId: String = ""

    fun round(decisionId: String): StateFlow<DecisionRound?> =
        decisionRepository.observeRound(decisionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun currentRound(groupId: String): StateFlow<DecisionRound?> =
        decisionRepository.observeCurrentRound(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun winner(groupId: String): StateFlow<DecisionRound?> =
        decisionRepository.observeLatestWinner(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun activities(groupId: String): StateFlow<List<Activity>> =
        activityRepository.observeForGroup(groupId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshActivities(groupId: String) {
        viewModelScope.launch { activityRepository.refresh(groupId) }
    }

    fun setUserId(userId: String) {
        currentUserId = userId
    }

    fun nextCard(total: Int) {
        if (cardIndex + 1 < total) cardIndex++ else cardIndex = 0
    }

    fun castVote(decisionId: String, activityId: String, choice: Boolean, total: Int) {
        message = null
        viewModelScope.launch {
            when (val result = decisionRepository.castVote(decisionId, activityId, choice)) {
                is VibeResult.Ok -> {
                    message = DecisionMessage.VoteSent
                    nextCard(total)
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun startRound(groupId: String, onStarted: (String) -> Unit) {
        error = null
        viewModelScope.launch {
            when (val result = decisionRepository.startRound(groupId)) {
                is VibeResult.Ok -> onStarted(result.value)
                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun closeRound(decisionId: String) {
        viewModelScope.launch {
            when (val result = decisionRepository.closeRound(decisionId)) {
                is VibeResult.Ok -> message = DecisionMessage.RoundClosed
                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun refresh(decisionId: String) {
        viewModelScope.launch { decisionRepository.refreshRound(decisionId) }
    }

    /** PoE: Surprise Me proposes one eligible activity at random. */
    fun surprise(groupId: String) {
        viewModelScope.launch {
            val pool = activityRepository.eligibleForRound(groupId).map { it.toIdea() }
            val pick = DecisionEngine.surpriseMe(pool, rejectedIds, Random(System.currentTimeMillis()))
            if (pick == null) {
                error = FieldError.NOT_ENOUGH_ACTIVITIES
            } else {
                rejectedIds = rejectedIds + pick.id
                proposal = pick.id
            }
        }
    }

    var proposal: String? by mutableStateOf(null)
        private set

    private var rejectedIds: Set<String> = emptySet()

    /** Accepting the proposal locks it in as the plan for the group. */
    fun acceptProposal(groupId: String, activityId: String, onPlanned: (String) -> Unit) {
        viewModelScope.launch {
            when (val result = memoryRepository.savePlan(groupId, activityId, null)) {
                is VibeResult.Ok -> {
                    message = DecisionMessage.ProposalAccepted
                    onPlanned(activityId)
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun rejectProposal() {
        proposal = null
        message = DecisionMessage.ProposalRejected
    }
}

enum class DecisionMessage { VoteSent, RoundClosed, ProposalAccepted, ProposalRejected }

/** -------------------------------------------------------- start a round --- */

@Composable
fun DecisionStartScreen(
    groupId: String,
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
    onAddActivity: () -> Unit,
) {
    val viewModel = vibeViewModel {
        DecisionViewModel(it.decisionRepository, it.activityRepository, it.memoryRepository)
    }
    val activities by viewModel.activities(groupId).collectAsStateWithLifecycle()
    val eligible = remember(activities) { activities.filter { it.status.isEligibleForRounds } }

    androidx.compose.runtime.LaunchedEffect(groupId) { viewModel.refreshActivities(groupId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.decision_title), onBack = onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Text(
                    text = stringResource(R.string.decision_start_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.decision_start_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.decision_vote_equal),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibeMint,
                )
                SectionHeader(stringResource(R.string.group_vibe_list))
            }

            if (eligible.size < 2) {
                item {
                    EmptyState(
                        title = stringResource(R.string.surprise_none),
                        body = stringResource(R.string.error_activities_needed),
                    ) {
                        PrimaryButton(
                            text = stringResource(R.string.group_add_activity),
                            onClick = onAddActivity,
                        )
                    }
                }
            } else {
                items(eligible, key = { it.id }) { activity ->
                    VibeCard(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = activity.icon, fontSize = 24.sp, modifier = Modifier.padding(end = 10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = activity.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = stringResource(R.string.activity_added_by, activity.createdByName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    ErrorText(viewModel.error)
                    PrimaryButton(
                        text = stringResource(R.string.action_start_voting),
                        onClick = { viewModel.startRound(groupId, onStarted) },
                    )
                }
            }
        }
    }
}

/** ------------------------------------------------------------- voting ----- */

@Composable
fun VoteScreen(
    decisionId: String,
    onBack: () -> Unit,
    onFinished: (String) -> Unit,
    onAddActivity: () -> Unit,
) {
    val container = com.vibe.app.ui.LocalVibeContainer.current
    val viewModel = vibeViewModel {
        DecisionViewModel(it.decisionRepository, it.activityRepository, it.memoryRepository)
    }
    val round by viewModel.round(decisionId).collectAsStateWithLifecycle()
    val groupId = round?.decision?.groupId
    val activitiesFlow = remember(groupId) {
        groupId?.let { id -> viewModel.activities(id) } ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    }
    val activities by activitiesFlow.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.setUserId(container.settings.currentUserId().orEmpty())
    }

    // While a round is open, the app asks the API for progress so other members'
    // votes appear (in the bundled demo backend the local cache already updates).
    LaunchedEffect(decisionId, round?.isOpen) {
        while (round?.isOpen == true) {
            delay(3_000)
            viewModel.refresh(decisionId)
        }
    }

    val current = round
    val roundIdeas = remember(current, activities) {
        val ids = current?.survivorIds.orEmpty()
        if (ids.isEmpty()) activities else activities.filter { it.id in ids }
    }
    val card = roundIdeas.getOrNull(viewModel.cardIndex % roundIdeas.size.coerceAtLeast(1))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.decision_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (current == null || card == null) {
                EmptyState(
                    title = stringResource(R.string.decision_start_title),
                    body = stringResource(R.string.error_activities_needed),
                ) {
                    PrimaryButton(text = stringResource(R.string.group_add_activity), onClick = onAddActivity)
                }
                return@Column
            }

            Text(
                text = stringResource(R.string.decision_round_label, current.decision.roundNumber),
                style = MaterialTheme.typography.labelLarge,
                color = VibeLilac,
            )
            Spacer(modifier = Modifier.height(6.dp))
            ProgressRow(
                label = stringResource(
                    R.string.decision_responses,
                    current.participation.votedCount,
                    current.participation.memberCount,
                ),
                progress = current.participation.progress,
            )

            Spacer(modifier = Modifier.height(18.dp))
            // One large card per activity, with the icon paired with its text.
            VibeCard(containerColor = MaterialTheme.colorScheme.surface) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(text = card.icon, fontSize = 46.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.activity_added_by, card.createdByName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Equal weight for NO and YES: the buttons are deliberately symmetric.
                        Button(
                            onClick = {
                                viewModel.castVote(decisionId, card.id, false, roundIdeas.size)
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(stringResource(R.string.action_no), fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                viewModel.castVote(decisionId, card.id, true, roundIdeas.size)
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VibeMint,
                                contentColor = Color(0xFF08301F),
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(stringResource(R.string.action_yes), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            viewModel.message?.let { message ->
                Text(
                    text = when (message) {
                        DecisionMessage.VoteSent -> stringResource(R.string.decision_vote_sent)
                        DecisionMessage.RoundClosed -> stringResource(R.string.decision_state_closed)
                        DecisionMessage.ProposalAccepted -> stringResource(R.string.surprise_accepted)
                        DecisionMessage.ProposalRejected -> stringResource(R.string.surprise_rejected)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibeLilac,
                )
            }
            ErrorText(viewModel.error)

            if (!current.votesRevealed) {
                Text(
                    text = stringResource(R.string.decision_vote_equal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (current.canCloseEarly) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = current.decision.deadlineEpochMillis?.let { deadline ->
                        stringResource(R.string.decision_waiting_deadline, formatDateTime(deadline))
                    } ?: stringResource(R.string.decision_can_close),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibeMint,
                )
                SecondaryButton(
                    text = stringResource(R.string.decision_state_closed),
                    onClick = { viewModel.closeRound(decisionId) },
                )
            }

            if (current.votesRevealed) {
                Spacer(modifier = Modifier.height(18.dp))
                OutcomesSection(round = current, activities = activities)
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.action_done),
                    onClick = { onFinished(current.decision.groupId) },
                )
            }
        }
    }
}

@Composable
private fun OutcomesSection(round: DecisionRound, activities: List<Activity>) {
    SectionHeader(stringResource(R.string.decision_eliminated_title))
    val tallies = round.tallies
    val eliminated = round.eliminatedIds
    if (eliminated.isEmpty()) {
        Text(
            text = stringResource(R.string.decision_no_winner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    eliminated.forEach { activityId ->
        val activity = activities.firstOrNull { it.id == activityId }
        val tally = tallies.firstOrNull { it.activityId == activityId }
        if (activity != null) {
            TallyRow(activity = activity, tally = tally)
        }
    }
}

@Composable
private fun TallyRow(activity: Activity, tally: Tally?) {
    VibeCard(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = activity.icon, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = activity.title, style = MaterialTheme.typography.titleMedium)
                if (tally != null) {
                    Text(
                        text = "${tally.yes} YES · ${tally.no} NO",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.activity_status_eliminated),
                style = MaterialTheme.typography.labelSmall,
                color = VibeLilac,
            )
        }
    }
}

/** ------------------------------------------------------------- winner ----- */

@Composable
fun WinnerScreen(
    groupId: String,
    onBack: () -> Unit,
    onPlanIt: (String) -> Unit,
) {
    val viewModel = vibeViewModel {
        DecisionViewModel(it.decisionRepository, it.activityRepository, it.memoryRepository)
    }
    val round by viewModel.winner(groupId).collectAsStateWithLifecycle()
    val activities by viewModel.activities(groupId).collectAsStateWithLifecycle()

    val winnerId = round?.decision?.winnerActivityId
    val winner = activities.firstOrNull { it.id == winnerId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.winner_title), onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Confetti only appears once a winner is confirmed.
            if (winner != null) Confetti()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "🎉", fontSize = 54.sp)
                Text(
                    text = stringResource(R.string.winner_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = VibeMint,
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (winner == null) {
                    EmptyState(
                        title = stringResource(R.string.decision_start_title),
                        body = stringResource(R.string.decision_no_winner),
                    )
                    return@Column
                }

                VibeCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(text = winner.icon, fontSize = 40.sp)
                        Text(text = winner.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        val tally = round?.tallies?.firstOrNull { it.activityId == winner.id }
                        if (tally != null && round?.votesRevealed == true) {
                            Text(
                                text = stringResource(
                                    R.string.winner_votes,
                                    tally.yes,
                                    round?.participation?.memberCount ?: tally.cast,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                PrimaryButton(
                    text = stringResource(R.string.action_plan_it),
                    onClick = { onPlanIt(winner.id) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryButton(
                    text = stringResource(R.string.action_add_to_memories),
                    onClick = { onPlanIt(winner.id) },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.winner_confetti),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Confetti: simple falling blocks in the VIBE accents, no third-party library. */
@Composable
private fun Confetti() {
    val transition = rememberInfiniteTransition(label = "confetti")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "fall",
    )
    val colours = listOf(VibeCoral, VibeLilac, VibeMint, Color(0xFFFFD166))

    Canvas(modifier = Modifier.fillMaxSize()) {
        val lanes = 14
        repeat(lanes) { index ->
            val laneX = size.width * (index + 0.5f) / lanes
            val offset = (progress + index * 0.11f) % 1f
            val y = offset * size.height
            drawRect(
                color = colours[index % colours.size].copy(alpha = 0.75f),
                topLeft = Offset(laneX, y),
                size = Size(10f, 18f),
            )
        }
    }
}

/** ----------------------------------------------------------- surprise ----- */

@Composable
fun SurpriseScreen(
    groupId: String,
    onBack: () -> Unit,
    onPlanned: (String) -> Unit,
    onAddActivity: () -> Unit,
) {
    val viewModel = vibeViewModel {
        DecisionViewModel(it.decisionRepository, it.activityRepository, it.memoryRepository)
    }
    val activities by viewModel.activities(groupId).collectAsStateWithLifecycle()
    val proposalId = viewModel.proposal
    val proposal = activities.firstOrNull { it.id == proposalId }

    LaunchedEffect(groupId) {
        if (proposalId == null) viewModel.surprise(groupId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.surprise_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "🎲", fontSize = 48.sp)
            Text(text = stringResource(R.string.surprise_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                text = stringResource(R.string.surprise_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))

            if (proposal == null) {
                EmptyState(
                    title = stringResource(R.string.surprise_none),
                    body = stringResource(R.string.quick_surprise_body),
                ) {
                    PrimaryButton(text = stringResource(R.string.group_add_activity), onClick = onAddActivity)
                }
            } else {
                VibeCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(text = proposal.icon, fontSize = 40.sp)
                        Text(
                            text = proposal.title,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                PrimaryButton(
                    text = stringResource(R.string.action_accept),
                    onClick = { viewModel.acceptProposal(groupId, proposal.id, onPlanned) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryButton(
                    text = stringResource(R.string.action_reject),
                    onClick = { viewModel.rejectProposal() },
                )
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryButton(
                    text = stringResource(R.string.action_surprise_again),
                    onClick = { viewModel.surprise(groupId) },
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            ErrorText(viewModel.error)
        }
    }
}
