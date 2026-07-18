package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression test for a latent NPE in `join_on_failure`'s
 * `OnFailureCaught` path — surfaced during #252 test-writing, fixed
 * by null-guarding WriteLocalWithContextNode.registerGlobal.
 *
 * Root cause: an inner `let` inside a match branch inside
 * `join_on_failure` compiled to a WriteLocalWithContextNode. When
 * the bound expression evaluated to null (a legitimate outcome for
 * suspended-effect resolution), the node's registerGlobal call
 * reached ConcurrentHashMap.put, which NPEs on null values.
 *
 * Fix: WriteLocalWithContextNode.registerGlobal now returns early
 * on null. The frame slot itself accepts null via setObject; only
 * the global registration was NPE-sensitive.
 */
class JoinOnFailureProbeTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `OnFailure with single cancelled child NPEs`() {
        val source = """
            use std::effects::async::{
              Async, LoomAsync, scoped_with, scope_spawn,
              OnFailure, UserRequested
            }

            let main = {} -> do
              let outcome = scoped_with {
                policy = OnFailure,
                body = { sc } -> do
                  let child = scope_spawn { sc = sc, f = {} -> do
                    Async.sleep { ms = 30 }
                    1
                  }
                  Async.cancel { p = child, reason = UserRequested { msg = "test" } }
                  [child]
              }
              1

            perform LoomAsync in main {}
        """
        assertEquals(1L, eval(source))
    }
}
