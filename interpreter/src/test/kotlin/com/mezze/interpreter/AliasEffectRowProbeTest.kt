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

    // ── what `<E>` actually attaches to ───────────────────────────────────────

    /**
     * `<E>` is ARROW syntax, not a modifier on the type beside it.
     *
     * `-> MyInt <Tick>` does not describe a `MyInt` that carries `Tick`; it describes
     * an arrow that performs `Tick` and returns a `MyInt`. The alias here is `Int` —
     * nothing about an `Int` can hold a row — so the row demonstrably went to the
     * `->`. The same reading explains why `-> Map Int Int <Http>` parses: the row is
     * the arrow's, and `Map Int Int` is merely what it returns.
     *
     * This pins the rule because the obvious "fix" for the record-field refusal below
     * is to make `<E>` a postfix type modifier — which would be a DIFFERENT language,
     * and would change what this test asserts.
     */
    @Test
    fun `a row after a non-function alias belongs to the arrow`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type MyInt = Int
            let f: {} -> MyInt <Tick> = {} -> Tick.tick {}
            perform Ticker in f {}
        """.trimIndent())))
    }

    /** And it is the arrow's REAL row: drop it and the same body is refused. */
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
            "the row after the alias name was decorative; got: ${t.message}",
        )
    }

    /**
     * A use-site row does NOT override the alias's own row — it belongs to the
     * enclosing arrow, and both rows stay live.
     *
     * `TickThunk <Tock>` reads like "TickThunk, but performing Tock". It is not:
     * it expands to `({} -> Int <Tick>) <Tock>`. The outer arrow performs `Tock`;
     * the thunk it returns still performs `Tick`, exactly as the alias says. The two
     * spellings even print identically, which is how this was established.
     *
     * So there is no syntax for "this alias with a different row" — the row inside an
     * alias is fixed at the point of definition. That gap is what L-1 (row
     * parameters) exists to close; it is deliberately NOT closed by reinterpreting
     * `A <E>` as an override, which would silently change every signature above.
     */
    @Test
    fun `a use-site row does not override the alias's own row`() {
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
     * the alias's `<Tick>` — even though the use site wrote `<Tock>` right next to the
     * alias name. If `<Tock>` overrode the alias, this would be accepted.
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
