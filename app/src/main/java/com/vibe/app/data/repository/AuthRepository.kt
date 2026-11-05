package com.vibe.app.data.repository

import com.vibe.app.core.VibeResult
import com.vibe.app.core.toProblem
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.prefs.Session
import com.vibe.app.data.remote.*
import com.vibe.app.data.sync.SyncQueue
import com.vibe.app.data.sync.onlineMutation
import com.vibe.app.identity.GoogleFirebaseIdentity
import com.vibe.app.identity.GoogleSignInGateway
import com.vibe.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    val settings: SettingsStore,
    private val queue: SyncQueue,
    private val media: MediaRepository? = null,
    private val googleSignIn: GoogleSignInGateway? = null,
) {
    val session = settings.session
    val appSettings = settings.settings
    val profile: Flow<User?> = session.flatMapLatest { current ->
        if (current == null) flowOf(null) else database.userDao().observeById(current.userId).map { it?.toDomain() }
    }
    suspend fun register(name: String, email: String, password: String, confirmation: String): VibeResult<User> {
        Validators.displayName(name)?.let { return VibeResult.Problem(it) }
        Validators.email(email)?.let { return VibeResult.Problem(it) }
        Validators.password(password)?.let { return VibeResult.Problem(it) }
        Validators.passwordConfirmation(password, confirmation)?.let { return VibeResult.Problem(it) }
        if (!queue.isOnline()) return VibeResult.Problem(kind = ApiErrorKind.NETWORK, detail = "Connect to the internet to create an account.")
        return when (val r = apiResult { api.register(RegisterRequest(name.trim(), email.trim(), password)) }) {
            is ApiResult.Success -> signIn(r.data)
            is ApiResult.Failure -> r.toProblem()
        }
    }
    suspend fun login(email: String, password: String): VibeResult<User> {
        Validators.email(email)?.let { return VibeResult.Problem(it) }
        // An existing session may reopen offline; a new password is never accepted offline.
        val cached = settings.currentSession()
        if (cached?.email.equals(email.trim(), true) && !queue.isOnline()) return profileNow()?.let { VibeResult.Ok(it) }
            ?: VibeResult.Problem(kind = ApiErrorKind.NETWORK)
        return when (val r = apiResult { api.login(LoginRequest(email.trim(), password)) }) {
            is ApiResult.Success -> signIn(r.data)
            is ApiResult.Failure -> r.toProblem()
        }
    }
    suspend fun signInWithGoogle(identity: GoogleFirebaseIdentity): VibeResult<User> {
        if (identity.googleIdToken.isBlank()) return VibeResult.Problem(detail = "Missing Google ID token")
        return when (val result = apiResult { api.googleSignIn(GoogleAuthRequest(identity.googleIdToken)) }) {
            is ApiResult.Success -> signIn(result.data, identity)
            is ApiResult.Failure -> result.toProblem()
        }
    }
    private suspend fun signIn(response: AuthResponse, identity: GoogleFirebaseIdentity? = null): VibeResult<User> {
        val apiUser = response.user.toDomain()
        val user = identity?.let { provider ->
            apiUser.copy(
                displayName = provider.displayName.ifBlank { apiUser.displayName },
                email = provider.email.ifBlank { apiUser.email },
                photoUri = provider.photoUrl ?: apiUser.photoUri,
            )
        } ?: apiUser
        val oldId = settings.currentUserId()
        if (oldId != null && oldId != user.id && database.syncQueueDao().count() > 0) return VibeResult.Problem(detail = "Sync the current account before switching accounts.")
        if (oldId != null && oldId != user.id) withContext(Dispatchers.IO) { database.clearAllTables(); media?.photos?.clear() }
        database.userDao().upsert(user.toEntity())
        settings.saveSession(
            Session(
                user.id, user.displayName, user.email, response.accessToken, response.refreshToken,
                response.expiresAtEpochMillis,
                googleAccountId = identity?.googleAccountId,
                firebaseUid = identity?.firebaseUid,
            ),
        )
        settings.setPhotoUri(user.photoUri)
        return VibeResult.Ok(user)
    }
    suspend fun deleteAccount(password: String?): VibeResult<Unit> {
        if (database.syncQueueDao().count() > 0) return VibeResult.Problem(detail = "Sync all changes before deleting your account.")
        return when (val r = apiResult { api.deleteAccount(DeleteAccountRequest(password?.takeIf { it.isNotBlank() })) }) {
            is ApiResult.Success -> { runCatching { googleSignIn?.signOut() }; settings.clearSession(); settings.setPhotoUri(null); withContext(Dispatchers.IO) { database.clearAllTables() }; media?.photos?.clear(); VibeResult.Ok(Unit) }
            is ApiResult.Failure -> r.toProblem()
        }
    }
    suspend fun logout(): VibeResult<Unit> {
        if (database.syncQueueDao().count() > 0) return VibeResult.Problem(detail = "Sync or resolve pending changes before signing out. Your work has not been deleted.")
        val token = settings.fcmToken()
        if (queue.isOnline()) {
            token?.let { apiResult { api.unregisterDevice(RegisterDeviceRequest(it)) } }
            val r = apiResult { api.logout() }
            if (r is ApiResult.Failure && r.kind != ApiErrorKind.NETWORK && r.kind != ApiErrorKind.UNAUTHORISED) return r.toProblem()
        }
        runCatching { googleSignIn?.signOut() }
        settings.clearSession()
        settings.setPhotoUri(null)
        withContext(Dispatchers.IO) { database.clearAllTables() }
        media?.photos?.clear()
        return VibeResult.Ok(Unit)
    }
    suspend fun refreshProfile(): VibeResult<User> = when (val r = apiResult { api.me() }) {
        is ApiResult.Success -> { if (database.syncQueueDao().count() == 0) database.userDao().upsert(r.data.toDomain().toEntity()); VibeResult.Ok(r.data.toDomain()) }
        is ApiResult.Failure -> r.toProblem()
    }
    suspend fun updateProfile(displayName: String, username: String?, photoUri: String? = null): VibeResult<User> {
        Validators.displayName(displayName)?.let { return VibeResult.Problem(it) }
        val current = profileNow() ?: return VibeResult.Problem(detail = "Please sign in.")
        val photo = photoUri?.let { media?.photos?.persist(it) ?: it } ?: current.photoUri
        val updated = current.copy(displayName = displayName.trim(), username = username?.trim()?.takeIf { it.isNotEmpty() } ?: current.username, photoUri = photo)
        database.userDao().upsert(updated.toEntity()); settings.updateDisplayName(updated.displayName); settings.setPhotoUri(photo)
        val payload = UpdateProfileRequest(updated.displayName, updated.username, photoUri = photo)
        val item = queue.enqueue(SyncAction.UPDATE_PROFILE, current.id, payload, UpdateProfileRequest::class.java)
        val requestPayload = if (queue.isOnline() && photo?.startsWith("file://") == true) payload.copy(photoUri = media?.upload(photo, null) ?: photo) else payload
        return when (val r = onlineMutation(queue, item) { api.updateProfile(requestPayload) }) {
            is ApiResult.Success -> VibeResult.Ok(r.data.toDomain())
            is ApiResult.Failure -> if (r.kind == ApiErrorKind.NETWORK || r.kind == ApiErrorKind.SERVER) VibeResult.Ok(updated) else r.toProblem()
        }
    }
    suspend fun updateSettings(themeMode: ThemeMode? = null, language: AppLanguage? = null, notificationsEnabled: Boolean? = null, privacyMembersOnly: Boolean? = null): VibeResult<Unit> {
        themeMode?.let { settings.setTheme(it) }; language?.let { settings.setLanguage(it) }
        notificationsEnabled?.let { settings.setNotificationsEnabled(it) }; privacyMembersOnly?.let { settings.setPrivacyMembersOnly(it) }
        val uid = settings.currentUserId() ?: return VibeResult.Ok(Unit)
        val payload = UpdateProfileRequest(language = language?.tag, themeMode = themeMode?.name, notificationsEnabled = notificationsEnabled, privacyMembersOnly = privacyMembersOnly)
        val item = queue.enqueue(SyncAction.UPDATE_PROFILE, "$uid-settings", payload, UpdateProfileRequest::class.java)
        val r = onlineMutation(queue, item) { api.updateProfile(payload) }
        return if (r is ApiResult.Success || (r is ApiResult.Failure && (r.kind == ApiErrorKind.NETWORK || r.kind == ApiErrorKind.SERVER))) VibeResult.Ok(Unit) else (r as ApiResult.Failure).toProblem()
    }
    suspend fun changePassword(current: String, fresh: String, confirmation: String): VibeResult<Unit> {
        if (current.isBlank()) return VibeResult.Problem(FieldError.CURRENT_PASSWORD_REQUIRED)
        Validators.password(fresh)?.let { return VibeResult.Problem(it) }
        Validators.passwordConfirmation(fresh, confirmation)?.let { return VibeResult.Problem(it) }
        return when (val r = apiResult { api.changePassword(ChangePasswordRequest(current, fresh)) }) {
            is ApiResult.Success -> { signIn(r.data); VibeResult.Ok(Unit) }
            is ApiResult.Failure -> r.toProblem()
        }
    }
    suspend fun requestPasswordReset(email: String): VibeResult<Unit> {
        Validators.email(email)?.let { return VibeResult.Problem(it) }
        return when (val r = apiResult { api.requestPasswordReset(PasswordResetRequest(email.trim())) }) {
            is ApiResult.Success -> VibeResult.Ok(Unit)
            is ApiResult.Failure -> r.toProblem()
        }
    }
    suspend fun providers(): VibeResult<ProvidersDto> = when (val r = apiResult { api.providers() }) { is ApiResult.Success -> VibeResult.Ok(r.data); is ApiResult.Failure -> r.toProblem() }
    suspend fun linkGoogle(token: String): VibeResult<Unit> = when (val r = apiResult { api.linkGoogle(GoogleAuthRequest(token)) }) { is ApiResult.Success -> VibeResult.Ok(Unit); is ApiResult.Failure -> r.toProblem() }
    suspend fun unlinkGoogle(): VibeResult<Unit> = when (val r = apiResult { api.unlinkGoogle() }) { is ApiResult.Success -> VibeResult.Ok(Unit); is ApiResult.Failure -> r.toProblem() }
    suspend fun markOnboarded() = settings.setOnboarded(true)
    suspend fun stats(userId: String) = UserStats(database.decisionDao().countForMember(userId), database.activityDao().countByUser(userId), database.groupDao().membershipsOf(userId).size)
    private suspend fun profileNow() = settings.currentUserId()?.let { database.userDao().byId(it)?.toDomain() }
}
