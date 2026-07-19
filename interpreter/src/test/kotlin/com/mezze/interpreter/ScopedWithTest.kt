package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Spec tests for `scoped_with` — §3 / #239.
 *
 * All four policies wired up. The parser non-termination on inline `||`
 * between two seq calls inside a record-field lambda body was worked
 * around by let-binding the operands; see join_on_success in async.mz.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ScopedWithTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `OnAllSettled returns OnAllOutcome with all results`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, scope_spawn, scoped_with }
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
            use std::effects::async::{ Async, LoomAsync, scope_spawn, scoped_with }

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
            use std::effects::async::{ Async, LoomAsync, scope_spawn, scoped_with }
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
    fun `OnSuccess returns OnSuccessOutcome wrapping the winner`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, scope_spawn, scoped_with }

            let main = {} -> do
              let outcome = scoped_with {
                policy = OnSuccess,
                body = { sc } -> [scope_spawn { sc = sc, f = {} -> 42 }]
              }
              match outcome is
                OnSuccessOutcome { winner, losers_cancelled = _ } -> winner
                _ -> 0

            perform LoomAsync in main {}
        """
        assertEquals(42L, eval(source))
    }
}
