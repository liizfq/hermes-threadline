package com.hermes.android.push

import com.hermes.android.domain.model.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class FormatSessionTitleTest {

    @Nested
    inner class BlankInputs {
        @Test
        fun `null body falls back to Session`() {
            assertEquals("Session", formatSessionTitle(null))
        }

        @Test
        fun `empty body falls back to Session`() {
            assertEquals("Session", formatSessionTitle(""))
        }

        @Test
        fun `whitespace-only body falls back to Session`() {
            assertEquals("Session", formatSessionTitle("   \t\n"))
        }
    }

    @Nested
    inner class ShortInputs {
        @Test
        fun `body shorter than max is returned verbatim`() {
            assertEquals("hi", formatSessionTitle("hi"))
        }

        @Test
        fun `body at exactly max boundary is returned verbatim`() {
            val exactly = "a".repeat(50)
            assertEquals(50, exactly.length)
            assertEquals(exactly, formatSessionTitle(exactly))
        }
    }

    @Nested
    inner class LongInputs {
        @Test
        fun `body one char over max is truncated to max plus ellipsis`() {
            val body = "a".repeat(51)
            val expected = "a".repeat(50) + "..."
            assertEquals(expected, formatSessionTitle(body))
        }

        @Test
        fun `very long body is truncated to max plus ellipsis`() {
            val body = "b".repeat(500)
            val expected = "b".repeat(50) + "..."
            assertEquals(expected, formatSessionTitle(body))
            assertEquals(53, formatSessionTitle(body).length)
        }

        @Test
        fun `truncation preserves the first 50 chars in order`() {
            val body = "0123456789".repeat(10) + "tail"
            val expected = "0123456789".repeat(5) + "..."
            assertEquals(expected, formatSessionTitle(body))
        }
    }

    @Nested
    inner class CacheSessionTitle {
        private fun session(id: String, title: String) = Session(
            id = id,
            title = title,
            lastMessage = null,
            lastActivityTime = Instant.EPOCH,
            replyCount = 0,
            unreadCount = 0,
            isProcessing = false,
            senderAvatarUrl = null,
            latestEventId = null,
        )

        @Test
        fun `null cache returns null`() {
            assertNull(pickCacheSessionTitle(null, ROOT_ID))
        }

        @Test
        fun `empty cache returns null`() {
            assertNull(pickCacheSessionTitle(emptyList(), ROOT_ID))
        }

        @Test
        fun `matching id returns the cached title`() {
            val sessions = listOf(
                session(OTHER_ID, "Other"),
                session(ROOT_ID, "Hello world"),
            )
            assertEquals("Hello world", pickCacheSessionTitle(sessions, ROOT_ID))
        }

        @Test
        fun `non-matching id returns null`() {
            val sessions = listOf(session(OTHER_ID, "Other"))
            assertNull(pickCacheSessionTitle(sessions, ROOT_ID))
        }

        @Test
        fun `blank cached title is treated as absent`() {
            val sessions = listOf(session(ROOT_ID, "   "))
            assertNull(pickCacheSessionTitle(sessions, ROOT_ID))
        }

        @Test
        fun `empty cached title is treated as absent`() {
            val sessions = listOf(session(ROOT_ID, ""))
            assertNull(pickCacheSessionTitle(sessions, ROOT_ID))
        }

        @Test
        fun `surrounding whitespace is trimmed`() {
            val sessions = listOf(session(ROOT_ID, "\t  Hello  \n"))
            assertEquals("Hello", pickCacheSessionTitle(sessions, ROOT_ID))
        }

        @Test
        fun `first matching entry wins when duplicates exist`() {
            val sessions = listOf(
                session(ROOT_ID, "First"),
                session(ROOT_ID, "Second"),
            )
            assertEquals("First", pickCacheSessionTitle(sessions, ROOT_ID))
        }
    }
}

private const val ROOT_ID = "\$abc"
private const val OTHER_ID = "\$other"
