package com.vibe.app.identity

/** Setup problems that can be identified locally before opening the Google account picker. */
enum class GoogleSignInSetupIssue {
    WEB_CLIENT_ID_MISSING,
}

/** Returns a user-actionable setup issue, or null when the local settings look usable. */
fun googleSignInSetupIssue(serverClientId: String): GoogleSignInSetupIssue? {
    if (serverClientId.isBlank()) return GoogleSignInSetupIssue.WEB_CLIENT_ID_MISSING
    return null
}
