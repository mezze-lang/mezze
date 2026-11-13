package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * `perform H in <something that evaluates to a function>` discharges H from that
 * function's arrow, and the handler must TRAVEL WITH THE VALUE so the type is
 * true when it is called later.
 *
 * `HandlerBoundFunction` exists for exactly this — it snapshots the live chain
 * where the function is produced and re-installs it per call, because "the type
 * system discharges the effect from the function's type and 19 tests pin that,
 * so the value has to carry the handler to make the type true".
 *
 * Those 19 tests all use a function REFERENCE. A BARE LAMBDA was broken and
 * nothing covered it: the handler-bind is gated on the body's type being a
 * function whose row carries a handled effect, and that gate was re-derived
 * post-solve from `body.type` — which `removeEffectsFromType` had already
 * stripped, and a lambda's type variable gets unified with the stripped result.
 * A reference escaped it only because its annotated type is concrete and cannot
 * be overwritten.
 *
 * Measured before the fix, same program, three shapes:
 *
 *     reference        = 1
 *     annotated lambda = 1
 *     bare lambda      -> NativeTypeMismatchException: no rendering for
 *                         runtime value of type MezzeSuspend
 *
 * An annotation making it work is the tell that inference was at fault rather
 * than the program. The row is now recorded pre-strip
 * (`ConstraintResult.performBodyFnRow`).
 *
 * All three shapes are pinned here because the asymmetry is what hid the bug.
 */
class PerformReturnedClosureTest {

    private val prelude = """
use std::effects::log::{ println, Console }

effect Tick where { tick: {} -> Int <Tick> }
impl Tick for Ticker where { tick = {} -> 1 }

pub let my_func: {} -> Int <Tick> = {} -> Tick.tick {}
    """.trimIndent()

    private fun run(decl: String, call: String): Any =
        MezzeTestHelper.run("$prelude\n$decl\n$call")

    @Test
    fun `a function REFERENCE returned by a perform carries its handler`() {
        val r = run(
            """
pub let via_reference: {} -> Int <*> = {} -> do
  let f = perform Ticker in my_func
  f {}
            """.trimIndent(),
            "via_reference {}",
        )
        assertEquals(1L, r, "the handler travelled with the value")
    }

    @Test
    fun `an ANNOTATED lambda returned by a perform carries its handler`() {
        val r = run(
            """
pub let via_annotated: {} -> Int <*> = {} -> do
  let g: {} -> Int <Tick> = {} -> Tick.tick {}
  let f = perform Ticker in g
  f {}
            """.trimIndent(),
            "via_annotated {}",
        )
        assertEquals(1L, r, "a concrete type was never the problem")
    }

    @Test
    fun `a BARE lambda returned by a perform carries its handler`() {
        // THE REGRESSION GUARD. This is the one that returned a MezzeSuspend out
        // of a binding declared Int while the other two worked.
        val r = run(
            """
pub let via_lambda: {} -> Int <*> = {} -> do
  let f = perform Ticker in {} -> Tick.tick {}
  f {}
            """.trimIndent(),
            "via_lambda {}",
        )
        assertEquals(
            1L, r,
            "a bare lambda must be handler-bound like the other two — it was not, " +
                "and the type said the effect was discharged while the runtime " +
                "handed back a suspension",
        )
    }

    @Test
    fun `the original function is not mutated by binding a handler into it`() {
        // `HandlerBoundFunction.wrap` returns a NEW function; the original must
        // still require a handler. Calling `my_func` under its own perform after
        // a bound copy was made has to keep working.
        val r = run(
            """
pub let both: {} -> Int <*> = {} -> do
  let bound = perform Ticker in my_func
  let a = bound {}
  let b = perform Ticker in my_func {}
  a + b
            """.trimIndent(),
            "both {}",
        )
        assertEquals(2L, r, "the bound copy and the original both work")
    }
}
