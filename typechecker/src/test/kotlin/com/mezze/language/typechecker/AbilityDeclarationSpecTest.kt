package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * What may be WRITTEN in an ability declaration.
 *
 * The ability sweep (27 shapes, 2026-11-13) probed impls, coherence and
 * dispatch. It did not probe the DECLARATION as its own section, and that is
 * where the two shapes this file is about were hiding: both could be written and
 * neither could be used.
 *
 * Every case here was measured with `mez check` before it was written down, and
 * nine of the ten held on the first run. Section order follows the dive:
 * receiver placement first, because that is the one that changed.
 *
 * NOT ASSERTED HERE, and deliberately: an ability-level `with` clause
 * (`ability Derived for 'a with Base 'a where { … }`) — a super-ability — is
 * refused by the PARSER with P0119, which this harness cannot read: it takes
 * codes off `TypeCheckError`, and a parse failure is neither that nor a
 * `ModuleError`. Measured by the dive (`mez check` prints
 * `error[P0119]`), and it belongs in a parser suite rather than asserted
 * through a channel that cannot see it.
 */
class AbilityDeclarationSpecTest : VariantSpecBase() {

    // ── the receiver (V0424, BUG 43) ───────────────────────────────────────

    /**
     * `it` IS the receiver, so an operation without one cannot be called with
     * method syntax — V0424, and it is the rule the ability redesign WROTE DOWN
     * and never enforced:
     *
     *     `it` in method params -> method syntax available (`x.method { … }`)
     *     No `it`              -> direct call only (`Ability.method { … }`)
     *
     * Measured before it was enforced: the receiver was accepted and DISCARDED
     * where the operation declared nothing to hold it, so
     *
     *     ability Pick for 'a where { pick: { r: Int } -> Int }
     *     a.pick { r = 0 }   ## 101      b.pick { r = 0 }   ## 201
     *
     * type-checked with `'a` mentioned NOWHERE — a type annotation in value
     * position, picking the impl and vanishing.
     *
     * A FIRST ATTEMPT AT THIS WAS WRONG and is worth remembering: it refused the
     * DECLARATION whenever an operation's parameters mentioned the `for` type
     * without an `it`. That passed its own tests and compiled the stdlib clean —
     * and then the full suite refused 71 cases, because `my_eq: { r: 'a }` is a
     * legal declaration whose only fault is being CALLED as a method. The rule
     * belongs on the call. A stdlib scan proves what the stdlib writes, not what
     * the language allows.
     */
    @Test fun `an op with no it cannot be called with method syntax`() {
        rejects(
            """
            ability MyEq for 'a where { my_eq: { r: 'a } -> Bool }
            impl MyEq for Int where { my_eq: { r: Int } -> Bool = { r } -> True }
            pub let t: Bool = (1).my_eq { r = 2 }
            $MAIN
            """,
            "V0424",
            "THE CASE. The declaration is fine; the CALL is not. `MyEq.my_eq { … }` " +
                "is the form for an operation with no receiver, and there the type the " +
                "call produces chooses the impl.",
        )
    }

    @Test fun `an op taking the for-type as it is accepted`() {
        accepts(
            """
            type Light = | Red, Green |
            ability Recv for 'a where { go: { it: 'a, n: Int } -> Int }
            impl Recv for Light where {
              go: { it: Light, n: Int } -> Int = { it, n } -> 1
            }
            $MAIN
            """,
            "The same declaration with the receiver spelled `it`. THE CONTROL for the " +
                "case above — without it, V0424 could be refusing the whole shape " +
                "rather than the spelling.",
        )
    }

    @Test fun `the for-type in OTHER parameters too is accepted when it is present`() {
        accepts(
            """
            type Light = | Red, Green |
            ability Same for 'a where { eq2: { it: 'a, r: 'a } -> Bool }
            impl Same for Light where {
              eq2: { it: Light, r: Light } -> Bool = { it, r } -> True
            }
            $MAIN
            """,
            "`Eq`'s own shape — `eq: { it: 'a, r: 'a }`. The rule asks only that `it` " +
                "EXISTS, not that the `for` type appears nowhere else, because the " +
                "stdlib's comparison abilities all take a second operand of the same " +
                "type. 91 stdlib operations, zero violations, and this is why the rule " +
                "is not 'the for type may appear only in it'.",
        )
    }

    @Test fun `a receiverless op with the for-type only in the RESULT is accepted`() {
        accepts(
            """
            type Light = | Red, Green |
            ability Mk for 'a where { mk: {} -> 'a }
            impl Mk for Light where { mk: {} -> Light = {} -> Red }
            pub let l: Light = Mk.mk {}
            $MAIN
            """,
            "`NumZero`/`NumOne`'s shape — `zero: {} -> 'a`. The `for` type is in the " +
                "RESULT, which is what chooses the impl, and the call is qualified " +
                "(`Mk.mk {}`) with the target stated. The rule asks about PARAMETERS " +
                "only, so this is untouched.",
        )
    }

    @Test fun `an op mentioning the for-type nowhere is accepted`() {
        accepts(
            """
            type Light = | Red, Green |
            ability Tag for 'a where { tag: { n: Int } -> Int }
            impl Tag for Light where { tag: { n: Int } -> Int = { n } -> n }
            $MAIN
            """,
            "Nothing to be the receiver and nothing claiming to be: the rule does not " +
                "fire, and the impl is chosen by the one Mezze already has in scope.",
        )
    }

    // ── the rest of the declaration, all of which HELD ─────────────────────

    @Test fun `two abilities sharing an operation name are refused`() {
        rejects(
            """
            ability One for 'a where { go: { it: 'a } -> Int }
            ability Two for 'a where { go: { it: 'a } -> Int }
            $MAIN
            """,
            "V0701",
            "One operation name, one ability — otherwise `x.go {}` names two things " +
                "and dispatch picks whichever was registered first, which is BUG 9's " +
                "shape one level up.",
        )
    }

    @Test fun `the same operation declared twice in one ability is refused`() {
        rejects(
            """
            type Light = | Red, Green |
            ability Dup for 'a where { go: { it: 'a } -> Int, go: { it: 'a } -> Int }
            impl Dup for Light where { go: { it: Light } -> Int = { it } -> 1 }
            $MAIN
            """,
            "V0701",
            "The same rule reaches inside ONE ability: a duplicate operation is a " +
                "duplicate declaration.",
        )
    }

    @Test fun `an ability with no operations is accepted`() {
        accepts(
            """
            ability Marker for 'a where { }
            type Light = | Red, Green |
            impl Marker for Light where { }
            $MAIN
            """,
            "A marker ability carries no operations and constrains by its presence " +
                "alone. Nothing requires an ability to have members, and a `with " +
                "Marker 'a` constraint is still a real obligation.",
        )
    }

    @Test fun `a having the impl never binds is refused`() {
        rejects(
            """
            type Light = | Red, Green |
            ability Carry for 'a having 'out where { go: { it: 'a } -> 'out }
            impl Carry for Light where { go: { it: Light } -> Int = { it } -> 1 }
            $MAIN
            """,
            "V0413",
            "An associated type is part of the contract: the impl says what `'out` IS " +
                "for its type, and leaving it out leaves callers with nothing to read.",
        )
    }
}
