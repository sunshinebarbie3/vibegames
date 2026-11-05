package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.firebase.FirestoreProvider
import com.vibe.app.data.local.NotificationEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.domain.Ids
import com.vibe.app.domain.NotificationType
import com.vibe.app.domain.VibeNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Alerts.
 *
 * Notifications are stored in Firestore and cached in Room. The local inbox
 * keeps alerts readable offline, localised on the device by the messaging
 * service.
 */
class NotificationRepository(
    private val firestore: FirestoreProvider,
    private val database: VibeDatabase,
    private val settings: SettingsStore,
    private val time: TimeProvider,
) {

    val notifications: Flow<List<VibeNotification>> =
        database.notificationDao().observeAll()
            .map { rows -> rows.filterNot { it.read }.map { it.toDomain() } }

    val unreadCount: Flow<Int> = database.notificationDao().observeUnreadCount()

    suspend fun refresh(): VibeResult<Int> {
        val uid = settings.currentUserId() ?: return VibeResult.Ok(0)
        return runCatching {
            val serverNotifications = firestore.getNotificationsForUser(uid)
            withContext(Dispatchers.IO) {
                serverNotifications.forEach { dto ->
                    val dao = database.notificationDao()
                    val existing = dao.byId(dto.id)
                    dao.upsert(dto.toEntity().copy(read = dto.read || existing?.read == true))
                }
            }
            VibeResult.Ok(serverNotifications.size)
        }.getOrElse { VibeResult.Ok(0) }
    }

    suspend fun markRead(notificationId: String) {
        database.notificationDao().markRead(notificationId)
        runCatching { firestore.markNotificationRead(notificationId, true) }
    }

    suspend fun markAllRead() {
        database.notificationDao().markAllRead()
        runCatching { Unit }
    }

    /** Called after Firebase hands us a (possibly rotated) registration token. */
    suspend fun registerDeviceToken(token: String) {
        settings.setFcmToken(token)
        val uid = settings.currentUserId() ?: return
        runCatching { firestore.registerDevice(token, token, uid) }
    }

    /**
     * Mirrors a push payload into the local inbox. The messaging service calls
     * this so an alert is readable offline and is localised on the device.
     */
    suspend fun mirrorPush(
        title: String,
        body: String,
        type: NotificationType,
        groupId: String? = null,
        notificationId: String? = null,
    ) {
        val userId = settings.currentUserId() ?: return
        if (!settings.settings.first().notificationsEnabled) return
        database.notificationDao().upsert(
            NotificationEntity(
                id = notificationId ?: Ids.newId(),
                userId = userId,
                title = title,
                body = body,
                type = type.name,
                read = false,
                createdAt = time.nowMillis(),
                groupId = groupId,
            ),
        )
    }
}
