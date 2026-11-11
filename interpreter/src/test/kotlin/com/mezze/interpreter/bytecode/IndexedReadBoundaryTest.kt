package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHICH INDEXED READS RUN INSIDE COMPILED CODE, and for seq it is HALF OF A
 * PAIR — neither half is a fix alone.
 *
 * Removing the `@TruffleBoundary` so a recognised container's read is a bounds
 * test and one array load helped Bytes (1.44x) and measured 0.78x on seq — a
 * REGRESSION — and naming the storage type inside `MezzeSeq.get` measured null.
 * Both of those stood as separate negative results until the 2x2 was finished:
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
 * Measured at n=2e7 and n=1e7, min of 8 per arm, interleaved, both arms built
 * by the dev launcher with the extracted classes confirmed to differ.
 * `seq::get` 1.21x and 1.18x across the two sizes, and its materialised twin —
 * a seq LITERAL rather than a lazy range — 1.18x and 1.11x, which is a second
 * independent row. Controls at the quieter size: counted loop 1.00x,
 * `bytes::get` 0.99x, `seq::size` 0.94x.
 *
 * WHAT THIS TEST CANNOT SEE, stated so its green is not read as more than it
 * is: the annotations below are checkable by reflection and the DEVIRTUALISATION
 * IN `MezzeSeq.get` IS NOT. `BooleanSeqStorage` appears six times in that file
 * for unrelated reasons, so no constant-pool check distinguishes the chain being
 * present from absent. If someone simplifies `MezzeSeq.get` back to
 * `storage.get(index)` and leaves the boundary off, these assertions still pass
 * and the row goes to 4 781 ps — the WORST cell of the table. The pairing is
 * documented on both sites; this test holds the half it can reach.
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
    fun `bytes and seq run inside compiled code and list does not`() {
        assertFalse(
            hasBoundary(MezzeBytecodeRootNode.InlineBytesGetRaw::class.java),
            "bytes::get measured 1.44x WITHOUT a boundary; putting one back costs that",
        )
        assertFalse(
            hasBoundary(MezzeBytecodeRootNode.InlineSeqGetRaw::class.java),
            "seq::get measured 1.21x without a boundary AND with a concrete " +
                "MezzeSeq.get; restoring the boundary gives back that 1.21x",
        )
        assertTrue(
            hasBoundary(MezzeBytecodeRootNode.InlineListGetRaw::class.java),
            "list::get measured 0.71x WITHOUT its boundary, and the PAIRED " +
                "experiment that rescued seq has not been run for list — a list " +
                "read is a loop over sealed-interface calls, not one indexed " +
                "load, so do not remove it on seq's evidence",
        )
    }

    /** The bounds logic lives in compiled code for both now, so exercise it. */
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
     * EVERY STORAGE KIND THROUGH THE NEW CHAIN. `MezzeSeq.get` now dispatches
     * on the storage type, so a seq of each kind must still read correctly —
     * and the `Object[]` form, which is a seq that was never wrapped, must not
     * fall off the end of it. Reading past the end must still be `None` rather
     * than an exception now that the bounds test is in compiled code.
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
}
