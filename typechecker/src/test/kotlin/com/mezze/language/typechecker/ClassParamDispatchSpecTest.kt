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
 *
 * The second half of the file covers BUG 39, the same ability shape with the
 * class param in the RETURN — `Into 'b for 'a`, where the stdlib ships three
 * impls for `Int`. There the type side's refusals are the load-bearing part:
 * they are what guarantee every call reaching the compiler has a determined
 * result type for it to dispatch on.
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

    // ───────────────────────────── the class param in the RETURN (BUG 39)
    //
    // `ability Into 'b for 'a where { into: { it: 'a } -> 'b }` — the arg record
    // is `{}`, so nothing supplied can separate `Into Str for Int` from
    // `Into Float for Int`. The TYPE side resolves it from the annotation, and
    // refuses a target with no impl (V0402); the compiler was the half that lost
    // the answer — see `ClassParamDispatchRuntimeTest`.
    //
    // An unpinned target is T0215 only when the obligation escapes to a
    // signature that cannot hold it; the last test here pins the shape where it
    // does not, which I had predicted wrongly.

    private val returnImpls = """
        use std::int
        use std::str
        ability Make 'b for 'a where { make: { it: 'a } -> 'b }
        type Src = { n: Int }
        impl Make Int for Src where {
          make: { it: Src } -> Int = { it } -> 111
        }
        impl Make Str for Src where {
          make: { it: Src } -> Str = { it } -> "222"
        }
    """.trimIndent()

    @Test fun `a return-position class param resolves from the annotation`() {
        accepts(
            """
            $returnImpls
            pub let as_int: {} -> Int = {} -> do
              let s: Src = { n = 1 }
              s.make {}
            pub let as_str: {} -> Str = {} -> do
              let s: Src = { n = 1 }
              s.make {}
            $MAIN
            """,
            "Both resolve, from the ANNOTATION alone — the two calls are " +
                "character-for-character identical. At runtime `as_str` used to " +
                "return `as_int`'s 111 (BUG 39), which is why the runtime half of " +
                "this pair is an :interpreter test.",
        )
    }

    @Test fun `a return target with no impl is refused`() {
        rejects(
            """
            $returnImpls
            pub let bad: {} -> Bool = {} -> do
              let s: Src = { n = 1 }
              s.make {}
            $MAIN
            """,
            "V0402",
            "There is no `Make Bool for Src`, and the message names the whole " +
                "tuple the way the receiver-position case does — \"Bool, Src does " +
                "not implement 'Make'\". The stdlib form reads \"Bool, Int does not " +
                "implement 'Into'\".",
        )
    }

    @Test fun `a return target nothing pins escapes to the signature`() {
        rejects(
            """
            $returnImpls
            let main = {} -> do
              let s: Src = { n = 1 }
              let v = s.make {}
              7
            """,
            "T0215",
            "With no annotation and no use the target is undetermined, and rather " +
                "than pick one the obligation escapes to the enclosing signature, " +
                "where it has nothing to attach to: \"'Make' is needed at a type " +
                "`main` does not mention: the body chooses\". Same family as " +
                "[[project_phantom_constraint_decl_typeparams]].",
        )
    }

    @Test fun `an unpinned target inside an ANNOTATED decl is accepted`() {
        accepts(
            """
            $returnImpls
            pub let unpinned: {} -> Int = {} -> do
              let s: Src = { n = 1 }
              let v = s.make {}
              7
            $MAIN
            """,
            "THE LIMIT OF THE REFUSAL ABOVE, measured rather than assumed — I " +
                "expected T0215 here too. The decl's own annotation absorbs the " +
                "result, so nothing escapes and the obligation is discharged " +
                "against whichever impl the solver reached. So a call whose target " +
                "is genuinely undetermined DOES reach the compiler; it is harmless " +
                "only because the value is discarded. Runs and returns 7. A shape " +
                "where the arbitrary choice is observable — impls that differ by " +
                "effect rather than by value — is worth probing and has not been.",
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
