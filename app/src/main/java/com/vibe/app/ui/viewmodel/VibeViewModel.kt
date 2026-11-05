package com.vibe.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vibe.app.core.VibeContainer
import com.vibe.app.core.VibeResult
import com.vibe.app.data.prefs.AppSettings
import com.vibe.app.data.prefs.Session
import com.vibe.app.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** XML fragments share one lifecycle-aware state owner; repositories remain UI-independent. */
class VibeViewModel(val c: VibeContainer) : ViewModel() {
    val session: StateFlow<Session?> = c.settings.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val profile: StateFlow<User?> = c.authRepository.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val groups: StateFlow<List<Group>> = c.groupRepository.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activities: StateFlow<List<Activity>> = c.activityRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val memories: StateFlow<List<Memory>> = c.memoryRepository.memories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notifications: StateFlow<List<VibeNotification>> = c.notificationRepository.notifications.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val online = c.connectivity.isOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    private val _message = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    init { viewModelScope.launch { session.filterNotNull().collect { refresh() } } }
    fun message(res: Int) { _message.tryEmit(res) }
    fun refresh() { viewModelScope.launch { c.refreshAll() } }
    fun sync() {
        // Firestore handles offline sync natively via persistence. Trigger a refresh
        // of all data from Firestore when the user pulls to sync.
        viewModelScope.launch {
            _busy.value = true
            try { c.refreshAll() } catch (e: Exception) { message(com.vibe.app.R.string.settings_sync_failed) }
            finally { _busy.value = false; message(com.vibe.app.R.string.settings_sync_done) }
        }
    }
    fun <T> action(block: suspend () -> VibeResult<T>, done: (VibeResult<T>) -> Unit = {}) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { done(block()) } catch (e: Exception) { done(VibeResult.Problem(detail = e.message)) } finally { _busy.value = false }
        }
    }
    fun signOut(done: (VibeResult<Unit>) -> Unit) = action({ c.authRepository.logout() }, done)
    fun updateSettings(theme: ThemeMode? = null, language: AppLanguage? = null, notifications: Boolean? = null, privacy: Boolean? = null) {
        viewModelScope.launch { c.authRepository.updateSettings(theme, language, notifications, privacy) }
    }
    fun group(id: String) = c.groupRepository.observeGroup(id)
    fun activity(id: String) = c.activityRepository.observeById(id)
    fun memory(id: String) = c.memoryRepository.observeMemory(id)
    fun round(id: String) = c.decisionRepository.observeRound(id)
    fun winner(groupId: String) = c.decisionRepository.observeLatestWinner(groupId)
    companion object {
        fun factory(c: VibeContainer): ViewModelProvider.Factory = viewModelFactory { initializer { VibeViewModel(c) } }
    }
}
