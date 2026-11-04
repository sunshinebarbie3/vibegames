@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.app.R
import com.vibe.app.core.ConnectivityObserver
import com.vibe.app.data.repository.AuthRepository
import com.vibe.app.data.repository.GroupRepository
import com.vibe.app.data.repository.NotificationRepository
import com.vibe.app.data.sync.SyncManager
import com.vibe.app.domain.Group
import com.vibe.app.ui.components.DottedOutlineButton
import com.vibe.app.ui.components.EmptyState
import com.vibe.app.ui.components.GroupRow
import com.vibe.app.ui.components.NotificationBell
import com.vibe.app.ui.components.OfflineBanner
import com.vibe.app.ui.components.PrimaryButton
import com.vibe.app.ui.components.SecondaryButton
import com.vibe.app.ui.components.SectionHeader
import com.vibe.app.ui.components.VibeBottomBar
import com.vibe.app.ui.components.VibeCard
import com.vibe.app.ui.components.VibeTab
import com.vibe.app.ui.components.Wordmark
import com.vibe.app.ui.theme.VibeCoral
import com.vibe.app.ui.theme.VibeInk
import com.vibe.app.ui.theme.VibeInkMuted
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint
import com.vibe.app.ui.vibeViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    groupRepository: GroupRepository,
    authRepository: AuthRepository,
    notificationRepository: NotificationRepository,
    syncManager: SyncManager,
    connectivity: ConnectivityObserver,
) : ViewModel() {

    val groups: StateFlow<List<Group>> = groupRepository.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val displayName: StateFlow<String> = authRepository.profile
        .map { user -> user?.displayName.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val unreadNotifications: StateFlow<Int> = notificationRepository.unreadCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pendingSync: StateFlow<Int> = syncManager.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val offline: StateFlow<Boolean> = connectivity.isOnline
        .map { online -> !online }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            // The cache paints first; the refresh brings the group list up to date.
            groupRepository.refresh()
            notificationRepository.refresh()
        }
    }
}

@Composable
fun HomeScreen(
    onOpenGroup: (String) -> Unit,
    onOpenGroups: () -> Unit,
    onOpenActivities: () -> Unit,
    onOpenNotifications: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onStartDecision: (String) -> Unit,
    onDecideForUs: () -> Unit,
    onSurpriseMe: (String) -> Unit,
    onMemories: () -> Unit,
    onProfile: () -> Unit,
) {
    val viewModel = vibeViewModel {
        HomeViewModel(
            it.groupRepository,
            it.authRepository,
            it.notificationRepository,
            it.syncManager,
            it.connectivity,
        )
    }

    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val unread by viewModel.unreadNotifications.collectAsStateWithLifecycle()
    val pending by viewModel.pendingSync.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()

    var quickActionsOpen by remember { mutableStateOf(false) }

    // Nothing to decide, surprise or plan until the member has a group, so the entry
    // points that need one open the create-group flow instead of a dead end.
    val hasGroups = groups.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.HOME,
                onSelect = { tab ->
                    when (tab) {
                        VibeTab.HOME -> Unit
                        VibeTab.ACTIVITIES -> onOpenActivities()
                        VibeTab.GROUPS -> onOpenGroups()
                        VibeTab.PROFILE -> onProfile()
                        VibeTab.MEMORIES -> onMemories()
                        VibeTab.ADD -> quickActionsOpen = true
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hey, ${name.ifBlank { "Lizzy 🌸" }}! 👋",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            text = "What are we vibing today?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    NotificationBell(unreadCount = unread, onClick = onOpenNotifications)
                }
            }

            item { OfflineBanner(pendingCount = pending, offline = offline) }

            item { SectionHeader(stringResource(R.string.home_your_groups), stringResource(R.string.home_view_all), onOpenGroups) }

            if (groups.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.home_empty_title),
                        body = stringResource(R.string.home_empty_body),
                    ) {
                        Column {
                            PrimaryButton(
                                text = stringResource(R.string.action_create_group),
                                onClick = onCreateGroup,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            SecondaryButton(
                                text = stringResource(R.string.action_join_group),
                                onClick = onJoinGroup,
                            )
                        }
                    }
                }
            } else {
                items(groups, key = { it.id }) { group ->
                    GroupRow(
                        icon = group.icon,
                        name = group.name,
                        detail = "${group.memberCount} members",
                        onClick = { onOpenGroup(group.id) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(6.dp))
                DottedOutlineButton(
                    text = "+ Create New Group",
                    onClick = onCreateGroup,
                )
            }

            item { SectionHeader(stringResource(R.string.home_quick_start)) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickStartTile(
                        icon = "💗",
                        title = stringResource(R.string.quick_decide_title),
                        subtitle = stringResource(R.string.quick_decide_body),
                        containerColor = Color(0xFFFFF0F5),
                        accentColor = VibeCoral,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (hasGroups) groups.firstOrNull()?.let { onStartDecision(it.id) } else onCreateGroup()
                        },
                    )
                    QuickStartTile(
                        icon = "🎲",
                        title = "Surprise Me",
                        subtitle = "Random activity picker",
                        containerColor = Color(0xFFE8FCD9),
                        accentColor = VibeMint,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (hasGroups) groups.firstOrNull()?.let { onSurpriseMe(it.id) } else onCreateGroup()
                        },
                    )
                    QuickStartTile(
                        icon = "📋",
                        title = "My Activities",
                        subtitle = "See all your saved ideas",
                        containerColor = Color(0xFFF3E8FF),
                        accentColor = VibeLilac,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenActivities,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (quickActionsOpen) {
        AlertDialog(
            onDismissRequest = { quickActionsOpen = false },
            title = { Text(stringResource(R.string.quick_actions)) },
            text = {
                Column {
                    QuickActionRow("➕", stringResource(R.string.quick_add_title)) {
                        quickActionsOpen = false
                        if (hasGroups) onOpenActivities() else onCreateGroup()
                    }
                    QuickActionRow("👥", stringResource(R.string.quick_create_group_title)) {
                        quickActionsOpen = false
                        onCreateGroup()
                    }
                    QuickActionRow("🗳️", stringResource(R.string.quick_start_decision_title)) {
                        quickActionsOpen = false
                        if (hasGroups) groups.firstOrNull()?.let { onStartDecision(it.id) } else onCreateGroup()
                    }
                    QuickActionRow("🎲", stringResource(R.string.quick_surprise_title)) {
                        quickActionsOpen = false
                        if (hasGroups) groups.firstOrNull()?.let { onSurpriseMe(it.id) } else onCreateGroup()
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { quickActionsOpen = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun QuickStartTile(
    icon: String,
    title: String,
    subtitle: String,
    containerColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        modifier = modifier.clickable { onClick() },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = icon, fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = VibeInk,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                color = VibeInkMuted,
            )
        }
    }
}

@Composable
private fun QuickActionRow(icon: String, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
            Text(text = label, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
