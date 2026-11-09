package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Applying what a call returned must still raise the returned function's row.
 *
 * `p.make {}` yields a `{} -> Int <Tick>`; applying it performs `Tick`, so the
 * enclosing function performs `Tick`. That held for a plain function and failed
 * for an ability op, because the two reach the apply with different information:
 * a plain callee's type is known at generation time, while a deferred method
 * call hands up a FRESH VAR (`ExprMethodConstraints`: `val resultType =
 * MezzeType.VarType(TypeVar.fresh())`) unified with the real return type only
 * later. The apply raised the callee's row only when the callee was already a
 * `FunctionType`, so the var case raised nothing.
 *
 * What that cost, measured before the fix:
 *
 *   - `{ p: P } -> Int` was ACCEPTED for a body performing Tick
 *   - declaring the TRUE row `<Tick>` was REFUSED — so there was no correct
 *     spelling of the function at all
 *   - at runtime the pure-certified function returned a SUSPENSION instead of
 *     an `Int`, identical to an unhandled effect, so the declared RETURN TYPE
 *     was wrong too
 *   - `perform Ticker in …` at the call site was refused as handling an effect
 *     the body does not perform, so the caller could not fix it either
 *
 * Fixed by deferring the harvest the way the closure-arg harvest already does
 * for the same reason (#331): when the callee is not yet a function type,
 * register a pending post-solve re-ask. Only ADDS effects, so it cannot loosen
 * the under-promise check.
 */
class EffectThroughAppliedResultSpecTest : VariantSpecBase() {

    private val eff = """
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 1 }
    """.trimIndent()

    @Test fun `applying an ability op's returned function raises its row`() {
        rejects(
            """
            use std::int
            $eff
            ability Maker for 'a where { make: { it: 'a } -> ({} -> Int <Tick>) }
            type P = { x: Int }
            impl Maker for P where { make = { it } -> ({} -> Tick.tick {}) }
            pub let x: { p: P } -> Int = { p } -> p.make {} {}
            $MAIN
            """,
            "T0210",
            "Declaring this pure was accepted before. The body performs Tick by " +
                "applying what `make` returned, and at runtime it really did — the " +
                "function returned a suspension rather than an Int.",
        )
    }

    @Test fun `the same through the receiver-less spelling`() {
        rejects(
            """
            use std::int
            $eff
            ability Maker for 'a where { make: { it: 'a } -> ({} -> Int <Tick>) }
            type P = { x: Int }
            impl Maker for P where { make = { it } -> ({} -> Tick.tick {}) }
            pub let x: { p: P } -> Int = { p } -> Maker.make { it = p } {}
            $MAIN
            """,
            "T0210",
            "`Maker.make { it = p } {}` reaches the same deferred path, so it had " +
                "the same hole.",
        )
    }

    /** And the honest row is now WRITEABLE, which it was not before. */
    @Test fun `declaring the true row is accepted`() {
        accepts(
            """
            use std::int
            $eff
            ability Maker for 'a where { make: { it: 'a } -> ({} -> Int <Tick>) }
            type P = { x: Int }
            impl Maker for P where { make = { it } -> ({} -> Tick.tick {}) }
            pub let x: { p: P } -> Int <Tick> = { p } -> p.make {} {}
            $MAIN
            """,
            "The worse half of the bug: `<Tick>` used to be REFUSED here, so the " +
                "function had no correct spelling. A fix that only stopped accepting " +
                "the pure version would leave it unwritable.",
        )
    }

    /** The shapes that already worked must keep working. */
    @Test fun `a plain function's returned row was and stays tracked`() {
        rejects(
            """
            use std::int
            $eff
            pub let mk: {} -> ({} -> Int <Tick>) = {} -> ({} -> Tick.tick {})
            pub let x: {} -> Int = {} -> mk {} {}
            $MAIN
            """,
            "T0210",
            "The control: a plain callee's type is known at generation time, so " +
                "this never had the hole and must not regress.",
        )
    }

    @Test fun `binding the intermediate was and stays tracked`() {
        rejects(
            """
            use std::int
            $eff
            ability Maker for 'a where { make: { it: 'a } -> ({} -> Int <Tick>) }
            type P = { x: Int }
            impl Maker for P where { make = { it } -> ({} -> Tick.tick {}) }
            pub let x: { p: P } -> Int = { p } -> do
              let f = p.make {}
              f {}
            $MAIN
            """,
            "T0210",
            "The workaround an author had. It must stay correct.",
        )
    }

    /** A pure returned function must NOT gain a row — the fix only adds what is there. */
    @Test fun `applying a pure returned function stays pure`() {
        accepts(
            """
            use std::int
            $eff
            ability Maker for 'a where { make: { it: 'a } -> ({} -> Int) }
            type P = { x: Int }
            impl Maker for P where { make = { it } -> ({} -> 7) }
            pub let x: { p: P } -> Int = { p } -> p.make {} {}
            $MAIN
            """,
            "The guard against over-harvesting: the deferred re-ask adds the row it " +
                "finds, and there is none here, so the function stays pure.",
        )
    }
}
