package com.vibe.app.identity

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
/**
 * Google Sign-In as the SSO route.
 *
 * This uses Credential Manager (the current Google-recommended API, replacing
 * the deprecated GoogleSignInClient). The ID token is exchanged directly with
 * Firebase Authentication (signInWithCredential), not with a custom backend.
 *
 * `GOOGLE_SERVER_CLIENT_ID` comes from the build config; when it is blank the
 * button stays visible but explains what is missing instead of failing silently.
 */
class GoogleSignInGateway(
    private val context: Context,
    private val serverClientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
) {

    private fun resolvedServerClientId(): String {
        if (serverClientId.isNotBlank()) return serverClientId.trim()
        val resourceId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName,
        )
        return if (resourceId == 0) "" else context.getString(resourceId).trim()
    }

    val isConfigured: Boolean get() = resolvedServerClientId().isNotBlank()

    fun setupIssue(): GoogleSignInSetupIssue? {
        if (resolvedServerClientId().isBlank()) return GoogleSignInSetupIssue.WEB_CLIENT_ID_MISSING
        return null
    }

    suspend fun requestIdToken(activityContext: Context = context): Result<String> = runCatching {
        val webClientId = resolvedServerClientId()
        check(webClientId.isNotBlank()) { "Google Web client ID is not configured" }

        val credentialManager = CredentialManager.create(activityContext)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val response = credentialManager.getCredential(context = activityContext, request = request)
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else {
            throw IllegalStateException("Unsupported credential type: ${credential.type}")
        }
    }
}
