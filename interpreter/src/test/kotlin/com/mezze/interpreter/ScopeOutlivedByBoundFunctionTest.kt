package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A handler-bound function can run its op body AFTER `finalize_scope`.
 *
 * `perform H in <expr that evaluates to a function>` discharges H from that
 * function's arrow and binds the live chain into the value so the type stays
 * true (`HandlerBoundFunction`). The snapshot includes the SCOPE. So a closure
 * produced inside a scoped handler, and called after the perform block exits,
 * reads a scope whose `finalize_scope` has already run.
 *
 * MEASURED ordering — this test pins it:
 *
 *     INIT
 *     FINALIZE            <- a real handler closes its resource here
 *     (block exited)
 *     OP reads scope.n    <- op body runs AFTER finalize
 *     result = 7
 *
 * Harmless for a plain-data scope: the value was snapshotted, so the read
 * succeeds and returns 7. It is NOT harmless for a scope holding a RESOURCE,
 * and the stdlib has those — `HttpClient` closes its connection pool in
 * `finalize_scope` (`__native_http_client_close { client = scope.client }`) and
 * `Async` finalizes its structured task scope. An escaped bound closure would
 * use a closed pool.
 *
 * THE TEST ASSERTS THE CURRENT BEHAVIOUR, including the ordering that is the
 * problem. It is here so a fix is a deliberate choice rather than a surprise,
 * and so the ordering cannot drift unnoticed.
 *
 * Candidate fixes, none free:
 *  - refuse `capture`-shaped escape from a SCOPED handler (narrowest, and the
 *    type system can see "this performer has initialize_scope")
 *  - tie the scope's lifetime to the bound function's, so finalize runs when the
 *    last holder is gone (needs ownership Mezze does not have)
 *  - let the late call fail loudly instead of reading a finalized scope
 *
 * Reachable only since bare lambdas became handler-bound (986bf38b3); a function
 * REFERENCE could always do it, which is why this is not new so much as newly
 * easy to write.
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

    @Test
    fun `the op body runs after finalize_scope`() {
        val r = MessageOrder(MezzeTestHelper.runWithOutput(src).output)
        // THE POINT: FINALIZE lands before OP. A handler that closed a resource
        // in finalize has already closed it by the time the op reads `scope`.
        assertTrue(r.idx("FINALIZE") >= 0, "finalize ran:\n${r.raw}")
        assertTrue(r.idx("OP") >= 0, "the op body ran:\n${r.raw}")
        assertTrue(
            r.idx("FINALIZE") < r.idx("OP"),
            "finalize_scope runs BEFORE the op body — that is the hazard:\n${r.raw}",
        )
    }

    @Test
    fun `a plain-data scope still reads, because the value was snapshotted`() {
        val out = MezzeTestHelper.runWithOutput(src).output
        assertTrue(
            out.contains("RESULT=7"),
            "the scope VALUE survives in the bound closure, so a data-only scope " +
                "reads fine — which is exactly why this is invisible until a scope " +
                "holds a resource:\n$out",
        )
    }

    private class MessageOrder(val raw: String) {
        private val lines = raw.lines().map { it.trim() }
        fun idx(marker: String) = lines.indexOfFirst { it == marker }
    }
}
