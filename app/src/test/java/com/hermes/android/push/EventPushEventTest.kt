package com.hermes.android.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EventPushEventTest {

    @Nested
    inner class JsonRoundTrip {
        @Test
        fun `round trip preserves required fields`() {
            val original = EventPushEvent(
                roomId = "!room:example.org",
                eventId = "\$event:example.org"
            )
            val resurrected = EventPushEvent.fromJson(original.toJson())
            assertNotNull(resurrected)
            assertEquals(original.roomId, resurrected!!.roomId)
            assertEquals(original.eventId, resurrected.eventId)
        }

        @Test
        fun `round trip preserves optional fields`() {
            val original = EventPushEvent(
                roomId = "!room:example.org",
                eventId = "\$event:example.org",
                sender = "Alice",
                body = "Hello!",
                msgType = "m.text",
                threadRootId = "\$root:example.org"
            )
            val resurrected = EventPushEvent.fromJson(original.toJson())
            assertNotNull(resurrected)
            assertEquals("Alice", resurrected!!.sender)
            assertEquals("Hello!", resurrected.body)
            assertEquals("m.text", resurrected.msgType)
            assertEquals("\$root:example.org", resurrected.threadRootId)
        }

        @Test
        fun `blank optional fields are omitted from JSON`() {
            val event = EventPushEvent(roomId = "!r:h", eventId = "\$e:h")
            val json = event.toJson()
            assertFalse(json.contains("sender"))
            assertFalse(json.contains("body"))
        }

        @Test
        fun `malformed JSON returns null`() {
            assertNull(EventPushEvent.fromJson("not json"))
        }

        @Test
        fun `missing required fields returns null`() {
            val missingEventId = """{"roomId":"!r:h"}"""
            assertNull(EventPushEvent.fromJson(missingEventId))

            val missingRoomId = """{"eventId":"${'$'}e:h"}"""
            assertNull(EventPushEvent.fromJson(missingRoomId))
        }
    }

    @Nested
    inner class DedupKey {
        @Test
        fun `dedupKey is roomId colon eventId`() {
            val event = EventPushEvent(roomId = "!r:h", eventId = "\$e:h")
            assertEquals("!r:h:\$e:h", event.dedupKey)
        }
    }
}
