package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `cont.resume { val = X }` must mean exactly `X`, in every shape.
 *
 * WHY THESE EXIST. `CallIndirectScalar1.doFallThroughResume` returns the
 * argument instead of calling the fall-through resume singleton, whose entire
 * body is `rec.getField("val")`. That removes a record allocation and an
 * un-inlined indirect call: measured in `bench/cases/effectsurface`, kernel 4
 * went from 4 091 ps/iter to 921, against an implicit bare-value handler at 788
 * and a direct call at 836 — so the explicit and implicit forms now cost the
 * same, where before the explicit one cost 11x.
 *
 * THESE PASS BEFORE AND AFTER THAT CHANGE, deliberately: it is a performance
 * change whose entire point is that nothing observable moves, so a test failing
 * beforehand would mean the change broke something. What they guard is the fast
 * path being WRONG — the identity guard matching a function it should not, the
 * field-name guard missing, or the argument not being what the callee would
 * have returned. Verified non-vacuous by making `doFallThroughResume` return a
 * different value and watching them fail.
 *
 * The nesting case is the one that would catch the subtlest error. The codebase
 * already documents the trap for the fused-variant reader — `Ok { val = { val =
 * 42 } }`, where a naive implementation drills one level too far. The same trap
 * applies here: when `X` is itself a record with a `val` field, returning the
 * ARGUMENT is right and returning `arg.val` would be wrong.
 */
class ResumeFallThroughSemanticsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `resume returns an Int payload`() {
        assertEquals(1L, MezzeTestHelper.run("""
            effect Counter where { tick: {} -> Int <Counter> }
            impl Counter for Bump where { tick = {} -> cont.resume { val = 1 } }
            perform Bump in Counter.tick {}
        """.trimIndent()))
    }

    @Test
    fun `resume returns a Str payload`() {
        assertEquals("hi", MezzeTestHelper.run("""
            effect Ask where { ask: {} -> Str <Ask> }
            impl Ask for Fixed where { ask = {} -> cont.resume { val = "hi" } }
            perform Fixed in Ask.ask {}
        """.trimIndent()))
    }

    @Test
    fun `resume carries the op's own argument through`() {
        assertEquals(43L, MezzeTestHelper.run("""
            effect Echo where { echo: { v: Int } -> Int <Echo> }
            impl Echo for Inc where { echo = { v } -> cont.resume { val = v + 1 } }
            perform Inc in Echo.echo { v = 42 }
        """.trimIndent()))
    }

    @Test
    fun `a handler may compute before resuming`() {
        assertEquals(30L, MezzeTestHelper.run("""
            effect Echo where { echo: { v: Int } -> Int <Echo> }
            impl Echo for Trebled where {
                echo = { v } -> do
                  let doubled = v * 2
                  let plus = doubled + v
                  cont.resume { val = plus }
            }
            perform Trebled in Echo.echo { v = 10 }
        """.trimIndent()))
    }

    /**
     * THE NESTING TRAP. The payload is itself a record with a `val` field, so
     * an implementation that unwraps one level too many returns 42 instead of
     * the record. Reading `.val` at the call site is what distinguishes them.
     */
    @Test
    fun `resume with a record payload does not unwrap a level too far`() {
        assertEquals(42L, MezzeTestHelper.run("""
            effect Wrap where { get: {} -> { val: Int } <Wrap> }
            impl Wrap for Nested where { get = {} -> cont.resume { val = { val = 42 } } }
            let get_it = {} -> do
              let r = perform Nested in Wrap.get {}
              r.val
            get_it {}
        """.trimIndent()))
    }

    @Test
    fun `resume with a two-field record payload`() {
        assertEquals(7L, MezzeTestHelper.run("""
            effect Pair where { pair: {} -> { a: Int, b: Int } <Pair> }
            impl Pair for Mk where { pair = {} -> cont.resume { val = { a = 3, b = 4 } } }
            let get_it = {} -> do
              let p = perform Mk in Pair.pair {}
              p.a + p.b
            get_it {}
        """.trimIndent()))
    }

    /**
     * The result of `resume` used further, so the handler's value is NOT just
     * the resume argument. Fall-through returns the argument, so this is
     * `1 + 5`; the specialization must not change that.
     */
    /**
     * V0503 REFUSES this shape, and that is the decision rather than a gap.
     *
     * These two cases asserted that `resume`'s result could be used FURTHER in
     * the handler (`let r = cont.resume { val = 1 }` then `r + 5`). That reads as
     * ordinary control flow and is not: resume does not transfer control, so the
     * work after it runs BEFORE the resumed computation. The rule is that
     * `resume` is the last thing an operation does — decided on the
     * type-soundness branch and pinned by 18 cases in
     * `HandlerTailPositionSpecTest`, including `work after resume runs before
     * the body resumes, not after`.
     *
     * Converted rather than deleted when `integration/all-three` merged the two
     * branches: the shape this file was written to observe is illegal, so what it
     * now guards is that the REFUSAL holds. The identity it was really about —
     * `cont.resume { val = X }` means exactly `X` — is still covered by every other
     * case here, in the legal tail spelling.
     *
     * The perf change this file guards is unaffected: it is about what
     * `cont.resume { val = X }` COSTS, not where it may appear.
     */
    @Test
    fun `resume's result may NOT be used further in the handler`() {
        val e = assertThrows(Throwable::class.java) {
            MezzeTestHelper.run("""
                effect Counter where { tick: {} -> Int <Counter> }
                impl Counter for Bump where {
                    tick = {} -> do
                      let r = cont.resume { val = 1 }
                      r + 5
                }
                perform Bump in Counter.tick {}
            """.trimIndent())
        }
        val message = e.message ?: ""
        assertTrue(
            message.contains("must be the last thing"),
            "expected the tail-position rule (V0503) to refuse a resume whose " +
                "result is used further in the handler, got: $message",
        )
    }

    @Test
    fun `two ops in one handler both resume correctly`() {
        assertEquals(12L, MezzeTestHelper.run("""
            effect Two where {
                first: {} -> Int <Two>,
                second: {} -> Int <Two>
            }
            impl Two for Both where {
                first = {} -> cont.resume { val = 5 },
                second = {} -> cont.resume { val = 7 }
            }
            perform Both in Two.first {} + Two.second {}
        """.trimIndent()))
    }

    @Test
    fun `resume inside a loop keeps returning the right value`() {
        assertEquals(500500L, MezzeTestHelper.run("""
            effect Unit1 where { one: {} -> Int <Unit1> }
            impl Unit1 for Ones where { one = {} -> cont.resume { val = 1 } }
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - Unit1.one {}, acc = acc + i }
            perform Ones in go { i = 1000, acc = 0 }
        """.trimIndent()))
    }

    // ── WARM variants ────────────────────────────────────────────────────────
    //
    // Everything above runs its handler ONCE, which takes the uncached path and
    // never reaches `doFallThroughResume`. Verified: breaking the fast path to
    // return a wrong constant failed only the loop test out of the original
    // ten. So each case that could expose a wrong unwrap is repeated here
    // inside a loop, where the specialization is engaged and a wrong answer has
    // somewhere to show up.

    /**
     * THE NESTING TRAP, ON THE FAST PATH. The payload is a record with its own
     * `val` field, and the loop makes sure the specialization is what answers.
     * Returning `arg.val` instead of `arg` would yield 42 per iteration here
     * rather than the record, and the sum would be wrong.
     */
    @Test
    fun `WARM - resume with a record payload does not unwrap a level too far`() {
        assertEquals(42000L, MezzeTestHelper.run("""
            effect Wrap where { get: {} -> { val: Int } <Wrap> }
            impl Wrap for Nested where { get = {} -> cont.resume { val = { val = 42 } } }
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let r = Wrap.get {}
                go { i = i - 1, acc = acc + r.val }
            perform Nested in go { i = 1000, acc = 0 }
        """.trimIndent()))
    }

    @Test
    fun `WARM - resume with a two-field record payload`() {
        assertEquals(7000L, MezzeTestHelper.run("""
            effect Pair where { pair: {} -> { a: Int, b: Int } <Pair> }
            impl Pair for Mk where { pair = {} -> cont.resume { val = { a = 3, b = 4 } } }
            let go = { i, acc } ->
              if i <= 0 then acc
              else do
                let p = Pair.pair {}
                go { i = i - 1, acc = acc + p.a + p.b }
            perform Mk in go { i = 1000, acc = 0 }
        """.trimIndent()))
    }

    @Test
    fun `WARM - resume carries the op's own argument through`() {
        assertEquals(501500L, MezzeTestHelper.run("""
            effect Echo where { echo: { v: Int } -> Int <Echo> }
            impl Echo for Inc where { echo = { v } -> cont.resume { val = v + 1 } }
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + Echo.echo { v = i } }
            perform Inc in go { i = 1000, acc = 0 }
        """.trimIndent()))
    }

    @Test
    fun `WARM - resume returns a Str payload`() {
        assertEquals(2000L, MezzeTestHelper.run("""
            use std::str
            effect Ask where { ask: {} -> Str <Ask> }
            impl Ask for Fixed where { ask = {} -> cont.resume { val = "hi" } }
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + str::size { s = Ask.ask {} } }
            perform Fixed in go { i = 1000, acc = 0 }
        """.trimIndent()))
    }


    /**
     * A handler that ABORTS still aborts — the resume specialization must not
     * have captured the abort binding, which resolves through the same
     * `EffectBinding` operation and is a different singleton.
     */
    @Test
    fun `abort is unaffected by the resume fast path`() {
        assertEquals(99L, MezzeTestHelper.run("""
            effect Stop where { stop: {} -> Int <Stop> }
            impl Stop for Halt where { stop = {} -> cont.abort { val = 99 } }
            perform Halt in Stop.stop {}
        """.trimIndent()))
    }
}
