package com.hermes.android.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SharedPreferencesPushEventStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SharedPreferencesPushEventStore

    @BeforeEach
    fun setUp() {
        // Clean any persisted state before each test.
        context.getSharedPreferences("hermes_push_events", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = SharedPreferencesPushEventStore(context)
    }

    @AfterEach
    fun tearDown() {
        store.clear()
    }

    @Nested
    inner class StoreIfAbsent {
        @Test
        fun `returns true for first event`() {
            val event = EventPushEvent(roomId = "!r:h", eventId = "E1")
            assertTrue(store.storeIfAbsent(event))
        }

        @Test
        fun `returns false for duplicate by dedupKey`() {
            val first = EventPushEvent(roomId = "!r:h", eventId = "E1")
            val dup = EventPushEvent(roomId = "!r:h", eventId = "E1", sender = "Different")
            store.storeIfAbsent(first)
            assertFalse(store.storeIfAbsent(dup))
        }

        @Test
        fun `allows distinct events`() {
            store.storeIfAbsent(EventPushEvent(roomId = "!r:h", eventId = "E1"))
            store.storeIfAbsent(EventPushEvent(roomId = "!r:h", eventId = "E2"))
            store.storeIfAbsent(EventPushEvent(roomId = "!r2:h", eventId = "E1"))
            assertEquals(3, store.size())
        }
    }

    @Nested
    inner class Drain {
        @Test
        fun `drain returns all events in insertion order`() {
            val a = EventPushEvent(roomId = "!r:h", eventId = "E1")
            val b = EventPushEvent(roomId = "!r:h", eventId = "E2")
            store.storeIfAbsent(a)
            store.storeIfAbsent(b)

            val drained = store.drain()
            assertEquals(2, drained.size)
            assertEquals(a.dedupKey, drained[0].dedupKey)
            assertEquals(b.dedupKey, drained[1].dedupKey)
        }

        @Test
        fun `drain clears the store`() {
            store.storeIfAbsent(EventPushEvent(roomId = "!r:h", eventId = "E1"))
            store.drain()
            assertEquals(0, store.size())
        }

        @Test
        fun `drain on empty store returns empty list`() {
            val drained = store.drain()
            assertTrue(drained.isEmpty())
        }
    }

    @Nested
    inner class Capacity {
        @Test
        fun `drops oldest when capacity exceeded`() {
            val smallStore = SharedPreferencesPushEventStore(context, maxCapacity = 3)
            repeat(5) { i ->
                smallStore.storeIfAbsent(EventPushEvent(roomId = "!r:h", eventId = "E$i"))
            }
            assertEquals(3, smallStore.size())
            val drained = smallStore.drain()
            assertEquals(3, drained.size)
            // Should keep the three most recent: E2, E3, E4
            assertEquals("E2", drained[0].eventId)
            assertEquals("E3", drained[1].eventId)
            assertEquals("E4", drained[2].eventId)
        }
    }

    @Nested
    inner class Clear {
        @Test
        fun `clear empties the store`() {
            store.storeIfAbsent(EventPushEvent(roomId = "!r:h", eventId = "E1"))
            store.clear()
            assertEquals(0, store.size())
        }
    }
}
