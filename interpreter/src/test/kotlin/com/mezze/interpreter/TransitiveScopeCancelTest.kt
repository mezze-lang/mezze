package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Spec tests for #252 — `scope_cancel_all` on a parent Scope must mark
 * any nested Scope opened by the parent's spawned children as cancelled
 * too. Without this, a deeply-nested child scope keeps running after a
 * top-level cancel.
 */
@Execution(ExecutionMode.SAME_THREAD)
class TransitiveScopeCancelTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `cancelling parent scope marks nested scope cancelled`() {
        // Parent opens scope A. A's body uses scope_spawn to launch a child;
        // the child opens nested scope B (registered via CURRENT_SCOPE as
        // child of A), parks B's body until B is cancelled, returns. After
        // the child has registered B as nested, parent calls cancel_all on
        // A. cancel_all must walk into B and mark it cancelled — the child's
        // park observes that and exits, so the parent's await unblocks.
        // We assert main returns 1, meaning the whole structure unwound
        // cleanly. Without #252's childScopes walk, the child's park would
        // not observe the cancel and the test would time out.
        val source = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scope_cancel_all, scope_is_cancelled}
            use std::effects::async::atom::{atom, deref, reset}

            let main = {} -> do
              let nested_ready = atom { val = False }
              Async.scoped { f = { sc } -> do
                let parent_sc = sc
                let _child = scope_spawn { sc = parent_sc, f = {} ->
                  Async.scoped { f = { sc } -> do
                    let nested_sc = sc
                    reset { atom = nested_ready, val = True }
                    # Park on the NESTED scope's cancel flag — the only way
                    # this returns is if cancel_all(parent_sc) walks into
                    # the childScopes list and marks nested_sc cancelled.
                    Async.park { predicate = {} -> scope_is_cancelled { sc = nested_sc } }
                    7
                  } }
                Async.park { predicate = {} -> deref { atom = nested_ready } }
                scope_cancel_all { sc = parent_sc }
                1
              }

            perform LoomAsync in main {}
        """
        assertEquals(1L, eval(source))
    }
}
