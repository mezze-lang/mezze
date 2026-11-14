package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A handler-bound function may no longer escape a SCOPED `perform` — V0511.
 *
 * This class used to pin the hazard: `perform H in <expr that evaluates to a
 * function>` discharges H from that function's arrow and binds the live chain
 * into the value so the type stays true (`HandlerBoundFunction`), and the
 * snapshot includes the SCOPE. A closure produced inside a scoped handler and
 * called after the block exits therefore read a scope whose `finalize_scope`
 * had already run. The measured ordering was:
 *
 *     INIT
 *     FINALIZE            <- a real handler closes its resource here
 *     (block exited)
 *     OP reads scope.n    <- op body runs AFTER finalize
 *     result = 7
 *
 * Harmless for a plain-data scope — the value was snapshotted, so the read
 * returns 7, which is exactly what kept this invisible. NOT harmless for a
 * scope holding a RESOURCE, and the stdlib has those: `PooledHttpClient` closes
 * its connection pool in `finalize_scope`
 * (`__native_http_client_close { client = scope.client }`) while `request` uses
 * `scope.client`.
 *
 * WHY REFUSING IT IS NOT A TRADE-OFF against S-20, which was the reading that
 * nearly stopped this fix. Desugared, a scoped handler is
 *
 *     let scope = initialize_scope {}
 *     Ctr.get {}                        -- ops belong HERE
 *     finalize_scope {}
 *
 * so the scope's lifetime IS the block. S-20 requires a returned function to
 * carry the handler so a discharged type is honest; for a scoped handler there
 * is no live scope left to carry, so `{} -> Int <*>` is untrue in a different
 * way than the one S-20 fixed. Calling the op outside the init/finalize window
 * is simply out of order.
 *
 * The other two candidates once listed here — tying the scope's lifetime to the
 * bound function (needs ownership Mezze does not have) and letting the late call
 * fail loudly — were not needed once the desugaring settled it.
 */
class ScopeOutlivedByBoundFunctionTest {

    private val src = """
use std::effects::log::{ println, Console }

effect Ctr where { get: {} -> Int <Ctr> }

impl Ctr for Scoped where {
  initialize_scope = {} -> do
    println { msg = "INIT" }
    { n = 7 },
  finalize_scope = {} -> do
    println { msg = "FINALIZE" }
    {},
  get = {} -> do
    println { msg = "OP" }
    scope.n
}

pub let escape: {} -> Int <Log, *> = {} -> do
  let f = perform Scoped in {} -> Ctr.get {}
  println { msg = "EXITED" }
  f {}

let main = {} -> perform Console in do
  let r = escape {}
  let s = r.to_str {}
  println { msg = "RESULT=$(s)" }
main {}
    """.trimIndent()

    /** The same program with the performer UNSCOPED — no `initialize_scope`. */
    private val unscopedSrc = """
use std::effects::log::{ println, Console }

effect Ctr where { get: {} -> Int <Ctr> }

impl Ctr for Plain where {
  get = {} -> do
    println { msg = "OP" }
    42
}

pub let escape: {} -> Int <Log, *> = {} -> do
  let f = perform Plain in {} -> Ctr.get {}
  println { msg = "EXITED" }
  f {}

let main = {} -> perform Console in do
  let r = escape {}
  let s = r.to_str {}
  println { msg = "RESULT=$(s)" }
main {}
    """.trimIndent()

    @Test
    fun `a function escaping a scoped perform is refused`() {
        val e = assertThrows(Throwable::class.java) { MezzeTestHelper.runWithOutput(src) }
        val message = e.message ?: ""
        assertTrue(
            message.contains("is scoped") || message.contains("V0511"),
            "expected the V0511 refusal naming the scoped performer, got: $message",
        )
    }

    @Test
    fun `the same escape from an UNSCOPED perform still works`() {
        val out = MezzeTestHelper.runWithOutput(unscopedSrc).output
        // THE MIRROR, and it is load-bearing: binding the handler chain into a
        // returned closure is S-20's whole point, and with no `initialize_scope`
        // there is no finalizer for it to outlive. If this ever goes red the rule
        // has stopped being about SCOPES and started being about closures.
        assertTrue(
            out.contains("RESULT=42"),
            "an unscoped performer's bound closure must still run its op:\n$out",
        )
    }
}
