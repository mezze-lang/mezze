package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * An effect belongs to the arrow that performs it. A lazy `Iter` STAGE (`map`,
 * `filter`, `unfold`, ...) does not perform its callback — it stores it for a
 * later pull — so there is no honest arrow for the callback's effects to sit on:
 * charging them to the stage call let a `perform` around the call "handle" an
 * effect that actually ran later, wherever the Iter was pulled, with no handler
 * installed. The runtime then printed `<suspended <hash>>`.
 *
 *     let it = perform Ticker in map { it = xs, f = { x } -> Tick.tick {} }
 *     for_each { it = it, f = { x } -> println { msg = x } }   # Tick runs HERE
 *
 * So stage callbacks are pure by type, and SINK callbacks (`for_each`, `fold`,
 * ...), which run during the call, keep their row. The same wildcard hides in a
 * written `<...>` that has no callback to carry from — a declaration's own row
 * or a returned record's field — and that spelling is refused (T0210).
 */
class IterStageCallbacksArePureTest : VariantSpecBase() {

    private val eff = """
        use std::collections::iter::{ from_seq, map, filter, unfold, for_each, fold }
        effect Tick where { tick: {} -> Str <Tick> }
        impl Tick for Ticker where { tick = {} -> "tick" }
    """.trimIndent()

    @Test fun `an effectful map callback is refused`() {
        rejects(
            """
            $eff
            pub let escape = {} -> perform Ticker in map { it = from_seq { xs = [1, 2] }, f = { x } -> Tick.tick {} }
            $MAIN
            """,
            STAGE_MISMATCH,
            "THE REPRO. `map` stores `f` for a later pull, so `perform Ticker` here handles " +
                "nothing and the returned Iter would perform Tick wherever it is pulled.",
        )
    }

    @Test fun `an effectful unfold step is refused`() {
        rejects(
            """
            $eff
            pub let src = {} -> perform Ticker in
              unfold { state = 0, step = { s } -> if s < 2 then Yield { val = Tick.tick {}, next = s + 1 } else Done }
            $MAIN
            """,
            STAGE_MISMATCH,
            "A source is a stage too: its step runs per pull, not when `unfold` is called.",
        )
    }

    @Test fun `an effectful filter predicate is refused`() {
        rejects(
            """
            $eff
            use std::str
            pub let keep = {} -> perform Ticker in
              filter { it = from_seq { xs = ["a", "tick"] }, p = { x } -> str::equals { l = x, r = Tick.tick {} } }
            $MAIN
            """,
            STAGE_MISMATCH,
            "`filter` decides later, at pull time.",
        )
    }

    @Test fun `a pure stage feeding an effectful sink is accepted`() {
        accepts(
            """
            $eff
            pub let run: {} -> {} <*> = {} -> perform Ticker in
              for_each { it = map { it = from_seq { xs = [1, 2] }, f = { x } -> x * 2 }, f = { x } -> do
                Tick.tick {}
                {} }
            $MAIN
            """,
            "THE MIRROR CASE. The sink runs its callback DURING the call, so the row sits on " +
                "the arrow the handler is installed around.",
        )
    }

    @Test fun `an effectful fold callback is accepted`() {
        accepts(
            """
            $eff
            pub let run: {} -> Str <*> = {} -> perform Ticker in
              fold { it = from_seq { xs = [1, 2] }, init = "", f = { acc, x } -> "${'$'}(acc)${'$'}(Tick.tick {})" }
            $MAIN
            """,
            "`fold` is the canonical sink.",
        )
    }

    // ---- the written wildcard: a bare `<...>` with nothing to carry from ----

    @Test fun `a bare open row on a parameterless declaration is refused`() {
        rejects(
            """
            use std::int
            effect Tick where { tick: {} -> Int <Tick> }
            impl Tick for Ticker where { tick = {} -> 1 }
            pub let g: {} -> Int <...> = {} -> Tick.tick {}
            $MAIN
            """,
            "T0210",
            "No parameter is a callback, so `<...>` carries from nothing and used to drop Tick: " +
                "the emitted type was `g : {} -> Int`, and a pure caller got through.",
        )
    }

    @Test fun `a returned record whose field carries a bare open row is refused`() {
        rejects(
            """
            effect Tick where { tick: {} -> Str <Tick> }
            impl Tick for Ticker where { tick = {} -> "tick" }
            pub type Thunk 'a = { run: {} -> 'a <...> }
            pub let mk: {} -> Thunk Str <*> = {} -> perform Ticker in { run = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0210",
            "The alias's `<...>` has no callback to carry from, so the annotation laundered Tick: " +
                "`(mk {}).run {}` outside any handler was accepted and hit the runtime guard.",
        )
    }

    @Test fun `the same record inferred, not annotated, is tracked`() {
        val src = """
            effect Tick where { tick: {} -> Str <Tick> }
            impl Tick for Ticker where { tick = {} -> "tick" }
            let mk = {} -> perform Ticker in { run = {} -> Tick.tick {} }
            pub let use_it = {} -> (mk {}).run {}
        """
        val t = printedType(src, "use_it")
        org.junit.jupiter.api.Assertions.assertTrue(
            "Tick" in t,
            "Inference carries the field's row through the value: calling `run` outside any " +
                "handler must leave Tick on `use_it`'s own row. Only the WRITTEN `<...>` was the hole. got: $t",
        )
    }

    @Test fun `a bare open row on a callback parameter stays correct`() {
        accepts(
            """
            use std::int
            effect Tick where { tick: {} -> Int <Tick> }
            impl Tick for Ticker where { tick = {} -> 1 }
            pub let apply: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            pub let run: {} -> Int <*> = {} -> perform Ticker in apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "On a parameter `<...>` is exactly right: the result's `<...>` carries from it. " +
                "Every stdlib HOF is written this way and must stay accepted.",
        )
    }

    @Test fun `a returned alias with an open-row field is fine when a parameter carries`() {
        accepts(
            """
            pub type Thunk 'a = { run: {} -> 'a <...> }
            pub let wrap: { f: {} -> Str <...> } -> Thunk Str = { f } -> { run = f }
            $MAIN
            """,
            "The result's field row carries from `f`'s open row, so nothing is laundered.",
        )
    }

    private companion object {
        /** The argument-mismatch diagnostic a non-pure callback hits at a pure stage parameter. */
        const val STAGE_MISMATCH = "T0201"
    }
}
