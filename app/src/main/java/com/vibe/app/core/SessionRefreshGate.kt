package com.vibe.app.core

import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.RefreshRequest
import com.vibe.app.data.remote.VibeApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** Single-flight refresh: simultaneous requests must not rotate the same token twice. */
class SessionRefreshGate(private val settings: SettingsStore, private val api: () -> VibeApi) {
    private val mutex = Mutex()
    suspend fun refresh(failedToken: String?): String? = mutex.withLock {
        val session = settings.currentSession() ?: return null
        if (session.accessToken != failedToken && session.accessToken.isNotBlank()) return session.accessToken
        try {
            val fresh = api().refresh(RefreshRequest(session.refreshToken))
            settings.saveSession(session.copy(accessToken = fresh.accessToken, refreshToken = fresh.refreshToken, expiresAtEpochMillis = fresh.expiresAtEpochMillis))
            fresh.accessToken
        } catch (e: HttpException) {
            if (e.code() == 401) settings.clearSession()
            null
        } catch (_: Exception) { null }
    }
}
