package com.hermes.android.data.repository

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Multi-room push-room-set tests for the real [SettingsRepositoryImpl].
 *
 * We exercise the live Impl: it is constructed with a [Context] whose
 * `getSharedPreferences` returns [InMemorySharedPreferences] (via a tiny
 * [ContextWrapper]), so the same read → mutate → write → emit path that runs
 * on-device runs here.
 *
 * No mocking framework is used — mockk's `JvmMockKGateway` fails to
 * initialize in this JVM, and [androidx.test.core.app.ApplicationProvider]
 * has no instrumentation context here. [ContextWrapper] is concrete (not
 * abstract like [Context]), so overriding a single method is enough.
 *
 * Serialization is deliberately not asserted via `org.json`: the android.jar
 * unit-test stub throws on `org.json` methods, so the Impl persists the push
 * set via the pure-Kotlin [encodePushRoomIds]/[decodePushRoomIds] helpers.
 */
class SettingsRepositoryImplTest {

    /**
     * Build a repo backed by [prefs]. Each call gets a fresh [InMemorySharedPreferences]
     * unless one is passed in, so tests are isolated by default.
     */
    private fun newRepo(prefs: InMemorySharedPreferences = InMemorySharedPreferences()): SettingsRepositoryImpl {
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        return SettingsRepositoryImpl(ctx)
    }

    @Test
    fun `push room ids read-write-observe are consistent after replace`() = runBlocking {
        val repo = newRepo()

        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))

        assertEquals(setOf(ROOM_A, ROOM_B), repo.getPushRoomIds())
        assertEquals(setOf(ROOM_A, ROOM_B), repo.observePushRoomIds().first())
    }

    @Test
    fun `setActiveRoom rejects a roomId outside the push set and keeps the prior value`() = runBlocking {
        val repo = newRepo()
        repo.replacePushRoomIds(setOf(ROOM_A))
        // Seed an active room so we can prove it is preserved.
        repo.saveBoundRoomId(ROOM_A)

        // ROOM_B is NOT in the push set → rejected, no-op.
        repo.setActiveRoom(ROOM_B)

        assertEquals(ROOM_A, repo.getBoundRoomId(), "prior active room must be preserved on reject")
        assertEquals(ROOM_A, repo.observeBoundRoom().first())
        assertFalse(repo.getPushRoomIds().contains(ROOM_B), "reject must not mutate the push set")
    }

    @Test
    fun `setActiveRoom accepts a roomId inside the push set and emits to observeBoundRoom`() = runBlocking {
        val repo = newRepo()
        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))

        repo.setActiveRoom(ROOM_B)

        assertEquals(ROOM_B, repo.getBoundRoomId())
        assertEquals(ROOM_B, repo.observeBoundRoom().first())
    }

    @Test
    fun `saveBoundRoomId forwards to setActiveRoom and rejects a roomId outside the push set`() = runBlocking {
        // saveBoundRoomId is the legacy UI entry point; it now delegates to
        // setActiveRoom so the push-set membership check is applied uniformly.
        // A roomId outside the push set must be rejected: the prior active
        // room is preserved and the call does not throw.
        val repo = newRepo()
        repo.replacePushRoomIds(setOf(ROOM_A))
        repo.saveBoundRoomId(ROOM_A) // seed a recognized active room

        // ROOM_B is NOT in the push set → rejected, no-op, no throw.
        repo.saveBoundRoomId(ROOM_B)

        assertEquals(ROOM_A, repo.getBoundRoomId(), "prior active room must be preserved on reject")
        assertEquals(ROOM_A, repo.observeBoundRoom().first())
    }

    @Test
    fun `saveBoundRoomId accepts a roomId inside the push set and persists it`() = runBlocking {
        val repo = newRepo()
        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))

        repo.saveBoundRoomId(ROOM_B)

        assertEquals(ROOM_B, repo.getBoundRoomId())
        assertEquals(ROOM_B, repo.observeBoundRoom().first())
    }

    @Test
    fun `resolveActiveRoomId prefers bound room when bound is in the push set`() = runBlocking {
        val repo = newRepo()
        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))
        repo.saveBoundRoomId(ROOM_B)

        assertEquals(ROOM_B, repo.resolveActiveRoomId())
    }

    @Test
    fun `resolveActiveRoomId falls back to push set first when bound is not in the set`() = runBlocking {
        val repo = newRepo()
        // Legacy bound_room_id left over from before multi-room, NOT in set.
        repo.saveBoundRoomId(ROOM_C)
        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))

        // bound not in set → fall back to first push element.
        assertEquals(ROOM_A, repo.resolveActiveRoomId())
    }

    @Test
    fun `resolveActiveRoomId returns null when push set is empty`() = runBlocking {
        val repo = newRepo()
        // bound_room_id may still be present on disk but is unrecognized.
        repo.saveBoundRoomId(ROOM_A)

        assertNull(repo.resolveActiveRoomId())
    }

    @Test
    fun `replacePushRoomIds emits the latest value on repeated writes`() = runBlocking {
        val repo = newRepo()

        repo.replacePushRoomIds(setOf(ROOM_A))
        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))
        repo.replacePushRoomIds(setOf(ROOM_B))

        // The flow's current value is the most recent write.
        assertEquals(setOf(ROOM_B), repo.observePushRoomIds().first())
        assertEquals(setOf(ROOM_B), repo.getPushRoomIds())
    }

    @Test
    fun `replacePushRoomIds with empty set removes the prefs key and stays fail-open`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val repo = newRepo(prefs)

        repo.replacePushRoomIds(setOf(ROOM_A))
        assertTrue(prefs.contains(PUSH_ROOM_IDS_KEY), "key should be present after non-empty write")

        repo.replacePushRoomIds(emptySet())

        assertFalse(prefs.contains(PUSH_ROOM_IDS_KEY), "empty set must delete the key")
        assertTrue(repo.getPushRoomIds().isEmpty())
        assertTrue(repo.observePushRoomIds().first().isEmpty())
    }

    @Test
    fun `a freshly constructed repo loads the push set previously persisted to prefs`() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val first = newRepo(prefs)
        first.replacePushRoomIds(setOf(ROOM_A, ROOM_B))

        // Simulate a process restart: new Impl instance, same prefs backing store.
        val restarted = newRepo(prefs)
        assertEquals(setOf(ROOM_A, ROOM_B), restarted.getPushRoomIds())
    }

    @Test
    fun `setActiveRoom accept then unselect keeps active in prefs but resolve falls back`() = runBlocking {
        // Active is strictly inside the push set: if the user later unchecks the
        // active room, bound_room_id stays on disk but resolveActiveRoomId no
        // longer recognizes it and falls back to the remaining push element.
        val repo = newRepo()
        repo.replacePushRoomIds(setOf(ROOM_A, ROOM_B))
        repo.setActiveRoom(ROOM_A)

        // User unchecks ROOM_A.
        repo.replacePushRoomIds(setOf(ROOM_B))

        assertEquals(ROOM_A, repo.getBoundRoomId(), "bound_room_id is retained on disk")
        assertEquals(ROOM_B, repo.resolveActiveRoomId(), "resolve must not return an unrecognized bound")
    }

    @Test
    fun `encoder round-trips the push set to a JSON array string`() {
        // The persisted wire format is a JSON array of quoted room ids, so a
        // future reader (or another platform) can consume it without a bespoke
        // parser. Pure-Kotlin encoder/decoder — org.json throws in the test JVM.
        val encoded = encodePushRoomIds(linkedSetOf(ROOM_A, ROOM_B, ROOM_C))
        assertEquals("[\"!a:server\",\"!b:server\",\"!c:server\"]", encoded)
        assertEquals(linkedSetOf(ROOM_A, ROOM_B, ROOM_C), decodePushRoomIds(encoded))
    }

    @Test
    fun `decoder is fail-open on corrupt input`() {
        // A corrupt key must never crash the app — degrade to empty set.
        assertTrue(decodePushRoomIds("not json").isEmpty())
        assertTrue(decodePushRoomIds("[corrupt,").isEmpty())
        assertTrue(decodePushRoomIds(null).isEmpty())
        assertTrue(decodePushRoomIds("").isEmpty())
    }

    private companion object {
        const val ROOM_A = "!a:server"
        const val ROOM_B = "!b:server"
        const val ROOM_C = "!c:server"
    }
}
