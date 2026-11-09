package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `iter::to_seq` over a chain that DROPS elements.
 *
 * The materialising sink sizes its buffer to the SOURCE and fills only what
 * survives the stages, then hands the buffer to the storage as a PREFIX rather
 * than trimming it — see `LongSeqStorage.ofPrefix`. So the backing array is
 * longer than the sequence, and the slack is ZERO.
 *
 * THE FAILURE MODE IS QUIET. Trailing zeros do not change a `size` that reads
 * the logical length, and they do not change a SUM either. Only an element
 * read, a PRODUCT, or a size taken from the array can see them. Every
 * assertion below is therefore about the elements or a product, never a sum.
 *
 * WHY THIS FILE EXISTS AT ALL: with `ofPrefix` made to ignore its length — the
 * exact way this goes wrong — six of the seven unit tests on the storage fail,
 * but `FusedPipelineElementTypeTest` and `IterableImplIntegrationTest` both
 * stay green. The user-visible path had no element-level assertion over a
 * filtered materialise, so the unit tests were guarding the mechanism and
 * nothing was guarding the result.
 */
class MaterializedSeqPrefixTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    /** 0..9 keeping the evens gives exactly 0,2,4,6,8 — five elements, not ten. */
    @Test
    fun `a filtered to_seq has the surviving length, not the source length`() {
        assertEquals(5L, run(
            """
            use std::collections::iter
            use std::collections::seq
            seq::size { xs = iter::to_seq {
              it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x % 2 == 0 }
            } }
            """
        ))
    }

    /**
     * The elements themselves, joined so a trailing zero would show up as text
     * rather than arithmetic. Keeping x > 5 of 0..10 gives 6,7,8,9.
     */
    @Test
    fun `a filtered to_seq holds exactly the surviving elements`() {
        assertEquals("6,7,8,9", run(
            """
            use std::collections::iter
            use std::collections::seq
            use std::int
            let xs = iter::to_seq {
              it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x > 5 }
            }
            seq::fold {
              xs = xs,
              init = "",
              f = { acc, x } -> if acc == "" then int::to_str { n = x }
                                else "$(acc),$(int::to_str { n = x })"
            }
            """
        ))
    }

    /**
     * A PRODUCT over the result. The padding is zero, so one stray slot takes
     * this to 0 while a sum would be unchanged: 6*7*8*9 = 3024.
     */
    @Test
    fun `a product over a filtered to_seq cannot see the padding`() {
        assertEquals(3024L, run(
            """
            use std::collections::iter
            use std::collections::seq
            seq::fold {
              xs = iter::to_seq {
                it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x > 5 }
              },
              init = 1,
              f = { acc, x } -> acc * x
            }
            """
        ))
    }

    /**
     * Appending to the materialised seq writes into the slack the sink
     * over-allocated, which is the point of handing over a fresh owner token.
     * The ORIGINAL must not change — same copy-on-write invariant the storage
     * unit tests pin, reached here through the language.
     */
    @Test
    fun `appending to a materialised seq leaves the original alone`() {
        assertEquals("4|5", run(
            """
            use std::collections::iter
            use std::collections::seq
            use std::int
            let base = iter::to_seq {
              it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x > 5 }
            }
            let grown = seq::add { xs = base, x = 99 }
            "$(int::to_str { n = seq::size { xs = base } })|$(int::to_str { n = seq::size { xs = grown } })"
            """
        ))
    }

    /** Nothing survives: the sink must give an empty seq, not a buffer of zeros. */
    @Test
    fun `a to_seq that keeps nothing is empty`() {
        assertEquals(0L, run(
            """
            use std::collections::iter
            use std::collections::seq
            seq::size { xs = iter::to_seq {
              it = iter::filter { it = iter::range { start = 0, end = 10 }, p = { x } -> x > 100 }
            } }
            """
        ))
    }
}
