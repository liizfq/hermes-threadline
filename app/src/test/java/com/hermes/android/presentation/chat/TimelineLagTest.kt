package com.hermes.android.presentation.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TimelineLagTest {
    private val listId = "evt-list-latest"
    private val threadId = "evt-thread-last"
    private val olderInChat = "evt-older"

    @Nested
    inner class TwoArgLegacy {
        @Test
        fun `both null returns null`() {
            assertNull(computeTimelineLag(null, null))
        }

        @Test
        fun `only listLatest returns null`() {
            assertNull(computeTimelineLag(listId, null))
        }

        @Test
        fun `only threadLast returns null`() {
            assertNull(computeTimelineLag(null, threadId))
        }

        @Test
        fun `empty string listLatest returns null`() {
            assertNull(computeTimelineLag("", threadId))
        }

        @Test
        fun `empty string threadLast returns null`() {
            assertNull(computeTimelineLag(listId, ""))
        }

        @Test
        fun `equal ids returns null`() {
            assertNull(computeTimelineLag(listId, listId))
        }

        @Test
        fun `unequal ids without membership returns lag with null direction`() {
            val lag = computeTimelineLag(listId, threadId)
            assertNotNull(lag)
            assertEquals(listId, lag!!.listLatestEventId)
            assertEquals(threadId, lag.threadLastEventId)
            assertNull(lag.direction, "without membership we cannot tell who is newer")
        }

        @Test
        fun `lag toString contains ids for logging`() {
            val lag = computeTimelineLag(listId, threadId)!!
            val s = lag.toString()
            assertTrue(s.contains(listId))
            assertTrue(s.contains(threadId))
        }
    }

    @Nested
    inner class DirectionFromMembership {
        @Test
        fun `listLatest not in chat means ChatBehind`() {
            val lag = computeTimelineLag(
                listLatestEventId = listId,
                threadLastEventId = threadId,
                threadMessageIds = listOf(threadId, olderInChat),
            )
            assertNotNull(lag)
            assertEquals(LagDirection.ChatBehind, lag!!.direction)
        }

        @Test
        fun `listLatest in chat but not last means SessionListBehind`() {
            // Chat has progressed past ThreadList's idea of latest.
            val lag = computeTimelineLag(
                listLatestEventId = olderInChat,
                threadLastEventId = threadId,
                threadMessageIds = listOf(olderInChat, threadId),
            )
            assertNotNull(lag)
            assertEquals(LagDirection.SessionListBehind, lag!!.direction)
            assertEquals(olderInChat, lag.listLatestEventId)
            assertEquals(threadId, lag.threadLastEventId)
        }

        @Test
        fun `listLatest equals threadLast still null even if membership true`() {
            assertNull(
                computeTimelineLag(
                    listLatestEventId = threadId,
                    threadLastEventId = threadId,
                    threadMessageIds = listOf(threadId),
                )
            )
        }

        @Test
        fun `boolean membership overload ChatBehind`() {
            val lag = computeTimelineLag(listId, threadId, listLatestInThreadMessages = false)
            assertEquals(LagDirection.ChatBehind, lag!!.direction)
        }

        @Test
        fun `boolean membership overload SessionListBehind`() {
            val lag = computeTimelineLag(listId, threadId, listLatestInThreadMessages = true)
            assertEquals(LagDirection.SessionListBehind, lag!!.direction)
        }
    }
}
