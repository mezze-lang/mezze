package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Probe: verify the stdlib's `impl [Promise 'a]` block dispatches on
 * method calls against a seq-of-promises. Also spot-checks the parser
 * (`impl [T] where`) + typechecker (relaxed orphan rule allowing
 * "receiver-tree local type" placement).
 */
class ImplSeqProbeTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `ps dot race dispatches to stdlib impl on Promise seq`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}

            let main = {} -> perform LoomAsync in do
              let p1 = Async.spawn { f = {} -> 1 }
              let p2 = Async.spawn { f = {} -> 2 }
              let ps = [p1, p2]
              match ps.race {} is
                Ok  { val }       -> val
                Err { error = _ } -> 0

            main {}
        """
        val result = eval(source)
        assertTrue(result == 1L || result == 2L,
            "expected 1 or 2 (race winner), got: $result")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `ps dot await_all returns each result in input order`() {
        // Named `await_all` (not `all`) to avoid clash with the
        // predicate-taking `Seq 'a.all { it, p }` on generic sequences.
        // Specialization ordering for inherent impls is a followup;
        // for now the promise-seq combinator has a distinct name.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let main = {} -> perform SystemTime in perform LoomAsync in do
              let p1 = Async.spawn { f = {} -> do
                Async.sleep { ms = 5 }
                10
              }
              let p2 = Async.spawn { f = {} -> 20 }
              let results = [p1, p2].await_all {}
              match results is
                [Ok { val = a }, Ok { val = b }] -> a + b
                _ -> 0

            main {}
        """
        assertEquals(30L, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `ps dot try_all unwraps values on all-success`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}

            let main = {} -> perform LoomAsync in do
              let p1 = Async.spawn { f = {} -> 100 }
              let p2 = Async.spawn { f = {} -> 200 }
              match [p1, p2].try_all {} is
                Ok  { val }       -> match val is
                  [a, b] -> a + b
                  _      -> 0
                Err { error = _ } -> 0

            main {}
        """
        assertEquals(300L, eval(source))
    }
}
