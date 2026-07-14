package com.hermes.android.presentation.chat

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimelineLagTest {
    private val listId = "evt-list-latest"
    private val threadId = "evt-thread-last"

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
    fun `unequal ids returns lag`() {
        val lag = computeTimelineLag(listId, threadId)
        assertNotNull(lag)
        assertEquals(listId, lag!!.listLatestEventId)
        assertEquals(threadId, lag.threadLastEventId)
    }

    @Test
    fun `lag toString contains ids for logging`() {
        val lag = computeTimelineLag(listId, threadId)!!
        val s = lag.toString()
        assertTrue(s.contains(listId))
        assertTrue(s.contains(threadId))
    }
}
