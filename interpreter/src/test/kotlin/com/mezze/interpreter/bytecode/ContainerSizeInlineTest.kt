package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap
import com.mezze.language.truffleir.NativeSignatures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EVERY CONTAINER'S `size` TAKES THE SAME ROUTE, and the route is asserted
 * because the answer cannot tell the routes apart.
 *
 * Three of the eight were on neither half of it. The emitter's `InlineMapSize`
 * cases were keyed on the PARAMETER NAME — one scan for `m`, one for `s`, one
 * for `d` — while these eight natives declare six different names for the same
 * thing, so seq, list and bytes were in none of them and reached the generic
 * native instead. A set keyed on the native's own name cannot have that shape
 * of hole: the operation dispatches on the container's TYPE, so what the
 * parameter is called never mattered.
 *
 * MEASURED on `bench/cases/allocsurface` at n=1e7 and n=2e7, one kernel per
 * process, min of 3 per size, converged, with the binary built by the DEV
 * launcher so the interpreter in it is the one under test:
 *
 * | row | before | after | young |
 * |---|---|---|---|
 * | `bytes::size` | 8 893 ps | **630** | 19 -> 3 |
 * | `seq::size`   | 8 522    | **2 217** | 19 -> 3 |
 * | `map::size` (reference) | | 672 | 3 |
 * | `str::size` (reference) | | 577 | 3 |
 * | counted loop (control)  | | 234 | 3 |
 *
 * 14.1x on `bytes::size` and 3.8x on `seq::size`, and the young-collection
 * count falls to the control's — so the per-call record is GONE rather than
 * cheaper. `bytes::size` now sits on `map::size`; `seq::size` is still 3.3x it,
 * which is `MezzeSeq.size()` going through the `SeqStorage` interface where
 * `MezzeBytes.length()` is a field read, and that is a separate item.
 */
class ContainerSizeInlineTest {

    private val emitted = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        BytecodeCompiler.FUSED_EMITS.set(emitted)
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        BytecodeCompiler.FUSED_EMITS.remove()
    }

    private fun sizeNativesEmitted(): List<String> =
        emitted.filter { it.startsWith("inline-size:") }.map { it.removePrefix("inline-size:") }

    /**
     * WHICH ONES MAY BE DECLARED, and the asymmetry is the point.
     *
     * `size` is part of `ChainCanonicalizer`'s vocabulary and every one of its
     * match sites is guarded `&& !node.isScalarABI`, so declaring
     * `__native_seq_size` does not merely fail to help — it makes the fuser
     * REFUSE the chain, and `seq::map` + `size` is 1503x on that path.
     * `SeqOpsFusionGuardTest` caught exactly that when all five were declared.
     * `bytes` is safe because the canonicalizer contains no reference to
     * `__native_bytes_` at all, and `map` because it contains none to
     * `__native_map_` either.
     *
     * Declaring `bytes::size` was worth 2.2x on top of the operation; for seq
     * it measured 2 182 declared against 2 217 not, so dropping the four cost
     * ~2%. That is the whole trade, written down so the next person does not
     * re-declare them for it.
     */
    @Test
    fun `only the natives the fuser never mentions are declared`() {
        // The registry is populated by the `*Ops.kt` init blocks, which only
        // run once something touches them — the same call SeqOpsFusionGuardTest
        // makes for the same reason.
        NativeBootstrap.ensureLoaded()
        for (n in listOf("__native_bytes_size", "__native_map_size")) {
            assertNotNull(NativeSignatures.signatureOf(n), "$n should be declared")
        }
        for (n in listOf(
            "__native_seq_size", "__native_list_size",
            "__native_set_size", "__native_deque_size",
        )) {
            assertNull(NativeSignatures.signatureOf(n),
                "$n must NOT be declared — it would make ChainCanonicalizer refuse the chain")
        }
    }

    /** `seq::size` reaches the operation, and the answer is still right. */
    @Test
    fun `seq size reaches the inline operation`() {
        assertEquals(3L, MezzeTestHelper.run(
            """
            use std::collections::seq
            seq::size { xs = [7, 8, 9] }
            """.trimIndent()
        ))
        assertTrue("__native_seq_size" in sizeNativesEmitted(),
            "seq::size did not reach InlineMapSize: $emitted")
    }

    @Test
    fun `bytes size reaches the inline operation`() {
        assertEquals(3L, MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq
            bytes::size { b = bytes::from_seq { arr = [1, 2, 3] } }
            """.trimIndent()
        ))
        assertTrue("__native_bytes_size" in sizeNativesEmitted(),
            "bytes::size did not reach InlineMapSize: $emitted")
    }

    @Test
    fun `list size reaches the inline operation`() {
        assertEquals(2L, MezzeTestHelper.run(
            """
            use std::collections::list
            use std::collections::seq
            list::size { xs = list::from_seq { xs = [4, 5] } }
            """.trimIndent()
        ))
        assertTrue("__native_list_size" in sizeNativesEmitted(),
            "list::size did not reach InlineMapSize: $emitted")
    }

    @Test
    fun `set and deque size reach the inline operation`() {
        assertEquals(5L, MezzeTestHelper.run(
            """
            use std::collections::set
            use std::collections::deque
            use std::collections::seq
            set::size { s = set::from_seq { xs = [1, 2, 2] } } + deque::size { d = deque::from_seq { xs = [1, 2, 3] } }
            """.trimIndent()
        ))
        val seen = sizeNativesEmitted()
        assertTrue("__native_set_size" in seen, "set::size missed: $emitted")
        assertTrue("__native_deque_size" in seen, "deque::size missed: $emitted")
    }

    /**
     * A SEQ THAT WAS NEVER WRAPPED. A seq is EITHER a `MezzeSeq` OR a bare
     * `Object[]`, and an operation that accepts only the canonical class is
     * narrower than the native it stands in for — which cost 39 interpreter
     * tests when `InlineSeqGetRaw` did it. The operation carries an `Object[]`
     * specialization for that reason; this is the case that would find its
     * absence.
     */
    @Test
    fun `size of a seq that came back from a native`() {
        assertEquals(3L, MezzeTestHelper.run(
            """
            use std::collections::seq
            seq::size { xs = seq::reverse { xs = [1, 2, 3] } }
            """.trimIndent()
        ))
    }

    private fun isEmptyNativesEmitted(): List<String> =
        emitted.filter { it.startsWith("inline-is-empty:") }
            .map { it.removePrefix("inline-is-empty:") }

    /**
     * `is_empty` WAS IN THE SAME POSITION, one step further on: all eight were
     * DECLARED and none had an operation, so the per-call record was already
     * gone and the generic scalar native's `@TruffleBoundary` was not.
     *
     * MEASURED interleaved, two builds from identical source but for the
     * operation, BOTH ARMS VERIFIED BY UNZIP to contain or not contain it
     * (`InlineContainerIsEmpty`: 37 references against 0), n=2e7, min of 3:
     *
     * | row | base | with the operation | | young |
     * |---|---|---|---|---|
     * | `bytes::is_empty` | 3 569 ps | **684** | 5.22x | 13 -> 3 |
     * | `seq::is_empty`   | 3 525    | **2 489** | 1.42x | 12 -> 3 |
     * | counted loop (control) | 220 | 226 | 0.97x | |
     *
     * `is_empty` is NOT in `NativeHofNames`'s vocabulary, so unlike `size` it
     * carries no fusion risk for any container.
     */
    @Test
    fun `seq and bytes is_empty reach the inline operation`() {
        assertEquals(1L, MezzeTestHelper.run(
            """
            use std::collections::seq
            use std::collections::bytes
            if seq::is_empty { xs = [1, 2] } then 0 else if bytes::is_empty { b = bytes::from_seq { arr = [1] } } then 0 else 1
            """.trimIndent()
        ))
        val seen = isEmptyNativesEmitted()
        assertTrue("__native_seq_is_empty" in seen, "seq::is_empty missed: $emitted")
        assertTrue("__native_bytes_is_empty" in seen, "bytes::is_empty missed: $emitted")
    }

    /**
     * AND `map::is_empty` / `set::is_empty` DO NOT USE THEIR is_empty NATIVE
     * AT ALL: the stdlib writes them `size { … } == 0`, so they ride the SIZE
     * operation and `__native_map_is_empty` is only reachable from a direct
     * call. Asserted, because the first version of this test assumed otherwise
     * and the seam said so — and because it means those two were already fixed
     * by the size work.
     */
    @Test
    fun `map set list and deque is_empty reach the inline operation`() {
        assertEquals(4L, MezzeTestHelper.run(
            """
            use std::collections::map
            use std::collections::set
            use std::collections::list
            use std::collections::deque
            use std::collections::seq
            (if map::is_empty { m = map::empty {} } then 1 else 0) + (if set::is_empty { s = set::from_seq { xs = [1] } } then 0 else 1) + (if list::is_empty { xs = list::from_seq { xs = [1] } } then 0 else 1) + (if deque::is_empty { d = deque::from_seq { xs = [1] } } then 0 else 1)
            """.trimIndent()
        ))
        val seen = isEmptyNativesEmitted()
        for (n in listOf("__native_list_is_empty", "__native_deque_is_empty")) {
            assertTrue(n in seen, "$n missed: $emitted")
        }
        // These two are `size { … } == 0` in the stdlib, so they reach the SIZE
        // operation instead and their is_empty native is never emitted here.
        val sizes = sizeNativesEmitted()
        assertTrue("__native_map_size" in sizes, "map::is_empty should ride size: $emitted")
        assertTrue("__native_set_size" in sizes, "set::is_empty should ride size: $emitted")
    }

    /**
     * AND THE FUSED CHAIN STILL FUSES. `seq::map` + `size` is the 1503x path
     * the guard test protects; if the emitter change had cost it, this is the
     * shape that would say so.
     */
    @Test
    fun `a mapped chain ending in size still works`() {
        assertEquals(4L, MezzeTestHelper.run(
            """
            use std::collections::seq
            seq::size { xs = seq::map { xs = [1, 2, 3, 4], f = { x } -> x * 2 } }
            """.trimIndent()
        ))
    }
}
