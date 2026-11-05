package com.vibe.app.identity

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.vibe.app.BuildConfig

/**
 * PoE: Google Sign-In as the SSO route.
 *
 * This uses Credential Manager (the current Google-recommended API, replacing
 * the deprecated GoogleSignInClient). The ID token is handed straight to the API,
 * which is the only component allowed to trust it: the API verifies signature,
 * `aud`, `iss` and `exp` before issuing a VIBE session, and the client never
 * treats the token as proof of identity on its own.
 *
 * `GOOGLE_SERVER_CLIENT_ID` comes from the build config; when it is blank the
 * button stays visible but explains what is missing instead of failing silently.
 */
class GoogleSignInGateway(
    private val context: Context,
    private val serverClientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
) {

    val isConfigured: Boolean get() = serverClientId.isNotBlank()

    suspend fun requestIdToken(activityContext: Context = context): Result<String> = runCatching {
        check(isConfigured) { "GOOGLE_SERVER_CLIENT_ID is not configured" }

        val credentialManager = CredentialManager.create(activityContext)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
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
