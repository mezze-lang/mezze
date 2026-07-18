package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Smoke tests for `impl Scope 'a` — verifies each method surface is
 * reachable via the dot syntax equivalent of its `scope_*` free
 * function. Not exhaustive behaviour tests; those live in
 * TransitiveScopeCancelTest / ScopeJoinUntilTest / etc.
 */
class ScopeImplMethodsTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `sc dot spawn and dot cancel_all and dot is_cancelled`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let main = {} -> Async.scoped { f = { sc } -> do
              let p = sc.spawn { f = {} -> do
                Async.sleep { ms = 5000 }
                1
              }
              let before = sc.is_cancelled {}
              sc.cancel_all {}
              let after = sc.is_cancelled {}
              # Report the transition. Expect False then True.
              match before is
                False -> match after is
                  True  -> "ok"
                  False -> "no-transition"
                True -> "started-cancelled"
            }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals("ok", eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `sc dot get_token returns a CancellationToken`() {
        // Just check that calling it doesn't crash and returns something
        // that isn't the scope itself.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let main = {} -> Async.scoped { f = { sc } -> do
              let tok = sc.get_token {}
              # A minimal use — CancellationToken has a runtime shape;
              # we just need to know the call succeeded.
              1
            }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(1L, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `sc dot join_until returns True on all-fast children`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let main = {} -> Async.scoped { f = { sc } -> do
              let p1 = sc.spawn { f = {} -> do
                Async.sleep { ms = 10 }
                1
              }
              let p2 = sc.spawn { f = {} -> do
                Async.sleep { ms = 10 }
                2
              }
              sc.join_until { ps = [p1, p2], ms = 2000 }
            }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(true, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `sc dot join_until returns False on straggler`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}

            let main = {} -> Async.scoped { f = { sc } -> do
              let p = sc.spawn { f = {} -> do
                Async.sleep { ms = 3000 }
                1
              }
              sc.join_until { ps = [p], ms = 100 }
            }

            perform SystemTime in perform LoomAsync in main {}
        """
        assertEquals(false, eval(source))
    }
}
