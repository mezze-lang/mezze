package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Two impls of a class-param ability sharing a RECEIVER, differing only in the
 * class param.
 *
 *     ability Combine 'r for 'l having 'out where { combine: { it: 'l, other: 'r } -> out }
 *     impl Combine B for A having 'out = Int where { combine = … -> 111 }
 *     impl Combine A for A having 'out = Str where { combine = … -> "222" }
 *
 * The TYPECHECKER gets this right — `with_b : {} -> Int` and
 * `with_a : {} -> Str`, and the wrong-result-type variant is a clean T0201
 * ("Type mismatch for associated type 'out': expected Int but got Str"). So the
 * first test here ACCEPTS, and BUG 38's defect was at runtime where this suite
 * cannot see it:
 *
 *     A+B expect 111 -> 111
 *     error[I0004]: class java.lang.Long cannot be cast to class java.lang.String
 *
 * `with_a`, typed `{} -> Str`, returned the Int impl's 111, because dispatch
 * disambiguated two receiver-sharing impls by looking for an argument field
 * named literally `r` — the stdlib's spelling, not this ability's. Fixed by
 * asking which candidate's OPERATION accepts the supplied arguments; the
 * runtime assertion lives in :interpreter's `ClassParamDispatchRuntimeTest`,
 * since only a VALUE distinguishes the impls.
 *
 * These tests pin the TYPE-LEVEL behaviour, which was correct throughout and
 * must stay so. That is the point of keeping them: the type side is what makes
 * a wrong runtime answer a dispatch bug rather than a typing one.
 */
class ClassParamDispatchSpecTest : VariantSpecBase() {

    private val twoImpls = """
        use std::int
        ability Combine 'r for 'l having 'out where {
          combine: { it: 'l, other: 'r } -> out
        }
        type A = { a: Int }
        type B = { b: Int }
        impl Combine B for A having 'out = Int where {
          combine: { it: A, other: B } -> Int = { it, other } -> 111
        }
        impl Combine A for A having 'out = Str where {
          combine: { it: A, other: A } -> Str = { it, other } -> "222"
        }
    """.trimIndent()

    @Test fun `the class param selects the impl, and both resolve`() {
        accepts(
            """
            $twoImpls
            pub let with_b: {} -> Int = {} -> do
              let x: A = { a = 1 }
              let y: B = { b = 2 }
              x.combine { other = y }
            pub let with_a: {} -> Str = {} -> do
              let x: A = { a = 1 }
              let z: A = { a = 3 }
              x.combine { other = z }
            $MAIN
            """,
            "THE TYPE SIDE IS CORRECT: `with_b : {} -> Int`, `with_a : {} -> Str`, " +
                "each resolving `out` from the class param. The runtime used to " +
                "mis-dispatch here — BUG 38 — which nothing at this level can see.",
        )
    }

    @Test fun `asking for the wrong result type is refused`() {
        rejects(
            """
            $twoImpls
            pub let bad: {} -> Str = {} -> do
              let x: A = { a = 1 }
              let y: B = { b = 2 }
              x.combine { other = y }
            $MAIN
            """,
            "T0201",
            "`A` combined with `B` resolves `out = Int`, so a Str annotation is a " +
                "mismatch — and the message names the associated type: \"Type " +
                "mismatch for associated type 'out': expected Int but got Str\". " +
                "The class param really is driving resolution here, which is what " +
                "makes the runtime divergence a dispatch bug rather than a typing " +
                "one.",
        )
    }

    @Test fun `a duplicate on the FULL tuple is refused`() {
        rejects(
            """
            use std::int
            ability Combine 'r for 'l having 'out where {
              combine: { it: 'l, other: 'r } -> out
            }
            type A = { a: Int }
            type B = { b: Int }
            impl Combine B for A having 'out = Int where {
              combine: { it: A, other: B } -> Int = { it, other } -> 1
            }
            impl Combine B for A having 'out = Int where {
              combine: { it: A, other: B } -> Int = { it, other } -> 2
            }
            $MAIN
            """,
            "V0407",
            "Identical in BOTH slots, so it is a genuine duplicate — the mirror of " +
                "the first test, where differing in the class param alone is legal.",
        )
    }

    @Test fun `a blanket class param overlapping a specific one is refused`() {
        rejects(
            """
            use std::int
            ability Combine 'r for 'l having 'out where {
              combine: { it: 'l, other: 'r } -> out
            }
            type A = { a: Int }
            type B = { b: Int }
            impl Combine 'r for A having 'out = Int where {
              combine: { it: A, other: 'r } -> Int = { it, other } -> 1
            }
            impl Combine B for A having 'out = Int where {
              combine: { it: A, other: B } -> Int = { it, other } -> 2
            }
            $MAIN
            """,
            "V0407",
            "Coherence holds in the class-param slot too: a blanket `'r` already " +
                "covers every concrete instance, so a specific `B` alongside it is " +
                "an overlap.",
        )
    }

    @Test fun `no impl for the combination is refused`() {
        rejects(
            """
            use std::int
            ability Combine 'r for 'l having 'out where {
              combine: { it: 'l, other: 'r } -> out
            }
            type A = { a: Int }
            type B = { b: Int }
            impl Combine B for A having 'out = Int where {
              combine: { it: A, other: B } -> Int = { it, other } -> 1
            }
            pub let bad: {} -> Int = {} -> do
              let x: B = { b = 1 }
              let y: B = { b = 2 }
              x.combine { other = y }
            $MAIN
            """,
            "V0402",
            "`B` as the RECEIVER has no impl, and the message names the whole tuple " +
                "— \"B, B does not implement 'Combine'\" — rather than the receiver " +
                "alone.",
        )
    }
}
