package com.hermes.android.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.matrix.rustcomponents.sdk.ThreadListEntriesListener
import org.matrix.rustcomponents.sdk.ThreadListUpdate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real-store collaborator tests for [RoomSessionListStore] ownership /
 * concurrency contracts.
 *
 * The store's ensureStarted / switch / refresh paths need real SDK method
 * invocations to exercise the C1 / I1 / I2 / I4 invariants. We drive the
 * store with hand-written SDK fakes (see [SdkFakes]); each fake records the
 * calls the store makes (close, destroy, reset, paginate, …) so tests can
 * assert on them directly. No slot mirror or reflection-only assertions live
 * here.
 */
class RoomSessionListStoreOwnershipTest {

    @Test
    fun `first ensureStarted takes ownership of the room handle`() {
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        val room = FakeRoom()

        val tookOwnership = store.ensureStarted(room, ROOM_A)

        assertTrue(tookOwnership, "Start path must take ownership and report true")
        // Caller must NOT close the handle when ownership transferred — the
        // store owns it now.
        assertEquals(0, room.closeCount.get(), "Start path must not close the caller handle")
    }

    @Test
    fun `same-room NoOp does NOT close caller handle and does NOT change instance`() {
        // C1: a defensive ensureStarted for an already-bound room must not
        // close the caller's Room handle — ChatViewModel passes the same
        // handle to ActiveThreadFactory after this call returns.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)

        val roomA = FakeRoom()
        val serviceA = FakeThreadListService().also { roomA.service = it }
        assertTrue(store.ensureStarted(roomA, ROOM_A))

        // Second call for the same room: duplicate handle provided by caller.
        val duplicateRoom = FakeRoom()
        val tookOwnership = store.ensureStarted(duplicateRoom, ROOM_A)

        assertFalse(tookOwnership, "NoOp must report false so caller knows it still owns the handle")
        assertEquals(0, duplicateRoom.closeCount.get(), "NoOp must not close caller handle")
        assertEquals(0, roomA.closeCount.get(), "Original handle stays alive — store still owns it")
        // Subscribe was called only on the first ensureStarted, not on NoOp.
        assertEquals(1, serviceA.subscribeCount.get(), "NoOp must not re-subscribe")
    }

    @Test
    fun `ensureStarted for a second room does NOT teardown the first room`() {
        // Multi-instance (design §2.2): ensureStarted does NOT teardown other
        // rooms. Starting room B while A is active leaves A's pipeline alive
        // (A simply stops being the active projection). Both rooms coexist in
        // the store.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)

        val roomA = FakeRoom()
        val serviceA = FakeThreadListService().also { roomA.service = it }
        assertTrue(store.ensureStarted(roomA, ROOM_A))

        val roomB = FakeRoom()
        val tookB = store.ensureStarted(roomB, ROOM_B)
        assertTrue(tookB, "Start path must take ownership of the new handle")

        // Old pipeline is NOT torn down — A stays alive as a background instance.
        assertEquals(0, serviceA.closeCount.get(), "old service must NOT be closed (multi-instance)")
        assertEquals(0, roomA.closeCount.get(), "old room must NOT be closed (multi-instance)")
        assertNotNull(store.sessionsForRoom(ROOM_A), "A's per-room flow must still exist")
        assertNotNull(store.sessionsForRoom(ROOM_B), "B's per-room flow must exist")
    }

    @Test
    fun `switching the active room keeps A alive and re-points the projection at B`() {
        // Multi-instance: ensureStarted(B) does NOT close A; it only moves the
        // active projection to B. The public `sessions` flow must reflect B,
        // not A. (The stale-callback isolation this used to enforce via
        // markClosed is now enforced by projection gating — covered by
        // `stale items listener does NOT publish after a switch to a new room`.)
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        val aSession = sessionFor("\$a1", "session A")
        settings.saveSessionCache(ROOM_A, listOf(aSession))
        val bSession = sessionFor("\$b1", "session B")
        settings.saveSessionCache(ROOM_B, listOf(bSession))

        assertTrue(store.ensureStarted(FakeRoom(), ROOM_A))
        assertEquals(listOf(aSession), store.sessionsSnapshot(), "projection reflects A")

        assertTrue(store.ensureStarted(FakeRoom(), ROOM_B), "switching active to B takes ownership of B")
        assertEquals(listOf(bSession), store.sessionsSnapshot(), "projection now reflects B")

        // A is still alive in the background (per-room flow intact).
        assertNotNull(store.sessionsForRoom(ROOM_A), "A must remain alive as a background instance")

        store.shutdown()
    }

    @Test
    fun `stopForRoom tears down only the target room and leaves others intact`() {
        // Multi-instance: stopForRoom removes exactly one room's instance.
        // Other rooms (active or background) are untouched. This is the path
        // used when a room is removed from the push set.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)

        val roomA = FakeRoom()
        val serviceA = FakeThreadListService().also { roomA.service = it }
        assertTrue(store.ensureStarted(roomA, ROOM_A))

        val roomB = FakeRoom()
        val serviceB = FakeThreadListService().also { roomB.service = it }
        assertTrue(store.ensureStartedForPushRoom(roomB, ROOM_B))

        // Tear down only A. B must be unaffected.
        store.stopForRoom(ROOM_A)

        assertTrue(serviceA.closeCount.get() >= 1, "stopForRoom must close A's service")
        assertTrue(roomA.closeCount.get() >= 1, "stopForRoom must close A's room")
        assertNull(store.sessionsForRoom(ROOM_A), "A must be gone after stopForRoom")
        assertNotNull(store.sessionsForRoom(ROOM_B), "B must survive stopForRoom(A)")
        assertEquals(0, serviceB.closeCount.get(), "B's service must NOT be closed")
        assertEquals(0, roomB.closeCount.get(), "B's room must NOT be closed")

        store.shutdown()
    }

    @Test
    fun `public flow is cleared on switch so old room sessions do not leak through`() {
        // C2/C3: room switch must immediately drop the old room's sessions
        // from the public state, even before the new room has data.
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        // Seed room A with sessions in the cache; ensureStarted will surface
        // them on the public flow.
        val aSession = sessionFor("\$a1", "session A")
        settings.saveSessionCache(ROOM_A, listOf(aSession))

        val roomA = FakeRoom()
        store.ensureStarted(roomA, ROOM_A)
        assertEquals(listOf(aSession), store.sessionsSnapshot())

        // Switch to room B with empty cache.
        val roomB = FakeRoom()
        store.ensureStarted(roomB, ROOM_B)

        // Old room sessions are gone — UI would otherwise show stale A data
        // while B is loading.
        assertTrue(store.sessionsSnapshot().isEmpty())
    }

    @Test
    fun `shutdown tears down active instance and clears public flow`() {
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        val room = FakeRoom()
        val service = FakeThreadListService().also { room.service = it }
        store.ensureStarted(room, ROOM_A)

        store.shutdown()

        assertTrue(store.sessionsSnapshot().isEmpty())
        assertTrue(service.closeCount.get() >= 1, "shutdown must close the service")
        assertTrue(room.closeCount.get() >= 1, "shutdown must close the room")
    }

    @Test
    fun `stale items listener does NOT publish or save cache after shutdown`() {
        // I4 stale-rejection: a callback firing after teardown must not write
        // to the public flow or the room-scoped cache.
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        val room = FakeRoom()
        val service = FakeThreadListService().also { room.service = it }
        store.ensureStarted(room, ROOM_A)
        val saveCountBefore = settings.saveCount
        val listener = service.listener ?: error("listener should be registered")

        store.shutdown()
        // Stale callback: even if some listener fires post-teardown, the
        // closed-check at the top of onUpdate must prevent any state change.
        listener.onUpdate(emptyList<ThreadListUpdate>())

        assertTrue(store.sessionsSnapshot().isEmpty())
        assertEquals(saveCountBefore, settings.saveCount, "stale callback must not save cache")
    }

    @Test
    fun `stale items listener does NOT publish after a switch to a new room`() {
        // Variant of the stale-rejection test: after switching rooms, a
        // callback from the OLD instance must not overwrite the NEW room's
        // published state.
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        val roomA = FakeRoom()
        val serviceA = FakeThreadListService().also { roomA.service = it }
        store.ensureStarted(roomA, ROOM_A)
        val listenerA = serviceA.listener ?: error("listener A should be registered")

        val roomB = FakeRoom()
        store.ensureStarted(roomB, ROOM_B)
        val snapshotBefore = store.sessionsSnapshot()

        // Stale callback from old A pipeline fires after switch.
        listenerA.onUpdate(emptyList<ThreadListUpdate>())

        assertEquals(snapshotBefore, store.sessionsSnapshot(), "stale A callback must not affect B's flow")
    }

    @Test
    fun `concurrent refreshIfMissing callers share a single leader`() = runBlocking {
        // I2 single-flight: many concurrent callers must coalesce onto one
        // leader. The leader's mutex + CompletableDeferred make all callers
        // wait for the same refresh.
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        val room = FakeRoom()
        val service = FakeThreadListService().also { room.service = it }
        val resetEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.resetEntered = resetEntered
        service.releaseReset = release

        assertTrue(store.ensureStarted(room, ROOM_A))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val a = scope.async { store.refreshIfMissing(ROOM_A, MISSING_THREAD_ROOT) }
            val b = scope.async { store.refreshIfMissing(ROOM_A, MISSING_THREAD_ROOT) }
            val c = scope.async { store.refreshIfMissing(ROOM_A, MISSING_THREAD_ROOT) }

            // Wait for the leader to enter reset().
            assertTrue(resetEntered.await(2, TimeUnit.SECONDS), "leader should have started")
            // Give the awaiters a moment to enter runRefreshSingleFlight.
            delay(100)
            assertEquals(1, service.resetCount.get(), "only one leader before release")

            release.countDown()

            assertTrue(a.await(), "all callers should return true when active room matches")
            assertTrue(b.await())
            assertTrue(c.await())

            assertEquals(1, service.resetCount.get(), "single-flight: reset called exactly once")
        } finally {
            release.countDown()  // safety: don't leave the leader stuck if an assertion fails
            scope.cancel()
            store.shutdown()
        }
    }

    @Test
    fun `refreshIfMissing returns true without refresh when thread root is present`() = runBlocking {
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        val room = FakeRoom()
        val service = FakeThreadListService().also { room.service = it }
        assertTrue(store.ensureStarted(room, ROOM_A))

        // Set the cache after ensureStarted so the post-ensureStarted
        // publishIfActive (which writes items, initially empty) does not
        // clobber our entry. refreshIfMissing reads from this cache.
        settings.saveSessionCache(ROOM_A, listOf(sessionFor(PRESENT_THREAD_ROOT, "present")))

        assertTrue(store.refreshIfMissing(ROOM_A, PRESENT_THREAD_ROOT))
        assertEquals(0, service.resetCount.get(), "thread root is present — no refresh should run")
    }

    @Test
    fun `refreshForPush refreshes even when session root is already present`() = runBlocking {
        val settings = FakeSettingsRepository()
        val store = RoomSessionListStore(settings, Dispatchers.Unconfined)

        val room = FakeRoom()
        val service = FakeThreadListService().also { room.service = it }
        assertTrue(store.ensureStarted(room, ROOM_A))

        // Presence must not suppress this refresh: a known root can still
        // have stale latestEventId/replyCount when SDK sync was delayed.
        settings.saveSessionCache(ROOM_A, listOf(sessionFor(PRESENT_THREAD_ROOT, "present")))

        assertTrue(store.refreshForPush(ROOM_A))
        assertEquals(1, service.resetCount.get(), "push must refresh known session summaries")
    }

    @Test
    fun `refreshIfMissing returns false when room does not match`() = runBlocking {
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        val room = FakeRoom()
        assertTrue(store.ensureStarted(room, ROOM_A))

        assertFalse(store.refreshIfMissing(ROOM_B, MISSING_THREAD_ROOT))
    }

    @Test
    fun `zero collectors do not close the pipeline — public flow outlives UI churn`() {
        // The store is application-scoped. It has no public release / close
        // method besides shutdown, so collector churn cannot tear it down.
        // Covered by `store public API has no collector-tracking or release
        // method` in the lifecycle suite; duplicated here as an explicit
        // functional assertion: after starting, no caller interaction closes
        // the instance until shutdown is invoked.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        val room = FakeRoom()
        store.ensureStarted(room, ROOM_A)

        // Snapshot immediately, then simulate "collector went away" by doing
        // nothing — the store survives.
        assertNotNull(store.sessionsSnapshot())

        // The instance is still active: a NoOp call for the same room should
        // NOT take ownership (the existing instance persists).
        val duplicateRoom = FakeRoom()
        assertFalse(store.ensureStarted(duplicateRoom, ROOM_A))
        assertEquals(0, duplicateRoom.closeCount.get(), "NoOp must not close caller handle")

        // Only shutdown tears it down.
        store.shutdown()
    }

    @Test
    fun `teardown outside stateLock — switch does not deadlock`() = runBlocking {
        // I1 invariant: native teardown (handle.destroy, scope.cancel, close)
        // runs OUTSIDE stateLock. If teardown held the lock, a concurrent
        // ensureStarted would block on it. We verify the switch path
        // completes synchronously and the next ensureStarted is not stuck.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)
        val roomA = FakeRoom()
        store.ensureStarted(roomA, ROOM_A)

        val roomB = FakeRoom()
        // Switch + teardown of A.
        assertTrue(store.ensureStarted(roomB, ROOM_B))
        // Immediate next call: should not block.
        val roomC = FakeRoom()
        assertTrue(store.ensureStarted(roomC, ROOM_C))

        store.shutdown()
    }

    private fun sessionFor(id: String, title: String) = com.hermes.android.domain.model.Session(
        id = id,
        title = title,
        lastMessage = null,
        lastActivityTime = java.time.Instant.EPOCH,
        replyCount = 0,
        unreadCount = 0,
        isProcessing = false,
        senderAvatarUrl = null,
        latestEventId = id,
    )

    private companion object {
        const val ROOM_A = "!a:server"
        const val ROOM_B = "!b:server"
        const val ROOM_C = "!c:server"
        const val MISSING_THREAD_ROOT = "\$missing:event"
        const val PRESENT_THREAD_ROOT = "\$present:event"
    }
}
