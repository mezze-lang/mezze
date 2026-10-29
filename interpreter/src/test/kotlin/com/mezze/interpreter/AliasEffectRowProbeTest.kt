package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A function type's effect row is REAL, and a NAMED type carries one only
 * through a record field.
 *
 * The enforcement cases are why this file exists. A row that is accepted but
 * never checked looks identical to a working one until something depends on it —
 * which is exactly how S-1 hid, where a dropped row was invisible against a
 * closed expected row (see `AbilityOpEffectRowTest`). So the negatives here are
 * the load-bearing cases and the positives are their context.
 *
 * REWRITTEN 2026-10-29 for T0219. Every case below used to be written through a
 * bare-function alias — `type Gen 'a = {} -> 'a <Tick>` — which is now refused:
 * an alias names a SHAPE, and what a function of that shape performs belongs on
 * the signature that uses it, where a reader can see it. A record FIELD holding
 * an effectful callback is unaffected, and that is the one spelling in which a
 * named type still carries a row (`Routes`, `RouteEntry`, `SelectBranch` all do
 * it; routing and `select` need it).
 *
 * The properties are unchanged and so are the assertions — only the spelling
 * moved, from `Gen Int` to the `{} -> Int <Tick>` it stood for. Where a case
 * specifically measured something ABOUT the alias, that is called out on the
 * case itself.
 *
 * L-2 is still closed, by the pair of open-row cases below: a bare `<...>`
 * parameter may perform ANY effects, so an enclosing function cannot declare a
 * specific one — refusing that is sound, and letting the row FLOW THROUGH is the
 * honest alternative. L-2 was filed as "an OPEN row inside a type alias does not
 * propagate"; it propagated, and the alias was never the cause, which is the
 * conclusion that outlives the alias spelling.
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
    fun `a concrete row on a callback parameter survives`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            let run_it: { g: {} -> Int <Tick> } -> Int <Tick> = { g } -> g {}
            perform Ticker in run_it { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    /** Both effects arrive: 7 + 9. One of them going missing would still typecheck. */
    @Test
    fun `a callback row carrying TWO effects delivers both`() {
        assertEquals(16L, MezzeTestHelper.asLong(eval("""
            let run_it: { g: {} -> Int <Tick, Tock> } -> Int <Tick, Tock> = { g } -> g {}
            perform Ticker in perform Tocker in run_it { g = {} -> Tick.tick {} + Tock.tock {} }
        """.trimIndent())))
    }

    /**
     * THE one that matters. The parameter declares `<Tick>`; this thunk performs
     * `<Tock>`. If the row were decorative this would be accepted and the other
     * cases here would prove nothing.
     */
    @Test
    fun `a thunk performing the WRONG effect is rejected`() {
        val e = runCatching {
            eval("""
                let run_it: { g: {} -> Int <Tick> } -> Int <Tick> = { g } -> g {}
                perform Ticker in perform Tocker in run_it { g = {} -> Tock.tock {} }
            """.trimIndent())
        }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(
            "Type mismatch for argument 'g'" in message,
            "the callback's row was not enforced; got: $message",
        )
    }

    /**
     * A row inside a record FIELD of a named type — the one spelling in which a
     * named type still carries a row, and the shape the stdlib uses.
     */
    @Test
    fun `a row nested in a record field survives the named type`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            type H 'a = { run: {} -> 'a <Tick> }
            let use_it: { h: H Int } -> Int <Tick> = { h } -> (h.run) {}
            perform Ticker in use_it { h = { run = {} -> Tick.tick {} } }
        """.trimIndent())))
    }

    /** A row on a function returned from a function. */
    @Test
    fun `a returned thunk keeps its row`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            let ticker_gen: {} -> ({} -> Int <Tick>) = {} -> {} -> Tick.tick {}
            perform Ticker in (ticker_gen {}) {}
        """.trimIndent())))
    }

    /**
     * The row reaches a `perform` and is discharged by it. Written with `<*>`
     * because the body handles what it performs — and the compiler REQUIRING that
     * marker is itself the evidence: a row that had been lost would have been
     * happy with a bare `-> Int`.
     */
    @Test
    fun `a perform discharges the row that arrived on the parameter`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            let run_it: { g: {} -> Int <Tick> } -> Int <Tick> = { g } -> g {}
            let discharged: { g: {} -> Int <Tick> } -> Int <*> = { g } -> perform Ticker in run_it { g = g }
            discharged { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    /**
     * An alias of a function type with NO row is still legal, and chains: T0219
     * refuses a row on such an alias, not the alias itself. `Gen` is `UnitFn`,
     * which is the pure thunk.
     */
    @Test
    fun `a row-less function alias still chains`() {
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
     * signature. The diagnostic says so and names both honest spellings.
     *
     * L-2 was filed as "an OPEN row inside a type ALIAS does not propagate". It
     * propagated; what fails is the signature, and it failed identically with and
     * without an alias — which is why the conclusion survives T0219 even though
     * the aliased half of the measurement cannot be written any more.
     */
    @Test
    fun `a bare open-row parameter cannot justify a declared effect`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                let run_it: { g: {} -> Int <...> } -> Int <Tick> = { g } -> g {}
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
     * Honest spelling ONE: name the effect on the parameter's own row.
     * `<Tick, ...>` says the callee DOES perform Tick, so `<Tick>` on the
     * enclosing function is true — and extra effects are still allowed by the tail.
     */
    @Test
    fun `a named effect AND an open tail justifies the declaration`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            let run_it: { g: {} -> Int <Tick, ...> } -> Int <Tick> = { g } -> g {}
            perform Ticker in run_it { g = {} -> Tick.tick {} }
        """.trimIndent())))
    }

    /**
     * Honest spelling TWO: let the row FLOW THROUGH. The function promises only
     * what its callee promises, which is what a bare `<...>` parameter supports —
     * and it is the shape a higher-order combinator wants.
     */
    @Test
    fun `a bare open row flows through when the result is open too`() {
        assertEquals(7L, MezzeTestHelper.asLong(eval("""
            let run_it: { g: {} -> Int <...> } -> Int <...> = { g } -> g {}
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
     * would mean something. It is also why T0219 has to REMOVE a row from a
     * function alias rather than ask you to move it to the use site: there is no
     * use-site position that could own it.
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
     * Two arrows, two rows, both enforced.
     *
     * `{ n: Int } -> ({} -> Int <Tick>) <Tock>` has `<Tock>` as the OUTER arrow's
     * row and `<Tick>` as the returned thunk's. Nothing overrides anything. This
     * was written through an alias (`-> TickThunk <Tock>`) to show that the
     * use-site row does not modify the alias; with T0219 the aliased spelling is
     * gone, and written out the independence is simply visible.
     */
    @Test
    fun `an inner arrow keeps its own row while the outer keeps its`() {
        assertEquals(16L, MezzeTestHelper.asLong(eval("""
            let f: { n: Int } -> ({} -> Int <Tick>) <Tock> = { n } -> do
              let t = Tock.tock {}
              {} -> Tick.tick {} + t
            perform Tocker in perform Ticker in (f { n = 1 }) {}
        """.trimIndent())))
    }

    /**
     * The enforcement half: the returned thunk performs `Tock`, so it does not
     * match the declared `<Tick>` on the inner arrow — the outer `<Tock>` does not
     * reach inside it.
     */
    @Test
    fun `the inner arrow's row still binds the returned thunk`() {
        val t = assertThrows(Throwable::class.java) {
            eval("""
                let f: { n: Int } -> ({} -> Int <Tick>) <Tock> = { n } -> do
                  let t = Tock.tock {}
                  {} -> Tock.tock {} + t
                perform Tocker in (f { n = 1 }) {}
            """.trimIndent())
        }
        assertTrue(
            (t.message ?: "").contains("Type annotation mismatch for 'f'"),
            "the outer row reached inside the inner arrow; got: ${t.message}",
        )
    }
}
