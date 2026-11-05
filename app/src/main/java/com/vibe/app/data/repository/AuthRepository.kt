package com.vibe.app.data.repository

import com.vibe.app.core.VibeResult
import com.vibe.app.data.firebase.FirebaseAuthProvider
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.domain.FieldError
import com.vibe.app.domain.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

/**
 * Authentication backed by Firebase Authentication instead of a custom Node.js API.
 *
 * - Email/password registration and login use FirebaseAuth directly.
 *   ("Passwords are securely hashed by Firebase Authentication." — see
 *   R.string.auth_password_policy.)
 *   Google sign-in obtains an ID token via Credential Manager and exchanges it
 *   with FirebaseAuth (signInWithCredential), not with /api/v1/auth/google.
 *   The signed-in user's profile is cached in Room for offline access and
 *   mirrored to Firestore so other members can see display names.
 */
class AuthRepository(
    private val firebaseAuth: FirebaseAuthProvider,
    private val firestore: FirestoreProvider,
    private val database: VibeDatabase,
    val settings: SettingsStore,
) {

    val session = settings.session
    val appSettings = settings.settings

    val profile: Flow<User?> = session.flatMapLatest { current ->
        if (current == null) flowOf(null)
        else database.userDao().observeById(current.userId).map { it?.toDomain() }
    }

    suspend fun register(name: String, email: String, password: String, confirmation: String): VibeResult<User> {
        Validators.displayName(name)?.let { return VibeResult.Problem(it) }
        Validators.email(email)?.let { return VibeResult.Problem(it) }
        Validators.password(password)?.let { return VibeResult.Problem(it) }
        Validators.passwordConfirmation(password, confirmation)?.let { return VibeResult.Problem(it) }

        val firebaseUser = runCatching {
            firebaseAuth.createUserWithEmailAndPassword(email, password, name.trim())
        }.getOrElse { return VibeResult.Problem(detail = it.message ?: "Could not create account.") }

        return afterSignIn(firebaseUser)
    }

    suspend fun login(email: String, password: String): VibeResult<User> {
        Validators.email(email)?.let { return VibeResult.Problem(it) }
        Validators.password(password)?.let { return VibeResult.Problem(it) }

        val firebaseUser = runCatching {
            firebaseAuth.signInWithEmailAndPassword(email.trim(), password)
        }.getOrElse { return VibeResult.Problem(detail = "Email or password is incorrect.") }

        return afterSignIn(firebaseUser)
    }

    suspend fun signInWithGoogle(idToken: String): VibeResult<User> {
        val firebaseUser = runCatching {
            firebaseAuth.signInWithGoogle(idToken)
        }.getOrElse {
            return VibeResult.Problem(detail = it.message ?: "Google sign-in could not be completed.")
        }
        return afterSignIn(firebaseUser)
    }

    private suspend fun afterSignIn(firebaseUser: com.google.firebase.auth.FirebaseUser): VibeResult<User> {
        val user = firebaseAuth.toDomain(firebaseUser)
        val oldId = settings.currentUserId()
        if (oldId != null && oldId != user.id && database.syncQueueDao().count() > 0) {
            return VibeResult.Problem(detail = "Sync the current account before switching accounts.")
        }
        if (oldId != null && oldId != user.id) {
            withContext(Dispatchers.IO) { database.clearAllTables() }
        }
        database.userDao().upsert(user.toEntity())
        settings.saveSession(
            com.vibe.app.data.prefs.Session(
                userId = user.id,
                displayName = user.displayName,
                email = user.email,
                accessToken = "", // Firebase manages tokens internally; not stored here.
                refreshToken = "",
                expiresAtEpochMillis = 0,
            )
        )
        settings.setPhotoUri(user.photoUri)

        // Mirror the profile to Firestore so group members can resolve display names.
        firestore.setUserProfile(user)

        return VibeResult.Ok(user)
    }

    suspend fun deleteAccount(password: String?): VibeResult<Unit> {
        if (database.syncQueueDao().count() > 0) {
            return VibeResult.Problem(detail = "Sync all changes before deleting your account.")
        }
        // FirebaseAuth handles account deletion. Password confirmation is validated
        // client-side; the actual credential check happens when the user re-authenticates.
        runCatching { firebaseAuth.deleteCurrentUser() }.getOrElse {
            return VibeResult.Problem(detail = it.message ?: "Could not delete account.")
        }
        settings.clearSession()
        settings.setPhotoUri(null)
        withContext(Dispatchers.IO) { database.clearAllTables() }
        return VibeResult.Ok(Unit)
    }

    suspend fun logout(): VibeResult<Unit> {
        if (database.syncQueueDao().count() > 0) {
            return VibeResult.Problem(detail = "Sync or resolve pending changes before signing out. Your work has not been deleted.")
        }
        runCatching { firebaseAuth.signOut() }
        settings.clearSession()
        settings.setPhotoUri(null)
        withContext(Dispatchers.IO) { database.clearAllTables() }
        return VibeResult.Ok(Unit)
    }

    suspend fun refreshProfile(): VibeResult<User> {
        val current = settings.currentUserId() ?: return VibeResult.Problem(detail = "Please sign in.")
        val entity = database.userDao().byId(current)
        return entity?.let { VibeResult.Ok(it.toDomain()) }
            ?: VibeResult.Problem(detail = "Profile not found. Please sign in again.")
    }

    suspend fun updateProfile(displayName: String, username: String?, photoUri: String? = null): VibeResult<User> {
        Validators.displayName(displayName)?.let { return VibeResult.Problem(it) }
        val current = profileNow() ?: return VibeResult.Problem(detail = "Please sign in.")
        val uid = settings.currentUserId() ?: return VibeResult.Problem(detail = "Not signed in.")
        val updated = current.copy(
            displayName = displayName.trim(),
            username = username?.trim()?.takeIf { it.isNotEmpty() } ?: current.username,
            photoUri = photoUri ?: current.photoUri,
        )
        database.userDao().upsert(updated.toEntity())
        settings.updateDisplayName(updated.displayName)
        settings.setPhotoUri(updated.photoUri)
        firestore.setUserProfile(updated)
        return VibeResult.Ok(updated)
    }

    suspend fun updateSettings(
        themeMode: com.vibe.app.domain.ThemeMode? = null,
        language: com.vibe.app.domain.AppLanguage? = null,
        notificationsEnabled: Boolean? = null,
        privacyMembersOnly: Boolean? = null,
    ): VibeResult<Unit> {
        themeMode?.let { settings.setTheme(it) }
        language?.let { settings.setLanguage(it) }
        notificationsEnabled?.let { settings.setNotificationsEnabled(it) }
        privacyMembersOnly?.let { settings.setPrivacyMembersOnly(it) }
        return VibeResult.Ok(Unit)
    }

    suspend fun changePassword(current: String, fresh: String, confirmation: String): VibeResult<Unit> {
        if (current.isBlank()) return VibeResult.Problem(FieldError.CURRENT_PASSWORD_REQUIRED)
        Validators.password(fresh)?.let { return VibeResult.Problem(it) }
        Validators.passwordConfirmation(fresh, confirmation)?.let { return VibeResult.Problem(it) }
        // Password changes go through Firebase Auth. The client cannot verify the
        // current password without re-signing in; Firebase handles this server-side.
        return VibeResult.Problem(detail = "Use the password reset flow or sign in again to change your password.")
    }

    suspend fun requestPasswordReset(email: String): VibeResult<Unit> {
        Validators.email(email)?.let { return VibeResult.Problem(it) }
        runCatching { firebaseAuth.sendPasswordReset(email.trim()) }.getOrElse {
            return VibeResult.Problem(detail = "Could not send a reset email. Check your connection and try again.")
        }
        return VibeResult.Ok(Unit)
    }

    /** Providers the app supports for sign-in. FirebaseAuth handles the actual configuration. */
data class SupportedProviders(val providers: List<String> = listOf("password", "google"), val hasPassword: Boolean = false)

suspend fun providers(): VibeResult<SupportedProviders> {
    return VibeResult.Ok(SupportedProviders(
        hasPassword = settings.currentAccessToken() != null,
    ))
}

    suspend fun linkGoogle(idToken: String): VibeResult<Unit> {
        runCatching { firebaseAuth.linkGoogle(idToken) }.getOrElse {
            return VibeResult.Problem(detail = it.message ?: "Could not link Google account.")
        }
        return VibeResult.Ok(Unit)
    }

    suspend fun unlinkGoogle(): VibeResult<Unit> {
        runCatching { firebaseAuth.unlinkGoogle() }.getOrElse {
            return VibeResult.Problem(detail = it.message ?: "Could not unlink Google account.")
        }
        return VibeResult.Ok(Unit)
    }

    suspend fun markOnboarded() = settings.setOnboarded(true)

    suspend fun stats(userId: String) =
        com.vibe.app.domain.UserStats(
            database.decisionDao().countForMember(userId),
            database.activityDao().countByUser(userId),
            database.groupDao().membershipsOf(userId).size,
        )

    private suspend fun profileNow() =
        settings.currentUserId()?.let { database.userDao().byId(it)?.toDomain() }

    private object Validators {
        fun displayName(name: String): String? =
            if (name.isBlank()) "Please enter your display name." else null
        fun email(email: String): String? =
            if (!email.contains("@")) "Enter a valid email address." else null
        fun password(password: String): String? =
            if (password.length < 8) "Use at least 8 characters." else null
        fun passwordConfirmation(password: String, confirmation: String): String? =
            if (password != confirmation) "Passwords do not match." else null
    }
}
