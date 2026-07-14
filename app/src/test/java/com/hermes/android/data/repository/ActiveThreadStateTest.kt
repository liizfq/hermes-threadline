package com.hermes.android.data.repository

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException

class ActiveThreadStateMachineTest {

    private val starting = ActiveThreadState.Starting
    private val active = ActiveThreadState.Active
    private val closed = ActiveThreadState.Closed
    private fun failed(cause: Throwable) = ActiveThreadState.Failed(cause)

    @Nested
    inner class FirstDiffProcessed {
        @Test
        fun `first diff transitions Starting to Active`() {
            val result = ActiveThreadStateMachine.transition(starting, ActiveThreadEvent.FirstDiffProcessed)
            assertTrue(result is ActiveThreadState.Active)
        }

        @Test
        fun `first diff is no-op on Active`() {
            val result = ActiveThreadStateMachine.transition(active, ActiveThreadEvent.FirstDiffProcessed)
            assertTrue(result is ActiveThreadState.Active)
        }

        @Test
        fun `first diff preserves Failed`() {
            val f = failed(RuntimeException("boom"))
            val result = ActiveThreadStateMachine.transition(f, ActiveThreadEvent.FirstDiffProcessed)
            assertTrue(result is ActiveThreadState.Failed)
        }

        @Test
        fun `first diff preserves Closed`() {
            val result = ActiveThreadStateMachine.transition(closed, ActiveThreadEvent.FirstDiffProcessed)
            assertTrue(result is ActiveThreadState.Closed)
        }
    }

    @Nested
    inner class ListenerFailure {
        @Test
        fun `real failure transitions Starting to Failed`() {
            val cause = IOException("network down")
            val result = ActiveThreadStateMachine.transition(starting, ActiveThreadEvent.ListenerFailure(cause))
            assertTrue(result is ActiveThreadState.Failed)
            assertEquals(cause, (result as ActiveThreadState.Failed).cause)
        }

        @Test
        fun `real failure transitions Active to Failed`() {
            val cause = RuntimeException("diff error")
            val result = ActiveThreadStateMachine.transition(active, ActiveThreadEvent.ListenerFailure(cause))
            assertTrue(result is ActiveThreadState.Failed)
            assertSame(cause, (result as ActiveThreadState.Failed).cause)
        }

        @Test
        fun `cancellation is no-op on Starting`() {
            val result = ActiveThreadStateMachine.transition(starting, ActiveThreadEvent.ListenerFailure(CancellationException()))
            assertTrue(result is ActiveThreadState.Starting)
        }

        @Test
        fun `cancellation is no-op on Active`() {
            val result = ActiveThreadStateMachine.transition(active, ActiveThreadEvent.ListenerFailure(CancellationException()))
            assertTrue(result is ActiveThreadState.Active)
        }

        @Test
        fun `cancellation does not overwrite Failed`() {
            val f = failed(IOException("real failure"))
            val result = ActiveThreadStateMachine.transition(f, ActiveThreadEvent.ListenerFailure(CancellationException()))
            assertTrue(result is ActiveThreadState.Failed)
            assertTrue((result as ActiveThreadState.Failed).cause is IOException)
        }

        @Test
        fun `cancellation does not overwrite Closed`() {
            val result = ActiveThreadStateMachine.transition(closed, ActiveThreadEvent.ListenerFailure(CancellationException()))
            assertTrue(result is ActiveThreadState.Closed)
        }

        @Test
        fun `second failure preserves first`() {
            val first = IOException("first")
            val second = RuntimeException("second")
            val current = failed(first)
            val result = ActiveThreadStateMachine.transition(current, ActiveThreadEvent.ListenerFailure(second))
            assertSame(first, (result as ActiveThreadState.Failed).cause)
        }
    }

    @Nested
    inner class ListenerClosed {
        @Test
        fun `close transitions Starting to Closed`() {
            val result = ActiveThreadStateMachine.transition(starting, ActiveThreadEvent.ListenerClosed)
            assertTrue(result is ActiveThreadState.Closed)
        }

        @Test
        fun `close transitions Active to Closed`() {
            val result = ActiveThreadStateMachine.transition(active, ActiveThreadEvent.ListenerClosed)
            assertTrue(result is ActiveThreadState.Closed)
        }

        @Test
        fun `close preserves Failed (real failure not overridden by completion)`() {
            val cause = IOException("real failure")
            val current = failed(cause)
            val result = ActiveThreadStateMachine.transition(current, ActiveThreadEvent.ListenerClosed)
            assertTrue(result is ActiveThreadState.Failed)
            assertSame(cause, (result as ActiveThreadState.Failed).cause)
        }

        @Test
        fun `double close is idempotent from Closed`() {
            val result = ActiveThreadStateMachine.transition(closed, ActiveThreadEvent.ListenerClosed)
            assertTrue(result is ActiveThreadState.Closed)
        }
    }

    @Nested
    inner class FailurePreservedThroughClose {
        @Test
        fun `Starting - failure - close stays Failed`() {
            val cause = IOException("failure")
            var s: ActiveThreadState = starting
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.ListenerFailure(cause))
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.ListenerClosed)
            assertTrue(s is ActiveThreadState.Failed)
            assertSame(cause, (s as ActiveThreadState.Failed).cause)
        }

        @Test
        fun `Active - failure - close stays Failed`() {
            val cause = IOException("failure")
            var s: ActiveThreadState = starting
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.FirstDiffProcessed)
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.ListenerFailure(cause))
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.ListenerClosed)
            assertTrue(s is ActiveThreadState.Failed)
        }

        @Test
        fun `Starting - cancel - close stays Closed (cancellation never marks Failed)`() {
            var s: ActiveThreadState = starting
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.ListenerFailure(CancellationException()))
            s = ActiveThreadStateMachine.transition(s, ActiveThreadEvent.ListenerClosed)
            assertTrue(s is ActiveThreadState.Closed)
        }
    }
}
