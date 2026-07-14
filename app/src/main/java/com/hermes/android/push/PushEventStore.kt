package com.hermes.android.push

import android.content.Context
import android.util.Log

/**
 * Process-independent persistence for received push events.
 *
 * Uses SharedPreferences with a simple JSON serialization, avoiding any
 * dependency on Room/database setup and keeping the receiver side free
 * of heavy dependencies (mirrors the existing PushSettings style).
 */
interface PushEventStore {
    /** Returns true if [dedupKey] was newly added, false if already present. */
    fun storeIfAbsent(event: EventPushEvent): Boolean

    /** Returns and clears the snapshot of all stored events (Most-recent-last). */
    fun drain(): List<EventPushEvent>

    /** Current size of the store. */
    fun size(): Int

    /** Clears everything — use after a full sync to recover disk space. */
    fun clear()
}

class SharedPreferencesPushEventStore(
    context: Context,
    private val maxCapacity: Int = MAX_EVENTS
) : PushEventStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readAll(): MutableList<EventPushEvent> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return mutableListOf()
        val arr = org.json.JSONArray(raw)
        val out = mutableListOf<EventPushEvent>()
        for (i in 0 until arr.length()) {
            val ev = EventPushEvent.fromJson(arr.getString(i)) ?: continue
            out.add(ev)
        }
        return out
    }

    private fun persist(events: List<EventPushEvent>) {
        val arr = org.json.JSONArray()
        events.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    override fun storeIfAbsent(event: EventPushEvent): Boolean {
        synchronized(this) {
            val events = readAll()
            if (events.any { it.dedupKey == event.dedupKey }) return false
            events.add(event)
            // Drop oldest entries when capacity is exceeded.
            while (events.size > maxCapacity) events.removeAt(0)
            persist(events)
            Log.d(TAG, "storeIfAbsent: stored=${event.dedupKey} size=${events.size}")
            return true
        }
    }

    override fun drain(): List<EventPushEvent> {
        synchronized(this) {
            val events = readAll()
            if (events.isNotEmpty()) prefs.edit().remove(KEY_EVENTS).apply()
            return events
        }
    }

    override fun size(): Int {
        synchronized(this) { return readAll().size }
    }

    override fun clear() {
        synchronized(this) { prefs.edit().remove(KEY_EVENTS).apply() }
    }

    companion object {
        private const val TAG = "PushEventStore"
        const val PREFS_NAME = "hermes_push_events"
        const val KEY_EVENTS = "events_json"
        const val MAX_EVENTS = 200
    }
}
