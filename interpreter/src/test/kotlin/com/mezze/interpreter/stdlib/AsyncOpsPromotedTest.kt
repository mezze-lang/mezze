package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase A of continuations-and-swappable-async: `await`, `cancel`,
 * `park` and `park_until` are OPS of `effect Async` (`yield_now` is a function over `park`), no longer plain functions
 * straight to natives.
 *
 * Why it matters: `spawn` used to be the sole op, so the four shipped performers
 * could differ ONLY in how they spawn — `await` always blocked a thread, and no
 * userland performer could change that. The plain names stay as forwarders
 * (`await { p }` is `Async.await { p }`), so every caller is unchanged.
 *
 * The performer below is pure userland — no `__native_*` — which is the L2
 * boundary the design draws: policy in Mezze, mechanism in the stdlib.
 */
class AsyncOpsPromotedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val imports = """
        use std::effects::async::{ Async, LoomAsync, await, cancel, yield_now, park, park_until,
                                   inspect, promise_of, AsyncError, CancelReason, CancelOutcome }
        use std::result
    """

    @Test
    fun `the plain names still work under LoomAsync, through both spellings`() = assertEquals(
        84L,
        eval(
            """
            $imports
            perform LoomAsync in do
              let p = Async.spawn { f = {} -> 42 }
              yield_now {}
              park { predicate = {} -> p.is_done {} }
              let a = result::get_or { result = await { p = p }, def = -1 }
              let b = result::get_or { result = Async.await { p = p }, def = -1 }
              a + b
            """,
        ),
    )

    /**
     * A SYNCHRONOUS performer written entirely in Mezze: spawn runs the task on
     * the spot into an already-settled promise, await reads the settled state.
     * Impossible before Phase A, because `await` was not an op to implement.
     */
    private val syncPerformer = """
        impl Async for SyncAsync where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          spawn = { f } -> promise_of { val = f {} },
          await = { p } -> match inspect { p = p } is
            PromiseCompleted { val } -> Ok { val = val }
            PromiseFailed { err } -> Err { error = err }
            PromisePending -> Err { error = AsyncFailed { msg = "sync performer cannot wait" } },
          cancel = { p, reason } -> CancelAlreadyCompleted,
          park = { predicate } -> {},
          park_until = { predicate, ms } -> True,
        }
    """

    @Test
    fun `a userland performer intercepts await`() = assertEquals(
        42L,
        eval(
            """
            $imports
            $syncPerformer
            perform SyncAsync in do
              let p = Async.spawn { f = {} -> 40 + 2 }
              result::get_or { result = await { p = p }, def = -1 }
            """,
        ),
    )

    @Test
    fun `a userland performer intercepts cancel, park and park_until`() = assertEquals(
        "CancelAlreadyCompleted True",
        eval(
            """
            $imports
            $syncPerformer
            perform SyncAsync in do
              let p = Async.spawn { f = {} -> 1 }
              park { predicate = {} -> False }
              let c = cancel { p = p, reason = CancelCustom { tag = "t", detail = "d" } }
              let waited = park_until { predicate = {} -> False, ms = 999999 }
              "${'$'}(c.to_dbg {}) ${'$'}(waited.to_dbg {})"
            """,
        ),
    )
}
