package com.hermes.android.data.repository

import kotlinx.coroutines.CancellationException

/**
 * Lifecycle state of an ActiveThread's timeline listener, produced by [ActiveThreadImpl].
 *
 * - [Starting]: the listener has been launched but has not yet successfully processed a diff.
 * - [Active]: at least one diff has been fully processed (apply + extract + auto-pagination succeeded).
 * - [Failed]: the upstream flow or a diff threw a non-cancellation exception; the listener has terminated.
 * - [Closed]: [ActiveThread.close] was called and completed without a prior [Failed].
 */
sealed class ActiveThreadState {
    object Starting : ActiveThreadState()
    object Active : ActiveThreadState()
    data class Failed(val cause: Throwable) : ActiveThreadState()
    object Closed : ActiveThreadState()
}

/**
 * Events that drive the [ActiveThreadState] state machine.
 */
public sealed class ActiveThreadEvent {
    /** First diff successfully processed (apply + extract + auto-pagination) — transitions Starting → Active. */
    object FirstDiffProcessed : ActiveThreadEvent()

    /** The listener flow threw [cause]. CancellationException is treated as normal teardown. */
    data class ListenerFailure(val cause: Throwable) : ActiveThreadEvent()

    /** [ActiveThread.close] completed without a prior failure. */
    object ListenerClosed : ActiveThreadEvent()
}

/**
 * Pure state machine for [ActiveThreadState] transitions.
 *
 * Rules:
 * - [ActiveThreadEvent.FirstDiffProcessed]: [Starting] → [Active]; other states unchanged.
 * - [ActiveThreadEvent.ListenerFailure]: if the cause is [CancellationException], no-op (normal teardown);
 *   else transitions any non-terminal state → [Failed]. Terminal states are preserved — a [Failed]
 *   is never overwritten by a later close, and a [Closed] swallows late failures.
 * - [ActiveThreadEvent.ListenerClosed]: transitions [Starting]/[Active] → [Closed]. Preserves a prior [Failed].
 */
public object ActiveThreadStateMachine {
    public fun transition(current: ActiveThreadState, event: ActiveThreadEvent): ActiveThreadState {
        return when (event) {
            is ActiveThreadEvent.FirstDiffProcessed -> {
                if (current is ActiveThreadState.Starting) ActiveThreadState.Active
                else current
            }
            is ActiveThreadEvent.ListenerFailure -> {
                if (event.cause is CancellationException) {
                    current // silent teardown — never mark Failed for cancellation
                } else if (current is ActiveThreadState.Failed || current is ActiveThreadState.Closed) {
                    current // already terminal — preserve earlier state
                } else {
                    ActiveThreadState.Failed(event.cause)
                }
            }
            is ActiveThreadEvent.ListenerClosed -> {
                if (current is ActiveThreadState.Failed) {
                    current // real failure happened before close — preserve it
                } else if (current is ActiveThreadState.Closed) {
                    current
                } else {
                    ActiveThreadState.Closed
                }
            }
        }
    }
}
