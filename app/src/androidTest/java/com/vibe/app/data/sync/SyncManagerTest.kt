package com.vibe.app.data.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vibe.app.core.ConnectivityObserver
import com.vibe.app.core.TimeProvider
import com.vibe.app.core.VibeResult
import com.vibe.app.data.local.SyncItemEntity
import com.vibe.app.data.local.VibeDatabase
import com.vibe.app.data.local.toDomain
import com.vibe.app.data.remote.DemoVibeApi
import com.vibe.app.data.remote.SyncBatchRequest
import com.vibe.app.data.remote.SyncBatchResponse
import com.vibe.app.data.remote.SyncResultDto
import com.vibe.app.data.remote.UpsertActivityRequest
import com.vibe.app.data.remote.VibeApi
import com.vibe.app.domain.SyncAction
import com.vibe.app.domain.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline queue, against a real RoomDB (in memory) and the demo backend.
 *
 * Two PoE requirements are checked here: an offline change is queued with the
 * time the member acted and drains when the API is reachable again, and a
 * conflict is **reported, never silently overwritten**.
 */
@RunWith(AndroidJUnit4::class)
class SyncManagerTest {

    private lateinit var database: VibeDatabase
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private var clock = 1_700_000_000_000L
    private val time = TimeProvider { clock }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            VibeDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun demoApi(): VibeApi = DemoVibeApi(
        db = database,
        time = time,
        scope = scope,
        currentUserId = { DemoVibeApi.DEMO_USER_ID },
        simulatedVoteDelayMillis = 0L,
    )

    /** Answers `conflict` while [conflict] is true, like a server whose row moved on. */
    private class ToggleApi(
        private val delegate: VibeApi,
        @Volatile var conflict: Boolean,
    ) : VibeApi by delegate {
        override suspend fun syncBatch(body: SyncBatchRequest): SyncBatchResponse =
            if (!conflict) {
                delegate.syncBatch(body)
            } else {
                SyncBatchResponse(
                    results = body.items.map {
                        SyncResultDto(
                            clientId = it.clientId,
                            status = "conflict",
                            serverPayload = "{\"title\":\"Bowling\"}",
                            message = "changed on the server",
                        )
                    },
                )
            }
    }

    private class FakeConnectivity(initial: Boolean) : ConnectivityObserver {
        private val state = MutableStateFlow(initial)
        override val isOnline: StateFlow<Boolean> = state
        fun set(online: Boolean) {
            state.value = online
        }
    }

    private suspend fun enqueueActivity(queue: SyncQueue, title: String = "Bowling night"): SyncItemEntity {
        // A minute apart, so two queued changes never share a client id.
        clock += 60_000L
        return queue.enqueue(
            action = SyncAction.ADD_ACTIVITY,
            entityId = "act-$clock",
            payload = UpsertActivityRequest(title = title, clientId = "act-$clock"),
            type = UpsertActivityRequest::class.java,
        )
    }

    @Test
    fun an_offline_change_is_queued_with_the_time_it_happened_and_drains_when_the_api_answers() = runTest {
        val queue = SyncQueue(database, moshi, time)
        val manager = SyncManager(demoApi(), database, FakeConnectivity(false), scope)

        val item = enqueueActivity(queue)

        assertEquals(clock, item.queuedAt)
        assertEquals(1, database.syncQueueDao().pending().size)
        assertEquals(1, database.syncQueueDao().observePendingCount().first())

        val result = manager.syncNow()

        assertTrue(result is VibeResult.Ok)
        assertTrue(database.syncQueueDao().pending().isEmpty())
    }

    @Test
    fun a_conflict_is_reported_and_kept_not_overwritten() = runTest {
        val queue = SyncQueue(database, moshi, time)
        val manager = SyncManager(ToggleApi(demoApi(), conflict = true), database, FakeConnectivity(false), scope)

        val item = enqueueActivity(queue)
        manager.syncNow()

        val stored = database.syncQueueDao().byId(item.id)
        assertNotNull(stored)
        assertEquals(SyncState.CONFLICT.name, stored!!.state)
        assertEquals("{\"title\":\"Bowling\"}", stored.serverPayloadJson)
        // No longer retried blindly, and the member can see what the server has.
        assertTrue(database.syncQueueDao().pending().isEmpty())
        assertEquals(item.id, database.syncQueueDao().observeConflicts().first().single().id)
    }

    @Test
    fun a_conflict_can_be_resolved_by_keeping_theirs_or_by_keeping_mine() = runTest {
        val queue = SyncQueue(database, moshi, time)
        val api = ToggleApi(demoApi(), conflict = true)
        val manager = SyncManager(api, database, FakeConnectivity(false), scope)

        // "Keep theirs": the queued change is dropped and the server copy wins.
        val theirs = enqueueActivity(queue)
        manager.syncNow()
        assertEquals(SyncState.CONFLICT.name, database.syncQueueDao().byId(theirs.id)!!.state)
        manager.resolveKeepingServer(database.syncQueueDao().byId(theirs.id)!!.toDomain())
        assertNull(database.syncQueueDao().byId(theirs.id))

        // "Keep mine": the change goes back to the front of the queue and the
        // next drain applies it once the server agrees again.
        val mine = enqueueActivity(queue, title = "Movie marathon")
        manager.syncNow()
        assertEquals(SyncState.CONFLICT.name, database.syncQueueDao().byId(mine.id)!!.state)

        api.conflict = false
        manager.resolveKeepingLocal(database.syncQueueDao().byId(mine.id)!!.toDomain())

        assertTrue(database.syncQueueDao().pending().isEmpty())
        assertNull(database.syncQueueDao().byId(mine.id))
    }

    @Test
    fun coming_back_online_drains_the_queue_without_the_member_doing_anything() = runTest {
        val queue = SyncQueue(database, moshi, time)
        val connectivity = FakeConnectivity(false)
        val manager = SyncManager(demoApi(), database, connectivity, scope)

        enqueueActivity(queue)
        manager.start()
        advanceUntilIdle()
        assertEquals(1, database.syncQueueDao().pending().size)

        connectivity.set(true)
        advanceUntilIdle()

        assertTrue(database.syncQueueDao().pending().isEmpty())
    }
}
