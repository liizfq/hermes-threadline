package com.hermes.android.data.repository

import com.hermes.android.domain.model.Message
import com.hermes.android.domain.model.MessageContent
import com.hermes.android.presentation.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineListener
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ActiveThreadStore]'s lifecycle / ownership contracts.
 *
 * The store is exercised with hand-written fakes: [FakeActiveThreadFactory]
 * (subclasses the real factory via the `open` hook and constructs an
 * [ActiveThreadImpl] backed by a [SpyTimeline] + [FakeRoom]), plus fake
 * collaborators for Room / Session / Settings repositories.
 *
 * Covered invariants:
 *  - same-key reuse — second open is a synchronous no-op
 *  - different-key switch — previous holder is torn down exactly once (room
 *    closed)
 *  - closeActive — tears down the active holder
 *  - stale messages callback after teardown — does not affect public state
 *  - public API — exposes no collector-release method (UI churn can't close)
 *  - commands without an active holder — silent no-op
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveThreadStoreTest {

    private lateinit var settings: FakeSettingsRepository
    private lateinit var sessionRepo: FakeSessionRepository
    private lateinit var roomRepo: FakeRoomRepository
    private lateinit var factory: FakeActiveThreadFactory
    private lateinit var holderScopeRoot: CoroutineScope
    private lateinit var store: ActiveThreadStore

    @BeforeEach
    fun setUp() {
        settings = FakeSettingsRepository()
        sessionRepo = FakeSessionRepository()
        roomRepo = FakeRoomRepository()
        holderScopeRoot = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        factory = FakeActiveThreadFactory(settings)
        store = ActiveThreadStore(factory, roomRepo, sessionRepo, settings, Dispatchers.Unconfined)
    }

    @AfterEach
    fun tearDown() {
        store.closeActive()
        holderScopeRoot.cancel()
    }

    @Test
    fun `active key is null before any open`() {
        assertNull(store.activeKey())
        assertEquals(ActiveThreadSnapshot.EMPTY, store.state.value)
    }

    @Test
    fun `open sets active key and creates holder`() = runBlocking {
        roomRepo.setRoom(ROOM_A, TestRoom(ROOM_A))
        store.open(ROOM_A, THREAD_A)

        // setupHolder is async even with Unconfined (Mutex.withLock can
        // re-dispatch); poll for completion.
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }

        assertEquals(ActiveThreadKey(ROOM_A, THREAD_A), store.activeKey())
        assertEquals(1, factory.createCount.get(), "open must trigger exactly one factory.create")
        assertEquals(1, roomRepo.getRoomCount.get(), "open must fetch the room handle")
    }

    @Test
    fun `same-key second open is a no-op`() = runBlocking {
        roomRepo.setRoom(ROOM_A, TestRoom(ROOM_A))
        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }
        val createsBefore = factory.createCount.get()

        store.open(ROOM_A, THREAD_A)
        // Brief yield window in case a stray setup was launched.
        Thread.sleep(50)

        assertEquals(createsBefore, factory.createCount.get(), "second open same key must not call create again")
        assertEquals(ActiveThreadKey(ROOM_A, THREAD_A), store.activeKey(), "reuse must keep the same active key")
        // Same-key open schedules a catch-up refresh but must not rebuild.
    }

    @Test
    fun `same-key second open does not tear down room handle`() = runBlocking {
        val room = TestRoom(ROOM_A)
        roomRepo.setRoom(ROOM_A, room)
        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }

        store.open(ROOM_A, THREAD_A)
        Thread.sleep(50)

        assertEquals(0, room.closeCount.get(), "reuse catch-up must not close the warm room handle")
        assertEquals(1, factory.createCount.get())
    }

    @Test
    fun `switch to different key tears down previous holder exactly once`() = runBlocking {
        val roomA = TestRoom(ROOM_A)
        val roomB = TestRoom(ROOM_B)
        roomRepo.setRoom(ROOM_A, roomA)
        roomRepo.setRoom(ROOM_B, roomB)

        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }
        assertEquals(0, roomA.closeCount.get(), "first room must stay open while active")

        store.open(ROOM_B, THREAD_B)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_B, THREAD_B) }
        // Allow teardown of A to complete outside stateLock.
        yieldUntil { roomA.closeCount.get() >= 1 }

        assertEquals(2, factory.createCount.get(), "switch must create a new holder")
        assertEquals(1, roomA.closeCount.get(), "previous room must be closed exactly once on switch")
        assertEquals(0, roomB.closeCount.get(), "new room must NOT be closed while active")
    }

    @Test
    fun `closeActive detaches holder and closes room`() = runBlocking {
        val room = TestRoom(ROOM_A)
        roomRepo.setRoom(ROOM_A, room)

        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }
        assertEquals(0, room.closeCount.get())

        store.closeActive()
        yieldUntil { room.closeCount.get() >= 1 }

        assertNull(store.activeKey())
        assertEquals(ActiveThreadSnapshot.EMPTY, store.state.value)
        assertEquals(1, room.closeCount.get(), "closeActive must close the active room")
    }

    @Test
    fun `closeActive is a no-op when nothing is active`() {
        // Must not throw.
        store.closeActive()
        assertNull(store.activeKey())
    }

    @Test
    fun `reopen after closeActive builds a fresh holder`() = runBlocking {
        val room1 = TestRoom(ROOM_A)
        val room2 = TestRoom(ROOM_A)
        roomRepo.setRoom(ROOM_A, room1, room2)

        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }
        store.closeActive()
        yieldUntil { store.activeKey() == null }
        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }

        assertEquals(2, factory.createCount.get(), "reopen must build a new holder")
    }

    @Test
    fun `stale messages callback after teardown does not affect public state`() = runBlocking {
        val roomA = TestRoom(ROOM_A)
        val roomB = TestRoom(ROOM_B)
        roomRepo.setRoom(ROOM_A, roomA)
        roomRepo.setRoom(ROOM_B, roomB)

        store.open(ROOM_A, THREAD_A)
        yieldUntil { factory.lastCreatedImpl != null }
        val threadA = factory.lastCreatedImpl ?: error("expected A thread")
        threadA.setMessages(listOf(message("\$a1", "hello from A")))
        yieldUntil { store.state.value.messages is UiState.Success }
        val aSuccess = store.state.value.messages as UiState.Success
        assertTrue(aSuccess.data.any { it.id == "\$a1" }, "A's message should surface")

        // Switch to B; A's holder is torn down.
        store.open(ROOM_B, THREAD_B)
        yieldUntil { factory.lastCreatedImpl != null && factory.lastCreatedImpl !== threadA }
        val threadB = factory.lastCreatedImpl ?: error("expected B thread")
        threadB.setMessages(listOf(message("\$b1", "hello from B")))
        yieldUntil {
            val msgs = store.state.value.messages
            msgs is UiState.Success && msgs.data.any { it.id == "\$b1" }
        }

        // Stale push on A — should be rejected by identity guard.
        threadA.setMessages(listOf(message("\$a2", "stale from A")))
        Thread.sleep(100) // give the collector a chance to (incorrectly) fire

        val snapshot = store.state.value
        assertEquals(ActiveThreadKey(ROOM_B, THREAD_B), snapshot.key)
        val success = snapshot.messages as UiState.Success
        assertTrue(success.data.any { it.id == "\$b1" }, "B's message should be visible")
        assertFalse(success.data.any { it.id == "\$a2" }, "stale A callback must not surface")
    }

    @Test
    fun `commands without an active holder are silent no-op`() {
        // No open() yet — every command must drop silently without throwing.
        store.sendMessage("hi")
        store.toggleReaction("\$x", "👍")
        store.loadMoreMessages()
        // No assertions to check beyond "did not throw" — there is no public
        // surface to observe the drop.
    }

    @Test
    fun `commands after closeActive are silent no-op`() = runBlocking {
        val room = TestRoom(ROOM_A)
        roomRepo.setRoom(ROOM_A, room)
        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() != null }
        store.closeActive()
        yieldUntil { store.activeKey() == null }

        store.sendMessage("hi")
        store.toggleReaction("\$x", "👍")
        store.loadMoreMessages()
        // Did not throw.
    }

    @Test
    fun `store public API has no collector-release method`() {
        // The store is application-scoped: nothing in its public API tracks
        // collector counts or lets a caller implicitly tear it down. UI
        // collector churn cannot close the pipeline.
        val publicMethods = ActiveThreadStore::class.java.methods
            .map { it.name }
            .toSet()
        assertTrue(publicMethods.contains("open"))
        assertTrue(publicMethods.contains("closeActive"))
        // `val state` compiles to getter getState() on JVM.
        assertTrue(publicMethods.contains("getState"))
        assertTrue(publicMethods.contains("activeKey"))
        val forbidden = listOf("release", "leave", "detach", "unsubscribe", "decrement", "stop")
        val leaked = forbidden.filter { it in publicMethods }
        assertEquals(emptyList<String>(), leaked, "store must not expose collector-release API")
    }

    @Test
    fun `open with missing room publishes Error without creating a thread`() = runBlocking {
        // No room seeded — roomRepo.getRoom returns null.
        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.state.value.messages is UiState.Error }

        assertEquals(0, factory.createCount.get(), "factory must not be called when room is missing")
        val snapshot = store.state.value
        assertEquals(ActiveThreadKey(ROOM_A, THREAD_A), snapshot.key)
        assertTrue(snapshot.messages is UiState.Error, "missing room must surface as Error")
    }

    @Test
    fun `refreshActiveIfAny is a no-op when nothing is open`() = runBlocking {
        // Foreground recovery must not throw when no chat is focused.
        store.refreshActiveIfAny()
        assertNull(store.activeKey())
    }

    @Test
    fun `refreshActiveIfAny is a no-op after closeActive`() = runBlocking {
        val room = TestRoom(ROOM_A)
        roomRepo.setRoom(ROOM_A, room)
        store.open(ROOM_A, THREAD_A)
        yieldUntil { store.activeKey() == ActiveThreadKey(ROOM_A, THREAD_A) }
        store.closeActive()
        // After teardown, refresh must not resurrect the holder.
        store.refreshActiveIfAny()
        assertNull(store.activeKey())
        assertEquals(ActiveThreadSnapshot.EMPTY, store.state.value)
    }

    @Test
    fun `public API exposes refreshActiveIfAny for foreground recovery`() {
        val publicMethods = ActiveThreadStore::class.java.methods.map { it.name }.toSet()
        assertTrue(publicMethods.contains("refreshActiveIfAny"))
    }

    // ---- helpers ----

    private fun yieldUntil(timeoutMs: Long = 1000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(5)
        }
        assertNotNull(null, "predicate never satisfied within ${timeoutMs}ms")
    }

    private fun message(id: String, body: String): Message = Message(
        id = id,
        senderId = "@user:server",
        senderName = "User",
        senderAvatarUrl = null,
        content = MessageContent.Text(null, body, emptyList()),
        timestamp = Instant.EPOCH,
        isOwn = false,
        reactions = emptyList(),
    )

    private companion object {
        const val ROOM_A = "!a:server"
        const val ROOM_B = "!b:server"
        const val THREAD_A = "\$threadA:event"
        const val THREAD_B = "\$threadB:event"
    }
}

/**
 * [FakeRoom] augmented with an `id()` override so [ActiveThreadImpl]'s init
 * `Log.d(room.id())` call doesn't hit a native handle (NoHandle constructor
 * leaves the Rust pointer at 0, so any FFI call would throw / segfault).
 * Existing FakeRoom only overrides the methods [RoomSessionListStore] calls.
 */
internal class TestRoom(
    val roomId: String,
) : FakeRoom() {
    override fun `id`(): String = roomId
}

// ---- Fakes ----

/**
 * Subclass of the real [ActiveThreadFactory] that bypasses the SDK
 * `timelineWithConfiguration` path. Each `create()` constructs a real
 * [ActiveThreadImpl] backed by a [SpyTimeline] so the store's collectors see
 * a real `messages` / `state` / `backwardPaginationStatus` flow, and tests can
 * drive the underlying `_messages` field via reflection.
 */
internal class FakeActiveThreadFactory(
    settingsRepository: SettingsRepository,
) : ActiveThreadFactory(settingsRepository) {
    val createCount = AtomicInteger(0)
    @Volatile
    var lastCreatedImpl: ActiveThreadImpl? = null
        private set

    override suspend fun create(room: Room, threadRootId: String): ActiveThreadImpl {
        createCount.incrementAndGet()
        // Construct directly, bypassing room.timelineWithConfiguration. The
        // init block's diff collector uses FakeTimeline.addListener (no-op).
        val timeline = SpyTimeline()
        val impl = ActiveThreadImpl(
            room = room,
            threadRootId = threadRootId,
            settingsRepository = settingsRepository,
            timeline = timeline,
        )
        lastCreatedImpl = impl
        return impl
    }
}

/**
 * Minimal [Timeline] fake: `addListener` returns a no-op handle, `close` is
 * recorded. No SDK methods are invoked; tests that need to drive messages
 * write directly to [ActiveThreadImpl._messages] via [ActiveThreadImplBridge].
 */
internal class SpyTimeline : Timeline(NoHandle) {
    val addListenerCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    override suspend fun addListener(listener: TimelineListener): TaskHandle {
        addListenerCount.incrementAndGet()
        return object : TaskHandle(NoHandle) {}
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

/**
 * Bridge for tests to drive [ActiveThreadImpl._messages] directly. The field
 * is private; reflection lets the stale-callback test simulate "the listener
 * processed a diff and the StateFlow emitted".
 */
internal fun ActiveThreadImpl.setMessages(messages: List<Message>) {
    val field = ActiveThreadImpl::class.java.getDeclaredField("_messages")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val flow = field.get(this) as MutableStateFlow<List<Message>>
    flow.value = messages
}

/**
 * Per-test fake [RoomRepository]. Holds a queue of [Room] handles per room id
 * so a test can hand back distinct handles for repeated getRoom() calls.
 */
internal class FakeRoomRepository : RoomRepository {
    private val rooms: MutableMap<String, ArrayDeque<Room>> = mutableMapOf()
    val getRoomCount = AtomicInteger(0)

    fun setRoom(roomId: String, vararg handles: FakeRoom) {
        rooms[roomId] = ArrayDeque(handles.toList())
    }

    override suspend fun getRoom(roomId: String): Room? {
        getRoomCount.incrementAndGet()
        val q = rooms[roomId] ?: return null
        return synchronized(q) {
            if (q.isEmpty()) null else q.removeFirst()
        }
    }

    override suspend fun verifyRoomAccess(roomId: String): Boolean = rooms[roomId]?.isNotEmpty() == true
}

/**
 * Minimal fake [SessionRepository] — only what [ActiveThreadStore] uses
 * (observeSessions for the reconcile collector). All create / delete paths
 * are stubbed since the store never calls them.
 */
internal class FakeSessionRepository : SessionRepository {
    private val sessions = MutableStateFlow<List<com.hermes.android.domain.model.Session>>(emptyList())

    override fun ensureSessionsStarted(room: Room, roomId: String): Boolean = false
    override fun observeSessions(): Flow<List<com.hermes.android.domain.model.Session>> = sessions.asStateFlow()
    override fun sessionsSnapshot(): List<com.hermes.android.domain.model.Session> = sessions.value
    override suspend fun refreshIfMissing(roomId: String, threadRootId: String?): Boolean = false
    override suspend fun refreshForPush(roomId: String): Boolean = false
    override suspend fun refreshSessions() {}
    override suspend fun createSession(room: Room, content: String): Result<String> = Result.success("")
    override suspend fun createSession(
        room: Room,
        content: String,
        title: String?,
        attachmentUri: android.net.Uri?,
        attachmentType: String?,
        context: android.content.Context?,
    ): Result<String> = Result.success("")
    override suspend fun deleteSession(room: Room, threadRootId: String): Result<Unit> = Result.success(Unit)
}
