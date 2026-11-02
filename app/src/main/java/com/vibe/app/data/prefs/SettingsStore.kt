package com.vibe.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vibe.app.domain.AppLanguage
import com.vibe.app.domain.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val Context.vibeDataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_settings")

/** The signed-in session. Tokens live on the device only, never in logs. */
data class Session(
    val userId: String,
    val displayName: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long = 0L,
)

/** Locally owned settings: theme, language, alerts, privacy and onboarding. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val notificationsEnabled: Boolean = true,
    val privacyMembersOnly: Boolean = true,
    val onboarded: Boolean = false,
    val photoUri: String? = null,
)

/**
 * DataStore-backed settings and session storage.
 *
 * The theme defaults to dark and the language to English, matching the design
 * document, and the language choice is the single source of truth for both the
 * visible labels (through `AppCompatDelegate`) and the alert copy.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.vibeDataStore
    private val vault = TokenVault(context.applicationContext)
    private val tokenVersion = MutableStateFlow(0)
    init {
        // Migrate plaintext tokens from v1, then remove the old values.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            store.edit { it.remove(Keys.ACCESS_TOKEN); it.remove(Keys.REFRESH_TOKEN) }
        }
    }

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val EXPIRES_AT = androidx.datastore.preferences.core.longPreferencesKey("expires_at")
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val PRIVACY = booleanPreferencesKey("privacy_members_only")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val PHOTO_URI = stringPreferencesKey("photo_uri")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
    }

    val session: Flow<Session?> = kotlinx.coroutines.flow.combine(store.data, tokenVersion) { prefs, _ ->
        val userId = prefs[Keys.USER_ID] ?: return@combine null
        Session(
            userId = userId,
            displayName = prefs[Keys.USER_NAME].orEmpty(),
            email = prefs[Keys.USER_EMAIL].orEmpty(),
            accessToken = vault.access(),
            refreshToken = vault.refresh(),
            expiresAtEpochMillis = prefs[Keys.EXPIRES_AT] ?: 0L,
        )
    }

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME] ?: ThemeMode.DARK.name) }
                .getOrDefault(ThemeMode.DARK),
            language = AppLanguage.fromTag(prefs[Keys.LANGUAGE]),
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            privacyMembersOnly = prefs[Keys.PRIVACY] ?: true,
            onboarded = prefs[Keys.ONBOARDED] ?: false,
            photoUri = prefs[Keys.PHOTO_URI],
        )
    }

    suspend fun currentUserId(): String? = store.data.first()[Keys.USER_ID]

    suspend fun currentAccessToken(): String? = vault.access().takeIf { it.isNotBlank() }

    suspend fun currentSession(): Session? = session.first()

    suspend fun saveSession(session: Session) = withContext(Dispatchers.IO) {
        vault.save(session.accessToken, session.refreshToken)
        store.edit { prefs ->
            prefs[Keys.USER_ID] = session.userId
            prefs[Keys.USER_NAME] = session.displayName
            prefs[Keys.USER_EMAIL] = session.email
            prefs[Keys.EXPIRES_AT] = session.expiresAtEpochMillis
            
        }
        tokenVersion.update { it + 1 }
    }

    suspend fun updateDisplayName(name: String) {
        store.edit { it[Keys.USER_NAME] = name }
    }

    suspend fun clearSession() = withContext(Dispatchers.IO) {
        vault.clear()
        store.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.EXPIRES_AT)
        }
        tokenVersion.update { it + 1 }
    }

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[Keys.THEME] = mode.name }

    suspend fun setLanguage(language: AppLanguage) = store.edit { it[Keys.LANGUAGE] = language.tag }

    suspend fun setNotificationsEnabled(enabled: Boolean) = store.edit { it[Keys.NOTIFICATIONS] = enabled }

    suspend fun setPrivacyMembersOnly(membersOnly: Boolean) = store.edit { it[Keys.PRIVACY] = membersOnly }

    suspend fun setOnboarded(onboarded: Boolean) = store.edit { it[Keys.ONBOARDED] = onboarded }

    suspend fun setPhotoUri(uri: String?) = store.edit { prefs ->
        if (uri == null) prefs.remove(Keys.PHOTO_URI) else prefs[Keys.PHOTO_URI] = uri
    }

    suspend fun setFcmToken(token: String) = store.edit { it[Keys.FCM_TOKEN] = token }

    suspend fun fcmToken(): String? = store.data.first()[Keys.FCM_TOKEN]
}
