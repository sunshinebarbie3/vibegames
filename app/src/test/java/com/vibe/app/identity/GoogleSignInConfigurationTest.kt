package com.vibe.app.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleSignInConfigurationTest {
    @Test
    fun missingWebClientIdIsReported() {
        assertEquals(
            GoogleSignInSetupIssue.WEB_CLIENT_ID_MISSING,
            googleSignInSetupIssue("  "),
        )
    }

    @Test
    fun configuredWebClientIdHasNoIssue() {
        assertNull(googleSignInSetupIssue("web-client-id"))
    }
}
