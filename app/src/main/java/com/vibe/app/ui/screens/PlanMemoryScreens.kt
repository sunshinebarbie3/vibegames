@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vibe.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.repository.ActivityRepository
import com.vibe.app.data.repository.MemoryRepository
import com.vibe.app.domain.Activity
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.Memory
import com.vibe.app.domain.Plan
import com.vibe.app.ui.components.EmptyState
import com.vibe.app.ui.components.ErrorText
import com.vibe.app.ui.components.PrimaryButton
import com.vibe.app.ui.components.SecondaryButton
import com.vibe.app.ui.components.SectionHeader
import com.vibe.app.ui.components.StarRating
import com.vibe.app.ui.components.VibeCard
import com.vibe.app.ui.components.VibeTopBar
import com.vibe.app.ui.components.VibeBottomBar
import com.vibe.app.ui.components.VibeTab
import com.vibe.app.ui.components.onSelectTab
import com.vibe.app.ui.formatDateWithTime
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint
import com.vibe.app.ui.vibeViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

class MemoryViewModel(
    private val activityRepository: ActivityRepository,
    private val memoryRepository: MemoryRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    var error: FieldError? by mutableStateOf(null)
        private set

    var message: MemoryMessage? by mutableStateOf(null)
        private set

    var currentPlan: Plan? by mutableStateOf(null)
        private set

    val memories: StateFlow<List<Memory>> = memoryRepository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun activity(activityId: String): StateFlow<Activity?> = activityRepository.observeById(activityId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun memory(memoryId: String): StateFlow<Memory?> = memoryRepository.observeMemory(memoryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh(groupId: String) {
        viewModelScope.launch {
            memoryRepository.refreshMemories(groupId)
            memoryRepository.refreshPlans(groupId)
        }
    }

    fun savePlan(groupId: String, activityId: String, scheduledAtEpochMillis: Long?) {
        viewModelScope.launch {
            when (val result = memoryRepository.savePlan(groupId, activityId, scheduledAtEpochMillis)) {
                is VibeResult.Ok -> {
                    currentPlan = result.value
                    message = MemoryMessage.PlanSaved
                }

                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun completePlan(planId: String, rating: Int, caption: String, photos: List<String>) {
        viewModelScope.launch {
            when (val result = memoryRepository.completePlan(planId, rating, caption, photos)) {
                is VibeResult.Ok -> message = MemoryMessage.MemorySaved
                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun updateMemory(memoryId: String, rating: Int, caption: String, photos: List<String>) {
        viewModelScope.launch {
            when (val result = memoryRepository.updateMemory(memoryId, rating, caption, photos)) {
                is VibeResult.Ok -> message = MemoryMessage.MemorySaved
                is VibeResult.Problem -> error = result.error
            }
        }
    }

    fun addPhoto(memoryId: String, uri: String) {
        viewModelScope.launch {
            when (val result = memoryRepository.addPhoto(memoryId, uri)) {
                is VibeResult.Ok -> message = MemoryMessage.PhotoAdded
                is VibeResult.Problem -> error = result.error
            }
        }
    }

    /** Picks the most recent plan for an activity so the screen can resume it. */
    fun planForActivity(activityId: String) {
        viewModelScope.launch {
            val activity = activityRepository.observeById(activityId).first()
            val groupId = activity?.groupId ?: return@launch
            val plans = memoryRepository.observePlans(groupId).first()
            currentPlan = plans.firstOrNull { it.activityId == activityId }
        }
    }

}

enum class MemoryMessage { PlanSaved, MemorySaved, PhotoAdded }

/** ------------------------------------------------------------ plan it ----- */

@Composable
fun PlanScreen(
    activityId: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = vibeViewModel {
        MemoryViewModel(it.activityRepository, it.memoryRepository, it.settings)
    }
    val activity by viewModel.activity(activityId).collectAsStateWithLifecycle()

    var scheduledAt by remember { mutableStateOf<Long?>(null) }
    var rating by remember { mutableStateOf(0) }
    var caption by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(activityId) { viewModel.planForActivity(activityId) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            photos = photos + uri.toString()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.plan_title), onBack = onBack) },
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
                Text(text = stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            VibeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = current.icon, fontSize = 28.sp, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(text = current.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = stringResource(R.string.activity_added_by, current.createdByName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.plan_when_label))
            Text(
                text = scheduledAt?.let { stringResource(R.string.plan_scheduled_for, formatDateWithTime(it)) }
                    ?: stringResource(R.string.plan_no_date),
                style = MaterialTheme.typography.bodyLarge,
                color = VibeLilac,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SecondaryButton(
                text = stringResource(R.string.plan_pick_datetime),
                onClick = {
                    val calendar = Calendar.getInstance()
                    DatePickerDialog(
                        context,
                        { _, year, month, day ->
                            calendar.set(year, month, day)
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                                    calendar.set(Calendar.MINUTE, minute)
                                    scheduledAt = calendar.timeInMillis
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true,
                            ).show()
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH),
                    ).show()
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            PrimaryButton(
                text = stringResource(R.string.plan_save),
                onClick = {
                    viewModel.savePlan(current.groupId, current.id, scheduledAt)
                    onSaved()
                },
            )

            // Completing the plan is what turns it into a memory: caption,
            // rating and photos, exactly as described in the design document.
            SectionHeader(stringResource(R.string.memory_detail_title))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.memory_rating_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                StarRating(rating = rating, onRate = { rating = it })
            }
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text(stringResource(R.string.memory_caption_label)) },
                placeholder = { Text(stringResource(R.string.memory_caption_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            SecondaryButton(
                text = stringResource(R.string.memory_add_photo),
                onClick = { photoPicker.launch(arrayOf("image/*")) },
            )
            if (photos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.memory_photo_count, photos.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = VibeMint,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            PrimaryButton(
                text = stringResource(R.string.plan_mark_complete),
                onClick = {
                    val plan = viewModel.currentPlan
                    if (plan != null) {
                        viewModel.completePlan(plan.id, rating, caption, photos)
                        onSaved()
                    } else {
                        viewModel.savePlan(current.groupId, current.id, scheduledAt)
                        onSaved()
                    }
                },
            )
            ErrorText(viewModel.error)
        }
    }
}

/** --------------------------------------------------------- memories ------- */

@Composable
fun MemoriesScreen(
    onBack: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onHome: () -> Unit,
    onProfile: () -> Unit,
) {
    val viewModel = vibeViewModel {
        MemoryViewModel(it.activityRepository, it.memoryRepository, it.settings)
    }
    val memories by viewModel.memories.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.memories_title), onBack = onBack) },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.MEMORIES,
                onSelect = { tab ->
                    onSelectTab(
                        tab = tab,
                        onHome = onHome,
                        onProfile = onProfile,
                        onMemories = {},
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item {
                Text(
                    text = stringResource(R.string.memories_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (memories.isEmpty()) {
                item {
                    EmptyState(
                        title = stringResource(R.string.memories_empty_title),
                        body = stringResource(R.string.memories_empty_body),
                    )
                }
            } else {
                items(memories, key = { it.id }) { memory ->
                    MemoryTile(memory = memory, onClick = { onOpenMemory(memory.id) })
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MemoryTile(memory: Memory, onClick: () -> Unit) {
    VibeCard(onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = memory.activityIcon, fontSize = 26.sp, modifier = Modifier.padding(end = 10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = memory.activityTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = com.vibe.app.ui.formatDate(memory.completedAtEpochMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.memory_rating_value, memory.rating),
                    style = MaterialTheme.typography.labelLarge,
                    color = VibeLilac,
                )
            }
            if (memory.photoUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                AsyncImage(
                    model = memory.photoUris.first(),
                    contentDescription = stringResource(R.string.cd_memory_photo),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            if (memory.caption.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = memory.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ------------------------------------------------------ memory detail ----- */

@Composable
fun MemoryDetailScreen(
    memoryId: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onMemories: () -> Unit,
    onProfile: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = vibeViewModel {
        MemoryViewModel(it.activityRepository, it.memoryRepository, it.settings)
    }
    val memory by viewModel.memory(memoryId).collectAsStateWithLifecycle()

    var rating by remember { mutableStateOf(0) }
    var caption by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(emptyList<String>()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(memory?.id, memory?.rating) {
        val current = memory
        if (current != null && !loaded) {
            rating = current.rating
            caption = current.caption
            photos = current.photoUris
            loaded = true
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            photos = photos + uri.toString()
            viewModel.addPhoto(memoryId, uri.toString())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { VibeTopBar(title = stringResource(R.string.memory_detail_title), onBack = onBack) },
        bottomBar = {
            VibeBottomBar(
                selected = VibeTab.MEMORIES,
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
            val current = memory
            if (current == null) {
                Text(text = stringResource(R.string.state_loading), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = current.activityIcon, fontSize = 30.sp, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text(text = current.activityTitle, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = com.vibe.app.ui.formatDate(current.completedAtEpochMillis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (photos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                photos.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.cd_memory_photo),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(bottom = 8.dp),
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.memories_empty_body),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            SectionHeader(stringResource(R.string.memory_rating_label))
            StarRating(rating = rating, onRate = { rating = it })
            Text(
                text = stringResource(R.string.memory_rating_your_turn),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text(stringResource(R.string.memory_caption_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            SecondaryButton(
                text = stringResource(R.string.memory_add_photo),
                onClick = { photoPicker.launch(arrayOf("image/*")) },
            )
            Spacer(modifier = Modifier.height(10.dp))
            PrimaryButton(
                text = stringResource(R.string.memory_save),
                onClick = { viewModel.updateMemory(current.id, rating, caption, photos) },
            )
            ErrorText(viewModel.error)
        }
    }
}
