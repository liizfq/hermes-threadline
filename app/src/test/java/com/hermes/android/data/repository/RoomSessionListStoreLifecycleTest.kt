package com.hermes.android.data.repository

import com.hermes.android.domain.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.ThreadListService
import org.matrix.rustcomponents.sdk.TimelineDiff

/**
 * Lifecycle / state tests for [RoomSessionListStore] and its collaborators.
 *
 * The pure decision logic is covered by [RoomSessionListSlotTest]; the
 * ownership / concurrency / cache contracts are covered by
 * [RoomSessionListStoreOwnershipTest] and [SettingsRepositoryRoomCacheTest].
 * This file covers the remaining contract:
 *  - The store's public API does NOT expose collector-tracking or release
 *    methods (UI collector churn cannot close the pipeline).
 *  - [RoomSessionListStore.refreshIfMissing] returns false when no room is
 *    active (the push pipeline correctly falls through to the SDK lookup).
 *  - [DiscoveryListener] rejects stale callbacks after the owning instance
 *    has been marked closed (the primary stale-rejection invariant).
 *
 * These tests run without dynamic JVM agents: the SDK types used here
 * (`Room`, `ThreadListService`) are constructed via the SDK's `NoHandle`
 * fake constructor and are never invoked — only `ActiveInstance` bookkeeping
 * is exercised. The store's ensureStarted / switch / refresh paths are
 * covered by slot-decision + bookkeeping tests + ownership tests rather than
 * full end-to-end integration here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSessionListStoreLifecycleTest {

    @Test
    fun `store public API has no collector-tracking or release method`() {
        // The store is application-scoped: nothing in its public API tracks
        // collector counts or lets a caller tear it down implicitly. UI
        // collector churn cannot close the pipeline — the only mutating
        // methods are ensureStarted, refresh, refreshIfMissing, refreshForPush,
        // shutdown.
        val publicMethods = RoomSessionListStore::class.java.methods
            .map { it.name }
            .toSet()
        assertTrue(publicMethods.contains("ensureStarted"))
        assertTrue(publicMethods.contains("refresh"))
        assertTrue(publicMethods.contains("refreshIfMissing"))
        assertTrue(publicMethods.contains("refreshForPush"))
        assertTrue(publicMethods.contains("shutdown"))
        val forbidden = listOf("release", "close", "decrement", "unsubscribe", "detach", "stop")
        val leaked = forbidden.filter { it in publicMethods }
        assertEquals(emptyList<String>(), leaked, "store must not expose collector-release API")
    }

    @Test
    fun `refreshIfMissing returns false when no room is active`() = runTest {
        // Construct the store via the internal test constructor so we control
        // the dispatcher without needing Hilt. No ensureStarted call means no
        // active instance — push pipeline must indicate "not ours".
        val store = RoomSessionListStore(
            FakeSettingsRepository(),
            Dispatchers.Unconfined,
        )
        assertFalse(store.refreshIfMissing(ROOM_A, null))
        assertFalse(store.refreshIfMissing(ROOM_A, THREAD_ROOT))
    }

    @Test
    fun `sessionsSnapshot is empty before ensureStarted`() {
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        assertEquals(emptyList<Session>(), store.sessionsSnapshot())
    }

    @Test
    fun `shutdown is a no-op when nothing is active`() {
        // Must not throw even though there is no instance to tear down.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        store.shutdown()
        assertEquals(emptyList<Session>(), store.sessionsSnapshot())
    }

    @Test
    fun `stale discovery callback is rejected after the instance is closed`() = runTest {
        // ActiveInstance is constructed directly with NoHandle SDK instances
        // (the listener never invokes SDK methods; it only reads bookkeeping).
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val instance = ActiveInstance(
            roomId = ROOM_A,
            room = Room(NoHandle),
            service = ThreadListService(NoHandle),
            scope = scope,
        )

        var refreshCalls = 0
        val listener = DiscoveryListener(instance) { refreshCalls++ }
        listener.markInitialized()

        // Closing the instance must short-circuit all scheduling, even when
        // the diff is non-empty.
        instance.markClosed()
        listener.onUpdate(listOf(TimelineDiff.Append(emptyList())))

        assertEquals(0, refreshCalls, "closed instance must not schedule refresh")
        scope.cancel()
    }

    @Test
    fun `stale discovery callback is rejected when the scope has been cancelled`() = runTest {
        val scheduler = testScheduler
        val scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        val instance = ActiveInstance(
            roomId = ROOM_A,
            room = Room(NoHandle),
            service = ThreadListService(NoHandle),
            scope = scope,
        )
        var refreshCalls = 0
        val listener = DiscoveryListener(instance) { refreshCalls++ }
        listener.markInitialized()

        // Cancel the scope (mimics teardown dropping pending jobs). The
        // listener must not attempt to schedule any new refresh.
        scope.cancel()
        listener.onUpdate(listOf(TimelineDiff.Append(emptyList())))

        assertEquals(0, refreshCalls, "cancelled scope must not schedule refresh")
    }

    private companion object {
        const val ROOM_A = "!a:server"
        const val THREAD_ROOT = "\$root:event.id"
    }
}

/**
 * Minimal in-memory [SettingsRepository] fake. Tracks per-room cache so
 * tests can assert room-scoped isolation without bringing up SharedPreferences.
 */
internal class FakeSettingsRepository : SettingsRepository {
    val caches: MutableMap<String, List<Session>> = mutableMapOf()
    val indexes: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
    var saveCount: Int = 0
        private set

    override fun getHomeserverUrl(): String? = null
    override fun getUserId(): String? = null
    override fun getAccessToken(): String? = null
    override fun getDeviceId(): String? = null
    override fun getBoundRoomId(): String? = null
    override fun observeBoundRoom() = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    override fun isLoggedIn(): Boolean = false
    override fun getSlidingSyncVersion(): String? = null
    override suspend fun saveLogin(homeserverUrl: String, userId: String, accessToken: String, deviceId: String?) {}
    override suspend fun saveBoundRoomId(roomId: String) {}
    override suspend fun saveSlidingSyncVersion(version: String) {}
    override suspend fun clear() { caches.clear() }
    override fun getSessionReadTimestamps(): Map<String, Long> = emptyMap()
    override fun saveSessionReadTimestamp(sessionId: String, timestampMs: Long) {}
    override fun clearSessionReadTimestamps() {}
    override fun saveSessionCache(roomId: String, sessions: List<Session>) {
        caches[roomId] = sessions
        saveCount += 1
    }
    override fun getSessionCache(roomId: String): List<Session>? = caches[roomId]
    override fun getSessionTitle(threadRootId: String): String? = null
    override fun saveSessionTitle(threadRootId: String, title: String) {}
    override fun getAllSessionTitles(): Map<String, String> = emptyMap()
    override fun deleteSessionTitle(threadRootId: String) {}
    override fun saveProvisionalSessionTitle(roomId: String, threadRootId: String, rootBody: String) {}
    override fun saveEventThreadRoot(roomId: String, eventId: String, threadRootId: String) {
        saveEventThreadRoots(roomId, mapOf(eventId to threadRootId))
    }
    override fun saveEventThreadRoots(roomId: String, mappings: Map<String, String>) {
        if (mappings.isEmpty()) return
        val existing = indexes.getOrPut(roomId) { LinkedHashMap() }
        for ((k, v) in mappings) existing[k] = v
    }
    override fun getEventThreadRoot(roomId: String, eventId: String): String? =
        indexes[roomId]?.get(eventId)
    override fun getDraft(threadRootId: String): String = ""
    override fun saveDraft(threadRootId: String, text: String) {}
    override fun clearDraft(threadRootId: String) {}
    override fun getLanguage(): String = "en"
    override fun setLanguage(locale: String) {}
}
