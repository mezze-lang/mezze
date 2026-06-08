package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Spec tests for `scoped_with` — §3 / #239.
 *
 * v1 wires three of four policies (OnAllSettled, OnFailure, OnFirst);
 * OnSuccess intentionally falls through to OnAllOutcome until the
 * dedicated helper can be made to typecheck against the stdlib precompile
 * (currently trips a non-termination in :typechecker:generateWellKnownHashes
 * — see memory/project_239_match_user_adt_bug.md).
 */
@Execution(ExecutionMode.SAME_THREAD)
class ScopedWithTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `OnAllSettled returns OnAllOutcome with all results`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scoped_with}
            use std::result

            let main = {} -> do
              let outcome = scoped_with {
                policy = OnAllSettled,
                body = { sc } -> do
                  let p1 = scope_spawn { sc = sc, f = {} -> 10 }
                  let p2 = scope_spawn { sc = sc, f = {} -> 20 }
                  [p1, p2]
              }
              match outcome is
                OnAllOutcome { results } -> match results is
                  [Ok { val = a }, Ok { val = b }] -> a + b
                  _ -> 0
                _ -> 0

            perform LoomAsync in main {}
        """
        assertEquals(30L, eval(source))
    }

    @Test
    fun `OnFailure with all-success returns OnFailureOutcome with unwrapped values`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scoped_with}

            let main = {} -> do
              let outcome = scoped_with {
                policy = OnFailure,
                body = { sc } -> do
                  let p1 = scope_spawn { sc = sc, f = {} -> 5 }
                  let p2 = scope_spawn { sc = sc, f = {} -> 7 }
                  [p1, p2]
              }
              match outcome is
                OnFailureOutcome { results } -> match results is
                  [a, b] -> a + b
                  _      -> 0
                _ -> 0

            perform LoomAsync in main {}
        """
        assertEquals(12L, eval(source))
    }

    @Test
    fun `OnFirst returns OnFirstOutcome wrapping the first settler's Result`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scoped_with}
            use std::result

            let main = {} -> do
              let outcome = scoped_with {
                policy = OnFirst,
                body = { sc } -> [scope_spawn { sc = sc, f = {} -> 99 }]
              }
              match outcome is
                OnFirstOutcome { result, settled_at = _, cancelled = _ } -> match result is
                  Ok { val }        -> val
                  Err { error = _ } -> 0
                _ -> 0

            perform LoomAsync in main {}
        """
        assertEquals(99L, eval(source))
    }

    @Test
    fun `OnSuccess falls through to OnAllOutcome in v1`() {
        // v1: OnSuccess uses the OnAllSettled path (see comment in scoped_with).
        val source = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scoped_with}
            use std::result

            let main = {} -> do
              let outcome = scoped_with {
                policy = OnSuccess,
                body = { sc } -> [scope_spawn { sc = sc, f = {} -> 42 }]
              }
              match outcome is
                OnAllOutcome { results } -> match results is
                  [Ok { val = v }] -> v
                  _ -> 0
                _ -> 0

            perform LoomAsync in main {}
        """
        assertEquals(42L, eval(source))
    }
}
