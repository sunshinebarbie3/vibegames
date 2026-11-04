package com.vibe.app.data.repository

import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.core.toProblem
import com.vibe.app.data.local.NotificationEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.local.toEntity
import com.vibe.app.data.prefs.SettingsStore
import com.vibe.app.data.remote.ApiResult
import com.vibe.app.data.remote.RegisterDeviceRequest
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.data.remote.apiResult
import com.vibe.app.data.remote.toDomain
import com.vibe.app.domain.Ids
import com.vibe.app.domain.NotificationType
import com.vibe.app.domain.VibeNotification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Alerts.
 *
 * PoE requirement: invitations, new options, vote deadlines and winners arrive
 * through Firebase Cloud Messaging, and the same payload is mirrored into the
 * local inbox so the member can still read it with no connection (and in the
 * language they chose, because [VibeMessagingService] formats the copy from
 * resources).
 */
class NotificationRepository(
    private val api: VibeApi,
    private val database: VibeDatabase,
    private val settings: SettingsStore,
    private val time: TimeProvider,
) {

    val notifications: Flow<List<VibeNotification>> =
        database.notificationDao().observeAll()
            .map { rows -> rows.filterNot { it.read }.map { it.toDomain() } }

    val unreadCount: Flow<Int> = database.notificationDao().observeUnreadCount()

    suspend fun refresh(): VibeResult<Int> =
        when (val result = apiResult { api.notifications() }) {
            is ApiResult.Success -> {
                result.data.forEach { dto ->
                    val dao = database.notificationDao()
                    val existing = dao.byId(dto.id)
                    dao.upsert(dto.toDomain().toEntity().copy(read = dto.read || existing?.read == true))
                }
                VibeResult.Ok(result.data.size)
            }

            is ApiResult.Failure -> result.toProblem()
        }

    suspend fun markRead(notificationId: String) {
        database.notificationDao().markRead(notificationId)
        apiResult { api.markNotificationRead(notificationId) }
    }

    suspend fun markAllRead() {
        database.notificationDao().markAllRead()
        apiResult { api.markAllNotificationsRead() }
    }

    /** Called after Firebase hands us a (possibly rotated) registration token. */
    suspend fun registerDeviceToken(token: String) {
        settings.setFcmToken(token)
        apiResult { api.registerDevice(RegisterDeviceRequest(fcmToken = token)) }
    }

    /**
     * Mirrors a push payload into the local inbox. The messaging service calls
     * this so an alert is readable offline and is localised on the device.
     */
    suspend fun mirrorPush(title: String, body: String, type: NotificationType, groupId: String? = null) {
        val userId = settings.currentUserId() ?: return
        // Respect the privacy/alert toggle: nothing is stored if the member
        // switched notifications off.
        if (!settings.settings.first().notificationsEnabled) return
        database.notificationDao().upsert(
            NotificationEntity(
                id = Ids.newId(),
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
