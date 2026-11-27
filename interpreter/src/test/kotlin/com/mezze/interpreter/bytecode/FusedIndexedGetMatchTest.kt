package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `match seq::get / list::get / bytes::get is Some { val } -> … , None -> …` is
 * emitted as a raw indexed read plus a null test, the same fusion
 * [FusedMapGetMatchTest] covers for `map::get`, skipping the `MezzeVariant` the
 * native allocates for the `Some`.
 *
 * WHY. `bench/cases/allocsurface` at n=2e7, one kernel per process, counting
 * young collections: `seq get` 22, `list get` 21, `bytes get` 21, against 3 for
 * `map has`, `map size`, `str size` and the tier control. Every keyed read that
 * returns `Option` allocates and its `has`/`size` sibling does not.
 *
 * NULL IS THE PRESENCE SIGNAL, and it is available for all three because Mezze
 * has no null value, so no container can hold one. `bytes::get` boxes its byte
 * into a `Long` before returning it, so even a byte of ZERO comes back
 * non-null — which is the case a fusion testing falsiness instead of nullity
 * would get wrong, and it is tested below.
 *
 * WHAT ELSE CAN GO WRONG, and so what is tested:
 *
 *  - **The bounds check moved** from the native into the operation, so every
 *    edge it used to handle has to still hold: index 0, the last index, one
 *    past the end, and a NEGATIVE index.
 *  - **An element that is itself `None`** must be present, for the same reason
 *    a stored `None` must be in a map.
 *  - **`list::get`'s record field is named `index`**, not `i` as the seq and
 *    bytes ones are — a detail that only matters on the record ABI and was
 *    wrong in the first draft of the emitter.
 *
 *  - **A SEQ HAS TWO RUNTIME REPRESENTATIONS** and a list has three. The
 *    native does not receive a container, it receives something
 *    `NativeDispatch.getSeq`/`getList` COERCES: a `MezzeSeq` or a bare
 *    `Object[]`, and for a list a `Cons`/`Nil` variant as well. The first
 *    version of this fusion accepted only the canonical class and so was
 *    NARROWER than the native it replaced — not an optimisation but a change
 *    of semantics. It cost 39 interpreter tests, none of which mention
 *    `seq::get`: the call site is `find_field_value` in `std::codec::json`,
 *    reading the `fields` of a `JObject` that the parser native built as an
 *    `Object[]`. Every test in this class used a seq LITERAL, which is
 *    already wrapped, so none of them could see it. The last test below goes
 *    through the parser for exactly that reason.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL: dropping the lower bound from all three
 * raw operations — so a NEGATIVE index is no longer refused — fails three of the
 * six range tests, one per container. The three that survive are the ones that
 * do not probe a negative index, which is the whole reason each range test
 * does. Narrowing the operations back to the canonical classes fails the
 * representation test, and nothing else here.
 */
class FusedIndexedGetMatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    // ─────────────────────────────── seq ───────────────────────────────

    @Test
    fun `a seq read in range gives the element and out of range gives None`() {
        assertEquals(1L, run(
            """
            use std::collections::seq
            let xs = [10, 20, 30]
            let at = { i } -> match seq::get { xs = xs, i = i } is
              Some { val } -> val
              None -> 0 - 1
            let first = at { i = 0 }
            let last = at { i = 2 }
            let past = at { i = 3 }
            let neg = at { i = 0 - 1 }
            if first == 10 && last == 30 && past == (0 - 1) && neg == (0 - 1) then 1 else 0
            """
        ))
    }

    @Test
    fun `a seq element that is itself None is still present`() {
        assertEquals(1L, run(
            """
            use std::collections::seq
            use std::option
            let xs = [None, Some { val = 5 }]
            let zeroth = match seq::get { xs = xs, i = 0 } is
              Some { val } -> match val is
                Some { ... } -> 0
                None -> 1
              None -> 0 - 1
            zeroth
            """
        ))
    }

    // ─────────────────────────────── list ──────────────────────────────

    @Test
    fun `a list read in range gives the element and out of range gives None`() {
        assertEquals(1L, run(
            """
            use std::collections::list
            let xs = list::cons { head = 7, tail = list::cons { head = 8, tail = list::empty {} } }
            let at = { i } -> match list::get { xs = xs, index = i } is
              Some { val } -> val
              None -> 0 - 1
            let first = at { i = 0 }
            let last = at { i = 1 }
            let past = at { i = 2 }
            let neg = at { i = 0 - 1 }
            if first == 7 && last == 8 && past == (0 - 1) && neg == (0 - 1) then 1 else 0
            """
        ))
    }

    @Test
    fun `the empty list reads as None`() {
        assertEquals(1L, run(
            """
            use std::collections::list
            match list::get { xs = list::empty {}, index = 0 } is
              Some { ... } -> 0
              None -> 1
            """
        ))
    }

    // ────────────────────────────── bytes ──────────────────────────────

    @Test
    fun `a bytes read in range gives the unsigned byte and out of range gives None`() {
        assertEquals(1L, run(
            """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [0, 128, 255] }
            let at = { i } -> match bytes::get { b = b, i = i } is
              Some { val } -> val
              None -> 0 - 1
            let zero = at { i = 0 }
            let high = at { i = 1 }
            let top = at { i = 2 }
            let past = at { i = 3 }
            let neg = at { i = 0 - 1 }
            if zero == 0 && high == 128 && top == 255 && past == (0 - 1) && neg == (0 - 1)
              then 1 else 0
            """
        ))
    }

    /**
     * THE CASE A FALSINESS TEST WOULD FAIL. A byte of ZERO is present, and it
     * boxes to a `Long` that is emphatically not null. Only an out-of-range
     * index is absent.
     */
    @Test
    fun `a byte of zero is present and not absent`() {
        assertEquals(1L, run(
            """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [0] }
            let present = match bytes::get { b = b, i = 0 } is
              Some { val } -> if val == 0 then 1 else 0 - 1
              None -> 0
            present
            """
        ))
    }

    // ───────────────── representation ──────────────────

    /**
     * A SEQ THAT IS STILL A BARE `Object[]`. Nothing here mentions `seq::get`:
     * the read is `find_field_value` inside `std::codec::json`, walking the
     * `fields` of a `JObject` the parser native built as an `Object[]` and
     * never wrapped. This is the shape that failed 39 interpreter tests when
     * the fusion accepted only `MezzeSeq`, and no seq-literal test can reach
     * it, because a literal is already wrapped.
     */
    @Test
    fun `a seq still in Object array form reads through the fused get`() {
        assertEquals("abc", run(
            """
            use std::codec::json::{ parse_str, FromJson }
            parse_str { s = "{\"code\":\"abc\"}" }
              .to_option {}
              .flat_map { f = { val } -> val.from_json {}.to_option {} }
              .map { f = { val } -> val.code }
              .get_or { def = "?" }
            """
        ).toString())
    }
}
