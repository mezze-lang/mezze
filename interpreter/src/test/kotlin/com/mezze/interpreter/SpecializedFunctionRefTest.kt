package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A reference to a generic function, at a site where its obligations resolve, is
 * its SPECIALIZATION — and it has to be the RIGHT one.
 *
 *     let step = { x } -> x * 2 + 1
 *     let apply = { v, f } -> f { x = v }
 *     apply { v = 5,   f = step }     -> 11
 *     apply { v = 5.0, f = step }     -> 11.0
 *
 * `step` is un-annotated, so it is polymorphic over the numeric type and carries
 * `__dict_mul` and `__dict_add`. Passing it as a VALUE cannot pass dictionaries
 * alongside it, so the compiler built a closure — `dict_wrapper_step` — capturing
 * the two resolved operations and forwarding to the generic function. Every call
 * of the callback then goes through that closure, and on `bench/cases/defunc`
 * kernel 3 that cost 82 873 ps/iter against 12 537 once the reference went
 * straight to the specialization: 6.6x, landing level with kernel 7, the same HOF
 * with the callback's type written down.
 *
 * THE RISK THE CHANGE CARRIES is picking the wrong copy. Resolving a reference's
 * dictionaries is the same act as choosing an instantiation, so the match is on
 * OP HASHES — the specialization records the impls it was cut against, the wrapper
 * holds the operations it would have carried — and it insists on a UNIQUE match.
 * These tests are that insistence: the same generic function reached at two
 * numeric types, where taking "the one specialization of `step`" would hand the
 * Float site Int's multiply.
 *
 * They assert VALUES, deliberately. The wrapper is only observable through the
 * full build — a typechecker-level test lowers the stdlib from source and never
 * produces one, which is the blindness `RecursiveSpecSelfCallTest` and
 * `LocalLambdaSpecializerTest` both record. The performance claim lives in
 * `PERF-LOG.md` beside its measurement; what belongs here is that the
 * specialization chosen is the one the site actually needs.
 *
 * WHAT THESE DO NOT COVER, stated rather than implied: the UNIQUENESS guard. I
 * replaced `matches.singleOrNull()` with `candidates.firstOrNull()` — take any copy
 * of the function, impl set unchecked — and all four still pass, including the one
 * below that holds an Int and a Float instantiation in the same program. So these
 * are tests OF the feature, not of its guard. Reproducing a wrong match needs two
 * specializations of one generic whose impl sets both cover the operations the
 * reference resolved, and this program does not produce that shape; the guard is
 * there on the `x.m == Get` precedent (see `Monomorphizer.selectOpEntry`, where
 * taking a lone candidate on faith once handed `__native_int_eq` a variant) rather
 * than on a test. Anyone who finds the shape should add it here.
 */
class SpecializedFunctionRefTest {

    private val PRELUDE = """
        let step = { x } -> x * 2 + 1
        let apply = { v, f } -> f { x = v }
    """.trimIndent()

    @Test
    fun `a generic callback passed as a value computes at Int`() {
        val out = MezzeTestHelper.run("$PRELUDE\nlet main = {} -> apply { v = 5, f = step }\nmain {}")
        assertEquals(11L, out)
    }

    @Test
    fun `the same generic callback computes at Float`() {
        val out = MezzeTestHelper.run("$PRELUDE\nlet main = {} -> apply { v = 5.0, f = step }\nmain {}")
        assertEquals(11.0, out)
    }

    @Test
    fun `both types in one program keep their own instantiation`() {
        // THE REAL CONTROL. Two instantiations of one generic exist here, so a
        // match that took whichever specialization it found first would send the
        // Float callback through Int's multiply — a program that type-checks
        // clean and computes the wrong number, which is how the sibling
        // `selectOpEntry` once handed `__native_int_eq` a variant.
        val out = MezzeTestHelper.run(
            "$PRELUDE\n" +
                "let main = {} -> do\n" +
                "  let i = apply { v = 5, f = step }\n" +
                "  let f = apply { v = 5.0, f = step }\n" +
                "  if f > 10.5 then i else 0\n" +
                "main {}",
        )
        // `f` is 11.0, so the branch is taken and the answer is the Int site's 11.
        // Had the Float site been handed Int's multiply it would not be.
        assertEquals(11L, out)
    }

    @Test
    fun `a generic callback through a recursive fold computes at Int`() {
        // The `bench/cases/defunc` shape: the callback is reached from inside a
        // local recursive loop, so the reference is captured rather than called in
        // place.
        val out = MezzeTestHelper.run(
            """
            let step = { x } -> x * 2 + 1
            let apply_fold = { n, f } -> do
              let go = { i, acc } ->
                if i <= 0 then acc
                else go { i = i - 1, acc = acc + f { x = i } }
              go { i = n, acc = 0 }
            let main = {} -> apply_fold { n = 10, f = step }
            main {}
            """.trimIndent(),
        )
        // sum over i=1..10 of (2i + 1) = 2*55 + 10 = 120
        assertEquals(120L, out)
    }
}
