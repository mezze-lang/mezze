package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Two modules may each declare an effect of the SAME NAME, and they merge.
 *
 * Effects have no visibility modifier — the parser says why: "'pub' is not
 * allowed on effect declarations. Effects are always public because they must
 * be usable in type signatures across modules." So nothing scopes an effect to
 * its module, and identity is keyed by name ([[project_ability_identity_is_name_keyed]]).
 *
 * The merge is fine while the declarations AGREE, and a disagreeing SIGNATURE
 * is caught — `tick: {} -> Int` against `tick: {} -> Str` is a T0210 at the use.
 * A disagreeing OP SET is not. V0404 ("implementation is missing required
 * operation") is checked per module, against THAT module's own declaration, so
 * an operation contributed by another module's same-named effect is never
 * checked against this module's performers.
 *
 * MEASURED, two modules, `tock` declared only by fx2, `Ticker` implemented only
 * by fx:
 *
 *     check passes
 *     error[I0004]: Path D dispatch: handler for f79402fc….tock field is not a
 *                   MezzeFunction (got null)
 *
 * The single-module mirror below is the whole argument: the check EXISTS and is
 * good — it names the operation and offers the line to add. The merge skips it.
 * That is the same shape as every other hole this sweep has turned up: not a
 * missing rule, a rule that runs somewhere it cannot see the problem.
 *
 * THE BREAK TEST ASSERTS THE BROKEN BEHAVIOUR. A failure means someone fixed
 * it; flip it to `rejectsMain` with V0404 then. The fix has to run V0404 against
 * the MERGED operation set rather than the declaring module's, which means
 * after imports are resolved — see `planned_features/known-bugs.md` BUG 36.
 */
class EffectNameMergeSpecTest : VariantSpecBase() {

    private fun givenPoorAndRichTick() {
        writeModule(
            "fx",
            """
            effect Tick where { tick: {} -> Int <Tick> }
            impl Tick for Ticker where { tick = {} -> 111 }
            """,
        )
        writeModule(
            "fx2",
            """
            effect Tick where { tick: {} -> Int <Tick>, tock: {} -> Int <Tick> }
            impl Tick for Tocker where { tick = {} -> 1, tock = {} -> 222 }
            """,
        )
    }

    @Test fun `THE MIRROR - one module, an impl that omits an op is refused`() {
        rejectsMain(
            """
            effect Tick where { tick: {} -> Int <Tick>, tock: {} -> Int <Tick> }
            impl Tick for Ticker where { tick = {} -> 111 }
            pub let run: {} -> Int <*> = {} -> perform Ticker in Tick.tock {}
            $MAIN
            """,
            "V0404",
            "THE CHECK EXISTS, and it is a good one — it names the missing " +
                "operation and offers the line to add. Everything below is about " +
                "it being skipped, so if this ever goes red the break test is " +
                "measuring nothing.",
        )
    }

    @Test fun `BUG 36 is closed at the import`() {
        givenPoorAndRichTick()
        rejectsMain(
            """
            use fx::{ Tick, Ticker }
            use fx2::{ Tick, Tocker }
            pub let run: {} -> Int <*> = {} -> perform Ticker in Tick.tock {}
            $MAIN
            """,
            "M0805",
            "FIXED, and not where the entry predicted. BUG 36 proposed checking " +
                "V0404 against the MERGED operation set, or asking at the op call " +
                "site whether THIS performer provides THIS operation. The rule " +
                "chosen instead refuses the arrangement one step earlier: two " +
                "different declarations may not be imported under one name, so " +
                "the merge never forms. Before: `tock` resolved through fx2's " +
                "Tick while `Ticker` is fx's and implements only `tick`, and the " +
                "run died with `handler for ….tock field is not a MezzeFunction " +
                "(got null)`.",
        )
    }

    @Test fun `the poorer module must still be imported for its performer to be named`() {
        givenPoorAndRichTick()
        rejectsMain(
            """
            use fx2::{ Tick, Tocker }
            pub let run: {} -> Int <*> = {} -> perform Ticker in Tick.tock {}
            $MAIN
            """,
            "V0702",
            "What BOUNDS the hole: `Ticker` is unknown without `use fx`. The merge " +
                "needs both modules in the import graph, so this is not reachable " +
                "from a single unrelated dependency.",
        )
    }

    @Test fun `a disagreeing op SIGNATURE is caught`() {
        writeModule(
            "fx",
            """
            effect Tick where { tick: {} -> Int <Tick> }
            impl Tick for IntTicker where { tick = {} -> 111 }
            """,
        )
        writeModule(
            "fx2",
            """
            effect Tick where { tick: {} -> Str <Tick> }
            impl Tick for StrTicker where { tick = {} -> "A STRING" }
            """,
        )
        rejectsMain(
            """
            use fx::{ Tick, IntTicker }
            use fx2::{ Tick, StrTicker }
            pub let run: {} -> Int <*> = {} -> perform StrTicker in Tick.tick {}
            $MAIN
            """,
            "M0805",
            "Was T0210 at the use site; now refused at the import, since the two " +
                "`Tick` declarations differ. The verdict is unchanged and the " +
                "diagnostic is earlier — it names both declaring files instead of " +
                "a downstream type mismatch.",
        )
    }

    @Test fun `two same-named effects that AGREE dispatch to their own performers`() {
        writeModule(
            "fx",
            """
            effect Tick where { tick: {} -> Int <Tick> }
            impl Tick for Ticker where { tick = {} -> 111 }
            """,
        )
        writeModule(
            "fx2",
            """
            effect Tick where { tick: {} -> Int <Tick> }
            impl Tick for Tocker where { tick = {} -> 222 }
            """,
        )
        acceptsMain(
            """
            use fx::{ Tick, Ticker }
            use fx2::{ Tick, Tocker }
            pub let via_ticker: {} -> Int <*> = {} -> perform Ticker in Tick.tick {}
            pub let via_tocker: {} -> Int <*> = {} -> perform Tocker in Tick.tick {}
            $MAIN
            """,
            "NOT a bug, and the case a fix must not break — measured at runtime as " +
                "Ticker=111 and Tocker=222, each dispatching to its own impl. One " +
                "name-keyed effect with two performers is exactly what the design " +
                "says. Refusing the merge outright would take this with it.",
        )
    }
}
