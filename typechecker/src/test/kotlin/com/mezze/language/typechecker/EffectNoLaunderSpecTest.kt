package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * An effect may not escape without appearing in a row.
 *
 * Thirteen shapes, each DECLARING THE ENCLOSING FUNCTION PURE and performing
 * something through an intermediary. Every one must be refused: an acceptance
 * here means a function certified pure performs an effect, which is the failure
 * family this system has already produced seven recorded instances of
 * (`effect_hole_field_call_launders`, `effect_hole_call_of_matchif_result`,
 * #215, #256, #331, #333, the callback row-var).
 *
 * Written as a net rather than as a fix's test: these all held when measured, and
 * the point is that they keep holding. The one shape that did NOT hold —
 * applying what a method call returned — is pinned in
 * EffectThroughAppliedResultSpecTest.
 */
class EffectNoLaunderSpecTest : VariantSpecBase() {

    private val eff = """
        effect Tick where { tick: {} -> Int <Tick> }
        effect Tock where { tock: {} -> Int <Tock> }
        impl Tick for Ticker where { tick = {} -> 1 }
        impl Tock for Tocker where { tock = {} -> 2 }
    """.trimIndent()

    private fun tracked(src: String, why: String) {
        val r = check(src.trimIndent())
        if (r.isSuccess) {
            kotlin.test.fail(
                "LAUNDERED — a pure-declared function performs an effect.\n$why\n\n$src",
            )
        }
    }

    // ───────────────────────────────── through a data structure

    @Test fun `an effectful function in a record field`() {
        tracked(
            """
            use std::int
            $eff
            pub let x: {} -> Int = {} -> do
              let r = { go = {} -> Tick.tick {} }
              r.go {}
            $MAIN
            """,
            "A record is not a laundry: storing the closure and calling it still " +
                "performs Tick.",
        )
    }

    @Test fun `an effectful function in a seq`() {
        tracked(
            """
            use std::int
            use std::collections::seq
            $eff
            pub let x: {} -> Int = {} -> do
              let fs = [{} -> Tick.tick {}]
              match fs.first {} is
                Some { val = f } -> f {}
                None -> 0
            $MAIN
            """,
            "Through a container and an Option, which is two intermediaries.",
        )
    }

    @Test fun `an effectful function in a record-alias field`() {
        tracked(
            """
            use std::int
            $eff
            type Box = { go: {} -> Int <Tick> }
            pub let x: {} -> Int = { } -> do
              let b: Box = { go = {} -> Tick.tick {} }
              b.go {}
            $MAIN
            """,
            "The alias names the row (T0219's record-field exception), and naming it " +
                "must not discharge it.",
        )
    }

    // ───────────────────────────────── through a returned closure

    @Test fun `a returned effectful closure, applied`() {
        tracked(
            """
            use std::int
            $eff
            pub let mk: {} -> ({} -> Int <Tick>) = {} -> ({} -> Tick.tick {})
            pub let x: {} -> Int = {} -> mk {} {}
            $MAIN
            """,
            "Calling `mk` is pure; calling what it returned is not.",
        )
    }

    @Test fun `a returned closure whose row the signature omits`() {
        tracked(
            """
            use std::int
            $eff
            pub let mk: {} -> ({} -> Int) = {} -> ({} -> Tick.tick {})
            $MAIN
            """,
            "The row belongs to the INNER arrow, so declaring that arrow pure is a " +
                "mismatch at the declaration, before anyone applies it.",
        )
    }

    // ───────────────────────────────── through a generic intermediary

    @Test fun `through an identity function`() {
        tracked(
            """
            use std::int
            $eff
            pub let id = { x } -> x
            pub let x: {} -> Int = {} -> do
              let f = id { x = ({} -> Tick.tick {}) }
              f {}
            $MAIN
            """,
            "#331's shape: a polymorphic intermediary must not erase the row it " +
                "carried through.",
        )
    }

    @Test fun `through a match result`() {
        tracked(
            """
            use std::int
            $eff
            pub let x: {} -> Int = { } -> do
              let f = match True is
                True -> ({} -> Tick.tick {})
                False -> ({} -> 0)
              f {}
            $MAIN
            """,
            "`effect_hole_call_of_matchif_result`'s shape — one arm effectful, one " +
                "pure, so the join must keep the effect.",
        )
    }

    @Test fun `through an if result`() {
        tracked(
            """
            use std::int
            $eff
            pub let x: {} -> Int = {} -> (if True then ({} -> Tick.tick {}) else ({} -> 0)) {}
            $MAIN
            """,
            "The same join, applied immediately rather than bound.",
        )
    }

    // ───────────────────────────────── through a newtype

    @Test fun `a newtype may not wrap a function type carrying a row`() {
        tracked(
            """
            use std::int
            $eff
            newtype Thunk = {} -> Int <Tick>
            $MAIN
            """,
            "T0219 for the same reason it refuses a function ALIAS with a row: the " +
                "row would be invisible at every use of the name.",
        )
    }

    @Test fun `a newtype of a pure function type may not hold an effectful one`() {
        tracked(
            """
            use std::int
            $eff
            newtype Thunk = {} -> Int
            pub let x: {} -> Int = {} -> match Thunk.({} -> Tick.tick {}) is
              Thunk.(f) -> f {}
            $MAIN
            """,
            "Wrapping is not laundering: the payload's declared row still governs.",
        )
    }

    // ───────────────────────────────── handler rules

    @Test fun `partial handling leaves the residual row`() {
        tracked(
            """
            use std::int
            $eff
            pub let x: {} -> Int = {} -> perform Ticker in (Tick.tick {} + Tock.tock {})
            $MAIN
            """,
            "Handling Tick discharges Tick and nothing else — Tock still reaches the " +
                "caller.",
        )
    }

    @Test fun `handling an effect nothing performs is refused`() {
        tracked(
            """
            use std::int
            $eff
            pub let x: {} -> Int = {} -> perform Ticker in 5
            $MAIN
            """,
            "V0510. A handler with nothing to handle is a mistake worth reporting, " +
                "not a no-op — it is usually a missing call or the wrong performer.",
        )
    }

    @Test fun `a performer that itself performs another effect`() {
        tracked(
            """
            use std::int
            $eff
            impl Tick for Loud where { tick = {} -> Tock.tock {} }
            pub let x: {} -> Int = {} -> perform Loud in Tick.tick {}
            $MAIN
            """,
            "Handling Tick with a performer that performs Tock trades one effect for " +
                "another; the new one is the caller's to account for.",
        )
    }
}
