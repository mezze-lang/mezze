package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * The ability half of BUG 36 — and abilities do NOT have the hole.
 *
 * BUG 36 (`EffectNameMergeSpecTest`): two modules may each declare an effect of
 * the same name; they merge by name, and V0404 "impl is missing required
 * operation" is checked PER MODULE against that module's own declaration. An
 * operation the other module contributes is never checked against this module's
 * performers, so `perform Ticker in Tick.tock {}` type-checks and dies with a
 * null handler field.
 *
 * Abilities are the same shape on paper: name-keyed identity
 * ([[project_ability_identity_is_name_keyed]]), no `pub` modifier — the parser
 * says "Abilities are always public because they must be usable in type
 * signatures across modules" — and the same V0404/V0405 family for a
 * missing/extra method. The identical arrangement is REFUSED here.
 *
 * WHY, which is the part worth keeping: an ability METHOD CALL resolves through
 * a constraint at the USE SITE, so the merge cannot hide anything — `t.tag {}`
 * asks "does `T` implement `Show`?" and the answer is no, V0402, naming the
 * impl to write. An effect OP resolves through the performer's handler record,
 * and nothing asks the equivalent question per operation: `perform Ticker in
 * Tick.tock {}` only checks that `Ticker` handles `Tick`, never that it handles
 * `tock`.
 *
 * That asymmetry is the fix lead recorded on BUG 36: rather than re-running
 * V0404 after import resolution, ask the ability question at the op call site —
 * does THIS performer provide THIS operation. Local, at a site that already
 * resolves the performer, reporting like V0402, and needing no phase reorder.
 *
 * These tests are the regression guard for that reasoning. If the first one
 * ever goes green-by-accepting, the lead above is no longer true and BUG 36's
 * recorded fix needs rethinking.
 */
class AbilityCrossModuleMergeSpecTest : VariantSpecBase() {

    private fun givenPoorAndRichShow() {
        writeModule(
            "fx",
            """
            ability Show for 'a where { show: { it: 'a } -> Int }
            pub type T = { n: Int }
            impl Show for T where { show = { it } -> it.n }
            """,
        )
        writeModule(
            "fx2",
            """
            ability Show for 'a where { show: { it: 'a } -> Int, tag: { it: 'a } -> Int }
            pub type U = { m: Int }
            impl Show for U where { show = { it } -> it.m, tag = { it } -> 9 }
            """,
        )
    }

    @Test fun `a method contributed by another module's same-named ability is refused`() {
        givenPoorAndRichShow()
        rejectsMain(
            """
            use fx::{ Show, T }
            use fx2::{ Show, U }
            pub let run: {} -> Int = {} -> do
              let t: T = { n = 1 }
              t.tag {}
            $MAIN
            """,
            "M0805",
            "Now refused at the IMPORT — two different `Show` declarations may not " +
                "be co-imported — so it never reaches the use-site constraint. " +
                "That constraint is still what made abilities safe where effects " +
                "were not, and the reasoning is preserved in the class header and " +
                "in known-bugs BUG 36; this test simply stops earlier now. The " +
                "V0402 path is still exercised by the single-module tests below.",
        )
    }

    @Test fun `one module, an impl that omits a method is refused`() {
        rejects(
            """
            ability Show for 'a where { show: { it: 'a } -> Int, tag: { it: 'a } -> Int }
            type T = { n: Int }
            impl Show for T where { show = { it } -> it.n }
            pub let run: {} -> Int = {} -> do
              let t: T = { n = 1 }
              t.tag {}
            $MAIN
            """,
            "V0404",
            "The same check effects have, and it works the same way for both. " +
                "What differs is only whether anything re-asks across a module " +
                "boundary.",
        )
    }

    @Test fun `an impl with a method the ability does not declare is refused`() {
        // The arms are the other way round here on purpose: fx declares BOTH
        // methods and fx2 declares one, while fx2's impl supplies `tag` anyway.
        // With the poor declaration in fx instead, the same program is V0402
        // ("T does not implement 'Show'. Available impls: impl Show for U") —
        // the merge settles on one declaration and the impl bound to the other
        // stops being visible. Measured both ways; the codes differ, so the
        // fixture has to match the claim.
        writeModule(
            "fx",
            """
            ability Show for 'a where { show: { it: 'a } -> Int, tag: { it: 'a } -> Int }
            pub type T = { n: Int }
            impl Show for T where { show = { it } -> it.n, tag = { it } -> 1 }
            """,
        )
        writeModule(
            "fx2",
            """
            ability Show for 'a where { show: { it: 'a } -> Int }
            pub type U = { m: Int }
            impl Show for U where { show = { it } -> it.m, tag = { it } -> 2 }
            """,
        )
        rejectsMain(
            """
            use fx::{ Show, T }
            use fx2::{ Show, U }
            pub let run: {} -> Int = {} -> do
              let t: T = { n = 1 }
              t.show {}
            $MAIN
            """,
            "V0405",
            "The other direction of the merge: `U`'s impl carries `tag`, which fx " +
                "declares but fx2 does not. Whichever declaration the merge settles " +
                "on, one of the two impls is wrong, and V0405 names the method.",
        )
    }

    @Test fun `two same-named abilities that AGREE both dispatch`() {
        writeModule(
            "fx",
            """
            ability Show for 'a where { show: { it: 'a } -> Int }
            pub type T = { n: Int }
            impl Show for T where { show = { it } -> 111 }
            """,
        )
        writeModule(
            "fx2",
            """
            ability Show for 'a where { show: { it: 'a } -> Int }
            pub type U = { m: Int }
            impl Show for U where { show = { it } -> 222 }
            """,
        )
        acceptsMain(
            """
            use fx::{ Show, T }
            use fx2::{ Show, U }
            pub let a: {} -> Int = {} -> do
              let t: T = { n = 1 }
              t.show {}
            pub let b: {} -> Int = {} -> do
              let u: U = { m = 1 }
              u.show {}
            $MAIN
            """,
            "THE MIRROR. One name-keyed ability with two impls is the normal case " +
                "and must stay accepted — the same reason BUG 36's fix cannot " +
                "simply refuse same-named declarations.",
        )
    }
}
