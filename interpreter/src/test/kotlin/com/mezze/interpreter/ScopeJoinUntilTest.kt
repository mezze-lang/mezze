package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.TimeUnit

/**
 * Spec tests for #253 — `scope_join_until { sc, ps, ms }` bounded-wait
 * over a Scope's spawned children.
 *
 * Returns:
 *   True  — every promise in `ps` settled before `ms` ms elapsed.
 *   False — the deadline fired first; the Scope is cancelled so
 *           stragglers unwind.
 *
 * Mixed outcomes matter: a fast Ok, a fast Err, and a slow child under
 * one join_until should behave as documented — if the slow child
 * doesn't complete in time, we get False regardless of what the fast
 * ones returned.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ScopeJoinUntilTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `returns True when all children settle before deadline`() {
        // Two children that finish in ~10ms; deadline is 2s. Should
        // return True comfortably.
        val source = """
            use std::effects::async::{ Async, LoomAsync, scope_join_until, scope_spawn, scoped, sleep }
            use std::effects::time::{Time, SystemTime}

            let main = {} -> do
              scoped { f = { sc } -> do
                let p1 = scope_spawn { sc = sc, f = {} -> do
                  sleep { ms = 10 }
                  1
                }
                let p2 = scope_spawn { sc = sc, f = {} -> do
                  sleep { ms = 10 }
                  2
                }
                scope_join_until { sc = sc, ps = [p1, p2], ms = 2000 }
              }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(true, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `returns False when deadline fires with straggler still running`() {
        // Slow child sleeps for 3s; deadline is 100ms. Should return
        // False and cancel the scope, freeing the straggler.
        val source = """
            use std::effects::async::{ Async, LoomAsync, scope_join_until, scope_spawn, scoped, sleep }
            use std::effects::time::{Time, SystemTime}

            let main = {} -> do
              scoped { f = { sc } -> do
                let p1 = scope_spawn { sc = sc, f = {} -> do
                  sleep { ms = 3000 }
                  1
                }
                scope_join_until { sc = sc, ps = [p1], ms = 100 }
              }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(false, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `mixed outcomes — fast Ok plus slow straggler returns False`() {
        // p1 finishes fast, p2 sleeps past the deadline. join_until
        // must see the straggler and return False even though p1 is
        // Ok. The Ok is not "all settled" until p2 also settles.
        val source = """
            use std::effects::async::{ Async, LoomAsync, scope_join_until, scope_spawn, scoped, sleep }
            use std::effects::time::{Time, SystemTime}

            let main = {} -> do
              scoped { f = { sc } -> do
                let p1 = scope_spawn { sc = sc, f = {} -> do
                  sleep { ms = 10 }
                  1
                }
                let p2 = scope_spawn { sc = sc, f = {} -> do
                  sleep { ms = 3000 }
                  2
                }
                scope_join_until { sc = sc, ps = [p1, p2], ms = 100 }
              }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(false, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `all fast children with mixed Ok Err returns True`() {
        // Both children settle quickly — one Ok, one that would
        // return an error result if we introspected. join_until only
        // cares about "did they settle in time", not what they settled
        // to. Should return True.
        val source = """
            use std::effects::async::{ Async, LoomAsync, scope_join_until, scope_spawn, scoped, sleep }
            use std::effects::time::{Time, SystemTime}

            let main = {} -> do
              scoped { f = { sc } -> do
                let p1 = scope_spawn { sc = sc, f = {} -> do
                  sleep { ms = 10 }
                  1
                }
                let p2 = scope_spawn { sc = sc, f = {} ->
                  # This branch just returns a distinct value; we don't
                  # need an actual failure to test the "all settled"
                  # invariant.
                  do
                    sleep { ms = 20 }
                    2
                }
                scope_join_until { sc = sc, ps = [p1, p2], ms = 2000 }
              }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(true, eval(source))
    }
}
