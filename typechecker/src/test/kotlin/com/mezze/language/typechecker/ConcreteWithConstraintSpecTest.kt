package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A `with <Ability> …` constraint whose arguments are ALL CONCRETE is checked
 * like any other.
 *
 * It used to be skipped, and the skip was deliberate — `ConstraintGenerator`
 * read "no type variable" as "nothing to require" and dropped the constraint
 * before it became an obligation. So this CHECKED, printing a signature with the
 * constraint silently gone:
 *
 *     pub let p: { x: Int } with Make Int Int -> Int = { x } -> 1
 *     ## -> pub p : { x: Int } -> Int        no diagnostic, `impl Make Int for
 *     ##                                    Int` does not exist anywhere
 *
 * while the SAME constraint carrying a variable reports that it is unsatisfied.
 * A constraint with no variable is not the undecidable case, it is the one case
 * that is decidable AT ONCE: no later substitution can bring an impl into
 * existence. And it is exactly where a typo lands — `Make Int Int` written for
 * `Make Int Src` — which is the class the arity rule (V0705) exists to catch,
 * with the same consequence: a signature that lies to its reader.
 *
 * Found by probing the `with` clause as its own matrix (2026-11-15); the ability
 * sweep before it had probed constraint DELIVERY but not the clause itself.
 */
class ConcreteWithConstraintSpecTest : VariantSpecBase() {

    private val abilities = """
        type Src = { n: Int }
        ability Weigh for 'a where { weigh: { it: 'a } -> Int }
        impl Weigh for Src where {
          weigh: { it: Src } -> Int = { it } -> 7
        }
        ability Make 'b for 'a where { make: { it: 'a } -> 'b }
        impl Make Int for Src where {
          make: { it: Src } -> Int = { it } -> 111
        }
    """.trimIndent()

    @Test fun `a concrete constraint no impl satisfies is refused`() {
        rejects(
            """
            $abilities
            pub let p: { x: Int } with Make Int Int -> Int = { x } -> 1
            $MAIN
            """,
            "V0408",
            "THE CASE. `impl Make Int for Int` does not exist — only `for Src` — " +
                "and the message says so, naming the impl to write. Before this it " +
                "checked and the constraint vanished from the printed signature.",
        )
    }

    @Test fun `a concrete constraint on a one-parameter ability is refused too`() {
        rejects(
            """
            $abilities
            pub let p: { x: Int } with Weigh Int -> Int = { x } -> 1
            $MAIN
            """,
            "V0408",
            "Not specific to class-param abilities: `Weigh` takes only its " +
                "receiver, and `impl Weigh for Int` does not exist either.",
        )
    }

    @Test fun `a concrete constraint that IS satisfied still passes`() {
        accepts(
            """
            $abilities
            pub let p: { x: Src } with Make Int Src -> Int = { x } -> x.make {}
            $MAIN
            """,
            "THE CONTROL that keeps the check from being a blanket refusal of " +
                "concrete constraints. `impl Make Int for Src` is right there, so " +
                "the obligation is MET — the point is that it is now asked.",
        )
    }

    @Test fun `a constraint carrying a variable is unaffected`() {
        accepts(
            """
            $abilities
            pub let p: { x: 'a } with Weigh 'a -> Int = { x } -> x.weigh {}
            $MAIN
            """,
            "The variable-carrying path is the one that always worked, and it must " +
                "keep deferring to the call site rather than being decided here — " +
                "`'a` is not yet known, so there is nothing to look up.",
        )
    }

    @Test fun `an unknown type name in a constraint is still its own error`() {
        rejects(
            """
            $abilities
            pub let p: { x: Int } with Make Int Nope -> Int = { x } -> 1
            $MAIN
            """,
            "T0213",
            "A name that resolves to no type is NOT an unsatisfied obligation, and " +
                "the new path must not swallow it into V0408 — \"no impl for Nope\" " +
                "would send the author off to write one for a type that does not exist.",
        )
    }
}
