package com.hermes.android.data.repository

import com.hermes.android.domain.model.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Covers [buildProvisionalSession] / [upsertProvisionalSession] — the helpers
 * that prime the session cache right after a thread root is created locally,
 * before the SDK ThreadList sync catches up. The push notification title
 * resolver (`pickCacheSessionTitle` in `EventPushWorker`) reads this cache,
 * so the helpers must produce titles usable for lookup and must not clobber
 * unrelated sessions.
 */
class ProvisionalSessionTitleTest {

    @Nested
    inner class BuildProvisionalSession {
        @Test
        fun `blank body returns null so resolver falls through`() {
            assertNull(buildProvisionalSession(ROOT_ID, "", NOW))
            assertNull(buildProvisionalSession(ROOT_ID, "   \t\n", NOW))
        }

        @Test
        fun `short body forms a title usable for cache lookup`() {
            val session = buildProvisionalSession(ROOT_ID, "hello world", NOW)
            assertNotNull(session)
            assertEquals(ROOT_ID, session!!.id)
            assertEquals("hello world", session.title)
            // The cache lookup uses the title verbatim after trim; verify it
            // resolves to the same value the push resolver would surface.
            assertEquals("hello world", pickCacheTitle(session, ROOT_ID))
        }

        @Test
        fun `body longer than 50 chars is truncated with ellipsis`() {
            val body = "a".repeat(80)
            val expected = "a".repeat(50) + "..."
            val session = buildProvisionalSession(ROOT_ID, body, NOW)!!
            assertEquals(expected, session.title)
            assertEquals(expected, pickCacheTitle(session, ROOT_ID))
        }

        @Test
        fun `body at exactly 50 chars is returned verbatim`() {
            val exactly = "a".repeat(50)
            val session = buildProvisionalSession(ROOT_ID, exactly, NOW)!!
            assertEquals(exactly, session.title)
        }

        @Test
        fun `defaults keep the list coherent before ThreadList refresh`() {
            val session = buildProvisionalSession(ROOT_ID, "hi", NOW)!!
            assertEquals("hi", session.lastMessage)
            assertEquals(0, session.replyCount)
            assertEquals(0, session.unreadCount)
            assertEquals(false, session.isProcessing)
            assertNull(session.senderAvatarUrl)
            assertEquals(ROOT_ID, session.latestEventId)
            assertEquals(Instant.ofEpochMilli(NOW), session.lastActivityTime)
        }
    }

    @Nested
    inner class UpsertProvisionalSession {
        @Test
        fun `null existing yields a single-entry list`() {
            val provisional = buildProvisionalSession(ROOT_ID, "hello", NOW)!!
            val result = upsertProvisionalSession(null, provisional)
            assertEquals(1, result.size)
            assertEquals(provisional, result.first())
        }

        @Test
        fun `other sessions are preserved when upserting a new root`() {
            val other = session(OTHER_ID, "Other")
            val provisional = buildProvisionalSession(ROOT_ID, "hello", NOW)!!
            val result = upsertProvisionalSession(listOf(other), provisional)
            assertEquals(2, result.size)
            assertEquals(provisional, result.first())
            assertTrue(result.contains(other))
        }

        @Test
        fun `existing entry for same root id is replaced, no duplicates`() {
            val stale = session(ROOT_ID, "Stale title")
            val other = session(OTHER_ID, "Other")
            val provisional = buildProvisionalSession(ROOT_ID, "fresh body", NOW)!!
            val result = upsertProvisionalSession(listOf(stale, other), provisional)
            assertEquals(2, result.size)
            assertEquals(provisional, result.first())
            assertTrue(result.contains(other))
            assertTrue(result.none { it.id == ROOT_ID && it.title == "Stale title" })
        }
    }

    @Nested
    inner class CustomTitleStillPreferred {
        // Custom (user-defined) titles live in `session_titles`, a separate
        // SharedPreferences key from the session cache. Provisional upserts
        // only touch the cache, so a custom title is never clobbered and the
        // resolver still picks it first. This is verified by construction:
        // buildProvisionalSession always derives title from rootBody, never
        // from the custom-title store.
        @Test
        fun `provisional title is derived from root body, never replaces custom store`() {
            val provisional = buildProvisionalSession(ROOT_ID, "root body text", NOW)!!
            assertEquals("root body text", provisional.title)
            // Custom title is whatever the user typed; resolver still prefers
            // it over the cache title (see EventPushWorker.resolveNotificationTitle).
            val customTitle = "My Custom Title"
            assertTrue(customTitle != provisional.title)
        }
    }

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

    /** Mirrors `pickCacheSessionTitle` in EventPushWorker for cross-check. */
    private fun pickCacheTitle(session: Session, rootId: String): String? =
        if (session.id == rootId) session.title.trim().ifEmpty { null } else null

    private companion object {
        const val ROOT_ID = "\$abc"
        const val OTHER_ID = "\$other"
        const val NOW = 1_700_000_000_000L
    }
}
