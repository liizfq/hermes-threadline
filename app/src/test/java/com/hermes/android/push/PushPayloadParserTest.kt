package com.hermes.android.push

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PushPayloadParserTest {

    @Nested
    inner class ThreadRelationExtraction {
        @Test
        fun `thread relation uses root event id`() {
            val r = resolveThreadRoot("\$child123", "m.thread", "\$rootId456")
            assertEquals("\$rootId456", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }

        @Test
        fun `empty thread event id falls back to notification event_id`() {
            val r = resolveThreadRoot("\$fallbackEvent", "m.thread", "")
            assertEquals("\$fallbackEvent", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }

        @Test
        fun `non-thread relation uses event_id`() {
            val r = resolveThreadRoot("\$regularEvent", "m.reference", "\$someOther")
            assertEquals("\$regularEvent", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }

        @Test
        fun `no thread relation uses event_id`() {
            val r = resolveThreadRoot("\$plain", "", "")
            assertEquals("\$plain", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }
    }

    @Nested
    inner class ReplaceRelationExtraction {
        @Test
        fun `replace relation returns replaced event id as provisional thread root`() {
            val r = resolveThreadRoot("\$editEvt", "m.replace", "\$origEvt")
            assertEquals("\$origEvt", r.threadRootId)
            assertTrue(r.isReplaceTarget, "m.replace must mark threadRootId as provisional")
        }

        @Test
        fun `replace relation with blank target falls back to notification event id`() {
            val r = resolveThreadRoot("\$editEvt", "m.replace", "")
            assertEquals("\$editEvt", r.threadRootId)
            assertFalse(r.isReplaceTarget, "no target → no provisional lookup needed")
        }
    }

    @Nested
    inner class SpecialCharacters {
        @Test
        fun `matrix event id with special chars routes unchanged`() {
            val r = resolveThreadRoot(
                "\$e3Flx+G8/abc:matrix.org",
                "m.thread",
                "\$root/abc:def.matrix.org"
            )
            assertEquals("\$root/abc:def.matrix.org", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `blank relType returns notification event id`() {
            val r = resolveThreadRoot("\$onlyEvent", "", "\$root")
            assertEquals("\$onlyEvent", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }

        @Test
        fun `thread relation takes root even if notification event is blank`() {
            val r = resolveThreadRoot("", "m.thread", "\$onlyRoot")
            assertEquals("\$onlyRoot", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }

        @Test
        fun `blank root falls back to notification event`() {
            val r = resolveThreadRoot("\$notificationEvt", "m.thread", "")
            assertEquals("\$notificationEvt", r.threadRootId)
            assertFalse(r.isReplaceTarget)
        }
    }
}
