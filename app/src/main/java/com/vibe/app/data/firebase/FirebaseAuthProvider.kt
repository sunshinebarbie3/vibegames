package com.vibe.app.data.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.auth.GoogleAuthProvider
import com.vibe.app.BuildConfig
import com.vibe.app.domain.AppLanguage
import com.vibe.app.domain.ThemeMode
import com.vibe.app.domain.User
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Firebase Authentication provider.
 *
 * Replaces the custom /api/v1/auth/* endpoints on the Node.js server.
 * All sign-in (email/password and Google) goes through FirebaseAuth directly.
 * The ID token is available to callers that need it (e.g. Firestore security rules
 * and any client-side validation), but the Android app authenticates via the
 * Firebase SDK rather than forwarding tokens to a custom backend.
 */
class FirebaseAuthProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val uid: String?
        get() = auth.currentUser?.uid

    /** Returns the Firebase ID token for the current user, refreshing if expired. */
    suspend fun getIdToken(forceRefresh: Boolean = false): String? =
        withContext(io) {
            auth.currentUser?.let { user ->
                user.getIdToken(forceRefresh).await()
            }
        }

    /** Sign in with email and password. */
    suspend fun signInWithEmailAndPassword(email: String, password: String): FirebaseUser =
        withContext(io) {
            auth.signInWithEmailAndPassword(email.trim(), password).await().user
                ?: error("No user returned by Firebase Authentication.")
        }

    /** Create an account with email and password. */
    suspend fun createUserWithEmailAndPassword(email: String, password: String, displayName: String): FirebaseUser {
        val user = withContext(io) {
            auth.createUserWithEmailAndPassword(email.trim(), password).await().user
                ?: error("No user returned by Firebase Authentication.")
        }
        // Update the display name on the Firebase Auth profile.
        user.updateProfile(displayName.trim()).await()
        return user
    }

    /** Send a password-reset email. */
    suspend fun sendPasswordReset(email: String) =
        withContext(io) {
            auth.sendPasswordResetEmail(email.trim()).await()
        }

    /** Sign out. */
    suspend fun signOut() = withContext(io) { auth.signOut() }

    /** Sign in with a Google ID token obtained from Credential Manager. */
    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        return withContext(io) {
            auth.signInWithCredential(credential).await().user
                ?: error("No user returned by Firebase Authentication for Google sign-in.")
        }
    }

    /** Link a Google credential to the current anonymous/email account. */
    suspend fun linkGoogle(idToken: String) {
        val user = auth.currentUser ?: error("No signed-in user.")
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        withContext(io) { user.linkWithCredential(credential).await() }
    }

    /** Unlink Google from the current account. */
    suspend fun unlinkGoogle() {
        val user = auth.currentUser ?: error("No signed-in user.")
        val providers = user.providerData.map { it.providerId }
        if (!providers.contains("google.com")) return
        // Require at least one other provider (e.g. password) before unlinking.
        if (providers.size == 1) error("Set a password before unlinking Google.")
        withContext(io) { user.unlink(providers.first { it != "google.com" }).await() }
    }

    /** Delete the current user's Firebase Auth account. */
    suspend fun deleteCurrentUser() {
        val user = auth.currentUser ?: error("No signed-in user.")
        withContext(io) { user.delete().await() }
    }

    /** Convert a FirebaseUser to the app's domain User. Local settings (theme,
     * language, etc.) are still stored in DataStore/Room, not Firebase Auth. */
    fun toDomain(user: FirebaseUser): User {
        val name = user.displayName.takeIf { it.isNotBlank() }.orEmpty()
        val email = user.email.orEmpty()
        return User(
            id = user.uid,
            displayName = name.takeIf { it.isNotEmpty() }.orElse(email.split("@").firstOrNull() ?: "Member"),
            username = name.takeIf { it.isNotEmpty() }.orElse(email.split("@").firstOrNull() ?: "member"),
            email = email,
            language = AppLanguage.ENGLISH,
            themeMode = ThemeMode.DARK,
            photoUri = user.photoUrl?.toString(),
            createdAt = user.metadata.creationTimestamp,
        )
    }

    companion object {
        private const val TAG = "FirebaseAuthProvider"
    }
}
