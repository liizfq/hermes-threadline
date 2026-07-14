package com.hermes.android.presentation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UiStateTest {
    @Test
    fun `loading state is singleton`() {
        val state = UiState.Loading
        assertTrue(state is UiState.Loading)
    }

    @Test
    fun `success state holds data`() {
        val state = UiState.Success("test")
        assertEquals("test", state.data)
    }

    @Test
    fun `error state holds message and throwable`() {
        val cause = RuntimeException("test error")
        val state = UiState.Error("failed", cause)
        assertEquals("failed", state.message)
        assertEquals(cause, state.throwable)
    }
}
