package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A type constructor is applied to exactly as many arguments as it declares,
 * and only a type may stand in a type position.
 *
 * Both of these were unasked. Neither was harmless:
 *
 *  - ARITY. The surplus arguments were not discarded. They stayed in the type
 *    and took part in unification, so `Option Int Str` was a type — distinct
 *    from `Option Int` and from `Option Int Bool`, unifying with neither. A
 *    typo therefore did not error, it minted a silently incompatible type. A
 *    MISSING argument left the parameter free, so every use site could settle
 *    it differently. `Int Str` was a type too. V0705 existed, with an
 *    explanation written, and fired at exactly one site — an ability impl's
 *    parameter count — never for an annotation.
 *  - NAMESPACE. An ability or effect name in a type position was waved through,
 *    deliberately and with a comment saying the question was not this
 *    validator's to decide. It is decided now.
 *
 * Rust answers both: E0107 for the wrong number of generic arguments including
 * too few, E0109 for arguments on something that takes none, E0782 for a bare
 * trait name where a type belongs. We follow all three; the two arity codes
 * collapse into V0705 because the remedy is one sentence either way.
 *
 * Measured cost of turning these on: ONE bare `Option` in a lint test that was
 * about something else, and three `{ x: SomeAbility } -> x` signatures used to
 * prove an import resolved. The stdlib needed no change.
 */
class TypeApplicationSpecTest : VariantSpecBase() {

    // ======================================================= too many arguments

    @Test fun `a builtin given too many type arguments is refused`() {
        rejects(
            """
            use std::option
            pub let u = { o: Option Int Str } -> 1
            $MAIN
            """,
            "V0705",
            "`Option` takes one. The surplus argument was KEPT, not dropped, so " +
                "this was a type distinct from `Option Int` and from " +
                "`Option Int Bool` — a typo silently became a new incompatible " +
                "type rather than an error.",
        )
    }

    @Test fun `a locally declared type given too many type arguments is refused`() {
        for (decl in listOf("type Box 'a = { v: 'a }", "type Tree 'a = | Leaf, Node 'a |")) {
            rejects(
                """
                use std::int
                $decl
                pub let u = { b: ${decl.split(" ")[1]} Int Str } -> 1
                $MAIN
                """,
                "V0705",
                "A declared type carries its count in `typeParams`, so records and " +
                    "variants are asked the same question as builtins.",
            )
        }
    }

    @Test fun `a newtype given too many type arguments is refused`() {
        rejects(
            """
            use std::int
            newtype Wrap 'a = 'a
            pub let u = { w: Wrap Int Str } -> 1
            $MAIN
            """,
            "V0705",
            "Nominal or not, the parameter count is the declaration's.",
        )
    }

    // ======================================================== too few arguments

    @Test fun `a builtin given too few type arguments is refused`() {
        rejects(
            """
            use std::option
            pub let u = { o: Option } -> 1
            $MAIN
            """,
            "V0705",
            "An omitted argument was NOT inferred — it left the parameter free, so " +
                "each use site could settle it differently and nothing tied them " +
                "together. Rust reports E0107 for a missing generic too; `Option 'a` " +
                "is how you say the parameter is yours to choose.",
        )
    }

    @Test fun `a two-parameter builtin given one argument is refused`() {
        for (app in listOf("Result Int", "Map Int")) {
            rejects(
                """
                use std::int
                use std::result
                use std::collections::map
                pub let u = { x: $app } -> 1
                $MAIN
                """,
                "V0705",
                "Partial application of a type constructor is not a thing the " +
                    "language has; `$app` was simply under-applied.",
            )
        }
    }

    @Test fun `an alias given too few type arguments is refused`() {
        rejects(
            """
            use std::int
            type Pair 'a = { l: 'a, r: 'a }
            pub let u = { p: Pair } -> p.l
            $MAIN
            """,
            "V0705",
            "The converter zipped parameters against arguments, which silently " +
                "truncated to the shorter of the two — so this was accepted and " +
                "`p.l` worked at whatever type each caller supplied.",
        )
    }

    // ============================== arguments on something that takes none

    @Test fun `a non-generic type given an argument is refused`() {
        rejects(
            """
            use std::int
            type P = { x: Int }
            pub let u = { p: P Int } -> 1
            $MAIN
            """,
            "V0705",
            "There is no count to get right — the application itself is the " +
                "mistake, which is why Rust splits this off as E0109.",
        )
    }

    @Test fun `a primitive given an argument is refused`() {
        for (prim in listOf("Int", "Str", "Bool")) {
            rejects(
                """
                pub let u = { n: $prim Str } -> 1
                $MAIN
                """,
                "V0705",
                "`$prim Str` was a type. A primitive takes nothing, so `arityOf` " +
                    "answers 0 for it rather than declining to answer.",
            )
        }
    }

    // ==================================================== what stays accepted

    @Test fun `the right number of arguments is accepted`() {
        accepts(
            """
            use std::int
            use std::option
            use std::result
            use std::collections::map
            type Pair 'a = { l: 'a, r: 'a }
            newtype Wrap 'a = 'a
            pub let a = { o: Option Int } -> 1
            pub let b = { r: Result Int Str } -> 1
            pub let c = { m: Map Str Int } -> 1
            pub let d = { p: Pair Int } -> p.l
            pub let e = { w: Wrap Int } -> 1
            pub let f = { n: Int } -> n
            $MAIN
            """,
            "The control. Every arity exercised at its declared count, so the " +
                "check cannot be passing by refusing everything.",
        )
    }

    /** A type VARIABLE is skipped — applying one is higher-kinded, which we lack. */
    @Test fun `a type variable in an argument position is accepted`() {
        accepts(
            """
            use std::option
            pub let u = { o: Option 'a } -> o
            $MAIN
            """,
            "`Option 'a` is the spelling for leaving the parameter open, and it " +
                "keeps it ONE type throughout — which is exactly what the bare " +
                "`Option` failed to do.",
        )
    }

    /** An unresolved name gets ONE error, not a second about its arity. */
    @Test fun `an unknown type is reported once, not also for arity`() {
        rejects(
            """
            use std::int
            pub let u = { x: NoSuchTypeAnywhere Int } -> 1
            $MAIN
            """,
            "T0213",
            "An unresolved name has no declared arity to compare against, so " +
                "asking would add a second error about a type that does not exist.",
        )
    }

    // ============================= an ability or effect is not a type (V0712)

    @Test fun `an ability name in a type position is refused`() {
        rejects(
            """
            use std::int
            use std::abilities::compare::{ Eq }
            pub let u = { x: Eq } -> 1
            $MAIN
            """,
            "V0712",
            "An ability names what a type can DO. There is no way to write \"some " +
                "value whose type has Eq\" as a type — that needs a boxed " +
                "existential, Rust's `dyn Trait`, and we have no such form, so the " +
                "name is refused rather than reinterpreted.",
        )
    }

    @Test fun `a locally declared ability in a type position is refused`() {
        rejects(
            """
            use std::int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            pub let u = { x: Norm } -> 1
            $MAIN
            """,
            "V0712",
            "Local or imported makes no difference — it is the namespace that is " +
                "wrong.",
        )
    }

    @Test fun `an effect name in a type position is refused`() {
        rejects(
            """
            use std::int
            effect Tick where { tick: { } -> Int }
            pub let u = { x: Tick } -> 1
            $MAIN
            """,
            "V0712",
            "An effect names what a function PERFORMS and belongs in the effect " +
                "row, not in the type of a value.",
        )
    }

    @Test fun `aliasing an ability or an effect is refused`() {
        rejects(
            """
            use std::int
            use std::abilities::compare::{ Eq }
            type X = Eq
            pub let u = { x: X } -> 1
            $MAIN
            """,
            "V0712",
            "The alias body is a type position like any other, so the refusal " +
                "lands on the declaration rather than waiting for a use.",
        )
        rejects(
            """
            use std::int
            effect Tick where { tick: { } -> Int }
            type X = Tick
            pub let u = { x: X } -> 1
            $MAIN
            """,
            "V0712",
            "Same for an effect.",
        )
    }

    /** The ability still works where it belongs — the refusal is positional. */
    @Test fun `an ability in constraint position is accepted`() {
        accepts(
            """
            use std::int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            pub let u : { x: 'a } with Norm 'a -> Int = { x } -> x.norm {}
            $MAIN
            """,
            "What V0712's message points the author at. If this did not work the " +
                "diagnostic would be sending them nowhere.",
        )
    }

    /** And an effect in its row — including inside a type alias's body. */
    @Test fun `an effect in an effect row is accepted`() {
        accepts(
            """
            use std::int
            effect Tick where {
              tick: {} -> Int <Tick>
            }
            pub type Ticker = { run: {} -> Int <Tick> }
            $MAIN
            """,
            "The other half of the same message. `Tick` appears three times and " +
                "never in a type position — the refusal is POSITIONAL, so an alias " +
                "whose body merely mentions an effect in a row is untouched.",
        )
    }
}
