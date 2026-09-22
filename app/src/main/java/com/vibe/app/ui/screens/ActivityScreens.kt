@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.repository.ActivityRepository
import com.vibe.app.data.repository.DecisionRepository
import com.vibe.app.domain.Activity
import com.vibe.app.domain.ActivityFilters
import com.vibe.app.domain.ActivityStatus
import com.vibe.app.domain.FieldError
import com.vibe.app.ui.components.ActivityRow
import com.vibe.app.ui.components.EmptyState
import com.vibe.app.ui.components.ErrorText
import com.vibe.app.ui.components.FilterPill
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivitiesViewModel(
    private val activityRepository: ActivityRepository,
    private val decisionRepository: DecisionRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val filterState = MutableStateFlow(ActivityFilterOption.ALL)

    val filter: ActivityFilterOption get() = filterState.value

    private val flowCache = mutableMapOf<String?, StateFlow<List<Activity>>>()

    var error: FieldError? by mutableStateOf(null)
        private set

    var savedMessage: Boolean by mutableStateOf(false)
        private set

    fun activities(groupId: String?): StateFlow<List<Activity>> = flowCache.getOrPut(groupId) {
        val source = if (groupId == null) {
            activityRepository.observeAll()
        } else {
            activityRepository.observeForGroup(groupId)
        }
        source.combine(filterState) { list, option ->
            ActivityFilters.apply(list, option.toDomain(), currentUserId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    fun selectFilter(option: ActivityFilterOption) {
        filterState.value = option
    }

    fun toggleFavourite(activity: Activity) {
        viewModelScope.launch { activityRepository.setFavourite(activity, !activity.favourite) }
    }

    fun save(groupId: String, title: String, description: String, icon: String, onSaved: () -> Unit) {
        error = null
        viewModelScope.launch {
            when (val result = activityRepository.addActivity(groupId, title, description, icon)) {
                is VibeResult.Ok -> {
                    savedMessage = true
                    onSaved()
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun update(activity: Activity, title: String, description: String, icon: String, onSaved: () -> Unit) {
        error = null
        viewModelScope.launch {
            when (val result = activityRepository.updateActivity(activity, title, description, icon)) {
                is VibeResult.Ok -> {
                    savedMessage = true
                    onSaved()
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun markForDecision(activity: Activity) {
        viewModelScope.launch { activityRepository.markForDecision(activity) }
    }

    fun delete(activity: Activity, onDeleted: () -> Unit) {
        viewModelScope.launch {
            activityRepository.deleteActivity(activity.id)
            onDeleted()
        }
    }

    /** The signed-in member, so "Mine" can filter the list. */
    private var currentUserId: String = ""

    init {
        viewModelScope.launch { currentUserId = settings.currentUserId().orEmpty() }
    }
}

enum class ActivityFilterOption { ALL, SUGGESTED, COMPLETED, FAVOURITES, MINE }

private fun ActivityFilterOption.toDomain(): ActivityFilters.Option = when (this) {
    ActivityFilterOption.ALL -> ActivityFilters.Option.ALL
    ActivityFilterOption.SUGGESTED -> ActivityFilters.Option.SUGGESTED
    ActivityFilterOption.COMPLETED -> ActivityFilters.Option.COMPLETED
    ActivityFilterOption.FAVOURITES -> ActivityFilters.Option.FAVOURITES
    ActivityFilterOption.MINE -> ActivityFilters.Option.MINE
}

@Composable
private fun filterRow(
    selected: ActivityFilterOption,
    onSelect: (ActivityFilterOption) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        FilterPill(stringResource(R.string.filter_all), selected == ActivityFilterOption.ALL) {
            onSelect(ActivityFilterOption.ALL)
        }
        FilterPill(stringResource(R.string.filter_suggested), selected == ActivityFilterOption.SUGGESTED) {
            onSelect(ActivityFilterOption.SUGGESTED)
        }
        FilterPill(stringResource(R.string.filter_completed), selected == ActivityFilterOption.COMPLETED) {
            onSelect(ActivityFilterOption.COMPLETED)
        }
        FilterPill(stringResource(R.string.filter_favourites), selected == ActivityFilterOption.FAVOURITES) {
            onSelect(ActivityFilterOption.FAVOURITES)
        }
        FilterPill(stringResource(R.string.filter_mine), selected == ActivityFilterOption.MINE) {
            onSelect(ActivityFilterOption.MINE)
        }
    }
}

/** --------------------------------------------------------- idea library --- */

@Composable
fun ActivitiesScreen(
    groupId: String?,
    onBack: () -> Unit,
    onOpenActivity: (String) -> Unit,
    onAddActivity: () -> Unit,
    onHome: () -> Unit,
    onMemories: () -> Unit,
    onProfile: () -> Unit,
) {
    val viewModel = vibeViewModel { ActivitiesViewModel(it.activityRepository, it.decisionRepository, it.settings) }
    val activities by viewModel.activities(groupId).collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VibeTopBar(
                title = stringResource(R.string.activities_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = onAddActivity) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add), tint = VibeCoral)
                    }
                },
            )
        },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.ACTIVITIES,
                onSelect = { tab ->
                    onSelectTab(
                        tab = tab,
                        onHome = onHome,
                        onActivities = {},
                        onProfile = onProfile,
                        onMemories = onMemories,
                        onAdd = onAddActivity,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                filterRow(viewModel.filter) { viewModel.selectFilter(it) }
                Spacer(modifier = Modifier.height(14.dp))
            }
            if (activities.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.group_empty_activities),
                        body = stringResource(R.string.activities_subtitle),
                    ) {
                        PrimaryButton(text = stringResource(R.string.quick_add_title), onClick = onAddActivity)
                    }
                }
            } else {
                items(activities, key = { it.id }) { activity ->
                    ActivityRow(
                        icon = activity.icon,
                        title = activity.title,
                        subtitle = if (activity.createdByName.isBlank()) {
                            stringResource(R.string.activity_yes_count, activity.yesVotes)
                        } else {
                            stringResource(R.string.activity_added_by, activity.createdByName)
                        },
                        participantCount = activity.participantCount,
                        favourite = activity.favourite,
                        onFavouriteToggle = { viewModel.toggleFavourite(activity) },
                        onClick = { onOpenActivity(activity.id) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/** --------------------------------------------------------- add / edit ----- */

@Composable
fun ActivityEditScreen(
    groupId: String,
    activityId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val viewModel = vibeViewModel { ActivitiesViewModel(it.activityRepository, it.decisionRepository, it.settings) }
    val container = com.vibe.app.ui.LocalVibeContainer.current
    val icons = stringArrayResource(R.array.activity_icons)

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(icons.first()) }
    var existing: Activity? by remember { mutableStateOf(null) }

    LaunchedEffect(activityId) {
        if (activityId != null) {
            // Read the cached row once so the form starts from the saved values.
            val activity = container.activityRepository.observeById(activityId).first()
            if (activity != null) {
                existing = activity
                title = activity.title
                description = activity.description
                icon = activity.icon
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VibeTopBar(
                title = if (activityId == null) {
                    stringResource(R.string.activity_add_title)
                } else {
                    stringResource(R.string.activity_edit_title)
                },
                onBack = onBack,
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.activity_title_label)) },
                placeholder = { Text(stringResource(R.string.activity_title_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(viewModel.error)
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.activity_description_label)) },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(stringResource(R.string.create_group_icon))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icons.take(5).forEach { candidate ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (candidate == icon) {
                            VibeCoral.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        onClick = { icon = candidate },
                    ) {
                        Text(text = candidate, fontSize = 22.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icons.drop(5).forEach { candidate ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (candidate == icon) {
                            VibeCoral.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        onClick = { icon = candidate },
                    ) {
                        Text(text = candidate, fontSize = 22.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            PrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    val current = existing
                    if (current == null) {
                        viewModel.save(groupId, title, description, icon, onSaved)
                    } else {
                        viewModel.update(current, title, description, icon, onSaved)
                    }
                },
            )
        }
    }
}

/** ------------------------------------------------------ activity detail --- */

@Composable
fun ActivityDetailScreen(
    activityId: String,
    onBack: () -> Unit,
    onPlan: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val container = com.vibe.app.ui.LocalVibeContainer.current
    val activity by container.activityRepository.observeById(activityId)
        .collectAsStateWithLifecycle(initialValue = null)
    val viewModel = vibeViewModel { ActivitiesViewModel(it.activityRepository, it.decisionRepository, it.settings) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.activity_detail_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            val current = activity
            if (current == null) {
                Text(
                    text = stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            VibeCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = current.icon, fontSize = 30.sp, modifier = Modifier.padding(end = 12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = current.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = stringResource(R.string.activity_added_by, current.createdByName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (current.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(text = current.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.activity_yes_count, current.yesVotes),
                            style = MaterialTheme.typography.labelLarge,
                            color = VibeMint,
                        )
                        Text(
                            text = statusLabelText(current.status),
                            style = MaterialTheme.typography.labelLarge,
                            color = VibeLilac,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            PrimaryButton(
                text = stringResource(R.string.activity_add_to_decision),
                onClick = { viewModel.markForDecision(current) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.action_plan_it),
                onClick = { onPlan(current.id) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.action_edit),
                onClick = { onEdit(current.id) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.activity_favourite),
                onClick = { viewModel.toggleFavourite(current) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.action_delete),
                onClick = { viewModel.delete(current, onDeleted) },
            )
        }
    }
}

@Composable
private fun statusLabelText(status: ActivityStatus): String = when (status) {
    ActivityStatus.SUGGESTED -> stringResource(R.string.activity_status_active)
    ActivityStatus.ACTIVE -> stringResource(R.string.decision_state_open)
    ActivityStatus.ELIMINATED -> stringResource(R.string.activity_status_eliminated)
    ActivityStatus.WINNER -> stringResource(R.string.activity_status_winner)
    ActivityStatus.COMPLETED -> stringResource(R.string.activity_status_completed)
}
