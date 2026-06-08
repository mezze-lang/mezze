package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Spec tests for `scoped_with` v0 — §3 / #239.
 *
 * v0 always returns `OnAllOutcome` regardless of the JoinPolicy passed.
 * Once the per-policy dispatch lands, this file will grow per-arm tests.
 * The slot-shift typechecker bug that blocked match-on-JoinPolicy inside
 * `Async.scoped` was fixed at 848c1b8c.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ScopedWithTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `scoped_with awaits both children and returns OnAllOutcome`() {
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
                  _ -> 0 - 1
                _ -> 0 - 2

            perform LoomAsync in main {}
        """
        assertEquals(30L, eval(source))
    }

    @Test
    fun `scoped_with accepts every JoinPolicy arm in v0`() {
        for (arm in listOf("OnFailure", "OnSuccess", "OnAllSettled", "OnFirst")) {
            val source = """
                use std::effects::async::{Async, LoomAsync, scope_spawn, scoped_with}
                use std::result

                let main = {} -> do
                  let outcome = scoped_with {
                    policy = $arm,
                    body = { sc } -> [scope_spawn { sc = sc, f = {} -> 7 }]
                  }
                  match outcome is
                    OnAllOutcome { results } -> match results is
                      [Ok { val = v }] -> v
                      _ -> 0 - 1
                    _ -> 0 - 2

                perform LoomAsync in main {}
            """
            assertEquals(7L, eval(source), "policy=$arm")
        }
    }
}
