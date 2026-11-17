package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * The EFFECT ROW axis: laundering, the `*` marker, and the handler rules.
 * Rows are exact and `<...>` is a row VARIABLE, not a wildcard.
 */
class AbilityEffectSpecRowTest : AbilityEffectSpecBase() {

    private val tick = """
        effect Tick where { tick: {} -> Int <Tick>, }
        impl Tick for T1 where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          tick = {} -> 1,
        }
    """.trimIndent()

    private val ex = """
        effect Ex where { throw: { e: Str } -> 'a <Ex>, }
    """.trimIndent()

    @Test
    fun `a pure callback slot refuses an effectful function`() {
        rejects(
            """
            $tick
            let apply_pure: { f: {} -> Int } -> Int = { f } -> f {}
            let leak: {} -> Int <Tick> = {} -> apply_pure { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0201",
            "a pure slot is pure; this is the laundering the row must refuse",
        )
    }

    @Test
    fun `a callback slot declaring an open row accepts an effectful function`() {
        // The mirror: `<...>` is a PERMISSION, so the same program is fine once
        // the slot asks for one. Without this the rule above is weak evidence.
        accepts(
            """
            $tick
            let apply_any: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            let ok: {} -> Int <Tick> = {} -> apply_any { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "`<...>` is a row variable the caller's effects bind to",
        )
    }

    @Test
    fun `a function annotated pure may not perform`() {
        rejects(
            """
            $tick
            let lies: {} -> Int = {} -> Tick.tick {}
            $MAIN
            """,
            "T0210",
            "the annotation is a claim the body must honour",
        )
    }

    @Test
    fun `a body that performs AND handles needs the star marker`() {
        rejects(
            """
            $ex
            impl Ex for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> abort { val = "s" },
            }
            let use_it: {} -> Str = {} -> perform Stop {} in Ex.throw { e = "x" }
            $MAIN
            """,
            "T0210",
            "an annotation with no `*` hides that the body handles what it performs",
        )
    }

    @Test
    fun `the star marker spelling is a bare star`() {
        accepts(
            """
            $ex
            impl Ex for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> abort { val = "s" },
            }
            let use_it: {} -> Str <*> = {} -> perform Stop {} in Ex.throw { e = "x" }
            $MAIN
            """,
            "`<*>`; `<*Ex>` is a parse error",
        )
    }

    @Test
    fun `an abort payload must match the perform block's type`() {
        rejects(
            """
            $ex
            impl Ex for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> abort { val = 0 },
            }
            let use_it: {} -> Str <*> = {} -> perform Stop {} in Ex.throw { e = "x" }
            $MAIN
            """,
            "T0201",
            "the abort payload becomes the block's value, so the types must agree",
        )
    }

    // -------------------------------------------------------- handler rules

    @Test
    fun `resume in tail position is accepted`() {
        accepts(
            """
            effect Ex2 where { throw: { e: Str } -> Int <Ex2>, }
            impl Ex2 for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> resume { val = 1 },
            }
            $MAIN
            """,
            "the designed position",
        )
    }

    @Test
    fun `resume used as a value is refused`() {
        rejects(
            """
            effect Ex2 where { throw: { e: Str } -> Int <Ex2>, }
            impl Ex2 for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> resume { val = 1 } + 1,
            }
            $MAIN
            """,
            "V0503",
            "`resume` is the identity function, so arithmetic on it lands on the wrong value",
        )
    }

    @Test
    fun `abort inside initialize_scope is refused`() {
        rejects(
            """
            effect Ex2 where { throw: { e: Str } -> Int <Ex2>, }
            impl Ex2 for Stop where {
              initialize_scope = {} -> abort { val = 1 },
              finalize_scope = {} -> {},
              throw = { e } -> 1,
            }
            $MAIN
            """,
            "V0503",
            "the scope hooks are not op bodies; the op loop continues past them",
        )
    }

    // ------------------------------------------------- effects vs abilities

    @Test
    fun `two effects may declare the same op name`() {
        accepts(
            """
            effect A1 where { go: {} -> Int <A1>, }
            effect A2 where { go: {} -> Int <A2>, }
            impl A1 for P1 where {
              initialize_scope = {} -> {}, finalize_scope = {} -> {}, go = {} -> 11,
            }
            impl A2 for P2 where {
              initialize_scope = {} -> {}, finalize_scope = {} -> {}, go = {} -> 22,
            }
            let use_it: {} -> Int <*> = {} -> perform P1 {}, P2 {} in A1.go {}
            $MAIN
            """,
            "an effect op is always called qualified, so there is nothing to disambiguate",
        )
    }

    @Test
    fun `two abilities may NOT declare the same op name`() {
        // The asymmetry with the rule above, and its reason: an ability op is
        // reachable receiver-style, where the name alone would have to choose.
        rejects(
            """
            ability Weigh for 'a where { rank: { it: 'a } -> Int }
            ability Price for 'a where { rank: { it: 'a } -> Int }
            $MAIN
            """,
            "V0701",
            "`x.rank {}` could not pick, so the name belongs to one ability",
        )
    }
    // ── BUG 51: an UNANNOTATED higher-order function is effect-polymorphic ──
    // `{ f } -> f {}` used to infer a PURE `f`, so every effectful argument was
    // refused and `<...>` was required by hand. Rows are exact, so declaring
    // `<Tick>` on the caller proves the effect arrived — no more, no less.

    @Test
    fun `an unannotated HOF carries its callback's effect to the caller`() {
        accepts(
            """
            $tick
            let apply = { f } -> f {}
            let ok: {} -> Int <Tick> = {} -> apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "the principal type is `{ f: {} -> 'a <...> } -> 'a <...>`; no annotation is needed",
        )
    }

    @Test
    fun `an unannotated HOF does not launder its callback's effect`() {
        rejects(
            """
            $tick
            let apply = { f } -> f {}
            let leak: {} -> Int = {} -> apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0210",
            "the mirror: inferring the row open must not let it vanish at the caller",
        )
    }

    @Test
    fun `an unannotated HOF given a pure callback stays pure`() {
        accepts(
            """
            $tick
            let apply = { f } -> f {}
            let pure: {} -> Int = {} -> apply { f = {} -> 1 }
            $MAIN
            """,
            "the open row binds to the argument's row, which here is empty",
        )
    }

    // ── BUG 52: FORWARDING a callback through a HOF kept its row ──
    // `{ g } -> apply_any { f = g }`: `g` is a bare variable when the call is
    // generated, so it carried no row to raise, and the forwarder inferred pure
    // while `g`'s slot was open. A pure-declared caller performed Tick through
    // it, was ACCEPTED, and at run time the effect silently never happened.

    @Test
    fun `forwarding a callback through an annotated HOF does not launder`() {
        rejects(
            """
            $tick
            let apply_any: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            let fwd = { g } -> apply_any { f = g }
            let leak: {} -> Int = {} -> fwd { g = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0210",
            "the forwarder's result row IS its callback's row",
        )
    }

    @Test
    fun `forwarding a callback through an annotated HOF carries the effect`() {
        accepts(
            """
            $tick
            let apply_any: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            let fwd = { g } -> apply_any { f = g }
            let ok: {} -> Int <Tick> = {} -> fwd { g = {} -> Tick.tick {} }
            $MAIN
            """,
            "the mirror: the effect arrives, so the exact `<Tick>` matches",
        )
    }

    @Test
    fun `forwarding through an unannotated HOF carries the effect`() {
        accepts(
            """
            $tick
            let apply = { f } -> f {}
            let fwd = { g } -> apply { f = g }
            let ok: {} -> Int <Tick> = {} -> fwd { g = {} -> Tick.tick {} }
            $MAIN
            """,
            "BUG 51 and 51 together: two unannotated layers, one row",
        )
    }

    @Test
    fun `forwarding through an unannotated HOF does not launder`() {
        rejects(
            """
            $tick
            let apply = { f } -> f {}
            let fwd = { g } -> apply { f = g }
            let leak: {} -> Int = {} -> fwd { g = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0210",
            "the mirror of the above",
        )
    }
    // ── BUG 53: a callback's effect reaching the caller INDIRECTLY ──
    // Effects flow through call-site harvests, not row bindings. A callee that
    // RETURNS a function carrying its callback's row performed nothing at the
    // call, and a callback handed over inside a nested record was one level
    // deeper than the harvest looked. Both let a pure-declared caller perform.

    @Test
    fun `a returned function carries its captured callback's effect`() {
        rejects(
            """
            $tick
            let delay = { f } -> {} -> f {}
            let leak: {} -> Int = {} -> (delay { f = {} -> Tick.tick {} }) {}
            $MAIN
            """,
            "T0210",
            "`delay`'s result row IS `f`'s row; calling it performs Tick",
        )
    }

    @Test
    fun `a returned function carries the effect when annotated too`() {
        rejects(
            """
            $tick
            let delay: { f: {} -> Int <...> } -> {} -> Int <...> = { f } -> {} -> f {}
            let leak: {} -> Int = {} -> (delay { f = {} -> Tick.tick {} }) {}
            $MAIN
            """,
            "T0210",
            "the same hole with the row written by hand — it was never inference's",
        )
    }

    @Test
    fun `a returned function's carried effect is exactly the callback's`() {
        accepts(
            """
            $tick
            let delay = { f } -> {} -> f {}
            let ok: {} -> Int <Tick> = {} -> (delay { f = {} -> Tick.tick {} }) {}
            let pure: {} -> Int = {} -> (delay { f = {} -> 1 }) {}
            $MAIN
            """,
            "the mirrors: Tick arrives exactly, and a pure callback stays pure",
        )
    }

    @Test
    fun `a callback inside a nested record argument is harvested`() {
        rejects(
            """
            $tick
            let box = { f } -> { run = f }
            let fire = { b } -> b.run {}
            let leak: {} -> Int = {} -> fire { b = box { f = {} -> Tick.tick {} } }
            $MAIN
            """,
            "T0210",
            "`{ b: { run: fn <Tick> } }` — the callback is two levels down",
        )
    }

    @Test
    fun `a callback inside a nested record argument carries exactly its effect`() {
        accepts(
            """
            $tick
            let box = { f } -> { run = f }
            let fire = { b } -> b.run {}
            let ok: {} -> Int <Tick> = {} -> fire { b = box { f = {} -> Tick.tick {} } }
            $MAIN
            """,
            "the mirror",
        )
    }
    // ── BUG 54: a PERFORMER op body dropped its ability obligations ──
    // Ability and inherent impl bodies report an unsatisfied obligation; a
    // performer body reported only T0201, so `m + 1` with `m: Str` compiled and
    // died at run time with "cannot add a Str and an Int".

    private val say = """
        effect Say where { say: { m: Str } -> Int <Say>, }
    """.trimIndent()

    @Test
    fun `a performer op body may not use an operator its types lack`() {
        rejects(
            """
            $say
            impl Say for S where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              say = { m } -> m + 1,
            }
            $MAIN
            """,
            "V0402",
            "`m` is Str by the op's signature; `NumAdd Str Int` has no impl",
        )
    }

    @Test
    fun `a performer op body may not call an ability its types lack`() {
        rejects(
            """
            $say
            ability Wt for 'a where { wt: { it: 'a } -> Int }
            impl Say for S where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              say = { m } -> Wt.wt { it = m },
            }
            $MAIN
            """,
            "V0402",
            "a user ability, same rule",
        )
    }

    @Test
    fun `a performer op body's satisfied obligations stay legal`() {
        accepts(
            """
            effect Chk where { chk: { v: 'a } -> Bool <Chk>, n: { k: Int } -> Int <Chk>, }
            impl Chk for C where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              chk = { v } -> v == v,
              n = { k } -> k + 1,
            }
            $MAIN
            """,
            "the mirrors: a SITE-chosen `'a` is not settled here, and Int + Int is fine",
        )
    }
    // ── BUG 55: two unannotated callbacks called in one body ──
    // Each got its own open row and a body carries one tail, so all but the
    // first were dropped: `compose`'s `g` performed Tick under a pure caller.

    private val compose = "let compose = { f, g } -> { x } -> g { x = f { x = x } }"

    @Test
    fun `compose carries the SECOND callback's effect`() {
        rejects(
            """
            $tick
            $compose
            let leak: {} -> Int = {} -> (compose { f = { x } -> x + 1, g = { x } -> Tick.tick {} }) { x = 1 }
            $MAIN
            """,
            "T0210",
            "`g`'s row was the one dropped",
        )
    }

    @Test
    fun `compose carries the FIRST callback's effect`() {
        rejects(
            """
            $tick
            $compose
            let leak: {} -> Int = {} -> (compose { g = { x } -> x + 1, f = { x } -> Tick.tick {} }) { x = 1 }
            $MAIN
            """,
            "T0210",
            "the mirror by position",
        )
    }

    @Test
    fun `compose's carried effect is exactly the callbacks'`() {
        accepts(
            """
            $tick
            $compose
            let ok: {} -> Int <Tick> = {} -> (compose { f = { x } -> x + 1, g = { x } -> Tick.tick {} }) { x = 1 }
            let pure: {} -> Int = {} -> (compose { f = { x } -> x + 1, g = { x } -> x * 2 }) { x = 1 }
            $MAIN
            """,
            "the mirrors: Tick arrives exactly; two pure callbacks stay pure",
        )
    }
    // ── A row mismatch is explained as a row mismatch ──
    // The receiver obligation of `b.noise {}` (discharged against `Bx`) stayed on
    // the inferred type as `with Noisy 'a`, the explainer bailed on the
    // difference, and the user read `{} with Noisy 'a -> Int <Tick>` instead of
    // "the body performs <Tick>".

    @Test
    fun `an effectful ability op under a pure annotation names the effect`() {
        val r = check(
            """
            $tick
            type Bx = { v: Int }
            ability Noisy for 'a where { noise: { it: 'a } -> Int <Tick> }
            impl Noisy for Bx where { noise = { it } -> Tick.tick {} }
            let go: {} -> Int = {} -> do
              let b: Bx = { v = 1 }
              b.noise {}
            $MAIN
            """.trimIndent(),
        )
        val msg = r.exceptionOrNull()?.message ?: ""
        kotlin.test.assertTrue(msg.contains("performs effect <Tick>"), "the cause, in words: $msg")
        kotlin.test.assertFalse(msg.contains("with Noisy"), "not a discharged obligation: $msg")
        kotlin.test.assertTrue(msg.contains("-> Int <Tick>"), "the fix uses the real return type: $msg")
    }
}
