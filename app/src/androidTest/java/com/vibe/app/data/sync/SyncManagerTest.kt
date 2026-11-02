import okhttp3.ResponseBody.Companion.toResponseBody
package com.vibe.app.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vibe.app.core.ConnectivityObserver
import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.remote.*
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.SyncState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SyncManagerTest {
    private lateinit var db: VibeDatabase
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private var clock = 1_700_000_000_000L
    private val time = TimeProvider { clock }
    private val jobs = SupervisorJob()
    private val scope = CoroutineScope(jobs + Dispatchers.IO)
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,VibeDatabase::class.java).build() }
    @After fun teardown() { jobs.cancel(); db.close() }
    private class Connectivity(initial: Boolean) : ConnectivityObserver {
        private val state = MutableStateFlow(initial)
        override val isOnline: StateFlow<Boolean> = state
        fun set(value: Boolean) { state.value = value }
    }
    private class FakeApi : VibeApi {
        var conflict = false
        var networkFailure = false
        var batches = 0
        val received = mutableListOf<SyncItemDto>()
        override suspend fun syncBatch(body: SyncBatchRequest): SyncBatchResponse {
            if (networkFailure) throw IOException("offline")
            batches++; received += body.items
            return SyncBatchResponse(body.items.map { SyncResultDto(it.clientId,if (conflict) "conflict" else "applied",if (conflict) """{"title":"Server title"}""" else null) })
        }
        override suspend fun register(body: RegisterRequest): AuthResponse = error("Not used")
        override suspend fun login(body: LoginRequest): AuthResponse = error("Not used")
        override suspend fun googleSignIn(body: GoogleAuthRequest): AuthResponse = error("Not used")
        override suspend fun refresh(body: RefreshRequest): AuthResponse = error("Not used")
        override suspend fun logout(): retrofit2.Response<Unit> = error("Not used")
        override suspend fun requestPasswordReset(body: PasswordResetRequest): retrofit2.Response<Unit> = error("Not used")
        override suspend fun deleteAccount(body: DeleteAccountRequest): retrofit2.Response<Unit> = error("Not used")
        override suspend fun me(): UserDto = error("Not used")
        override suspend fun updateProfile(body: UpdateProfileRequest): UserDto = error("Not used")
        override suspend fun changePassword(body: ChangePasswordRequest): AuthResponse = error("Not used")
        override suspend fun providers(): ProvidersDto = error("Not used")
        override suspend fun linkGoogle(body: GoogleAuthRequest): UserDto = error("Not used")
        override suspend fun unlinkGoogle(): retrofit2.Response<Unit> = error("Not used")
        override suspend fun unregisterDevice(body: RegisterDeviceRequest): retrofit2.Response<Unit> = error("Not used")
        override suspend fun uploadPhoto(photo: okhttp3.MultipartBody.Part, groupId: okhttp3.RequestBody?): MediaDto = error("Not used")
        override suspend fun groups(): List<GroupDto> = error("Not used")
        override suspend fun group(groupId: String): GroupDto = error("Not used")
        override suspend fun createGroup(body: CreateGroupRequest): GroupDto = error("Not used")
        override suspend fun deleteGroup(id: String): retrofit2.Response<Unit> = error("Not used")
        override suspend fun joinGroup(body: JoinGroupRequest): GroupDto = error("Not used")
        override suspend fun leaveGroup(groupId: String): retrofit2.Response<Unit> = error("Not used")
        override suspend fun activities(groupId: String): List<ActivityDto> = error("Not used")
        override suspend fun myActivities(): List<ActivityDto> = error("Not used")
        override suspend fun addActivity(groupId: String,body: UpsertActivityRequest): ActivityDto = error("Not used")
        override suspend fun updateActivity(activityId: String,body: UpsertActivityRequest): ActivityDto = error("Not used")
        override suspend fun deleteActivity(activityId: String): retrofit2.Response<Unit> = error("Not used")
        override suspend fun startDecision(groupId: String,body: StartDecisionRequest): DecisionDto = error("Not used")
        override suspend fun decision(decisionId: String): DecisionDto = error("Not used")
        override suspend fun castVote(decisionId: String,body: CastVoteRequest): VoteAckDto = error("Not used")
        override suspend fun closeDecision(decisionId: String): DecisionDto = error("Not used")
        override suspend fun surprise(decisionId: String,body: SurpriseRequest): ActivityDto = error("Not used")
        override suspend fun plans(groupId: String): List<PlanDto> = error("Not used")
        override suspend fun savePlan(body: SavePlanRequest): PlanDto = error("Not used")
        override suspend fun completePlan(planId: String,body: CompletePlanRequest): MemoryDto = error("Not used")
        override suspend fun updateMemory(id: String,body: CompletePlanRequest): MemoryDto = error("Not used")
        override suspend fun memories(groupId: String): List<MemoryDto> = error("Not used")
        override suspend fun notifications(): List<NotificationDto> = error("Not used")
        override suspend fun markNotificationRead(notificationId: String): retrofit2.Response<Unit> = error("Not used")
        override suspend fun markAllNotificationsRead(): retrofit2.Response<Unit> = error("Not used")
        override suspend fun registerDevice(body: RegisterDeviceRequest): retrofit2.Response<Unit> = error("Not used")
    }
    private suspend fun enqueue(queue: SyncQueue) = queue.enqueue(SyncAction.UPDATE_ACTIVITY,"idea-1",UpsertActivityRequest("Bowling",clientId="idea-1",groupId="group-1"),UpsertActivityRequest::class.java)
    private fun manager(api: VibeApi, online: Connectivity) = SyncManager(api,db,online,scope,moshi=moshi)
    @Test fun offlineChangeKeepsTimestampAndReconnectDrains() = runTest {
        val queue=SyncQueue(db,moshi,time); val api=FakeApi(); val online=Connectivity(false); val manager=manager(api,online)
        val item=enqueue(queue); assertEquals(clock,item.queuedAt)
        assertTrue(manager.syncNow() is VibeResult.Problem); assertEquals(0,api.batches)
        online.set(true); assertTrue(manager.syncNow() is VibeResult.Ok)
        assertEquals(0,db.syncQueueDao().count()); assertEquals(item.id,api.received.single().clientId)
    }
    @Test fun conflictIsPreservedAndBothResolutionsWork() = runTest {
        val queue=SyncQueue(db,moshi,time); val api=FakeApi(); api.conflict=true; val manager=manager(api,Connectivity(true))
        val first=enqueue(queue); manager.syncNow()
        assertEquals(SyncState.CONFLICT.name,db.syncQueueDao().byId(first.id)?.state)
        manager.resolveKeepingServer(db.syncQueueDao().byId(first.id)!!.toDomain()); assertNull(db.syncQueueDao().byId(first.id))
        clock+=1000; val second=enqueue(queue); manager.syncNow(); api.conflict=false
        manager.resolveKeepingLocal(db.syncQueueDao().byId(second.id)!!.toDomain())
        assertNull(db.syncQueueDao().byId(second.id)); assertTrue(api.received.last().payloadJson.contains("force"))
    }
    @Test fun failedTransportDoesNotDiscardQueue() = runTest {
        val queue=SyncQueue(db,moshi,time); val api=FakeApi(); api.networkFailure=true
        val item=enqueue(queue); assertTrue(manager(api,Connectivity(true)).syncNow() is VibeResult.Problem)
        assertEquals(SyncState.PENDING.name,db.syncQueueDao().byId(item.id)?.state)
    }
    @Test fun immediateSuccessAcknowledgesItsOwnQueueItem() = runTest {
        val queue=SyncQueue(db,moshi,time); val item=enqueue(queue)
        val result=onlineMutation(queue,item) { true }
        assertTrue(result is ApiResult.Success); assertEquals(0,db.syncQueueDao().count())
    }
    @Test fun validationFailureIsNotMisreportedAsOfflineSuccess() = runTest {
        val queue=SyncQueue(db,moshi,time); val item=enqueue(queue)
        val result=onlineMutation(queue,item) { throw retrofit2.HttpException(retrofit2.Response.error<Any>(422,"{}".toResponseBody())) }
        assertEquals(ApiErrorKind.VALIDATION,(result as ApiResult.Failure).kind)
        assertEquals(SyncState.FAILED.name,db.syncQueueDao().byId(item.id)?.state)
    }
}
