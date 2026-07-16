package com.hermes.android.data.repository

import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ThreadListEntriesListener
import org.matrix.rustcomponents.sdk.ThreadListService
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.ThreadListPaginationState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hand-written fakes for the small slice of the Matrix SDK that
 * [RoomSessionListStore] touches directly.
 *
 * We avoid mockk here because its JVM agent cannot self-attach under JDK 17+
 * in plain JVM unit tests without a `-javaagent` flag (which would require
 * Gradle changes we don't want to make). The SDK classes are uniffi-generated
 * `open class`es with a `NoHandle` test constructor, so subclassing them is
 * straightforward and lets tests drive exactly the behaviour they need.
 *
 * Each fake records the calls the store makes (close, destroy, reset,
 * paginate, …) so tests can assert on them directly.
 */

internal class FakeTaskHandle : TaskHandle(NoHandle) {
    val destroyCount = AtomicInteger(0)
    override fun destroy() {
        destroyCount.incrementAndGet()
    }
}

internal class FakeThreadListService : ThreadListService(NoHandle) {
    val subscribeCount = AtomicInteger(0)
    val resetCount = AtomicInteger(0)
    val paginateCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    @Volatile var listener: ThreadListEntriesListener? = null
    @Volatile var paginationStateValue: ThreadListPaginationState =
        ThreadListPaginationState.Idle(true)

    /** When non-null, reset() blocks on this latch; tests use it to force an in-flight leader. */
    @Volatile var resetEntered: CountDownLatch? = null
    @Volatile var releaseReset: CountDownLatch? = null

    override fun subscribeToItemsUpdates(listener: ThreadListEntriesListener): TaskHandle {
        subscribeCount.incrementAndGet()
        this.listener = listener
        return FakeTaskHandle()
    }

    override suspend fun reset() {
        resetCount.incrementAndGet()
        resetEntered?.countDown()
        releaseReset?.await(5, TimeUnit.SECONDS)
    }

    override suspend fun paginate() {
        paginateCount.incrementAndGet()
    }

    override fun paginationState(): ThreadListPaginationState = paginationStateValue

    override fun close() {
        closeCount.incrementAndGet()
    }
}

internal class FakeTimeline : Timeline(NoHandle) {
    val addListenerCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    @Volatile var listener: TimelineListener? = null
    override suspend fun addListener(listener: TimelineListener): TaskHandle {
        addListenerCount.incrementAndGet()
        this.listener = listener
        return FakeTaskHandle()
    }
    override fun close() {
        closeCount.incrementAndGet()
    }
}

internal open class FakeRoom(
    @Volatile var service: ThreadListService = FakeThreadListService(),
    @Volatile var timelineResult: Timeline = FakeTimeline(),
) : Room(NoHandle) {
    val closeCount = AtomicInteger(0)
    val threadListServiceCount = AtomicInteger(0)
    val timelineCount = AtomicInteger(0)

    override fun threadListService(): ThreadListService {
        threadListServiceCount.incrementAndGet()
        return service
    }

    override suspend fun timeline(): Timeline {
        timelineCount.incrementAndGet()
        return timelineResult
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}
