package com.hermes.android.domain.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class SessionTest {
    @Test
    fun `session data class holds values correctly`() {
        val session = Session(
            id = "test-id",
            title = "Test Session",
            lastMessage = "Hello",
            lastActivityTime = Instant.now(),
            replyCount = 5,
            unreadCount = 2,
            isProcessing = true,
            senderAvatarUrl = null,
            latestEventId = "evt-latest"
        )
        assertEquals("test-id", session.id)
        assertEquals("Test Session", session.title)
        assertTrue(session.isProcessing)
        assertEquals("evt-latest", session.latestEventId)
    }

    @Test
    fun `session latestEventId defaults to null`() {
        val session = Session(
            id = "root-id",
            title = "Default",
            lastMessage = null,
            lastActivityTime = Instant.now(),
            replyCount = 0,
            unreadCount = 0,
            isProcessing = false,
            senderAvatarUrl = null
        )
        assertNull(session.latestEventId)
    }

    @Test
    fun `message content sealed class variants work`() {
        val text = MessageContent.Text("<p>Hello</p>", "Hello")
        val image = MessageContent.Image("mxc://server/media", 1920, 1080)
        assertTrue(text is MessageContent.Text)
        assertTrue(image is MessageContent.Image)
    }
}
