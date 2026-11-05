package com.vibe.app.identity

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.vibe.app.BuildConfig
import com.vibe.app.data.repository.NotificationRepository
import kotlinx.coroutines.tasks.await

class PushGateway(context: Context, private val notifications: NotificationRepository) {
    val configured = BuildConfig.FCM_ENABLED && FirebaseApp.getApps(context).isNotEmpty()
    suspend fun registerCurrentToken() {
        if (!configured) return
        runCatching { notifications.registerDeviceToken(FirebaseMessaging.getInstance().token.await()) }
    }
}
