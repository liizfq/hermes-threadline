package com.hermes.android.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [stableSessionNotificationId]. The hash must stay
 * stable across app versions so that cancel() from ChatViewModel hits the
 * same id that EventPushWorker used when posting.
 */
class StableSessionNotificationIdTest {

    @Test
    fun `same room and root produce the same id`() {
        val a = stableSessionNotificationId(ROOM_A, ROOT_1)
        val b = stableSessionNotificationId(ROOM_A, ROOT_1)
        assertEquals(a, b)
    }

    @Test
    fun `different roots produce different ids`() {
        val a = stableSessionNotificationId(ROOM_A, ROOT_1)
        val b = stableSessionNotificationId(ROOM_A, ROOT_2)
        assertNotEquals(a, b)
    }

    @Test
    fun `different rooms produce different ids for same root`() {
        val a = stableSessionNotificationId(ROOM_A, ROOT_1)
        val b = stableSessionNotificationId(ROOM_B, ROOT_1)
        assertNotEquals(a, b)
    }

    @Test
    fun `id is non-negative and fits in 31 bits`() {
        val id = stableSessionNotificationId(ROOM_A, ROOT_1)
        assertTrue(id >= 0)
        assertTrue(id <= Int.MAX_VALUE)
    }

    @Test
    fun `matches historical EventPushWorker hash algorithm`() {
        // Independent reimplementation of the previous private helper so a
        // future refactor of stableSessionNotificationId cannot silently
        // change cancel targets for already-posted notifications.
        fun legacy(roomId: String, threadRootId: String): Int {
            var hash = 17
            for (ch in "$roomId:$threadRootId") hash = hash * 31 + ch.code
            return hash and 0x7FFFFFFF
        }
        assertEquals(legacy(ROOM_A, ROOT_1), stableSessionNotificationId(ROOM_A, ROOT_1))
        assertEquals(legacy(ROOM_B, ROOT_2), stableSessionNotificationId(ROOM_B, ROOT_2))
    }

    private companion object {
        const val ROOM_A = "!roomA:server"
        const val ROOM_B = "!roomB:server"
        const val ROOT_1 = "\$root1:event"
        const val ROOT_2 = "\$root2:event"
    }
}
