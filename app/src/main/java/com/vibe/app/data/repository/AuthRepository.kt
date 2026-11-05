@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.core.toProblem
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.AppSettings
import com.vibe.app.data.prefs.Session
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.ChangePasswordRequest
import com.vibe.app.data.remote.GoogleAuthRequest
import com.vibe.app.data.remote.LoginRequest
import com.vibe.app.data.remote.RegisterRequest
import com.vibe.app.data.remote.UpdateProfileRequest
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.remote.apiResult
import com.vibe.app.data.remote.toDomain
import com.vibe.app.data.sync.SyncQueue
import com.vibe.app.identity.GoogleFirebaseIdentity
import com.vibe.app.identity.GoogleSignInGateway
import com.vibe.app.domain.AppLanguage
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.ThemeMode
import com.vibe.app.domain.User
import com.vibe.app.domain.UserStats
import com.vibe.app.domain.Validators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Accounts, session and settings.
 *
 * Registration and login validate on the device first (mirroring the API rules)
 * so a member sees a clear message without a round trip, and the API is still
 * the only place a password is ever hashed - the client never stores one.
 */
class AuthRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val settings: SettingsStore,
    private val queue: SyncQueue,
    private val time: TimeProvider,
    private val googleSignIn: GoogleSignInGateway,
) {

    val session: Flow<Session?> = settings.session

    val appSettings: Flow<AppSettings> = settings.settings

    val profile: Flow<User?> = settings.session.flatMapLatest { current ->
        if (current == null) {
            flowOf(null)
        } else {
            database.userDao().observeById(current.userId).map { it?.toDomain() }
        }
    }

    suspend fun register(displayName: String, email: String, password: String, confirmation: String): VibeResult<User> {
        Validators.displayName(displayName)?.let { return VibeResult.Problem(error = it) }
        Validators.email(email)?.let { return VibeResult.Problem(error = it) }
        Validators.password(password)?.let { return VibeResult.Problem(error = it) }
        Validators.passwordConfirmation(password, confirmation)?.let { return VibeResult.Problem(error = it) }

        return when (val result = apiResult { api.register(RegisterRequest(displayName.trim(), email.trim(), password)) }) {
            is ApiResult.Success -> signIn(result.data.accessToken, result.data.refreshToken, result.data.user.toDomain())
            is ApiResult.Failure -> result.toProblem()
        }
    }

    suspend fun login(email: String, password: String): VibeResult<User> {
        Validators.email(email)?.let { return VibeResult.Problem(error = it) }
        return when (val result = apiResult { api.login(LoginRequest(email.trim(), password)) }) {
            is ApiResult.Success -> signIn(result.data.accessToken, result.data.refreshToken, result.data.user.toDomain())
            is ApiResult.Failure -> result.toProblem()
        }
    }

    /** Firebase verifies the Google credential; the VIBE API issues the app session. */
    suspend fun signInWithGoogle(identity: GoogleFirebaseIdentity): VibeResult<User> {
        if (identity.googleIdToken.isBlank()) {
            return VibeResult.Problem(detail = "Missing Google ID token")
        }
        return when (val result = apiResult {
            api.googleSignIn(GoogleAuthRequest(identity.googleIdToken))
        }) {
            is ApiResult.Success -> {
                val apiUser = result.data.user.toDomain()
                val user = apiUser.copy(
                    displayName = identity.displayName.ifBlank { apiUser.displayName },
                    email = identity.email.ifBlank { apiUser.email },
                    photoUri = identity.photoUrl ?: apiUser.photoUri,
                )
                signIn(
                    result.data.accessToken,
                    result.data.refreshToken,
                    user,
                    googleAccountId = identity.googleAccountId,
                    firebaseUid = identity.firebaseUid,
                    refreshFromServer = false,
                )
            }
            is ApiResult.Failure -> result.toProblem()
        }
    }

    private suspend fun signIn(
        accessToken: String,
        refreshToken: String,
        user: User,
        googleAccountId: String? = null,
        firebaseUid: String? = null,
        refreshFromServer: Boolean = true,
    ): VibeResult<User> {
        database.userDao().upsert(user.toEntity())
        settings.saveSession(
            Session(
                userId = user.id,
                displayName = user.displayName,
                email = user.email,
                accessToken = accessToken,
                refreshToken = refreshToken,
                googleAccountId = googleAccountId,
                firebaseUid = firebaseUid,
            ),
        )
        // Keep provider claims until the backend profile has caught up.
        if (refreshFromServer) refreshProfile()
        return VibeResult.Ok(user)
    }

    suspend fun logout() {
        apiResult { api.logout() }
        runCatching { googleSignIn.signOut() }
        settings.clearSession()
        // Cached group data is member data: it is wiped so the next person on the
        // device cannot read it. Pending offline work is kept out of the wipe only
        // after a successful sync, which `SyncManager` attempts first.
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }

    suspend fun refreshProfile(): VibeResult<User> =
        when (val result = apiResult { api.me() }) {
            is ApiResult.Success -> {
                val user = result.data.toDomain()
                database.userDao().upsert(user.toEntity())
                settings.updateDisplayName(user.displayName)
                VibeResult.Ok(user)
            }

            is ApiResult.Failure -> VibeResult.Problem(kind = result.kind, detail = result.message)
        }

    /**
     * Profile edits are optimistic: the local copy and the session update
     * immediately, the change goes into the offline queue, and the API is told
     * when there is a connection.
     */
    suspend fun updateProfile(
        displayName: String,
        username: String?,
        photoUri: String? = null,
    ): VibeResult<User> {
        Validators.displayName(displayName)?.let { return VibeResult.Problem(error = it) }
        val current = profileNow() ?: return VibeResult.Problem(detail = "Not signed in")

        val updated = current.copy(
            displayName = displayName.trim(),
            username = username?.trim().takeUnless { it.isNullOrBlank() } ?: current.username,
            photoUri = photoUri ?: current.photoUri,
        )
        database.userDao().upsert(updated.toEntity())
        settings.updateDisplayName(updated.displayName)
        if (photoUri != null) settings.setPhotoUri(photoUri)

        val payload = UpdateProfileRequest(
            displayName = updated.displayName,
            username = updated.username,
            photoUri = updated.photoUri,
        )
        queue.enqueue(SyncAction.UPDATE_PROFILE, updated.id, payload, UpdateProfileRequest::class.java)

        return when (val result = apiResult { api.updateProfile(payload) }) {
            is ApiResult.Success -> VibeResult.Ok(result.data.toDomain())
            // Saved locally; the queue will carry it to the API later.
            is ApiResult.Failure -> VibeResult.Ok(updated)
        }
    }

    suspend fun updateSettings(
        themeMode: ThemeMode? = null,
        language: AppLanguage? = null,
        notificationsEnabled: Boolean? = null,
        privacyMembersOnly: Boolean? = null,
    ): VibeResult<Unit> {
        themeMode?.let { settings.setTheme(it) }
        language?.let { settings.setLanguage(it) }
        notificationsEnabled?.let { settings.setNotificationsEnabled(it) }
        privacyMembersOnly?.let { settings.setPrivacyMembersOnly(it) }

        val payload = UpdateProfileRequest(
            language = language?.tag,
            themeMode = themeMode?.name,
            notificationsEnabled = notificationsEnabled,
            privacyMembersOnly = privacyMembersOnly,
        )
        val userId = settings.currentUserId() ?: return VibeResult.Ok(Unit)
        queue.enqueue(SyncAction.UPDATE_PROFILE, "$userId-settings", payload, UpdateProfileRequest::class.java)
        api.updateProfile(payload)
        return VibeResult.Ok(Unit)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String, confirmation: String): VibeResult<Unit> {
        if (currentPassword.isBlank()) {
            return VibeResult.Problem(error = com.vibe.app.domain.FieldError.CURRENT_PASSWORD_REQUIRED)
        }
        Validators.password(newPassword)?.let { return VibeResult.Problem(error = it) }
        Validators.passwordConfirmation(newPassword, confirmation)?.let { return VibeResult.Problem(error = it) }

        return when (
            val result = apiResult { api.changePassword(ChangePasswordRequest(currentPassword, newPassword)) }
        ) {
            is ApiResult.Success -> VibeResult.Ok(Unit)
            is ApiResult.Failure -> result.toProblem()
        }
    }

    suspend fun requestPasswordReset(email: String): VibeResult<Unit> {
        Validators.email(email)?.let { return VibeResult.Problem(error = it) }
        return when (val result = apiResult { api.requestPasswordReset(com.vibe.app.data.remote.PasswordResetRequest(email.trim())) }) {
            is ApiResult.Success -> VibeResult.Ok(Unit)
            is ApiResult.Failure -> result.toProblem()
        }
    }

    suspend fun markOnboarded() = settings.setOnboarded(true)

    suspend fun setPhotoUri(uri: String?) = settings.setPhotoUri(uri)

    suspend fun stats(userId: String): UserStats = UserStats(
        decisions = database.decisionDao().countForMember(userId),
        activities = database.activityDao().countByUser(userId),
        groups = database.groupDao().membershipsOf(userId).size,
    )

    private suspend fun profileNow(): User? {
        val id = settings.currentUserId() ?: return null
        return database.userDao().byId(id)?.toDomain()
    }
}
