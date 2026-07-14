package com.hermes.android.ui.components

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentContentDetectorTest {

    @Test
    fun `detect thinking block by emoji prefix`() {
        val text = "💬 thinking about the problem..."
        assertTrue(text.startsWith("💬"))
    }

    @Test
    fun `detect tool call by emoji prefix`() {
        val text = "🔧 terminal: \"ls -la\""
        assertTrue(text.matches(Regex("^[\\uD83D\\uDD27\\uD83D\\uDCBB\\uD83D\\uDEE0\\u2699\\uFE0F]\\s+\\w+:.*")))
    }

    @Test
    fun `extract tool name and preview from tool call text`() {
        val text = "🔧 terminal: \"ls -la\""
        val match = Regex("^[^\\s]+\\s+(\\w+):\\s*\"?(.*?)\"?$").find(text)
        assertNotNull(match)
        assertEquals("terminal", match!!.groupValues[1])
        assertEquals("ls -la", match.groupValues[2])
    }

    @Test
    fun `lifecycle status from reactions`() {
        val reactions = mapOf("👀" to 1)
        val status = when {
            reactions.containsKey("👀") -> LifecycleStatus.PROCESSING
            reactions.containsKey("✅") -> LifecycleStatus.SUCCESS
            reactions.containsKey("❌") -> LifecycleStatus.FAILURE
            else -> LifecycleStatus.NONE
        }
        assertEquals(LifecycleStatus.PROCESSING, status)
    }

    @Test
    fun `lifecycle status none when no reactions`() {
        val reactions = emptyMap<String, Int>()
        val status = when {
            reactions.containsKey("👀") -> LifecycleStatus.PROCESSING
            reactions.containsKey("✅") -> LifecycleStatus.SUCCESS
            reactions.containsKey("❌") -> LifecycleStatus.FAILURE
            else -> LifecycleStatus.NONE
        }
        assertEquals(LifecycleStatus.NONE, status)
    }
}
