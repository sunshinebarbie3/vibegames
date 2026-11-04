@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.app.R
import com.vibe.app.core.LocaleManager
import com.vibe.app.core.VibeResult
import com.vibe.app.data.repository.ActivityRepository
import com.vibe.app.data.repository.AuthRepository
import com.vibe.app.data.repository.GroupRepository
import com.vibe.app.data.repository.NotificationRepository
import com.vibe.app.data.sync.SyncManager
import com.vibe.app.domain.AppLanguage
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.NotificationType
import com.vibe.app.domain.SyncItem
import com.vibe.app.domain.ThemeMode
import com.vibe.app.domain.User
import com.vibe.app.domain.UserStats
import com.vibe.app.domain.VibeNotification
import com.vibe.app.ui.formatDateWithTime
import com.vibe.app.ui.components.AvatarCircle
import com.vibe.app.ui.components.EmptyState
import com.vibe.app.ui.components.ErrorText
import com.vibe.app.ui.components.LabelValueRow
import com.vibe.app.ui.components.PrimaryButton
import com.vibe.app.ui.components.SecondaryButton
import com.vibe.app.ui.components.SectionHeader
import com.vibe.app.ui.components.StatTile
import com.vibe.app.ui.components.VibeCard
import com.vibe.app.ui.components.VibeTopBar
import com.vibe.app.ui.components.VibeBottomBar
import com.vibe.app.ui.components.VibeTab
import com.vibe.app.ui.components.onSelectTab
import com.vibe.app.ui.theme.VibeCoral
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint
import com.vibe.app.ui.vibeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val groupRepository: GroupRepository,
    activityRepository: ActivityRepository,
    private val notificationRepository: NotificationRepository,
    private val syncManager: SyncManager,
) : ViewModel() {

    private val statsState = MutableStateFlow(UserStats())

    val profile: StateFlow<User?> = authRepository.profile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val stats: StateFlow<UserStats> = statsState

    val settings = authRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.vibe.app.data.prefs.AppSettings())

    val notifications: StateFlow<List<VibeNotification>> = notificationRepository.notifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unread: StateFlow<Int> = notificationRepository.unreadCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pendingSync: StateFlow<Int> = syncManager.pendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val conflicts: StateFlow<List<SyncItem>> = syncManager.conflicts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groupsCount: StateFlow<Int> = groupRepository.groups
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val myActivityCount: StateFlow<Int> = activityRepository.observeAll()
        .map { list -> list.count { it.createdBy.isNotBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    var error: FieldError? by mutableStateOf(null)
        private set

    var infoMessage: ProfileMessage? by mutableStateOf(null)
        private set

    fun refresh() {
        viewModelScope.launch {
            when (val result = authRepository.refreshProfile()) {
                is VibeResult.Ok -> {
                    statsState.value = authRepository.stats(result.value.id)
                }

                is VibeResult.Problem -> Unit
            }
            groupRepository.refresh()
            notificationRepository.refresh()
        }
    }

    fun updateProfile(displayName: String, username: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = authRepository.updateProfile(displayName, username)) {
                is VibeResult.Ok -> {
                    infoMessage = ProfileMessage.ProfileSaved
                    onSaved()
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { authRepository.updateSettings(themeMode = mode) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            // Persist first so Activity recreation cannot restore the previous locale.
            authRepository.updateSettings(language = language)
            LocaleManager.apply(language)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { authRepository.updateSettings(notificationsEnabled = enabled) }
    }

    fun setPrivacyMembersOnly(membersOnly: Boolean) {
        viewModelScope.launch { authRepository.updateSettings(privacyMembersOnly = membersOnly) }
    }

    fun changePassword(current: String, new: String, confirm: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            when (val result = authRepository.changePassword(current, new, confirm)) {
                is VibeResult.Ok -> {
                    infoMessage = ProfileMessage.PasswordChanged
                    onSaved()
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            when (val result = syncManager.syncNow()) {
                is VibeResult.Ok -> infoMessage = ProfileMessage.Synced
                is VibeResult.Problem -> infoMessage = ProfileMessage.Offline
            }
        }
    }

    fun resolveConflict(item: SyncItem, keepMine: Boolean) {
        viewModelScope.launch {
            if (keepMine) syncManager.resolveKeepingLocal(item) else syncManager.resolveKeepingServer(item)
            infoMessage = ProfileMessage.ConflictResolved
        }
    }

    fun markNotificationRead(notification: VibeNotification) {
        viewModelScope.launch { notificationRepository.markRead(notification.id) }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch { notificationRepository.markAllRead() }
    }

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onSignedOut()
        }
    }

    fun clearMessages() {
        error = null
        infoMessage = null
    }
}

enum class ProfileMessage { ProfileSaved, PasswordChanged, Synced, Offline, ConflictResolved }

/** ----------------------------------------------------------- profile ------ */

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onEditProfile: () -> Unit,
    onMyGroups: () -> Unit,
    onMyActivities: () -> Unit,
    onSettings: () -> Unit,
    onLanguage: () -> Unit,
    onHelp: () -> Unit,
    onHome: () -> Unit,
    onMemories: () -> Unit,
) {
    val viewModel = vibeViewModel {
        ProfileViewModel(
            it.authRepository,
            it.groupRepository,
            it.activityRepository,
            it.notificationRepository,
            it.syncManager,
        )
    }
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VibeTopBar(
                title = stringResource(R.string.profile_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onSettings) {
                        Text("⚙️", fontSize = 20.sp)
                    }
                },
            )
        },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.PROFILE,
                onSelect = { tab ->
                    onSelectTab(
                        tab = tab,
                        onHome = onHome,
                        onActivities = onMyActivities,
                        onGroups = onMyGroups,
                        onProfile = {},
                        onMemories = onMemories,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                AvatarCircle(
                    initials = profile?.displayName.orEmpty().ifBlank { "Lizzy" },
                    size = 90,
                    highlight = true,
                )
                Surface(
                    shape = CircleShape,
                    color = VibeCoral,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📷", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = profile?.displayName ?: "Lizzy 🌸",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "@" + (profile?.username.orEmpty().ifBlank { "lizzy_vibes" }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatTile(
                    label = stringResource(R.string.profile_stats_decisions),
                    value = stats.decisions.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.profile_stats_activities),
                    value = stats.activities.toString(),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.profile_stats_groups),
                    value = stats.groups.toString(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            ProfileMenuItem("👤", stringResource(R.string.profile_edit), onClick = onEditProfile)
            Spacer(modifier = Modifier.height(8.dp))
            ProfileMenuItem("👥", stringResource(R.string.profile_my_groups), onClick = onMyGroups)
            Spacer(modifier = Modifier.height(8.dp))
            ProfileMenuItem("⭐", stringResource(R.string.profile_my_activities), onClick = onMyActivities)
            Spacer(modifier = Modifier.height(8.dp))
            ProfileMenuItem("⚙️", stringResource(R.string.profile_settings), onClick = onSettings)
            Spacer(modifier = Modifier.height(8.dp))
            ProfileMenuItem(
                "🌐",
                stringResource(R.string.profile_language),
                value = settings.language.label,
                onClick = onLanguage,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ProfileMenuItem("❓", stringResource(R.string.profile_help), onClick = onHelp)
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileMenuItem(
    icon: String,
    label: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    VibeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(text = "›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** -------------------------------------------------------- edit profile ---- */

@Composable
fun EditProfileScreen(onBack: () -> Unit) {
    val viewModel = vibeViewModel {
        ProfileViewModel(
            it.authRepository,
            it.groupRepository,
            it.activityRepository,
            it.notificationRepository,
            it.syncManager,
        )
    }
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(profile?.id) {
        val current = profile
        if (current != null && !loaded) {
            name = current.displayName
            username = current.username
            loaded = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.profile_edit), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            AvatarCircle(initials = name, size = 72, highlight = true)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.profile_photo_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.profile_username_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(viewModel.error)
            Spacer(modifier = Modifier.height(14.dp))
            PrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = { viewModel.updateProfile(name, username) {} },
            )
            viewModel.infoMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (message) {
                        ProfileMessage.ProfileSaved -> stringResource(R.string.profile_saved)
                        ProfileMessage.PasswordChanged -> stringResource(R.string.password_updated)
                        ProfileMessage.Synced -> stringResource(R.string.settings_sync_done)
                        ProfileMessage.Offline -> stringResource(R.string.state_saved_offline)
                        ProfileMessage.ConflictResolved -> stringResource(R.string.conflict_resolved)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibeMint,
                )
            }
        }
    }
}

/** ----------------------------------------------------------- settings ----- */

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLanguage: () -> Unit,
    onChangePassword: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val viewModel = vibeViewModel {
        ProfileViewModel(
            it.authRepository,
            it.groupRepository,
            it.activityRepository,
            it.notificationRepository,
            it.syncManager,
        )
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pending by viewModel.pendingSync.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    var showConflicts by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_notifications))
            ToggleRow(
                label = stringResource(R.string.settings_notifications),
                detail = stringResource(R.string.settings_notifications_detail),
                checked = settings.notificationsEnabled,
                onCheckedChange = { viewModel.setNotificationsEnabled(it) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            ToggleRow(
                label = stringResource(R.string.settings_privacy),
                detail = stringResource(R.string.settings_privacy_members),
                checked = settings.privacyMembersOnly,
                onCheckedChange = { viewModel.setPrivacyMembersOnly(it) },
            )

            SectionHeader(stringResource(R.string.settings_theme))
            LabelValueRow(
                label = stringResource(R.string.settings_theme_dark),
                value = if (settings.themeMode == ThemeMode.DARK) "✓" else "",
                onClick = { viewModel.setTheme(ThemeMode.DARK) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabelValueRow(
                label = stringResource(R.string.settings_theme_light),
                value = if (settings.themeMode == ThemeMode.LIGHT) "✓" else "",
                onClick = { viewModel.setTheme(ThemeMode.LIGHT) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabelValueRow(
                label = stringResource(R.string.settings_theme_system),
                value = if (settings.themeMode == ThemeMode.SYSTEM) "✓" else "",
                onClick = { viewModel.setTheme(ThemeMode.SYSTEM) },
            )

            SectionHeader(stringResource(R.string.settings_account))
            LabelValueRow(
                label = stringResource(R.string.settings_language),
                value = settings.language.label,
                onClick = onLanguage,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabelValueRow(
                label = stringResource(R.string.settings_password),
                value = stringResource(R.string.settings_password_detail),
                onClick = onChangePassword,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LabelValueRow(
                label = stringResource(R.string.settings_linked_accounts),
                value = stringResource(R.string.settings_linked_google) + " · " +
                    stringResource(R.string.settings_linked_not_connected),
            )

            SectionHeader(stringResource(R.string.settings_local_first))
            Text(
                text = stringResource(R.string.settings_pending_sync, pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.settings_sync_now),
                onClick = { viewModel.syncNow() },
            )
            if (conflicts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryButton(
                    text = stringResource(R.string.settings_open_conflicts),
                    onClick = { showConflicts = true },
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            SecondaryButton(
                text = stringResource(R.string.settings_sign_out),
                onClick = { viewModel.signOut(onSignedOut) },
            )
            viewModel.infoMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when (message) {
                        ProfileMessage.Synced -> stringResource(R.string.settings_sync_done)
                        ProfileMessage.Offline -> stringResource(R.string.state_saved_offline)
                        ProfileMessage.ConflictResolved -> stringResource(R.string.conflict_resolved)
                        else -> stringResource(R.string.profile_saved)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = VibeMint,
                )
            }
        }
    }

    if (showConflicts) {
        // Conflicts are surfaced, never overwritten silently: the member chooses.
        AlertDialog(
            onDismissRequest = { showConflicts = false },
            title = { Text(stringResource(R.string.conflict_title)) },
            text = {
                Column {
                    conflicts.take(3).forEach { item ->
                        Text(
                            text = "${item.action.wire} · ${item.entityId.take(8)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.conflict_body,
                                item.serverPayloadJson?.take(40) ?: item.entityId.take(8),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                viewModel.resolveConflict(item, keepMine = true)
                                showConflicts = false
                            }) {
                                Text(stringResource(R.string.conflict_keep_mine), color = VibeLilac)
                            }
                            TextButton(onClick = {
                                viewModel.resolveConflict(item, keepMine = false)
                                showConflicts = false
                            }) {
                                Text(stringResource(R.string.conflict_keep_theirs), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConflicts = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    VibeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/** ----------------------------------------------------------- language ----- */

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val viewModel = vibeViewModel {
        ProfileViewModel(
            it.authRepository,
            it.groupRepository,
            it.activityRepository,
            it.notificationRepository,
            it.syncManager,
        )
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.settings_language), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_language_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            AppLanguage.entries.forEach { language ->
                LabelValueRow(
                    label = languageName(language),
                    value = if (settings.language == language) "✓" else "",
                    onClick = { viewModel.setLanguage(language) },
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun languageName(language: AppLanguage): String = when (language) {
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.ISIZULU -> stringResource(R.string.language_zulu)
    AppLanguage.SESOTHO -> stringResource(R.string.language_sesotho)
}

/** ---------------------------------------------------- change password ----- */

@Composable
fun ChangePasswordScreen(onBack: () -> Unit) {
    val viewModel = vibeViewModel {
        ProfileViewModel(
            it.authRepository,
            it.groupRepository,
            it.activityRepository,
            it.notificationRepository,
            it.syncManager,
        )
    }
    var current by remember { mutableStateOf("") }
    var fresh by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.settings_password), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = current,
                onValueChange = { current = it },
                label = { Text(stringResource(R.string.field_current_password)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = fresh,
                onValueChange = { fresh = it },
                label = { Text(stringResource(R.string.field_new_password)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text(stringResource(R.string.field_confirm_password)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(viewModel.error)
            Spacer(modifier = Modifier.height(14.dp))
            PrimaryButton(
                text = stringResource(R.string.action_update_password),
                onClick = { viewModel.changePassword(current, fresh, confirm) {} },
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.password_reset_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** -------------------------------------------------------------- help ------ */

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.help_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.help_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Faq(stringResource(R.string.help_q1), stringResource(R.string.help_a1))
            Faq(stringResource(R.string.help_q2), stringResource(R.string.help_a2))
            Faq(stringResource(R.string.help_q3), stringResource(R.string.help_a3))
            Faq(stringResource(R.string.help_q4), stringResource(R.string.help_a4))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.help_contact),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.help_contact_body),
                style = MaterialTheme.typography.bodyMedium,
                color = VibeLilac,
            )
        }
    }
}

@Composable
private fun Faq(question: String, answer: String) {
    VibeCard(modifier = Modifier.padding(bottom = 8.dp)) {
        Column {
            Text(text = question, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ------------------------------------------------------ notifications ----- */

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onMemories: () -> Unit,
    onProfile: () -> Unit,
) {
    val viewModel = vibeViewModel {
        ProfileViewModel(
            it.authRepository,
            it.groupRepository,
            it.activityRepository,
            it.notificationRepository,
            it.syncManager,
        )
    }
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.notifications_title), onBack = onBack) },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.HOME,
                onSelect = { tab -> onSelectTab(tab, onHome, onMemories, onProfile) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (notifications.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.notifications_title),
                    body = stringResource(R.string.notifications_empty),
                )
            } else {
                SecondaryButton(
                    text = stringResource(R.string.notifications_mark_all_read),
                    onClick = { viewModel.markAllNotificationsRead() },
                )
                Spacer(modifier = Modifier.height(12.dp))
                notifications.forEach { notification ->
                    VibeCard(
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClick = { viewModel.markNotificationRead(notification) },
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = notificationIcon(notification.type),
                                    fontSize = 20.sp,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text(
                                    text = notification.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (notification.read) FontWeight.Normal else FontWeight.Bold,
                                )
                            }
                            Text(
                                text = notification.body,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = formatDateWithTime(notification.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun notificationIcon(type: NotificationType): String = when (type) {
    NotificationType.INVITE -> "👥"
    NotificationType.NEW_ACTIVITY -> "➕"
    NotificationType.DEADLINE -> "⏰"
    NotificationType.WINNER -> "🏆"
    NotificationType.MEMORY -> "📸"
}
