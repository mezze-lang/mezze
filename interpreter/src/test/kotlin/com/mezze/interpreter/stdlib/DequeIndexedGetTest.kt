package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `deque::get` by index, which became O(1).
 *
 * WHY THESE EXIST. The native used to WALK THE ITERATOR to the index, making it
 * O(n) for an operation a ring buffer does in constant time. The fix calls
 * `MezzeDeque.get(int)` — one bounds check and `buffer[(head + index) & mask]`.
 * `bench/cases/dsmore`, interleaved, 1 000 elements:
 *
 *     deque::get by index    222 457 -> 9 070   24.5x
 *     deque::size   (control)    439      452   flat
 *     deque::first  (control)  5 307    5 306   flat
 *     sorted_map::size (ctrl)    445      433   flat
 *
 * and the slope went from linear — 71 933 / 221 294 / 839 410 ps at 250 / 1 000
 * / 4 000 — to flat: 8 150 / 10 797 / 11 076.
 *
 * WHAT THESE GUARD, and why the iteration version could not have needed them.
 * Walking the iterator cannot compute a wrong index; `(head + index) & mask`
 * can. The arithmetic is only exercised when the buffer has WRAPPED — when
 * `head` sits near the end of the array, so `head + index` runs past it and the
 * mask folds it back to the front. A deque that has only ever been pushed to
 * the back has `head == 0`, where a broken index calculation looks perfectly
 * correct. So the tests below move `head` deliberately, with pops and with
 * `push_front`, and then read every position.
 */
class DequeIndexedGetTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::collections::deque
        use std::option
    """.trimIndent()

    private val fill = """
        let fill = { i, n, d } -> if i > n then d
          else fill { i = i + 1, n = n, d = deque::push_back { d = d, x = i } }
    """.trimIndent()

    @Test
    fun `every index of a back-filled deque reads its own element`() {
        // 1..64 pushed to the back; sum of get(i) over 0..63 is 1+..+64 = 2080.
        assertEquals(2080L, MezzeTestHelper.run("""
            $prelude
            $fill
            let d = fill { i = 1, n = 64, d = deque::empty {} }
            let sum = { i, acc } -> if i < 0 then acc
              else do
                let v = option::get_or { opt = deque::get { d = d, i = i }, def = 0 }
                sum { i = i - 1, acc = acc + v }
            sum { i = 63, acc = 0 }
        """.trimIndent()))
    }

    @Test
    fun `index 0 is the front and the last index is the back`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let d = fill { i = 1, n = 10, d = deque::empty {} }
            let first = option::get_or { opt = deque::get { d = d, i = 0 }, def = 0 }
            let last = option::get_or { opt = deque::get { d = d, i = 9 }, def = 0 }
            if first == 1 && last == 10 then 1 else 0
        """.trimIndent()))
    }

    @Test
    fun `an out-of-range index is None, at both ends`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let d = fill { i = 1, n = 10, d = deque::empty {} }
            let over = match deque::get { d = d, i = 10 } is
              Some { val } -> 0
              None -> 1
            let under = match deque::get { d = d, i = 0 - 1 } is
              Some { val } -> 0
              None -> 1
            let empty = match deque::get { d = deque::empty {}, i = 0 } is
              Some { val } -> 0
              None -> 1
            if over == 1 && under == 1 && empty == 1 then 1 else 0
        """.trimIndent()))
    }

    /**
     * THE WRAP-AROUND CASE, which is the whole point. Pops advance `head`, so
     * after enough of them `head + index` runs past the end of the buffer and
     * the mask has to fold it back. An index calculation that forgets the mask,
     * or uses the wrong base, reads the wrong element or throws here and looks
     * fine on a deque that was only ever pushed to the back.
     */
    @Test
    fun `indices are right after pops have advanced the head`() {
        // Build 1..32, drop the first 20, push 40..49 on the back.
        // Remaining front-to-back: 21..32 then 40..49 — 22 elements.
        // Sum = (21+..+32) + (40+..+49) = 318 + 445 = 763.
        assertEquals(763L, MezzeTestHelper.run("""
            $prelude
            $fill
            let drop_front = { k, d } -> if k <= 0 then d
              else match deque::pop_front { d = d } is
                Some { val } -> drop_front { k = k - 1, d = val.tail }
                None -> d
            let base = fill { i = 1, n = 32, d = deque::empty {} }
            let dropped = drop_front { k = 20, d = base }
            let grown = fill { i = 40, n = 49, d = dropped }
            let sum = { i, acc } -> if i < 0 then acc
              else do
                let v = option::get_or { opt = deque::get { d = grown, i = i }, def = 0 }
                sum { i = i - 1, acc = acc + v }
            sum { i = 21, acc = 0 }
        """.trimIndent()))
    }

    /** `push_front` moves `head` backwards, which wraps the other way. */
    @Test
    fun `indices are right after push_front has moved the head backwards`() {
        // push_front 3,2,1 onto a deque holding 10,11 -> 1,2,3,10,11
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            let d0 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 10 }, x = 11 }
            let d1 = deque::push_front { d = deque::push_front { d = deque::push_front {
                d = d0, x = 3 }, x = 2 }, x = 1 }
            let at = { i } -> option::get_or { opt = deque::get { d = d1, i = i }, def = 0 }
            if at { i = 0 } == 1 && at { i = 1 } == 2 && at { i = 2 } == 3 &&
               at { i = 3 } == 10 && at { i = 4 } == 11 then 1 else 0
        """.trimIndent()))
    }

    /**
     * A base and a deque derived from it must each index their own contents —
     * the deque shares its buffer on a pop, so the two views overlap in memory
     * and differ only in their window.
     */
    @Test
    fun `a base and a popped view index independently`() {
        assertEquals(1L, MezzeTestHelper.run("""
            $prelude
            $fill
            let base = fill { i = 1, n = 20, d = deque::empty {} }
            let rest = match deque::pop_front { d = base } is
              Some { val } -> val.tail
              None -> base
            let b0 = option::get_or { opt = deque::get { d = base, i = 0 }, def = 0 }
            let r0 = option::get_or { opt = deque::get { d = rest, i = 0 }, def = 0 }
            let b19 = option::get_or { opt = deque::get { d = base, i = 19 }, def = 0 }
            let r18 = option::get_or { opt = deque::get { d = rest, i = 18 }, def = 0 }
            if b0 == 1 && r0 == 2 && b19 == 20 && r18 == 20 then 1 else 0
        """.trimIndent()))
    }

    /** Warm, so the indexed path is exercised compiled as well. */
    @Test
    fun `indexed get stays correct in a warm loop`() {
        assertEquals(2000L, MezzeTestHelper.run("""
            $prelude
            $fill
            let d = fill { i = 1, n = 64, d = deque::empty {} }
            let go = { i, acc } -> if i <= 0 then acc
              else do
                let want = i % 64
                let v = option::get_or { opt = deque::get { d = d, i = want }, def = 0 }
                go { i = i - 1, acc = acc + (if v == want + 1 then 1 else 0) }
            go { i = 2000, acc = 0 }
        """.trimIndent()))
    }
}
