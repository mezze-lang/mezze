package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
 * and rely on it.
 *
 * An OPEN row inside an alias was filed as a bug (L-2) and is NOT one — the last
 * three tests here are the measurement that closed it. A bare `<...>` parameter may
 * perform ANY effects, so an enclosing function cannot declare a specific one; both
 * honest spellings work, and both work THROUGH an alias.
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

    // ── L-2, closed: a bare `<...>` parameter is correctly refused ────────────

    /**
     * A bare `<...>` parameter cannot justify a declared effect, and refusing it is
     * SOUND rather than the bug L-2 filed.
     *
     * `g : {} -> Int <...>` may perform anything, so a body that only calls `g`
     * cannot be known to perform `Tick` — declaring `<Tick>` would be a lie, and
     * accepting it would let a `g` that performs `Tock` hide behind a `<Tick>`
     * signature. The diagnostic now says so and names both honest spellings.
     */
    @Test
    fun `a bare open-row parameter cannot justify a declared effect`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                type UnitFn 'a = {} -> 'a <...>
                let run_it: { g: UnitFn Int } -> Int <Tick> = { g } -> g {}
                perform Ticker in run_it { g = {} -> Tick.tick {} }
            """.trimIndent())
        }
        val message = t.message ?: ""
        assertTrue(
            message.contains("may perform ANY effects"),
            "expected the open-row explanation, got: $message",
        )
    }

    /**
     * THE measurement that closed L-2: the identical shape with NO alias is refused
     * identically, so the alias carries the open row faithfully and is uninvolved.
     *
     * L-2 was filed as "an OPEN row inside a type alias does not propagate". It
     * propagates; what fails is the signature, aliased or not.
     */
    @Test
    fun `the same refusal happens INLINE, so the alias is not the cause`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                let run_it: { g: {} -> Int <...> } -> Int <Tick> = { g } -> g {}
                perform Ticker in run_it { g = {} -> Tick.tick {} }
            """.trimIndent())
        }
        assertTrue(
            (t.message ?: "").contains("may perform ANY effects"),
            "inline form should fail the same way as the aliased one: ${t.message}",
        )
    }

    /**
     * Honest spelling ONE, through an alias: name the effect on the parameter's own
     * row. `<Tick, ...>` says the callee DOES perform Tick, so `<Tick>` on the
     * enclosing function is true — and extra effects are still allowed by the tail.
     */
    @Test
    fun `an alias with a named effect AND an open tail justifies the declaration`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type TickFn 'a = {} -> 'a <Tick, ...>
            let run_it: { g: TickFn Int } -> Int <Tick> = { g } -> g {}
            perform Ticker in run_it { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    /**
     * Honest spelling TWO, through an alias: let the row FLOW THROUGH. The function
     * promises only what its callee promises, which is what a bare `<...>` parameter
     * supports — and it is the shape a higher-order combinator wants.
     */
    @Test
    fun `a bare open row flows through when the result is open too`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type UnitFn 'a = {} -> 'a <...>
            let run_it: { g: UnitFn Int } -> Int <...> = { g } -> g {}
            perform Ticker in run_it { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    // ── a row has no standalone form ──────────────────────────────────────────

    /**
     * There is no such type as "`T` with a row". A row exists ONLY as the trailing
     * part of a function type — `params -> return <row>` — and the type written
     * before it is simply the return type.
     *
     * So `MyInt <Tick>`, `Map Int Int <Http>` and `AliasName <Effect>` are not
     * forms at all. What parses in `{} -> MyInt <Tick>` is the function form, whose
     * return type happens to be `MyInt`; the row is the ARROW's. Every position
     * without an arrow to own it is a PARSE error, and
     * `AliasEffectRowSyntaxTest` is the list of them.
     *
     * This test and its negative are the pair that shows the row is the arrow's and
     * is real: the alias is `Int`, which cannot carry a row under any reading, and
     * removing `<Tick>` refuses the identical body.
     *
     * Pinned because the obvious "fix" for those parse errors is to make `<E>` a
     * postfix type modifier — a different language, in which `Map Int Int <Http>`
     * would mean something, and in which the record-field case below would compile.
     */
    @Test
    fun `the row is the arrow's even when the return type is an alias`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type MyInt = Int
            let f: {} -> MyInt <Tick> = {} -> Tick.tick {}
            perform Ticker in f {}
        """.trimIndent())))
    }

    /** The negative half: drop the arrow's row and the same body is refused. */
    @Test
    fun `without that row the same body is refused`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                type MyInt = Int
                let f: {} -> MyInt = {} -> Tick.tick {}
                perform Ticker in f {}
            """.trimIndent())
        }
        assertTrue(
            (t.message ?: "").contains("performs effect"),
            "the arrow's row was decorative; got: ${t.message}",
        )
    }

    /**
     * Two arrows, two rows, both enforced — and neither is "applied to" the alias.
     *
     * `{ n: Int } -> TickThunk <Tock>` is the ordinary function form: `<Tock>` is
     * this arrow's row, and `TickThunk` is its return type, which is itself a
     * function type carrying `<Tick>`. Nothing overrides anything. Written out in
     * full it is `{ n: Int } -> ({} -> Int <Tick>) <Tock>`, and the aliased and
     * inline spellings print identically — which is how this was established.
     *
     * Since a row has no standalone form, there is no way to write "this alias but
     * with a different row", and no way to name a row. That is the gap L-1 (row
     * parameters) exists to close.
     */
    @Test
    fun `an alias return type keeps its own row while the arrow keeps its`() {
        assertEquals(16L, MezzeTestHelper.asLong(eval("""
            type TickThunk = {} -> Int <Tick>
            let f: { n: Int } -> TickThunk <Tock> = { n } -> do
              let t = Tock.tock {}
              {} -> Tick.tick {} + t
            perform Tocker in perform Ticker in (f { n = 1 }) {}
        """.trimIndent())))
    }

    /**
     * The enforcement half: the returned thunk performs `Tock`, so it does not match
     * the alias's `<Tick>` — even though `<Tock>` is written immediately after the
     * alias name. If that `<Tock>` were somehow modifying `TickThunk`, this would be
     * accepted; it belongs to the outer arrow instead.
     */
    @Test
    fun `the alias's row still binds the returned thunk`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                type TickThunk = {} -> Int <Tick>
                let f: { n: Int } -> TickThunk <Tock> = { n } -> do
                  let t = Tock.tock {}
                  {} -> Tock.tock {} + t
                perform Tocker in (f { n = 1 }) {}
            """.trimIndent())
        }
        assertTrue(
            (t.message ?: "").contains("Type annotation mismatch for 'f'"),
            "the use-site row overrode the alias's own row; got: ${t.message}",
        )
    }
}
