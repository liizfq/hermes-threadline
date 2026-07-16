package com.hermes.android.data.repository

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [RoomSessionListSlot] — the lifecycle decision logic
 * extracted from [RoomSessionListStore]. Covers:
 *  - same-room ensureStarted → NoOp
 *  - switch to a different room → Switch(fromRoomId)
 *  - first start → Start
 *  - lifecycle after close(): same-room ensureStarted is no longer NoOp
 *
 * No SDK, no coroutines, no Android — verifies the same invariants the store
 * relies on (no collector refCount, switch closes old, restart after close).
 */
class RoomSessionListSlotTest {

    @Nested
    inner class FreshSlot {
        @Test
        fun `empty slot reports null current room and closed`() {
            val slot = RoomSessionListSlot()
            assertNull(slot.currentRoomId)
            assertTrue(slot.closed)
        }

        @Test
        fun `decide on empty slot returns Start`() {
            val slot = RoomSessionListSlot()
            assertEquals(StartDecision.Start, slot.decide(ROOM_A))
        }
    }

    @Nested
    inner class SameRoomNoOp {
        @Test
        fun `decide on same room after occupy returns NoOp`() {
            val slot = RoomSessionListSlot().apply { occupy(ROOM_A) }
            assertEquals(StartDecision.NoOp, slot.decide(ROOM_A))
        }

        @Test
        fun `occupy clears the closed flag`() {
            val slot = RoomSessionListSlot().apply {
                occupy(ROOM_A)
                close()
            }
            assertTrue(slot.closed)
            slot.occupy(ROOM_A)
            assertFalse(slot.closed)
        }
    }

    @Nested
    inner class SwitchDetection {
        @Test
        fun `decide for a different active room returns Switch with prior id`() {
            val slot = RoomSessionListSlot().apply { occupy(ROOM_A) }
            val decision = slot.decide(ROOM_B)
            assertTrue(decision is StartDecision.Switch)
            assertEquals(ROOM_A, (decision as StartDecision.Switch).fromRoomId)
        }
    }

    @Nested
    inner class AfterClose {
        @Test
        fun `close marks the slot closed`() {
            val slot = RoomSessionListSlot().apply {
                occupy(ROOM_A)
                close()
            }
            assertTrue(slot.closed)
        }

        @Test
        fun `decide after close returns Start even for the same room`() {
            // Closed instances must NOT short-circuit ensureStarted — the store
            // needs to rebuild from scratch. The NoOp path is only valid for an
            // occupied, live slot.
            val slot = RoomSessionListSlot().apply {
                occupy(ROOM_A)
                close()
            }
            assertEquals(StartDecision.Start, slot.decide(ROOM_A))
        }

        @Test
        fun `occupy after close revives the slot for same room`() {
            val slot = RoomSessionListSlot().apply {
                occupy(ROOM_A)
                close()
                occupy(ROOM_A)
            }
            assertEquals(ROOM_A, slot.currentRoomId)
            assertFalse(slot.closed)
            assertEquals(StartDecision.NoOp, slot.decide(ROOM_A))
        }

        @Test
        fun `occupy after close revives the slot for a new room`() {
            val slot = RoomSessionListSlot().apply {
                occupy(ROOM_A)
                close()
                occupy(ROOM_B)
            }
            assertEquals(ROOM_B, slot.currentRoomId)
            assertFalse(slot.closed)
            assertEquals(StartDecision.NoOp, slot.decide(ROOM_B))
        }
    }

    private companion object {
        const val ROOM_A = "!roomA:server"
        const val ROOM_B = "!roomB:server"
    }
}
