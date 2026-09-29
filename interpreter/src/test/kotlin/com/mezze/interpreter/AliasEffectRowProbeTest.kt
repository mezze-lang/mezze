package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A type alias carries an effect row, and the row is REAL.
 *
 * The enforcement case is why this file exists. A row that is accepted but never
 * checked looks identical to a working one until something depends on it — which is
 * exactly how S-1 hid, where a dropped row was invisible against a closed expected
 * row (see `AbilityOpEffectRowTest`). So the negative case below is the load-bearing
 * one and the positives are its context.
 *
 * This is what lets `std::effects::sample` declare `type Gen 'a = {} -> 'a <Sample>`
 * and rely on it. The one shape that does NOT work is an OPEN row inside an alias
 * (`{} -> 'a <...>`), which is task L-2 and deliberately not asserted here.
 */
class AliasEffectRowProbeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val effects = """
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 7 }
        effect Tock where { tock: {} -> Int <Tock> }
        impl Tock for Tocker where { tock = {} -> 9 }
    """.trimIndent()

    private fun eval(body: String): Any? = MezzeTestHelper.run("$effects\n$body")

    @Test
    fun `a concrete row survives the alias in parameter position`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type Gen 'a = {} -> 'a <Tick>
            let run_it: { g: Gen Int } -> Int <Tick> = { g } -> g {}
            perform Ticker in run_it { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    /** Both effects arrive: 7 + 9. One of them going missing would still typecheck. */
    @Test
    fun `an alias carrying TWO effects delivers both`() {
        assertEquals(16L, MezzeTestHelper.asLong(eval("""
            type Both 'a = {} -> 'a <Tick, Tock>
            let run_it: { g: Both Int } -> Int <Tick, Tock> = { g } -> g {}
            perform Ticker in perform Tocker in run_it { g = {} -> Tick.tick {} + Tock.tock {} }
        """.trimIndent())))
    }

    /**
     * THE one that matters. The alias declares `<Tick>`; this thunk performs
     * `<Tock>`. If the alias's row were decorative this would be accepted and the
     * other cases here would prove nothing.
     */
    @Test
    fun `a thunk performing the WRONG effect is rejected`() {
        val e = runCatching {
            eval("""
                type Gen 'a = {} -> 'a <Tick>
                let run_it: { g: Gen Int } -> Int <Tick> = { g } -> g {}
                perform Ticker in perform Tocker in run_it { g = {} -> Tock.tock {} }
            """.trimIndent())
        }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(
            "Type mismatch for argument 'g'" in message,
            "the alias's row was not enforced; got: $message",
        )
    }

    @Test
    fun `a row nested in a record field survives the alias`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type H 'a = { run: {} -> 'a <Tick> }
            let use_it: { h: H Int } -> Int <Tick> = { h } -> (h.run) {}
            perform Ticker in use_it { h = { run = {} -> Tick.tick {} } }
        """.trimIndent())))
    }

    @Test
    fun `the alias works in return position`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type Gen 'a = {} -> 'a <Tick>
            let ticker_gen: {} -> Gen Int = {} -> {} -> Tick.tick {}
            perform Ticker in (ticker_gen {}) {}
        """.trimIndent())))
    }

    /**
     * The row reaches a `perform` and is discharged by it. Written with `<*>`
     * because the body handles what it performs — and the compiler REQUIRING that
     * marker is itself the evidence: an alias whose row had been lost would have
     * been happy with a bare `-> Int`.
     */
    @Test
    fun `a perform discharges the row that arrived through the alias`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type Gen 'a = {} -> 'a <Tick>
            let run_it: { g: Gen Int } -> Int <Tick> = { g } -> g {}
            let discharged: { g: Gen Int } -> Int <*> = { g } -> perform Ticker in run_it { g = g }
            discharged { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    /** Chaining is transparent: `Gen` is `UnitFn`, which is the pure thunk. */
    @Test
    fun `an alias of an alias carries the base's row`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type UnitFn 'a = {} -> 'a
            type Gen 'a = UnitFn 'a
            let run_it: { g: Gen Int } -> Int = { g } -> g {}
            run_it { g = {} -> 7 }
        """.trimIndent())))
    }
}
