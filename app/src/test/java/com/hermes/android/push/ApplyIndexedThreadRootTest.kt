package com.hermes.android.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [applyIndexedThreadRoot] — the pure decision that merges
 * a locally-indexed thread root into an [EventPushEvent]. Covers the
 * m.replace resolution path used by [EventPushWorker.resolveReplaceTargetViaIndex].
 */
class ApplyIndexedThreadRootTest {

    private val baseEvent = EventPushEvent(
        roomId = "!room:server",
        eventId = "\$editEvent",
        body = "edited body",
        // threadRootId left null — simulates an m.replace push with no m.thread
        // relation (the common case the index exists to handle).
        threadRootId = null,
        replaceTargetId = "\$originalMessage",
    )

    @Test
    fun `index_hit_overrides_null_threadRootId`() {
        val result = applyIndexedThreadRoot(
            event = baseEvent,
            indexedRoot = "\$threadRoot",
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals("\$threadRoot", result.threadRootId)
    }

    @Test
    fun `index_miss_returns_event_unchanged`() {
        val result = applyIndexedThreadRoot(
            event = baseEvent,
            indexedRoot = null,
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals(baseEvent, result)
        assertNull(result.threadRootId)
    }

    @Test
    fun `blank_indexedRoot_treated_as_miss`() {
        val result = applyIndexedThreadRoot(
            event = baseEvent,
            indexedRoot = "",
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals(baseEvent, result)
    }

    @Test
    fun `indexedRoot_equal_to_eventId_returns_unchanged`() {
        // Self-referential — the index resolved the edit event itself to its
        // own id, which would be a no-op and signals bad data.
        val result = applyIndexedThreadRoot(
            event = baseEvent,
            indexedRoot = "\$editEvent",  // same as event.eventId
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals(baseEvent, result)
        assertNull(result.threadRootId)
    }

    @Test
    fun `payload_already_has_real_threadRoot_preserved`() {
        // Exotic case: m.replace + m.thread on the same event. The explicit
        // m.thread relation wins over the index.
        val eventWithThread = baseEvent.copy(threadRootId = "\$payloadThreadRoot")
        val result = applyIndexedThreadRoot(
            event = eventWithThread,
            indexedRoot = "\$indexThreadRoot",
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals("\$payloadThreadRoot", result.threadRootId)
    }

    @Test
    fun `payload_threadRootId_equal_to_eventId_allows_index_override`() {
        // The parser fell back to the event's own id (no m.thread); the index
        // can still recover the real root.
        val fallback = baseEvent.copy(threadRootId = "\$editEvent")  // == eventId
        val result = applyIndexedThreadRoot(
            event = fallback,
            indexedRoot = "\$realThreadRoot",
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals("\$realThreadRoot", result.threadRootId)
    }

    @Test
    fun `other_fields_preserved_on_override`() {
        val result = applyIndexedThreadRoot(
            event = baseEvent,
            indexedRoot = "\$threadRoot",
            target = "\$originalMessage",
            logTag = "Test",
        )
        assertEquals("!room:server", result.roomId)
        assertEquals("\$editEvent", result.eventId)
        assertEquals("edited body", result.body)
        assertEquals("\$originalMessage", result.replaceTargetId)
    }
}
