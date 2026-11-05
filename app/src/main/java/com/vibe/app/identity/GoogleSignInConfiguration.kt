package com.vibe.app.identity

/** Setup problems that can be identified locally before opening the Google account picker. */
enum class GoogleSignInSetupIssue {
    WEB_CLIENT_ID_MISSING,
    API_URL_NOT_HTTPS,
    API_URL_IS_PLACEHOLDER,
    API_URL_MISSING_TRAILING_SLASH,
}

/** Returns a user-actionable setup issue, or null when the local settings look usable. */
fun googleSignInSetupIssue(serverClientId: String, apiBaseUrl: String): GoogleSignInSetupIssue? {
    if (serverClientId.isBlank()) return GoogleSignInSetupIssue.WEB_CLIENT_ID_MISSING

    val url = apiBaseUrl.trim()
    if (!url.startsWith("https://", ignoreCase = true)) {
        return GoogleSignInSetupIssue.API_URL_NOT_HTTPS
    }
    if (url.trimEnd('/').equals("https://api.vibeapp.com", ignoreCase = true)) {
        return GoogleSignInSetupIssue.API_URL_IS_PLACEHOLDER
    }
    if (!url.endsWith('/')) return GoogleSignInSetupIssue.API_URL_MISSING_TRAILING_SLASH
    return null
}
