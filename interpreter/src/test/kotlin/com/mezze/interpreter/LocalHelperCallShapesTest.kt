package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A call through a `let`-bound lambda that captures nothing is compiled as a
 * DIRECT call by hash rather than a dispatch on the slot's value, which is what
 * lets the helper's body be inlined and any record it builds be erased.
 *
 * THESE ARE THE SHAPES THAT CHANGE MEANING IF THAT IS DONE CARELESSLY. A direct
 * call commits to the callee's arity and parameter order at compile time, so
 * every way of calling a function that ISN'T "all arguments, by name, once" has
 * to keep working: partial application, a spread argument, the helper handed
 * around as a value, a capturing helper (whose captures live in the value and
 * would be dropped), one name rebound to a different lambda, and a shadowing
 * `let` in an inner block.
 *
 * A lowering test cannot see any of this — the IR looks reasonable either way.
 * Only running the program does.
 */
class LocalHelperCallShapesTest {

    private fun run(src: String): Any? = MezzeTestHelper.run(src.trimIndent())

    @Test
    fun `the loop that motivated this computes the same sum`() = assertEquals(
        55L,
        run(
            """
            use std::int
            let main = {} -> do
              let split = { i } -> { nxt = i - 1, cur = i }
              let go = { i, acc } ->
                if i <= 0 then acc
                else do
                  let p = split { i = i }
                  go { i = p.nxt, acc = acc + p.cur }
              go { i = 10, acc = 0 }
            main {}
            """,
        ),
    )

    @Test
    fun `a local helper applied to only some of its params still partially applies`() = assertEquals(
        // A direct scalar call needs the full parameter set; this one has half
        // of it, so the call has to fall back to the shape that returns a
        // function rather than binding `b` to nothing.
        30L,
        run(
            """
            use std::int
            let main = {} -> do
              let add = { a, b } -> a + b
              let add10 = add { a = 10 }
              add10 { b = 20 }
            main {}
            """,
        ),
    )

    @Test
    fun `a local helper called with a spread argument`() = assertEquals(
        // A spread is one of the shapes whose field set is not known positionally
        // at the call site, so this must not be emitted as a positional call.
        7L,
        run(
            """
            use std::int
            let main = {} -> do
              let pair = { a, b } -> a + b
              let args = { a = 3, b = 4 }
              pair { ...args }
            main {}
            """,
        ),
    )

    @Test
    fun `a local helper is still a value`() = assertEquals(
        // Devirtualising the CALL must not stop the name from being a function
        // value: the slot is written exactly as before, so it can be passed on
        // and called through, here in the same body as a direct call.
        12L,
        run(
            """
            use std::int
            let main = {} -> do
              let twice = { x } -> x * 2
              let apply = { f, v } -> f { x = v }
              twice { x = 1 } + apply { f = twice, v = 5 }
            main {}
            """,
        ),
    )

    @Test
    fun `a capturing helper keeps its captures`() = assertEquals(
        // The refusal, as behaviour. `bias` lives in the closure VALUE; a direct
        // call by hash would enter a frame that was never given it.
        63L,
        run(
            """
            use std::int
            let main = {} -> do
              let bias = 7
              let scale = { i } -> i * bias
              scale { i = 9 }
            main {}
            """,
        ),
    )

    // THERE IS NO "REBIND" CASE because the language has no such thing: a second
    // `let f` in the same block is rejected outright — "'f' is already bound in
    // this scope. This `let` silently overwrites the earlier binding". So the
    // only way one spelling can mean two functions is the inner block below,
    // and that is the case this file has to pin.

    @Test
    fun `an inner block's shadowing helper does not leak out`() = assertEquals(
        // Both calls are spelled the same and mean different functions. The
        // inner one must not still be in effect after its block ends.
        "100|2",
        run(
            """
            use std::int
            let main = {} -> do
              let g = { x } -> x + 1
              let inner = do
                let g = { x } -> x * 100
                g { x = 1 }
              let outer = g { x = 1 }
              "$(inner.to_str {})|$(outer.to_str {})"
            main {}
            """,
        ),
    )

    @Test
    fun `a helper called from a nested closure gets the right one of two`() = assertEquals(
        // Two capture-free helpers captured by the same lambda: the map is keyed
        // by capture INDEX, and crossing the indices would run the other one.
        // The two results are kept in one number rather than a string because
        // `use_both`'s param is generic — a local lambda is generalised at its
        // `let`, so `dbl { x = v }` has no known type and an inherent method on
        // it cannot resolve. Crossed, this reads 406 instead of 604.
        604L,
        run(
            """
            use std::int
            let main = {} -> do
              let dbl = { x } -> x * 2
              let inc = { x } -> x + 1
              let use_both = { v } -> do
                let a = dbl { x = v }
                let b = inc { x = v }
                a * 100 + b
              use_both { v = 3 }
            main {}
            """,
        ),
    )

    @Test
    fun `a recursive local helper still recurses`() = assertEquals(
        // A recursive `let` is deliberately NOT registered: its self-call goes
        // through the path that keeps its own captures. This pins that leaving
        // it out did not break it.
        120L,
        run(
            """
            use std::int
            let main = {} -> do
              let fact = { n } -> if n <= 1 then 1 else n * fact { n = n - 1 }
              fact { n = 5 }
            main {}
            """,
        ),
    )
}
