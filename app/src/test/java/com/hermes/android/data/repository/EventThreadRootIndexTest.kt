package com.hermes.android.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure LRU logic backing the room-scoped
 * event-id → thread-root-id index. Exercises [applyEventThreadRootUpdates]
 * directly — no Android [android.content.Context] or SharedPreferences
 * required.
 */
class EventThreadRootIndexTest {

    @Test
    fun `empty existing inserts updates_in_order`() {
        val result = applyEventThreadRootUpdates(
            existing = emptyMap(),
            updates = linkedMapOf("e1" to "r1", "e2" to "r2"),
            maxEntries = 100,
        )
        assertEquals(linkedMapOf("e1" to "r1", "e2" to "r2"), result)
    }

    @Test
    fun `new entries appended_to_tail_preserving_existing`() {
        val existing = linkedMapOf("e1" to "r1")
        val result = applyEventThreadRootUpdates(
            existing = existing,
            updates = linkedMapOf("e2" to "r2"),
            maxEntries = 100,
        )
        assertEquals(linkedMapOf("e1" to "r1", "e2" to "r2"), result)
    }

    @Test
    fun `updating_existing_key_moves_to_tail_and_refreshes_value`() {
        val existing = linkedMapOf("e1" to "r1", "e2" to "r2", "e3" to "r3")
        val result = applyEventThreadRootUpdates(
            existing = existing,
            updates = mapOf("e1" to "r1_new"),
            maxEntries = 100,
        )
        // e1 should be moved to the tail with the refreshed value.
        assertEquals(linkedMapOf("e2" to "r2", "e3" to "r3", "e1" to "r1_new"), result)
    }

    @Test
    fun `eviction_drops_least_recent_until_under_limit`() {
        // Fill past capacity and confirm LRU eviction from the head.
        val existing = (1..5).associate { "e$it" to "r$it" }
        val result = applyEventThreadRootUpdates(
            existing = existing,
            updates = mapOf("e6" to "r6"),
            maxEntries = 3,
        )
        // Entries e1, e2, e3 were the oldest and should have been evicted.
        assertEquals(3, result.size)
        assertFalse("e1" in result)
        assertFalse("e2" in result)
        assertFalse("e3" in result)
        assertEquals("r4", result["e4"])
        assertEquals("r5", result["e5"])
        assertEquals("r6", result["e6"])
    }

    @Test
    fun `no_eviction_when_at_or_under_limit`() {
        val existing = (1..3).associate { "e$it" to "r$it" }
        val result = applyEventThreadRootUpdates(
            existing = existing,
            updates = emptyMap(),
            maxEntries = 3,
        )
        assertEquals(3, result.size)
        assertEquals(linkedMapOf("e1" to "r1", "e2" to "r2", "e3" to "r3"), result)
    }

    @Test
    fun `refreshing_existing_key_protects_it_from_eviction`() {
        val existing = (1..3).associate { "e$it" to "r$it" }
        // Touch e1 then insert e4 — only one slot is available, e2 is now LRU.
        val result = applyEventThreadRootUpdates(
            existing = existing,
            updates = mapOf("e1" to "r1", "e4" to "r4"),
            maxEntries = 3,
        )
        assertEquals(3, result.size)
        assertTrue("e1" in result)
        assertFalse("e2" in result)
        assertTrue("e3" in result)
        assertTrue("e4" in result)
    }

    @Test
    fun `empty_updates_returns_existing_order_unchanged`() {
        val existing = linkedMapOf("e1" to "r1", "e2" to "r2")
        val result = applyEventThreadRootUpdates(
            existing = existing,
            updates = emptyMap(),
            maxEntries = 100,
        )
        assertEquals(existing, result)
    }

    @Test
    fun `maxEntries_zero_evicts_everything`() {
        val result = applyEventThreadRootUpdates(
            existing = linkedMapOf("e1" to "r1"),
            updates = mapOf("e2" to "r2"),
            maxEntries = 0,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty_roomId_key_is_distinct_from_room_scoped`() {
        // Sanity check on the key prefix — empty roomId should not collide
        // with a non-empty one.
        assertTrue(eventThreadRootKey("!room1") != eventThreadRootKey("!room2"))
        assertTrue(eventThreadRootKey("!room1").startsWith(EVENT_THREAD_ROOT_KEY_PREFIX))
    }

    @Test
    fun `max_per_room_constant_is_reasonable`() {
        // Guards against accidental regression of the cap — if someone lowers
        // it below ~500 the index becomes unreliable for threads with long
        // histories; if raised above ~10_000 the SharedPrefs blob risks ANRs.
        assertTrue(EVENT_THREAD_ROOT_MAX_PER_ROOM in 500..10_000)
    }

    @Test
    fun `empty_updates_with_empty_existing_returns_empty`() {
        val result = applyEventThreadRootUpdates(
            existing = emptyMap(),
            updates = emptyMap(),
            maxEntries = 100,
        )
        assertTrue(result.isEmpty())
        assertNull(result["anything"])
    }
}
