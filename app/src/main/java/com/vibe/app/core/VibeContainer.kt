package com.vibe.app.core

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import com.vibe.app.data.firebase.FirebaseAuthProvider
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.repository.*
import com.vibe.app.identity.GoogleSignInGateway
import com.vibe.app.identity.PushGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VibeContainer private constructor(private val context: Context) {
    val time: TimeProvider = SystemTime
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.databaseBuilder(context, VibeDatabase::class.java, "vibe.db").build()
    val settings = SettingsStore(context)
    val connectivity: ConnectivityObserver = AndroidConnectivityObserver(context)

    // Firebase services replace the custom Node.js REST API.
    val firebaseAuth = FirebaseAuthProvider()
    val firestore = FirestoreProvider()

    val authRepository = AuthRepository(firebaseAuth, firestore, database, settings)
    val groupRepository = GroupRepository(firestore, database, settings, time)
    val activityRepository = ActivityRepository(firestore, database, settings, time)
    val decisionRepository = DecisionRepository(firestore, database, settings, time)
    val memoryRepository = MemoryRepository(firestore, database, settings, time)
    val notificationRepository = NotificationRepository(firestore, database, settings, time)
    val googleSignIn = GoogleSignInGateway(context)
    val push = PushGateway(context, notificationRepository)

    val imageLoader: ImageLoader = ImageLoader.Builder(context).crossfade(true).build()

    init {
        // SyncWorker.schedule is invoked by the connectivity observer when the
        // device reconnects; Firestore persistence handles the actual sync.
    }

    suspend fun refreshAll() {
        if (settings.currentUserId() == null) return
        authRepository.refreshProfile()
        val groups = database.groupDao().all()
        for (group in groups) {
            activityRepository.refresh(group.id)
            memoryRepository.refreshPlans(group.id)
            memoryRepository.refreshMemories(group.id)
            database.decisionDao().latestForGroup(group.id)?.let { decisionRepository.refreshRound(it.id) }
        }
        notificationRepository.refresh()
        push.registerCurrentToken()
    }

    fun start() {
        appScope.launch {
            settings.session.collect { session ->
                if (session == null) {
                    database.clearAllTables()
                } else {
                    push.registerCurrentToken()
                }
            }
        }
    }

    companion object {
        @Volatile private var instance: VibeContainer? = null
        fun provide(context: Context): VibeContainer = instance ?: synchronized(this) {
            instance ?: VibeContainer(context.applicationContext).also { instance = it; it.start() }
        }
    }
}
