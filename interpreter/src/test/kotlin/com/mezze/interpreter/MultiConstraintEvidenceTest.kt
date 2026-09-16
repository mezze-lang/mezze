package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * One generic, TWO ability constraints on DIFFERENT type variables — each must
 * dispatch its own variable's instance.
 *
 * Written to drive C.3 ("compiler emits evidence; no re-resolution at call
 * sites") by finding a case where evidence selection guesses. It does not: this
 * already works, and the test stays as the guard for why.
 *
 * Two mechanisms have to agree for it to pass. C.2 keys dictionary parameters on
 * `(ability, method, type var)` rather than on the method name, so `Ord 'a` and
 * `Ord 'b` get separate `__dict_lt` slots instead of one clobbering the other.
 * C.1 makes a specialization resolve its OWN constraints, so when these calls are
 * monomorphized the evidence recorded is the callee's rather than the caller's.
 * Break either and one variable silently inherits the other's ordering.
 *
 * `B`'s `Ord` is deliberately REVERSED, which is what makes a wrong pick visible:
 * with the correct instance its "minimum" is the LARGEST wrapped value. A test
 * using two normally-ordered types would pass no matter which evidence was
 * chosen, which is the blind spot that hid BUG 7 in `ElementInstanceMatrixTest`.
 */
class MultiConstraintEvidenceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val program = """
        use std::collections::seq
        use std::abilities::compare::{ Ord }

        type A = { n: Int }
        impl Ord for A where {
          lt = { it, r } -> it.n < r.n,
          le = { it, r } -> it.n <= r.n,
          gt = { it, r } -> it.n > r.n,
          ge = { it, r } -> it.n >= r.n
        }

        newtype B = Int
        impl Ord for B where {
          lt = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x > y,
          le = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x >= y,
          gt = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x < y,
          ge = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x <= y
        }

        let pick_both: { xs: ['a], ys: ['b] } with Ord 'a, Ord 'b
          -> { l: Option 'a, r: Option 'b }
          = { xs, ys } -> { l = seq::min { xs = xs }, r = seq::min { xs = ys } }

        let out = pick_both { xs = [{ n = 3 }, { n = 1 }], ys = [B.(3), B.(1)] }
    """.trimIndent()

    @Test
    fun `the first constraint's variable uses its own Ord`() {
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                $program
                match out.l is
                  Some { val = a } -> a.n
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the second constraint's variable uses its own REVERSED Ord`() {
        // B orders descending, so `min` must return the LARGEST wrapped value.
        // A primitive comparison — or A's instance leaking across — gives 1.
        assertEquals(
            3L,
            MezzeTestHelper.run(
                """
                $program
                match out.r is
                  Some { val = b } -> match b is
                    B.(v) -> v
                  None -> 0
                """.trimIndent(),
            ),
        )
    }
}
