package com.vibe.app.identity

import android.content.Context
import android.content.MutableContextWrapper
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.vibe.app.BuildConfig
import kotlinx.coroutines.tasks.await

data class GoogleFirebaseIdentity(
    val googleIdToken: String,
    val googleAccountId: String,
    val firebaseUid: String,
    val displayName: String,
    val email: String,
    val photoUrl: String?,
)

/**
 * Uses Credential Manager for Google account selection and Firebase Auth to
 * validate/sign in the returned Google credential.
 */
class GoogleSignInGateway(private val appContext: Context) {

    suspend fun signIn(activityContext: Context): Result<GoogleFirebaseIdentity> = runCatching {
        val firebaseApp = FirebaseApp.initializeApp(appContext)
            ?: error("Firebase is not configured. Add app/google-services.json and enable Google sign-in.")

        val webClientId = appContext.resources.getIdentifier(
            "default_web_client_id",
            "string",
            appContext.packageName,
        ).takeIf { it != 0 }?.let(appContext::getString)
            ?.takeIf(String::isNotBlank)
            ?: BuildConfig.GOOGLE_SERVER_CLIENT_ID.takeIf(String::isNotBlank)
            ?: error("The Google web client ID is missing from Firebase configuration.")

        val credentialManager = CredentialManager.create(activityContext)
        val googleIdOption = GetGoogleIdOption.Builder()
            // Show all device accounts so the user can choose an existing account.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        // Credential Manager needs the foreground activity context for its UI.
        val contextWrapper = MutableContextWrapper(activityContext)
        val response = credentialManager.getCredential(context = contextWrapper, request = request)
        val credential = response.credential
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Google did not return an ID token credential." }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        val authResult = FirebaseAuth.getInstance(firebaseApp)
            .signInWithCredential(firebaseCredential)
            .await()
        val firebaseUser = requireNotNull(authResult.user) { "Firebase returned no signed-in user." }

        GoogleFirebaseIdentity(
            googleIdToken = googleCredential.idToken,
            googleAccountId = googleCredential.uniqueId,
            firebaseUid = firebaseUser.uid,
            displayName = firebaseUser.displayName
                ?.takeIf(String::isNotBlank)
                ?: googleCredential.displayName.orEmpty(),
            email = firebaseUser.email
                ?.takeIf(String::isNotBlank)
                ?: googleCredential.id,
            photoUrl = firebaseUser.photoUrl?.toString()
                ?: googleCredential.profilePictureUri?.toString(),
        )
    }

    suspend fun signOut() {
        FirebaseApp.getApps(appContext).firstOrNull()?.let { app ->
            FirebaseAuth.getInstance(app).signOut()
        }
        CredentialManager.create(appContext)
            .clearCredentialState(ClearCredentialStateRequest())
    }
}
