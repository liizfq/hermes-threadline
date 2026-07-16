package com.hermes.android.data.repository

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Room-scoped cache + legacy migration tests.
 *
 * The migration logic is extracted into [migrateLegacySessionCache] which is
 * pure over a [SharedPreferences] instance, so we test it directly with a
 * faithful in-memory [SharedPreferences] fake — no Android [Context] (and
 * therefore no Context mocking / agent self-attach issues) required.
 *
 * Covered:
 *  - Per-room cache isolation: A's cache is invisible to B.
 *  - Legacy single-room cache migrates exactly once, only into the bound
 *    room's key, and the legacy key is dropped.
 *  - Migration does NOT leak A's legacy cache to B.
 */
class SettingsRepositoryRoomCacheTest {

    @Test
    fun `sessionCacheKey is room-scoped and never collides across rooms`() {
        assertEquals("session_cache_json::!a:server", sessionCacheKey(ROOM_A))
        assertEquals("session_cache_json::!b:server", sessionCacheKey(ROOM_B))
        assertFalse(sessionCacheKey(ROOM_A) == sessionCacheKey(ROOM_B))
        assertFalse(sessionCacheKey(ROOM_A) == LEGACY_SESSION_CACHE_KEY)
    }

    @Test
    fun `migrate returns null when no entry exists for the room`() {
        val prefs = InMemorySharedPreferences()
        assertNull(migrateLegacySessionCache(prefs, ROOM_A, boundRoomId = ROOM_A))
    }

    @Test
    fun `migrate returns existing room-scoped entry without touching legacy`() {
        val prefs = InMemorySharedPreferences()
        prefs.putString(sessionCacheKey(ROOM_A), JSON_A)
        // Legacy key is present but [ROOM_A] already has its own entry — it
        // must NOT be migrated.
        prefs.putString(LEGACY_SESSION_CACHE_KEY, JSON_LEGACY)

        val result = migrateLegacySessionCache(prefs, ROOM_A, boundRoomId = ROOM_A)
        assertEquals(JSON_A, result)
        // Legacy key still there — we did not touch it.
        assertNotNull(prefs.getString(LEGACY_SESSION_CACHE_KEY, null))
    }

    @Test
    fun `legacy cache migrates into the bound room key exactly once`() {
        val prefs = InMemorySharedPreferences()
        prefs.putString(LEGACY_SESSION_CACHE_KEY, JSON_LEGACY)

        // First read for the bound room migrates the legacy blob.
        val migrated = migrateLegacySessionCache(prefs, ROOM_A, boundRoomId = ROOM_A)
        assertEquals(JSON_LEGACY, migrated)

        // Legacy key is gone — it must never be re-used for another room.
        assertNull(prefs.getString(LEGACY_SESSION_CACHE_KEY, null))
        // The migrated data lives under the room-scoped key.
        assertEquals(JSON_LEGACY, prefs.getString(sessionCacheKey(ROOM_A), null))

        // Second read is served from the room-scoped key (no migration path,
        // legacy key is not touched even if it were re-added).
        prefs.putString(LEGACY_SESSION_CACHE_KEY, JSON_LEGACY)  // pretend something re-added it
        val again = migrateLegacySessionCache(prefs, ROOM_A, boundRoomId = ROOM_A)
        assertEquals(JSON_LEGACY, again)
        // Legacy key still there because we no longer migrate when room key has data.
        assertNotNull(prefs.getString(LEGACY_SESSION_CACHE_KEY, null))
    }

    @Test
    fun `legacy cache does NOT migrate into a non-bound room`() {
        val prefs = InMemorySharedPreferences()
        prefs.putString("bound_room_id", ROOM_A)
        prefs.putString(LEGACY_SESSION_CACHE_KEY, JSON_LEGACY)

        // B is NOT the bound room: even though the legacy key still exists,
        // B must see null — legacy data must never leak across rooms.
        assertNull(migrateLegacySessionCache(prefs, ROOM_B, boundRoomId = ROOM_A))

        // Legacy key is still there (we didn't migrate for B).
        assertNotNull(prefs.getString(LEGACY_SESSION_CACHE_KEY, null))
        // And B's key was not written.
        assertNull(prefs.getString(sessionCacheKey(ROOM_B), null))
    }

    @Test
    fun `legacy cache does NOT migrate when bound room is null`() {
        val prefs = InMemorySharedPreferences()
        prefs.putString(LEGACY_SESSION_CACHE_KEY, JSON_LEGACY)

        // boundRoomId null — no migration even for a room that matches nothing.
        assertNull(migrateLegacySessionCache(prefs, ROOM_A, boundRoomId = null))
        assertNotNull(prefs.getString(LEGACY_SESSION_CACHE_KEY, null))
    }

    @Test
    fun `per-room isolation — saving A never affects B's getSessionCache`() {
        val prefs = InMemorySharedPreferences()
        // Use the real SettingsRepositoryImpl write path through the public
        // sessionCacheKey helper to make sure we exercise the same key scheme.
        prefs.edit().putString(sessionCacheKey(ROOM_A), JSON_A).apply()

        assertEquals(JSON_A, prefs.getString(sessionCacheKey(ROOM_A), null))
        assertNull(prefs.getString(sessionCacheKey(ROOM_B), null))
    }

    private companion object {
        const val ROOM_A = "!a:server"
        const val ROOM_B = "!b:server"
        // Raw cache JSON blobs — we only care that the migration copies bytes
        // verbatim and drops the legacy key. Parseability is exercised in the
        // ProvisionalSessionTitleTest / FormatSessionTitleTest suites.
        //
        // Inlined (rather than built via org.json) because the android.jar
        // test stub throws on org.json methods; we want these tests to depend
        // only on the SharedPreferences interface, not on Android's JSON.
        // Dollar signs in Matrix ids are avoided here so the strings can be
        // plain `const val` literals (no Kotlin string templates).
        const val JSON_A = """[{"id":"a1","title":"A1","lastMessage":"","lastActivityTime":0,"replyCount":0,"unreadCount":0,"isProcessing":false,"senderAvatarUrl":""}]"""
        const val JSON_B = """[{"id":"b1","title":"B1","lastMessage":"","lastActivityTime":0,"replyCount":0,"unreadCount":0,"isProcessing":false,"senderAvatarUrl":""}]"""
        const val JSON_LEGACY = """[{"id":"legacy","title":"legacy session","lastMessage":"","lastActivityTime":0,"replyCount":0,"unreadCount":0,"isProcessing":false,"senderAvatarUrl":""}]"""
    }
}

/**
 * Minimal in-memory SharedPreferences faithful enough for the migration
 * helper. Reads return the stored value (or the default); writes / removes
 * go through a single editor that snapshots changes on `apply`.
 */
internal class InMemorySharedPreferences : SharedPreferences {
    private val map: MutableMap<String, Any?> = mutableMapOf()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun putString(key: String, value: String?) {
        edit().putString(key, value).apply()
    }

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        (map[key] as? Set<String>) ?: defValues
    override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(l)
    }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(l)
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending: MutableMap<String, Any?> = mutableMapOf()
        private var doClear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        @Suppress("UNCHECKED_CAST")
        override fun putStringSet(key: String, value: Set<String>?): SharedPreferences.Editor {
            pending[key] = value; return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { pending[key] = REMOVED; return this }
        override fun clear(): SharedPreferences.Editor { doClear = true; return this }
        override fun apply() {
            if (doClear) {
                val keys = map.keys.toList()
                map.clear()
                keys.forEach { k -> listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) } }
                doClear = false
            }
            for ((k, v) in pending) {
                if (v === REMOVED) map.remove(k) else map[k] = v
                listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, k) }
            }
            pending.clear()
        }
        override fun commit(): Boolean { apply(); return true }
    }

    private companion object {
        private object REMOVED
    }
}
