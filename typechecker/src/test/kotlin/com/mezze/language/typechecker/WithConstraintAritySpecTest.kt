package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A `with <Ability> …` clause must supply EVERY parameter of the ability.
 *
 * USER RULE. An ability's parameters are positional — `ability Make 'b for 'a`
 * declares `['b, 'a]`, class parameters first and the RECEIVER LAST — so a
 * partial list leaves the rest to be filled in, and what filled them was a COPY
 * of a neighbouring slot. Measured before the rule (BUG 41 ii):
 *
 *     with Make 'b       (1 of 2)  ->  V0402 "Int, Int does not implement 'Make'"
 *     with Conv Cfg 'b   (2 of 3)  ->  V0402 "Int, Cfg, Int does not implement 'Conv'"
 *
 * Neither tuple can exist: `Make`'s receiver is always `Src`, and `Conv`'s slots
 * are `[Cfg, Str, Src]`. The arguments shifted as well as duplicated, so a typo
 * in a constraint was reported as a missing impl for a tuple nobody wrote — and
 * only when the function was CALLED, never at the declaration.
 *
 * The check is as-written (`TypeValidator.validateAsWritten`), so it lands at the
 * constraint rather than at a use site, and it refuses the UNDER-supplied,
 * OVER-supplied and EMPTY spellings alike.
 *
 * SCOPE, pinned by the last two tests: an ability whose declaration this pass
 * cannot see is skipped rather than guessed about, and a one-parameter ability is
 * satisfied by its single argument — the receiver is a parameter like any other.
 */
class WithConstraintAritySpecTest : VariantSpecBase() {

    private val twoParam = """
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

    @Test fun `every parameter named is accepted`() {
        accepts(
            """
            $twoParam
            pub let via: { x: 'a } with Make 'b 'a -> 'b = { x } -> x.make {}
            $MAIN
            """,
            "`Make 'b 'a` names both — the class param and the receiver — which is " +
                "the whole rule. This is also the spelling that DISPATCHES correctly " +
                "through a generic hop (BUG 40), so the rule and the fix agree.",
        )
    }

    @Test fun `omitting the receiver is refused`() {
        rejects(
            """
            $twoParam
            pub let via: { x: Src } with Make 'b -> 'b = { x } -> x.make {}
            $MAIN
            """,
            "V0705",
            "THE CASE THE RULE EXISTS FOR. Before it, this was accepted at the " +
                "declaration and surfaced at the CALL as \"Int, Int does not " +
                "implement 'Make'\" — a receiver of `Int` for an ability whose " +
                "receiver is always `Src`.",
        )
    }

    @Test fun `omitting a class param is refused`() {
        rejects(
            """
            use std::int
            use std::str
            ability Conv 'cfg 'b for 'a where { conv: { it: 'a, cfg: 'cfg } -> 'b }
            type Src = { n: Int }
            type Cfg = { mode: Int }
            impl Conv Cfg Int for Src where {
              conv: { it: Src, cfg: Cfg } -> Int = { it, cfg } -> 111
            }
            pub let go: { x: Src, c: Cfg } with Conv Cfg 'b -> 'b = { x, c } -> x.conv { cfg = c }
            $MAIN
            """,
            "V0705",
            "Two of three. This is the shape the STDLIB used to be written in — " +
                "`with Decode QueryC 'a` for `ability Decode 'config 'a for 'in` — " +
                "and all twelve such constraints now name their receiver.",
        )
    }

    @Test fun `naming no parameters at all is refused`() {
        rejects(
            """
            $twoParam
            pub let via: { x: Src } with Make -> Int = { x } -> 1
            $MAIN
            """,
            "V0705",
            "A constraint naming no type cannot constrain anything. This was " +
                "accepted when merely declared and only became a T0210 once the " +
                "function was called.",
        )
    }

    @Test fun `supplying too many is refused`() {
        rejects(
            """
            $twoParam
            pub let via: { x: 'a } with Make 'b 'a 'c -> 'b = { x } -> x.make {}
            $MAIN
            """,
            "V0705",
            "The rule is EVERY parameter, not AT LEAST every parameter — a surplus " +
                "argument is as much a typo as a missing one, and the same family " +
                "([[project_type_application_arity_and_namespace]]) records surplus " +
                "args being RETAINED so a typo minted a distinct type.",
        )
    }

    @Test fun `a one-parameter ability is satisfied by one argument`() {
        accepts(
            """
            use std::int
            ability Weigh for 'a where { weigh: { it: 'a } -> Int }
            type Src = { n: Int }
            impl Weigh for Src where {
              weigh: { it: Src } -> Int = { it } -> 7
            }
            pub let total: { x: 'a } with Weigh 'a -> Int = { x } -> x.weigh {}
            $MAIN
            """,
            "THE CONTROL that keeps the rule from being an off-by-one: `ability " +
                "Weigh for 'a` has ONE parameter, its receiver, and `with Weigh 'a` " +
                "names it. The receiver counts — which is exactly why the two-param " +
                "cases above must name it too.",
        )
    }

    @Test fun `a stdlib ability is checked the same way`() {
        rejects(
            """
            use std::int
            use std::abilities::numeric::{ NumSub }
            pub let diff: { x: 'a } with NumSub 'a -> 'a = { x } -> x
            $MAIN
            """,
            "V0705",
            "An IMPORTED ability's arity comes from the registry, so the rule is not " +
                "limited to locally-declared ones. `ability NumSub 'r for 'l` takes " +
                "two; `AnnotationTypeCheckingTest` had two cases written this way and " +
                "now writes `with NumSub 'a 'a`.",
        )
    }
}
