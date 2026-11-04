@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.data.repository.ActivityRepository
import com.vibe.app.data.repository.DecisionRepository
import com.vibe.app.data.repository.GroupRepository
import com.vibe.app.domain.Activity
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.Group
import com.vibe.app.domain.GroupRole
import com.vibe.app.ui.components.ActivityRow
import com.vibe.app.ui.components.EmptyState
import com.vibe.app.ui.components.ErrorText
import com.vibe.app.ui.components.GroupRow
import com.vibe.app.ui.components.MemberAvatars
import com.vibe.app.ui.components.PrimaryButton
import com.vibe.app.ui.components.SecondaryButton
import com.vibe.app.ui.components.SectionHeader
import com.vibe.app.ui.components.VibeCard
import com.vibe.app.ui.components.VibeTopBar
import com.vibe.app.ui.components.VibeBottomBar
import com.vibe.app.ui.components.VibeTab
import com.vibe.app.ui.components.onSelectTab
import com.vibe.app.ui.theme.VibeCoral
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint
import com.vibe.app.ui.vibeViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupViewModel(
    private val groupRepository: GroupRepository,
    private val activityRepository: ActivityRepository,
    private val decisionRepository: DecisionRepository,
) : ViewModel() {

    val groups: StateFlow<List<Group>> = groupRepository.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var error: FieldError? by mutableStateOf(null)
        private set

    var busy: Boolean by mutableStateOf(false)
        private set

    var createdGroup: Group? by mutableStateOf(null)
        private set

    var joinedGroup: Group? by mutableStateOf(null)
        private set

    var startedDecisionId: String? by mutableStateOf(null)
        private set

    fun group(groupId: String): StateFlow<Group?> = groupRepository.observeGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun activities(groupId: String): StateFlow<List<Activity>> = activityRepository.observeForGroup(groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearMessages() {
        error = null
        createdGroup = null
        joinedGroup = null
    }

    fun refreshActivities(groupId: String) {
        viewModelScope.launch { activityRepository.refresh(groupId) }
    }

    fun createGroup(name: String, icon: String) {
        error = null
        busy = true
        viewModelScope.launch {
            when (val result = groupRepository.createGroup(name, icon)) {
                is VibeResult.Ok -> createdGroup = result.value
                is VibeResult.Problem -> error = result.error ?: FieldError.GROUP_NAME_REQUIRED
            }
            busy = false
        }
    }

    fun joinGroup(code: String) {
        error = null
        busy = true
        viewModelScope.launch {
            when (val result = groupRepository.joinGroup(code)) {
                is VibeResult.Ok -> joinedGroup = result.value
                is VibeResult.Problem -> error = result.error ?: FieldError.INVITE_CODE_INVALID
            }
            busy = false
        }
    }

    fun startDecision(groupId: String, onStarted: (String) -> Unit) {
        error = null
        busy = true
        viewModelScope.launch {
            when (val result = decisionRepository.startRound(groupId)) {
                is VibeResult.Ok -> {
                    startedDecisionId = result.value
                    onStarted(result.value)
                }

                is VibeResult.Problem -> error = result.error
            }
            busy = false
        }
    }

    fun removeActivity(activity: Activity) {
        viewModelScope.launch { activityRepository.deleteActivity(activity.id) }
    }

    fun toggleFavourite(activity: Activity) {
        viewModelScope.launch { activityRepository.setFavourite(activity, !activity.favourite) }
    }

}

/** -------------------------------------------------------------- list ------ */

@Composable
fun GroupsScreen(
    onOpenGroup: (String) -> Unit,
    onBack: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onHome: () -> Unit,
    onMemories: () -> Unit,
    onProfile: () -> Unit,
) {
    val viewModel = vibeViewModel {
        GroupViewModel(it.groupRepository, it.activityRepository, it.decisionRepository)
    }
    val groups by viewModel.groups.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.groups_title), onBack = onBack) },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.GROUPS,
                onSelect = { tab -> onSelectTab(tab, onHome = onHome, onMemories = onMemories, onProfile = onProfile) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.groups_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (groups.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                    )
                }
            } else {
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        icon = group.icon,
                        name = group.name,
                        detail = pluralStringResource(R.plurals.group_members, group.memberCount, group.memberCount),
                        onClick = { onOpenGroup(group.id) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            item {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryButton(text = stringResource(R.string.action_create_group), onClick = onCreateGroup)
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryButton(text = stringResource(R.string.action_join_group), onClick = onJoinGroup)
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/** ------------------------------------------------------------ create ------ */

@Composable
fun CreateGroupScreen(onBack: () -> Unit, onOpenGroup: (String) -> Unit) {
    val viewModel = vibeViewModel {
        GroupViewModel(it.groupRepository, it.activityRepository, it.decisionRepository)
    }
    val icons = stringArrayResource(R.array.group_icons)
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(icons.first()) }
    val created = viewModel.createdGroup

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.create_group_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.create_group_name)) },
                placeholder = { Text(stringResource(R.string.create_group_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(viewModel.error)

            SectionHeader(stringResource(R.string.create_group_icon))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icons.take(4).forEach { candidate ->
                    IconChoice(candidate, candidate == icon) { icon = candidate }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icons.drop(4).forEach { candidate ->
                    IconChoice(candidate, candidate == icon) { icon = candidate }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            PrimaryButton(
                text = stringResource(R.string.action_create_group),
                enabled = !viewModel.busy,
                onClick = { viewModel.createGroup(name, icon) },
            )

            if (created != null) {
                Spacer(modifier = Modifier.height(16.dp))
                VibeCard {
                    Column {
                        Text(
                            text = stringResource(R.string.group_created_message),
                            style = MaterialTheme.typography.titleMedium,
                            color = VibeMint,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.group_invite_code),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = created.inviteCode,
                            style = MaterialTheme.typography.headlineMedium,
                            color = VibeLilac,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InviteActions(groupName = created.name, inviteCode = created.inviteCode)
                        Spacer(modifier = Modifier.height(8.dp))
                        PrimaryButton(
                            text = stringResource(R.string.action_done),
                            onClick = { onOpenGroup(created.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconChoice(icon: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) VibeCoral.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Text(text = icon, fontSize = 24.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun InviteActions(groupName: String, inviteCode: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shareText = stringResource(R.string.group_share_text, groupName, inviteCode)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(
            text = stringResource(R.string.action_copy_code),
            modifier = Modifier.weight(1f),
            onClick = { clipboard.setText(AnnotatedString(inviteCode)) },
        )
        SecondaryButton(
            text = stringResource(R.string.action_share),
            modifier = Modifier.weight(1f),
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(intent, null))
            },
        )
    }
}

/** -------------------------------------------------------------- join ------ */

@Composable
fun JoinGroupScreen(onBack: () -> Unit, onJoined: (String) -> Unit) {
    val viewModel = vibeViewModel {
        GroupViewModel(it.groupRepository, it.activityRepository, it.decisionRepository)
    }
    var code by remember { mutableStateOf("") }
    val joined = viewModel.joinedGroup

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.join_group_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text(stringResource(R.string.join_group_code)) },
                supportingText = { Text(stringResource(R.string.join_group_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(viewModel.error)

            if (joined == null) {
                Spacer(modifier = Modifier.height(12.dp))
                PrimaryButton(
                    text = stringResource(R.string.action_join_group),
                    enabled = !viewModel.busy && code.isNotBlank(),
                    onClick = { viewModel.joinGroup(code) },
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                // Confirmation before entering, as specified in the design.
                VibeCard {
                    Column {
                        Text(text = joined.icon, fontSize = 30.sp)
                        Text(text = joined.name, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = stringResource(R.string.join_group_confirm, joined.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PrimaryButton(
                            text = stringResource(R.string.action_done),
                            onClick = { onJoined(joined.id) },
                        )
                    }
                }
            }
        }
    }
}

/** ------------------------------------------------------- group detail ----- */

@Composable
fun GroupDetailScreen(
    groupId: String,
    onBack: () -> Unit,
    onActivity: (String) -> Unit,
    onAddActivity: (String) -> Unit,
    onVote: (String) -> Unit,
    onPlan: (String) -> Unit,
    onHome: () -> Unit,
    onMemories: () -> Unit,
    onProfile: () -> Unit,
) {
    val viewModel = vibeViewModel {
        GroupViewModel(it.groupRepository, it.activityRepository, it.decisionRepository)
    }
    val group by viewModel.group(groupId).collectAsStateWithLifecycle()
    val activities by viewModel.activities(groupId).collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(groupId) {
        viewModel.clearMessages()
        viewModel.refreshActivities(groupId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VibeTopBar(
                title = group?.name ?: stringResource(R.string.group_vibe_list),
                onBack = onBack,
            )
        },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.HOME,
                onSelect = { tab -> onSelectTab(tab, onHome, onMemories, onProfile) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAddActivity(groupId) },
                containerColor = VibeCoral,
                contentColor = Color.White,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            item {
                VibeCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = group?.icon.orEmpty(), fontSize = 28.sp, modifier = Modifier.padding(end = 10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group?.name.orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = if (group?.role == GroupRole.OWNER) {
                                        stringResource(R.string.group_role_owner)
                                    } else {
                                        stringResource(R.string.group_role_member)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MemberAvatars(names = group?.members?.map { it.displayName }.orEmpty())
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.group_invite_code) + ": " + group?.inviteCode.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = VibeLilac,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        group?.let { g ->
                            InviteActions(groupName = g.name, inviteCode = g.inviteCode)
                        }
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.group_vibe_list)) }

            if (activities.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.group_empty_activities),
                        body = stringResource(R.string.activities_subtitle),
                    )
                }
            } else {
                items(activities, key = { it.id }) { activity ->
                    val subtitle = if (activity.createdByName.isBlank()) {
                        stringResource(R.string.activity_yes_count, activity.yesVotes)
                    } else {
                        stringResource(R.string.activity_added_by, activity.createdByName)
                    }
                    ActivityRow(
                        icon = activity.icon,
                        title = activity.title,
                        subtitle = subtitle,
                        statusLabel = statusLabel(activity.status),
                        favourite = activity.favourite,
                        onFavouriteToggle = { viewModel.toggleFavourite(activity) },
                        onClick = { onActivity(activity.id) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                ErrorText(viewModel.error)
                if (group?.role == GroupRole.OWNER) {
                    PrimaryButton(
                        text = stringResource(R.string.group_start_decision),
                        enabled = !viewModel.busy && activities.count { it.status.isEligibleForRounds } >= 2,
                        onClick = { viewModel.startDecision(groupId, onVote) },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.error_owner_only),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                SecondaryButton(
                    text = stringResource(R.string.group_add_activity),
                    onClick = { onAddActivity(groupId) },
                )
                if (activities.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SecondaryButton(
                        text = stringResource(R.string.action_plan_it),
                        onClick = { onPlan(activities.first().id) },
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun statusLabel(status: ActivityStatus): String? = when (status) {
    ActivityStatus.SUGGESTED -> null
    ActivityStatus.ACTIVE -> stringResource(R.string.decision_state_open)
    ActivityStatus.ELIMINATED -> stringResource(R.string.activity_status_eliminated)
    ActivityStatus.WINNER -> stringResource(R.string.activity_status_winner)
    ActivityStatus.COMPLETED -> stringResource(R.string.activity_status_completed)
}
