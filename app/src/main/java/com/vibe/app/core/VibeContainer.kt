package com.vibe.app.core

import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vibe.app.BuildConfig
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.repository.*
import com.vibe.app.data.sync.*
import com.vibe.app.identity.GoogleSignInGateway
import com.vibe.app.identity.PushGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class VibeContainer private constructor(private val context: Context) {
    val time: TimeProvider = SystemTime
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = Room.databaseBuilder(context, VibeDatabase::class.java, "vibe.db").build()
    val settings = SettingsStore(context)
    val connectivity: ConnectivityObserver = AndroidConnectivityObserver(context)
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val authInterceptor = AuthInterceptor { settings.currentAccessToken() }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .authenticator(SessionAuthenticator(SessionRefreshGate(settings) { unauthenticatedApi() }))
        .addInterceptor(HttpLoggingInterceptor().apply { level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE; redactHeader("Authorization") })
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    private fun builder(client: OkHttpClient) = Retrofit.Builder().baseUrl(BuildConfig.API_BASE_URL).client(client).addConverterFactory(MoshiConverterFactory.create(moshi))
    private fun unauthenticatedApi(): VibeApi = builder(OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()).build().create(VibeApi::class.java)
    val api: VibeApi = builder(client).build().create(VibeApi::class.java)
    val imageLoader: ImageLoader = ImageLoader.Builder(context).okHttpClient(client).crossfade(true).build()
    val media = MediaRepository(api, PhotoStore(context))
    val googleSignIn = GoogleSignInGateway(context)
    val syncQueue = SyncQueue(database, moshi, time)
    val syncManager = SyncManager(api, database, connectivity, appScope, settings, moshi, media)
    val authRepository = AuthRepository(api, database, settings, syncQueue, media, googleSignIn)
    val groupRepository = GroupRepository(api, database, syncQueue, settings, time)
    val activityRepository = ActivityRepository(api, database, syncQueue, settings, time)
    val decisionRepository = DecisionRepository(api, database, syncQueue, settings, time)
    val memoryRepository = MemoryRepository(api, database, syncQueue, settings, time, media)
    val notificationRepository = NotificationRepository(api, database, settings, time)
    val push = PushGateway(context, notificationRepository)
    init {
        syncQueue.isOnline = { connectivity.isOnline.first() }
        syncQueue.changed = { SyncWorker.schedule(context) }
        SyncWorkerBridge.schedule = { SyncWorker.schedule(context) }
        syncManager.afterSync = { refreshAll() }
    }
    suspend fun refreshAll() {
        if (settings.currentUserId() == null) return
        authRepository.refreshProfile()
        groupRepository.refresh()
        val groups = database.groupDao().all()
        for (group in groups) {
            // Never clobber locally queued changes during a background refresh.
            activityRepository.refresh(group.id)
            memoryRepository.refreshPlans(group.id)
            memoryRepository.refreshMemories(group.id)
            database.decisionDao().latestForGroup(group.id)?.let { decisionRepository.refreshRound(it.id) }
        }
        notificationRepository.refresh()
        push.registerCurrentToken()
    }
    fun start() {
        syncManager.start()
        appScope.launch {
            settings.session.collect { session ->
                if (session == null) {
                    database.clearAllTables()
                } else {
                    if (session.expiresAtEpochMillis < time.nowMillis() + 60000 && connectivity.isOnline.first()) {
                        // The authenticator refreshes expired access tokens on the next 401.
                    }
                    push.registerCurrentToken()
                }
            }
        }
    }
    companion object {
        @Volatile private var instance: VibeContainer? = null
        fun provide(context: Context): VibeContainer = instance ?: synchronized(this) { instance ?: VibeContainer(context.applicationContext).also { instance = it; it.start() } }
    }
}
