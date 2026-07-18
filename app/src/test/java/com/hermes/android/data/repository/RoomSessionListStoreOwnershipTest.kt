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
    fun `switch detaches old instance and closes its native handles`() {
        // I1 + C1: room switch must tear down the previous pipeline. Native
        // teardown (handle.destroy, service.close, room.close, scope.cancel)
        // happens outside stateLock but is observable here as "old handle is
        // closed at the end".
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)

        val roomA = FakeRoom()
        val serviceA = FakeThreadListService().also { roomA.service = it }
        assertTrue(store.ensureStarted(roomA, ROOM_A))

        val roomB = FakeRoom()
        val tookB = store.ensureStarted(roomB, ROOM_B)
        assertTrue(tookB, "Switch path must take ownership of the new handle")

        // Old pipeline is torn down.
        assertTrue(serviceA.closeCount.get() >= 1, "old service must be closed")
        assertTrue(roomA.closeCount.get() >= 1, "old room must be closed")
    }

    @Test
    fun `switch marks old instance closed under stateLock to close the discovery race`() {
        // Regression for stale-rejection invariant: the discovery / refresh
        // paths gate on `instance.closed`, NOT on `_active === instance`.
        // Switch must therefore call markClosed() UNDER stateLock, before
        // teardownUnlocked releases the SDK handles — otherwise an in-flight
        // discovery callback could schedule a refresh against the old
        // service in the window between detach and scope.cancel.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)

        val roomA = FakeRoom()
        assertTrue(store.ensureStarted(roomA, ROOM_A))

        val instanceA = readActiveInstance(store)
            ?: error("expected active instance after ensureStarted")

        val roomB = FakeRoom()
        assertTrue(store.ensureStarted(roomB, ROOM_B), "switch must take ownership of B")

        assertTrue(
            instanceA.closed,
            "switch must mark old instance closed under stateLock so stale discovery / refresh callbacks reject before teardown",
        )

        store.shutdown()
    }

    @Test
    fun `start tears down any inconsistent prior instance under stateLock`() {
        // Defensive: decide() returns Start when slot is empty OR already
        // closed. If a prior path left `_active != null` together with a
        // closed slot, the Start branch must still mark the survivor closed
        // and feed it to teardown — the new instance must be the only one
        // bound to SDK handles.
        val store = RoomSessionListStore(FakeSettingsRepository(), Dispatchers.Unconfined)

        val roomA = FakeRoom()
        val serviceA = FakeThreadListService().also { roomA.service = it }
        assertTrue(store.ensureStarted(roomA, ROOM_A))
        val instanceA = readActiveInstance(store) ?: error("expected active instance")

        // Force the inconsistent state: close the slot without clearing
        // `_active`. decide() now returns Start for any room (including A).
        forceSlotClosedWithoutClearingActive(store)

        val roomB = FakeRoom()
        assertTrue(store.ensureStarted(roomB, ROOM_B), "Start must take ownership of the new handle")

        // The survivor was marked closed and its native handles torn down.
        assertTrue(instanceA.closed, "Start must mark any prior instance closed")
        assertTrue(serviceA.closeCount.get() >= 1, "Start must tear down prior service")
        assertTrue(roomA.closeCount.get() >= 1, "Start must tear down prior room")

        store.shutdown()
    }

    private fun readActiveInstance(store: RoomSessionListStore): ActiveInstance? {
        val field = RoomSessionListStore::class.java.getDeclaredField("_active")
        field.isAccessible = true
        return field.get(store) as? ActiveInstance
    }

    private fun forceSlotClosedWithoutClearingActive(store: RoomSessionListStore) {
        val slotField = RoomSessionListStore::class.java.getDeclaredField("slot")
        slotField.isAccessible = true
        val slot = slotField.get(store)!!
        val closedField = slot.javaClass.getDeclaredField("_closed")
        closedField.isAccessible = true
        closedField.setBoolean(slot, true)
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
