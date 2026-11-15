package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * EVERY INDEXED READ NOW RUNS INSIDE COMPILED CODE, and for seq and list each
 * is HALF OF A PAIR — neither half is a fix alone, and for both the boundary
 * ALONE was a regression.
 *
 * Removing the `@TruffleBoundary` so a recognised container's read is a bounds
 * test and one array load helped Bytes (1.44x) and measured 0.78x on seq and
 * 0.71x on list — REGRESSIONS — and naming the storage type inside
 * `MezzeSeq.get` measured null. Those stood as separate negative results until
 * the 2x2 was finished:
 *
 * | `InlineSeqGetRaw` boundary | `MezzeSeq.get` | `seq::get` | |
 * |---|---|---|---|
 * | kept | delegating | 3 561 ps | baseline |
 * | kept | concrete | 3 550 | null |
 * | REMOVED | delegating | 4 781 | **0.78x worse** |
 * | REMOVED | concrete | 2 949 | **1.21x** |
 *
 * With the boundary gone and the call still an interface call over four
 * storages, partial evaluation inlines a megamorphic call and the caller's
 * graph grows for a read that did not get cheaper. With both gone there is one
 * concrete array read to inline — the shape `bytes::get` always had, which is
 * why the boundary alone was enough there.
 *
 * AND THEN LIST, the same pair, which is the largest of the three by far:
 * removing its boundary while naming the three cons shapes so `head` and `tail`
 * are FIELD READS took `list::get` from 3 056 ps to **730** — **4.19x**,
 * converged (4.19x at n=2e7, 3.65x at n=1e7) with `seq::get`, `bytes::get` and
 * the counted loop flat beside it. A cons cell's head is a field once the
 * receiver is concrete; a sealed-interface call is not. `list::get` now reads
 * BELOW `bytes::get` and close to the count-read floor.
 *
 * The walk's fallback is the interface call rather than `null`: `MezzeList` is
 * sealed, so the three cons shapes plus `MezzeNil` are all of it and the
 * fallback is unreachable today — but writing `null` there would mean that
 * adding a fourth cons shape makes `list::get` answer `None` for an element
 * that is present. A fast path may be slower than the general one, never
 * different from it.
 *
 * WHAT THIS TEST CANNOT SEE, stated so its green is not read as more than it
 * is: the annotations below are checkable by reflection and the CONCRETE-RECEIVER
 * half of each pair IS NOT. If someone simplifies `MezzeSeq.get` back to
 * `storage.get(index)`, or collapses the list walk back to interface calls, and
 * leaves the boundaries off, these assertions still pass and both rows land in
 * the WORST cell of their table — 4 781 ps for seq. The pairing is documented at
 * each site; this test holds the half it can reach.
 */
class IndexedReadBoundaryTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun hasBoundary(cls: Class<*>): Boolean =
        cls.declaredMethods.single { it.name == "doOp" }
            .isAnnotationPresent(TruffleBoundary::class.java)

    @Test
    fun `all three indexed reads run inside compiled code`() {
        assertFalse(
            hasBoundary(MezzeBytecodeRootNode.InlineBytesGetRaw::class.java),
            "bytes::get measured 1.44x WITHOUT a boundary; putting one back costs that",
        )
        assertFalse(
            hasBoundary(MezzeBytecodeRootNode.InlineSeqGetRaw::class.java),
            "seq::get measured 1.21x without a boundary AND with a concrete " +
                "MezzeSeq.get; the boundary ALONE was 0.78x, so neither half " +
                "may be restored on its own",
        )
        assertFalse(
            hasBoundary(MezzeBytecodeRootNode.InlineListGetRaw::class.java),
            "list::get measured 4.19x without a boundary AND with the three cons " +
                "shapes named so head/tail are field reads; the boundary ALONE " +
                "was 0.71x, so neither half may be restored on its own",
        )
    }

    /** The bounds logic lives in compiled code for all three now. */
    @Test
    fun `bytes get in range, out of range and at the edges`() {
        assertEquals(1L, MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq
            let b = bytes::from_seq { arr = [0, 128, 255] }
            match bytes::get { b = b, i = 0 } is
              Some { val } -> if val == 0 then 1 else 0
              None -> 0
            """.trimIndent()
        ))
        // 255 must arrive UNSIGNED, not as -1.
        assertEquals(255L, MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq
            match bytes::get { b = bytes::from_seq { arr = [0, 128, 255] }, i = 2 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        assertEquals(0L - 1L, MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq
            match bytes::get { b = bytes::from_seq { arr = [1, 2] }, i = 7 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
    }

    /**
     * EVERY STORAGE KIND THROUGH THE NEW CHAIN. `MezzeSeq.get` dispatches on
     * the storage type, so a seq of each kind must still read correctly — and
     * the `Object[]` form, which is a seq that was never wrapped, must not fall
     * off the end of it.
     */
    @Test
    fun `seq get across the storage kinds and off the end`() {
        assertEquals(2L, MezzeTestHelper.run(
            """
            use std::collections::seq
            match seq::get { xs = [1, 2, 3], i = 1 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        // A lazy range, whose array is never materialised.
        assertEquals(7L, MezzeTestHelper.run(
            """
            use std::collections::seq
            match seq::get { xs = seq::range { start = 5, end = 9 }, i = 2 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        // A seq that came back from a native, i.e. possibly a bare Object[].
        assertEquals(1L, MezzeTestHelper.run(
            """
            use std::collections::seq
            match seq::get { xs = seq::reverse { xs = [1, 2, 3] }, i = 2 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        assertEquals(0L - 1L, MezzeTestHelper.run(
            """
            use std::collections::seq
            match seq::get { xs = [1, 2], i = 9 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        // A Str seq, so the ObjectSeqStorage branch is exercised too.
        assertEquals(2L, MezzeTestHelper.run(
            """
            use std::collections::seq
            use std::str
            match seq::get { xs = ["ab", "cd"], i = 1 } is
              Some { val } -> str::size { s = val }
              None -> 0 - 1
            """.trimIndent()
        ))
    }

    /**
     * EVERY LIST SHAPE THROUGH THE NEW WALK. The three cons types are named
     * explicitly, so each must still read correctly — plus `MezzeNil`, an index
     * past the end, and a NON-ZERO index so the walking loop itself runs rather
     * than only the head case.
     */
    @Test
    fun `list get across the cons shapes and off the end`() {
        assertEquals(1L, MezzeTestHelper.run(
            """
            use std::collections::list
            use std::collections::seq
            match list::get { xs = list::from_seq { xs = [1, 2, 3] }, index = 0 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        // A non-zero index, so the loop runs rather than only the head case.
        assertEquals(3L, MezzeTestHelper.run(
            """
            use std::collections::list
            use std::collections::seq
            match list::get { xs = list::from_seq { xs = [1, 2, 3] }, index = 2 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        // Past the end, and the empty list: both None, not an exception.
        assertEquals(0L - 1L, MezzeTestHelper.run(
            """
            use std::collections::list
            use std::collections::seq
            match list::get { xs = list::from_seq { xs = [1, 2] }, index = 5 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        assertEquals(0L - 1L, MezzeTestHelper.run(
            """
            use std::collections::list
            match list::get { xs = list::empty {}, index = 0 } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        ))
        // An Object list, so MezzeCons is exercised and not MezzeLongCons.
        assertEquals(2L, MezzeTestHelper.run(
            """
            use std::collections::list
            use std::collections::seq
            use std::str
            match list::get { xs = list::from_seq { xs = ["ab", "cd"] }, index = 1 } is
              Some { val } -> str::size { s = val }
              None -> 0 - 1
            """.trimIndent()
        ))
    }
}
