package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.TimeUnit

/**
 * Deep transitive-cancel spec test for #252 — verifies that cancelling
 * the outermost Scope walks THREE levels deep, not just one.
 *
 * Structure: parent opens scope A. A's spawned child opens scope B.
 * B's spawned child opens scope C. C's spawned child parks on a signal
 * that only cancel_all(A)-transitively-reaching-C can produce.
 *
 * We measure it as: `escaped` atom flips iff C's park exited for any
 * reason OTHER than being cancelled. A 5s bounded park guards against
 * an infinite hang.
 */
@Execution(ExecutionMode.SAME_THREAD)
class TransitiveScopeCancelDeepTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `cancel_all reaches 3-level-deep child`() {
        // Verifies the #252 machinery — cancel_all(a_sc) → childScopes
        // walk into b_sc → childScopes walk into c_sc. All three flags
        // flip together on the parent thread. The deepest child's park
        // observes c_sc.cancelled = true and returns immediately.
        //
        // Uses plain Async.scoped throughout — we're testing the scope
        // cancel machinery, not the scoped_with policy layer.
        val source = """
            use std::effects::async::{
              Async, LoomAsync, scope_spawn, scope_cancel_all, scope_is_cancelled
            }
            use std::effects::async::atom::{atom, deref, reset}

            let main = {} -> do
              let b_ready  = atom { val = False }
              let c_ready  = atom { val = False }
              let escaped  = atom { val = False }

              Async.scoped { f = { sc } -> do
                let a_sc = sc
                let a_child = scope_spawn { sc = a_sc, f = {} ->
                  Async.scoped { f = { sc } -> do
                    let b_sc = sc
                    let b_child = scope_spawn { sc = b_sc, f = {} ->
                      Async.scoped { f = { sc } -> do
                        let c_sc = sc
                        # 5s guard so a broken cancel wave doesn't hang.
                        let timed_out = atom { val = False }
                        let sleeper = Async.spawn { f = {} -> do
                          Async.sleep { ms = 5000 }
                          reset { atom = timed_out, val = True }
                          0
                        }
                        reset { atom = c_ready, val = True }
                        Async.park { predicate = {} ->
                          scope_is_cancelled { sc = c_sc } || deref { atom = timed_out } }
                        # Cancel wave reached us? Then c_sc is cancelled.
                        # Otherwise the 5s guard fired — escape.
                        match scope_is_cancelled { sc = c_sc } is
                          True  -> 3
                          False -> do
                            reset { atom = escaped, val = True }
                            3
                      } }
                    reset { atom = b_ready, val = True }
                    Async.park { predicate = {} -> scope_is_cancelled { sc = b_sc } }
                    Async.await { p = b_child }
                  } }
                # Wait until both nested scopes are open + registered,
                # then request cancel at the top.
                Async.park { predicate = {} -> deref { atom = b_ready } && deref { atom = c_ready } }
                scope_cancel_all { sc = a_sc }
                Async.await { p = a_child }
              }

              match deref { atom = escaped } is
                True  -> "escaped"
                False -> "cancelled-deep"

            perform LoomAsync in main {}
        """.trimIndent()
        assertEquals("cancelled-deep", eval(source))
    }
}
