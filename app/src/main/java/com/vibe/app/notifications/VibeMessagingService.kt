package com.vibe.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vibe.app.MainActivity
import com.vibe.app.R
import com.vibe.app.VibeApplication
import com.vibe.app.domain.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * PoE: real-time prompts through Firebase Cloud Messaging.
 *
 * Invitations, new activity options, vote deadlines and winners arrive here. The
 * title and body are built from resources, so an alert is delivered in the
 * language the member picked (English, isiZulu or Sesotho), and the same payload
 * is mirrored into the local inbox so it can be read offline.
 */
class VibeMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val container = (application as? VibeApplication)?.container ?: return
        scope.launch { container.notificationRepository.registerDeviceToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val container = (application as? VibeApplication)?.container ?: return
        val type = runCatching {
            NotificationType.valueOf(message.data["type"].orEmpty().uppercase())
        }.getOrDefault(NotificationType.NEW_ACTIVITY)

        val groupId = message.data["groupId"]
        val title = localisedTitle(type)
        val body = localisedBody(type)

        runBlocking(Dispatchers.IO) {
            if (!container.settings.settings.first().notificationsEnabled) return@runBlocking
            container.notificationRepository.mirrorPush(title, body, type, groupId, message.data["id"])
            showNotification(title, body)
        }
    }

    /** Alerts follow the chosen language: the copy is resolved from resources. */
    private fun localisedTitle(type: NotificationType): String = when (type) {
        NotificationType.INVITE -> getString(R.string.notification_invite_title)
        NotificationType.NEW_ACTIVITY -> getString(R.string.notification_activity_title)
        NotificationType.DEADLINE -> getString(R.string.notification_deadline_title)
        NotificationType.WINNER -> getString(R.string.notification_winner_title)
        NotificationType.MEMORY -> getString(R.string.notification_memory_title)
    }

    private fun localisedBody(type: NotificationType): String = when (type) {
        NotificationType.INVITE -> getString(R.string.notification_invite_body)
        NotificationType.NEW_ACTIVITY -> getString(R.string.notification_activity_body)
        NotificationType.DEADLINE -> getString(R.string.notification_deadline_body)
        NotificationType.WINNER -> getString(R.string.notification_winner_body)
        NotificationType.MEMORY -> getString(R.string.notification_memory_body)
    }

    private fun showNotification(title: String, body: String) {
        ensureChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The member has not granted notifications; the inbox copy still landed.
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_INBOX, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_PLANS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        const val CHANNEL_PLANS = "vibe_plans"
        const val EXTRA_OPEN_INBOX = "open_inbox"

        /** Called from the application and from the service before posting. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_PLANS) != null) return
            val channel = NotificationChannel(
                CHANNEL_PLANS,
                context.getString(R.string.notification_channel_plans),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_plans_detail)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
