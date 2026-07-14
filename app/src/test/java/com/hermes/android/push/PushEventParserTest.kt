package com.hermes.android.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PushEventParserTest {

    private val parser = PushEventParser()

    private fun payload(
        eventId: String? = "\$ev:matrix.org",
        roomId: String = "!room:matrix.org",
        type: String = "m.room.message",
        sender: String = "Alice",
        senderDisplayName: String = "Alice D",
        body: String = "Hello world",
        msgType: String? = "m.text",
        roomName: String = "Room A",
        threadRootId: String? = null,
        unread: Int = 1
    ): ByteArray {
        val content = if (msgType != null || threadRootId != null || body.isNotEmpty()) {
            val c = org.json.JSONObject()
            msgType?.let { c.put("msgtype", it) }
            c.put("body", body)
            if (threadRootId != null) {
                val rel = org.json.JSONObject().apply {
                    put("rel_type", "m.thread")
                    put("event_id", threadRootId)
                }
                c.put("m.relates_to", rel)
            }
            c.toString()
        } else {
            ""
        }
        val notification = org.json.JSONObject().apply {
            put("event_id", eventId)
            put("room_id", roomId)
            put("type", type)
            put("sender", sender)
            put("sender_display_name", senderDisplayName)
            put("room_name", roomName)
            if (content.isNotEmpty()) put("content", org.json.JSONObject(content))
            put("counts", org.json.JSONObject().apply { put("unread", unread) })
        }
        val root = org.json.JSONObject().apply {
            put("notification", notification)
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    @Nested
    inner class HappyPath {
        @Test
        fun `returns event for valid message`() {
            val bytes = payload()
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
            assertEquals("!room:matrix.org", event!!.roomId)
            assertEquals("\$ev:matrix.org", event.eventId)
            assertEquals("Alice D", event.sender)
            assertEquals("Hello world", event.body)
            assertEquals("m.text", event.msgType)
        }

        @Test
        fun `extracts thread root when present`() {
            val bytes = payload(threadRootId = "\$root:matrix.org")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
            assertEquals("\$root:matrix.org", event!!.threadRootId)
        }
    }

    @Nested
    inner class ForegroundFilter {
        @Test
        fun `returns null when foreground`() {
            val bytes = payload()
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = true, timeoutMinutes = 5)
            assertNull(event)
        }
    }

    @Nested
    inner class BoundRoomFilter {
        @Test
        fun `passes when boundRoomId matches`() {
            val bytes = payload(roomId = "!bound:matrix.org")
            val event = parser.parseAndFilter(bytes, boundRoomId = "!bound:matrix.org", isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
        }

        @Test
        fun `rejects when boundRoomId differs`() {
            val bytes = payload(roomId = "!other:matrix.org")
            val event = parser.parseAndFilter(bytes, boundRoomId = "!bound:matrix.org", isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `passes when boundRoomId is null (no binding)`() {
            val bytes = payload(roomId = "!any:matrix.org")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
        }
    }

    @Nested
    inner class TypeFilter {
        @Test
        fun `rejects blank eventId`() {
            val bytes = payload(eventId = "")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `rejects non-m-room-message type`() {
            val bytes = payload(type = "m.room.member")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }
    }

    @Nested
    inner class ContentFilter {
        @Test
        fun `skips tool-call status messages`() {
            listOf(
                "💻 Running command",
                "🐍 Python execution",
                "📖 Reading file",
                "🔀 Switching branch",
                "```Code",
                "[Background process proc"
            ).forEach { prefix ->
                val bytes = payload(body = prefix)
                val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
                assertNull(event, "Expected null for prefix: $prefix")
            }
        }

        @Test
        fun `skips working message below threshold`() {
            val bytes = payload(body = "⏳ Working … 2 min — still running")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `allows working message at or above threshold`() {
            val bytes = payload(body = "⏳ Working … 5 min — still running")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `returns null on malformed JSON`() {
            val bytes = "not json".toByteArray()
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `returns null when notification key is missing`() {
            val bytes = """{"foo":"bar"}""".toByteArray()
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }
    }

    @Nested
    inner class EventIdOnly {
        private fun eventIdOnlyPayload(
            eventId: String = "\$ev_only:matrix.org",
            roomId: String = "!room:matrix.org",
            sender: String = "Bob",
            senderDisplayName: String? = null,
            threadRootId: String? = null,
            includeContent: Boolean = false,
            includeType: Boolean = false
        ): ByteArray {
            val notification = org.json.JSONObject().apply {
                put("event_id", eventId)
                put("room_id", roomId)
                put("sender", sender)
                if (senderDisplayName != null) put("sender_display_name", senderDisplayName)
                if (includeType) put("type", "m.room.message")
                if (includeContent) {
                    val content = org.json.JSONObject().apply {
                        put("msgtype", "m.text")
                        put("body", "Hello from SDK")
                        if (threadRootId != null) {
                            put("m.relates_to", org.json.JSONObject().apply {
                                put("rel_type", "m.thread")
                                put("event_id", threadRootId)
                            })
                        }
                    }
                    put("content", content)
                }
            }
            val root = org.json.JSONObject().apply { put("notification", notification) }
            return root.toString().toByteArray(Charsets.UTF_8)
        }

        @Test
        fun `accepts payload with only event_id and room_id`() {
            val bytes = eventIdOnlyPayload()
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
            assertEquals("!room:matrix.org", event!!.roomId)
            assertEquals("\$ev_only:matrix.org", event.eventId)
            assertEquals("Bob", event.sender)
            assertNull(event.body)
            assertNull(event.msgType)
        }

        @Test
        fun `prefers sender_display_name over sender`() {
            val bytes = eventIdOnlyPayload(senderDisplayName: "Bob Display")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
            assertEquals("Bob Display", event!!.sender)
        }

        @Test
        fun `does not require type field`() {
            val bytes = eventIdOnlyPayload(includeType = false)
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
            assertEquals("\$ev_only:matrix.org", event!!.eventId)
        }

        @Test
        fun `does not require content field`() {
            val bytes = eventIdOnlyPayload(includeContent = false)
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
            assertNull(event!!.body)
            assertNull(event.msgType)
        }

        @Test
        fun `still filters by bound room`() {
            val bytes = eventIdOnlyPayload(roomId = "!other:matrix.org")
            val event = parser.parseAndFilter(bytes, boundRoomId = "!room:matrix.org", isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `still filters when foreground`() {
            val bytes = eventIdOnlyPayload()
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = true, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `rejects blank event_id`() {
            val bytes = eventIdOnlyPayload(eventId = "")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `rejects blank room_id`() {
            val bytes = eventIdOnlyPayload(roomId = "")
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNull(event)
        }

        @Test
        fun `rejects explicit non-message type even when event_id only`() {
            val notification = org.json.JSONObject().apply {
                put("event_id", "\$x:h")
                put("room_id", "!r:h")
                put("type", "m.room.member")
            }
            val root = org.json.JSONObject().apply { put("notification", notification) }
            val event = parser.parseAndFilter(
                root.toString().toByteArray(), boundRoomId = null, isForeground = false, timeoutMinutes = 5
            )
            assertNull(event)
        }

        @Test
        fun `handles explicit m-room-message type`() {
            val bytes = eventIdOnlyPayload(includeType = true)
            val event = parser.parseAndFilter(bytes, boundRoomId = null, isForeground = false, timeoutMinutes = 5)
            assertNotNull(event)
        }
    }
}
