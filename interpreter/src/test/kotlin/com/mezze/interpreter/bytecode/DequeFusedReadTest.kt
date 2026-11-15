package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `deque::get` and `deque::first` go through the fused
 * `match … is Some/None` read, like map, seq, list and bytes already did.
 *
 * WHY THEY WERE LEFT OUT AND WHAT IT COST. The elision covered four natives and
 * never deque, so both still allocated a `MezzeVariant` per call and crossed a
 * boundary — which made them THE SLOWEST ELEMENT READS IN THE LANGUAGE.
 * Measured on `bench/cases/dsmore`, 1 000 elements, min of 5, interleaved, both
 * arms built by the dev launcher with the extracted classes confirmed to differ:
 *
 * | row | base | fused | n=1e7 | n=3e6 |
 * |---|---|---|---|---|
 * | `deque::first` | 5 449 ps | **2 177** | 2.50x | 2.81x |
 * | `deque::get by index` | 8 557 | **3 114** | 2.75x | 2.92x |
 * | `deque::size` (control) | 414 | 424 | 0.98x | 1.03x |
 * | `sorted_map::insert` (control) | 184 351 | 183 363 | 1.01x | 1.02x |
 *
 * For scale, `list::get` is 730 ps and `bytes::get` ~1 200, so deque had been
 * 7x and 12x the cheapest element read before this.
 *
 * `deque::first` also made the two-argument fused emitter learn a ONE-argument
 * shape: the match, the local and the `IsNotNull` test are identical, only the
 * key operand is absent.
 *
 * THE RING BUFFER IS WHAT THESE TESTS ARE FOR. A `MezzeDeque` reads
 * `buffer[(head + index) & mask]`, so a deque whose contents have WRAPPED —
 * pushed at the front, or popped from the front and pushed at the back — must
 * still index correctly. A fast path that only ever saw `head == 0` would pass
 * a naive test and be wrong in service.
 */
class DequeFusedReadTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    @Test
    fun `get reads the first, a middle and the last element`() {
        assertEquals(10L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::get { d = deque::from_seq { xs = [10, 20, 30] }, i = 0 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
        assertEquals(20L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::get { d = deque::from_seq { xs = [10, 20, 30] }, i = 1 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
        assertEquals(30L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::get { d = deque::from_seq { xs = [10, 20, 30] }, i = 2 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
    }

    @Test
    fun `get off the end and on an empty deque is None`() {
        assertEquals(0L - 1L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::get { d = deque::from_seq { xs = [1, 2] }, i = 7 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
        assertEquals(0L - 1L, run(
            """
            use std::collections::deque
            match deque::get { d = deque::empty {}, i = 0 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
    }

    /**
     * PUSHED AT THE FRONT, so `head` is no longer 0 and the ring is offset.
     * Index 0 must be the element pushed last at the front, not the one at
     * buffer slot 0.
     */
    @Test
    fun `get indexes correctly after a push_front moves the head`() {
        assertEquals(5L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::get { d = deque::push_front { d = deque::from_seq { xs = [10, 20] }, x = 5 }, i = 0 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
        assertEquals(20L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::get { d = deque::push_front { d = deque::from_seq { xs = [10, 20] }, x = 5 }, i = 2 } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
    }

    /** `first` on a deque, on one whose head has moved, and on an empty one. */
    @Test
    fun `first returns the front element`() {
        assertEquals(10L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::first { d = deque::from_seq { xs = [10, 20, 30] } } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
        assertEquals(5L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::first { d = deque::push_front { d = deque::from_seq { xs = [10, 20] }, x = 5 } } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
        assertEquals(0L - 1L, run(
            """
            use std::collections::deque
            match deque::first { d = deque::empty {} } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
    }

    /** `first` must be the FRONT and not the back — the two differ. */
    @Test
    fun `first is the front and not the back`() {
        assertEquals(1L, run(
            """
            use std::collections::deque
            use std::collections::seq
            match deque::first { d = deque::push_back { d = deque::from_seq { xs = [1, 2] }, x = 9 } } is
              Some { val } -> val
              None -> 0 - 1
            """
        ))
    }

    /**
     * A WARM LOOP, because the fused read is a compiled path and a
     * straight-line test exercises only its first execution. Sums
     * `get { i = i % 3 }` over many iterations, so a wrong index or a stale
     * local shows up as a different total.
     */
    @Test
    fun `the fused reads stay correct in a warm loop`() {
        // 300 iterations over [10,20,30] by i%3: 100 each = 6000, plus
        // `first` (10) each iteration = 3000. Total 9000.
        assertEquals(9000L, run(
            """
            use std::collections::deque
            use std::collections::seq
            let d = deque::from_seq { xs = [10, 20, 30] }
            let go = { i, acc, d } ->
              if i <= 0 then acc
              else do
                let a = match deque::get { d = d, i = i % 3 } is
                  Some { val } -> val
                  None -> 0
                let b = match deque::first { d = d } is
                  Some { val } -> val
                  None -> 0
                go { i = i - 1, d = d, acc = acc + a + b }
            go { i = 300, acc = 0, d = d }
            """
        ))
    }
}
