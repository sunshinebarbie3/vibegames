package com.vibe.app.core

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class SessionAuthenticator(private val gate: SessionRefreshGate) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath.contains("/auth/")) return null
        var prior = response.priorResponse
        var count = 0
        while (prior != null) { count++; prior = prior.priorResponse }
        if (count >= 2) return null
        val failed = response.request.header("Authorization")?.removePrefix("Bearer ")
        val token = runBlocking { gate.refresh(failed) } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $token").build()
    }
}
