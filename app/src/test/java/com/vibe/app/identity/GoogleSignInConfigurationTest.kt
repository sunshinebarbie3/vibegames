package com.vibe.app.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleSignInConfigurationTest {
    @Test
    fun missingWebClientIdIsReportedBeforeTheApiUrl() {
        assertEquals(
            GoogleSignInSetupIssue.WEB_CLIENT_ID_MISSING,
            googleSignInSetupIssue("  ", "https://api.example.com/"),
        )
    }

    @Test
    fun rejectsInsecureAndPlaceholderApiUrls() {
        assertEquals(
            GoogleSignInSetupIssue.API_URL_NOT_HTTPS,
            googleSignInSetupIssue("web-client-id", "http://api.example.com/"),
        )
        assertEquals(
            GoogleSignInSetupIssue.API_URL_IS_PLACEHOLDER,
            googleSignInSetupIssue("web-client-id", "https://api.vibeapp.com/"),
        )
    }

    @Test
    fun requiresTrailingSlashAndAcceptsConfiguredHttpsApi() {
        assertEquals(
            GoogleSignInSetupIssue.API_URL_MISSING_TRAILING_SLASH,
            googleSignInSetupIssue("web-client-id", "https://api.example.com"),
        )
        assertNull(googleSignInSetupIssue("web-client-id", "https://api.example.com/"))
    }
}
