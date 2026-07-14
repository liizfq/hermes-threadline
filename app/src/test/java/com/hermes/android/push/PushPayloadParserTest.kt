package com.hermes.android.push

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PushPayloadParserTest {

    @Nested
    inner class ThreadRelationExtraction {
        @Test
        fun `thread relation uses root event id`() {
            assertEquals(
                "\$rootId456",
                resolveThreadRoot("\$child123", "m.thread", "\$rootId456")
            )
        }

        @Test
        fun `empty thread event id falls back to notification event_id`() {
            assertEquals(
                "\$fallbackEvent",
                resolveThreadRoot("\$fallbackEvent", "m.thread", "")
            )
        }

        @Test
        fun `non-thread relation uses event_id`() {
            assertEquals(
                "\$regularEvent",
                resolveThreadRoot("\$regularEvent", "m.reference", "\$someOther")
            )
        }

        @Test
        fun `no thread relation uses event_id`() {
            assertEquals(
                "\$plain",
                resolveThreadRoot("\$plain", "", "")
            )
        }
    }

    @Nested
    inner class SpecialCharacters {
        @Test
        fun `matrix event id with special chars routes unchanged`() {
            assertEquals(
                "\$root/abc:def.matrix.org",
                resolveThreadRoot(
                    "\$e3Flx+G8/abc:matrix.org",
                    "m.thread",
                    "\$root/abc:def.matrix.org"
                )
            )
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `blank relType returns notification event id`() {
            assertEquals(
                "\$onlyEvent",
                resolveThreadRoot("\$onlyEvent", "", "\$root")
            )
        }

        @Test
        fun `thread relation takes root even if notification event is blank`() {
            assertEquals(
                "\$onlyRoot",
                resolveThreadRoot("", "m.thread", "\$onlyRoot")
            )
        }

        @Test
        fun `blank root falls back to notification event`() {
            assertEquals(
                "\$notificationEvt",
                resolveThreadRoot("\$notificationEvt", "m.thread", "")
            )
        }
    }
}
