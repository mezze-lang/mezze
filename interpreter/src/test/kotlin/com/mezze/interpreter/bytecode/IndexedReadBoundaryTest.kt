package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * WHICH OF THE THREE INDEXED READS MAY RUN INSIDE COMPILED CODE, and the
 * answer is one of them.
 *
 * Removing the `@TruffleBoundary` so a recognised container's read is a bounds
 * test and one array load looks free for all three. MEASURED it is not. Two
 * builds from identical source but for the split, interleaved, n=2e7, min of
 * 3, BOTH ARMS VERIFIED BY UNZIPPING THE APP JAR (`coerceThenGet`: 3
 * references against 0) and both built by the dev launcher:
 *
 * | row | boundary | inline | |
 * |---|---|---|---|
 * | `bytes::get` | 3 184 ps | **2 092** | 1.52x |
 * | `seq::get`   | 3 716    | 4 781 | **0.78x** |
 * | `list::get`  | 3 024    | 4 250 | **0.71x** |
 *
 * and then confirmed three-way, with seq and list put back behind theirs:
 *
 * | row | boundary | bytes-only | |
 * |---|---|---|---|
 * | `bytes::get` | 3 210 ps | **2 230** | 1.44x |
 * | `seq::get`   | 3 824    | 3 650 | 1.05x |
 * | `list::get`  | 2 995    | 3 051 | 0.98x |
 * | counted loop (control) | 231 | 229 | 1.01x |
 *
 * So Bytes keeps the split and the other two keep the boundary. The difference
 * is what sits behind it: a `MezzeBytes` read is `data[i]` on a final
 * `byte[]`, with nothing polymorphic and nothing to box, while `MezzeSeq.get`
 * is a polymorphic `SeqStorage` call that boxes its result and a list read is a
 * loop over sealed-interface calls — inlining either grows the caller's graph
 * for a read that does not get cheaper. A boundary can be load-bearing in the
 * GOOD direction.
 *
 * THE ANNOTATIONS ARE ASSERTED because nothing else can see this. All three
 * shapes compute the same answers either way, so a value test is green
 * whichever side of the split a read is on, and the next person to "finish the
 * job" by removing the other two boundaries would lose 1.3-1.4x with no
 * failure anywhere.
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
    fun `bytes get runs inside compiled code and seq and list do not`() {
        assertFalse(
            hasBoundary(MezzeBytecodeRootNode.InlineBytesGetRaw::class.java),
            "bytes::get measured 1.44-1.52x WITHOUT a boundary; putting one back costs that",
        )
        assertTrue(
            hasBoundary(MezzeBytecodeRootNode.InlineSeqGetRaw::class.java),
            "seq::get measured 0.78x WITHOUT its boundary — it is load-bearing",
        )
        assertTrue(
            hasBoundary(MezzeBytecodeRootNode.InlineListGetRaw::class.java),
            "list::get measured 0.71x WITHOUT its boundary — it is load-bearing",
        )
    }

    /** The bounds logic now lives in compiled code for bytes, so exercise it. */
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
}
