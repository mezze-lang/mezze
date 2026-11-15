package com.mezze.interpreter.collections

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every deque op moved onto the scalar native ABI, exercised by VALUE.
 *
 * The record ABI hands the handler a `MezzeRecord` built per call which it reads
 * back by field name; a declared op gets positional, typed arguments and
 * allocates nothing. MEASURED on `bench/cases/dequeabi` at n=5e6, min of 3, two
 * builds (the declaration table is read at COMPILE time, so the A/B is two
 * `compileStdlib` runs — getting that wrong fails loudly with
 * `no-handler:__native_deque_is_empty`, which is how I found out):
 *
 * | op | record | scalar |
 * |---|---|---|
 * | `is_empty` | 8 648 ps/iter | **4 336** — 2.06x on the op alone |
 * | `peek_front` | 12 861 | **8 778** |
 * | `push_back` | 52 241 | **30 990** |
 * | `rotate_left` | 56 506 | **48 787** |
 *
 * Control 244 against 253, so the table is readable. The spread is widest for
 * the narrowest op, which is what you would expect: `rotate_left` allocates a
 * whole new deque, so the ABI is a small share of it.
 *
 * WHY VALUE TESTS AND NOT A SHAPE ASSERTION. The failure mode of a declaration
 * is a wrong `Param` ORDER or a wrong `SlotKind` — the positional ABI passes
 * arguments by position, so swapping two of them type-checks, compiles, runs,
 * and returns a plausible wrong answer. Nothing about the lowered shape would
 * look different. So each op is called with arguments whose positions matter and
 * checked against a number only the correct order produces.
 */
class DequeScalarAbiTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(body: String): Any = MezzeTestHelper.run(
        "use std::collections::deque\nuse std::collections::seq\nuse std::collections::set\n\n${body.trimIndent()}",
    )

    private val D = "deque::from_seq { xs = [10, 20, 30, 40] }"

    @Test
    fun `is_empty distinguishes empty from not`() {
        assertEquals(
            1L,
            run(
                """
                let main = {} -> do
                  let e = if deque::is_empty { d = deque::from_seq { xs = [] } } then 1 else 0
                  let f = if deque::is_empty { d = $D } then 10 else 0
                  e + f
                main {}
                """,
            ),
        )
    }

    /** Front is 10 and back is 40 — swapping the two ops would read 40 and 10. */
    @Test
    fun `peek_front and peek_back read opposite ends`() {
        assertEquals(
            1040L,
            run(
                """
                let main = {} -> do
                  let d = $D
                  let f = match deque::peek_front { d = d } is
                    Some { val } -> val
                    None -> 0
                  let b = match deque::peek_back { d = d } is
                    Some { val } -> val
                    None -> 0
                  f * 100 + b
                main {}
                """,
            ),
        )
    }

    /** push_front and push_back must not be interchangeable. */
    @Test
    fun `push_front and push_back add to opposite ends`() {
        assertEquals(
            599L,
            run(
                """
                let main = {} -> do
                  let d = deque::push_front { d = deque::push_back { d = $D, x = 99 }, x = 5 }
                  let f = match deque::peek_front { d = d } is
                    Some { val } -> val
                    None -> 0
                  let b = match deque::peek_back { d = d } is
                    Some { val } -> val
                    None -> 0
                  f * 100 + b
                main {}
                """,
            ),
        )
    }

    /** pop_front and pop_back remove from opposite ends. */
    @Test
    fun `pop_front and pop_back remove from opposite ends`() {
        assertEquals(
            2030L,
            run(
                """
                let main = {} -> do
                  let d = $D
                  let pf = match deque::pop_front { d = d } is
                    Some { val } -> match deque::peek_front { d = val.tail } is
                      Some { val = v } -> v
                      None -> 0
                    None -> 0
                  let pb = match deque::pop_back { d = d } is
                    Some { val } -> match deque::peek_back { d = val.init } is
                      Some { val = v } -> v
                      None -> 0
                    None -> 0
                  pf * 100 + pb
                main {}
                """,
            ),
        )
    }

    /** `get` takes (d, i) — a swapped order would index the deque by the deque. */
    @Test
    fun `get indexes from the front`() {
        assertEquals(
            30L,
            run(
                """
                let main = {} -> match deque::get { d = $D, i = 2 } is
                  Some { val } -> val
                  None -> 0 - 1
                main {}
                """,
            ),
        )
    }

    /** `slice` takes (d, start, end) — swapping start and end yields nothing. */
    @Test
    fun `slice honours start before end`() {
        assertEquals(
            50L,
            run(
                """
                let main = {} -> do
                  let s = deque::slice { d = $D, start = 1, end = 3 }
                  deque::fold { d = s, init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    /** rotate_left and rotate_right must move in opposite directions. */
    @Test
    fun `rotate_left and rotate_right disagree`() {
        assertEquals(
            2040L,
            run(
                """
                let main = {} -> do
                  let l = match deque::peek_front { d = deque::rotate_left { d = $D, n = 1 } } is
                    Some { val } -> val
                    None -> 0
                  let r = match deque::peek_front { d = deque::rotate_right { d = $D, n = 1 } } is
                    Some { val } -> val
                    None -> 0
                  l * 100 + r
                main {}
                """,
            ),
        )
    }

    /** `concat` takes (d1, d2) in that order — reversed, the front would be 1. */
    @Test
    fun `concat keeps its argument order`() {
        assertEquals(
            10L,
            run(
                """
                let main = {} -> do
                  let c = deque::concat { d1 = $D, d2 = deque::from_seq { xs = [1, 2] } }
                  match deque::peek_front { d = c } is
                    Some { val } -> val
                    None -> 0
                main {}
                """,
            ),
        )
    }

    @Test
    fun `reverse singleton intersperse and unique`() {
        assertEquals(
            40L,
            run(
                """
                let main = {} -> match deque::peek_front { d = deque::reverse { d = $D } } is
                  Some { val } -> val
                  None -> 0
                main {}
                """,
            ),
        )
        assertEquals(
            7L,
            run(
                """
                let main = {} -> match deque::peek_front { d = deque::singleton { x = 7 } } is
                  Some { val } -> val
                  None -> 0
                main {}
                """,
            ),
        )
        // 10,0,20,0,30,0,40 — the separator goes BETWEEN, so three of them.
        assertEquals(
            100L,
            run(
                """
                let main = {} -> do
                  let i = deque::intersperse { d = $D, sep = 0 }
                  deque::fold { d = i, init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
        assertEquals(
            60L,
            run(
                """
                let main = {} -> do
                  let u = deque::unique { d = deque::from_seq { xs = [10, 20, 10, 30, 20] } }
                  deque::fold { d = u, init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }

    /** Conversions keep their contents. */
    @Test
    fun `to_set and from_set round-trip the elements`() {
        assertEquals(
            100L,
            run(
                """
                let main = {} -> do
                  let s = deque::to_set { d = $D }
                  let back = deque::from_set { s = s }
                  deque::fold { d = back, init = 0, f = { acc, x } -> acc + x }
                main {}
                """,
            ),
        )
    }
}
