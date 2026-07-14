package com.hermes.android.data

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ErrorTest {

    @Test
    fun `safeCall returns success for successful block`() = runTest {
        val result = safeCall { 42 }
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `safeCall returns failure wrapping exception`() = runTest {
        val result = safeCall { throw RuntimeException("boom") }
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.Unknown)
        assertEquals("Unknown error", error?.message)
    }

    @Test
    fun `AppError Network wraps cause`() {
        val cause = RuntimeException("connection refused")
        val error = AppError.Network(cause)
        assertEquals("Network error", error.message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `AppError Matrix holds error code and message`() {
        val error = AppError.Matrix("M_FORBIDDEN", "Access denied")
        assertEquals("M_FORBIDDEN", error.errorCode)
        assertEquals("Access denied", error.message)
    }

    @Test
    fun `AppError Crypto holds message`() {
        val error = AppError.Crypto("Decryption failed")
        assertEquals("Decryption failed", error.message)
    }

    @Test
    fun `AppError Unknown wraps cause`() {
        val cause = IllegalStateException("unexpected")
        val error = AppError.Unknown(cause)
        assertEquals("Unknown error", error.message)
        assertEquals(cause, error.cause)
    }
}
