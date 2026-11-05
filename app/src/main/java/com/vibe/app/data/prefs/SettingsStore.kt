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

private val Context.vibeDataStore: DataStore<Preferences> by preferencesDataStore(name = "vibe_settings")

/** The signed-in session. Tokens live on the device only, never in logs. */
data class Session(
    val userId: String,
    val displayName: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val googleAccountId: String? = null,
    val firebaseUid: String? = null,
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

    private object Keys {
        val USER_ID = stringPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_EMAIL = stringPreferencesKey("user_email")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val GOOGLE_ACCOUNT_ID = stringPreferencesKey("google_account_id")
        val FIREBASE_UID = stringPreferencesKey("firebase_uid")
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val PRIVACY = booleanPreferencesKey("privacy_members_only")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val PHOTO_URI = stringPreferencesKey("photo_uri")
        val FCM_TOKEN = stringPreferencesKey("fcm_token")
    }

    val session: Flow<Session?> = store.data.map { prefs ->
        val userId = prefs[Keys.USER_ID] ?: return@map null
        Session(
            userId = userId,
            displayName = prefs[Keys.USER_NAME].orEmpty(),
            email = prefs[Keys.USER_EMAIL].orEmpty(),
            accessToken = prefs[Keys.ACCESS_TOKEN].orEmpty(),
            refreshToken = prefs[Keys.REFRESH_TOKEN].orEmpty(),
            googleAccountId = prefs[Keys.GOOGLE_ACCOUNT_ID],
            firebaseUid = prefs[Keys.FIREBASE_UID],
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

    suspend fun currentAccessToken(): String? = store.data.first()[Keys.ACCESS_TOKEN]

    suspend fun saveSession(session: Session) {
        store.edit { prefs ->
            prefs[Keys.USER_ID] = session.userId
            prefs[Keys.USER_NAME] = session.displayName
            prefs[Keys.USER_EMAIL] = session.email
            prefs[Keys.ACCESS_TOKEN] = session.accessToken
            prefs[Keys.REFRESH_TOKEN] = session.refreshToken
            session.googleAccountId?.let { prefs[Keys.GOOGLE_ACCOUNT_ID] = it }
                ?: prefs.remove(Keys.GOOGLE_ACCOUNT_ID)
            session.firebaseUid?.let { prefs[Keys.FIREBASE_UID] = it }
                ?: prefs.remove(Keys.FIREBASE_UID)
        }
    }

    suspend fun updateDisplayName(name: String) {
        store.edit { it[Keys.USER_NAME] = name }
    }

    suspend fun clearSession() {
        store.edit { prefs ->
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.USER_NAME)
            prefs.remove(Keys.USER_EMAIL)
            prefs.remove(Keys.ACCESS_TOKEN)
            prefs.remove(Keys.REFRESH_TOKEN)
            prefs.remove(Keys.GOOGLE_ACCOUNT_ID)
            prefs.remove(Keys.FIREBASE_UID)
        }
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
