package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A `seq::to_<other>` conversion is NOT a seq source.
 *
 * `ChainCanonicalizer.classifyTerminalSource` tagged any native whose name
 * begins `__native_seq_` as a `RawSeq` source, under the comment "tag only
 * seq-producing natives as RawSeq". The module is not the return type: SIX
 * natives in the seq module hand back a different collection — `to_list`,
 * `to_set`, `to_deque`, `to_sorted_set`, `to_map` and `to_sorted_map`. A chain
 * whose source peeled down to one of those was tagged `RawSeq`, and the generic
 * fused loop then read it with `SeqSize` and an indexed `SeqGet`.
 *
 * That CRASHED rather than slowing down:
 *
 * ```
 * seq::size { xs = set::to_seq { s = seq::to_set { xs = xs } } }
 * ```
 *
 * threw `UnsupportedSpecializationException … SeqSize_Node … [MezzeSet]` on a
 * TWO-element seq. What placed the fault in the canonicalizer rather than in
 * either native is that binding each step to a `let` first worked, and
 * `MEZZE_CHAIN_CANONICALIZE=0` answered correctly — both natives are right on
 * their own.
 *
 * Found while benchmarking: the row measuring what `seq::unique` COULD cost if
 * it went through a set is written exactly this way. It is also an ordinary
 * thing to write — "dedupe through a set and count what is left" — and it is
 * pre-existing, from 2026-09-04, not from the perf work around it.
 *
 * Every case below is nested on purpose. The `let`-bound form is kept beside it
 * as the oracle, because the two must agree.
 */
class SeqConversionSourceTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = """
        use std::collections::seq
        use std::collections::set
        use std::collections::list
        use std::collections::deque
        use std::collections::sorted_set
        use std::collections::map
        use std::int

        ## 0,1,0,1,… so a set or map collapses it to two.
        let dups = { i, acc } -> if i <= 0 then acc
          else dups { i = i - 1, acc = seq::push { xs = acc, x = i % 2 } }
    """

    @Test
    fun `size of a seq through a set counts the distinct elements`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              seq::size { xs = set::to_seq { s = seq::to_set { xs = xs } } }
            main {}
        """
        assertEquals(2L, eval(src))
    }

    /** The oracle: the same steps, each bound, so nothing fuses across them. */
    @Test
    fun `the let-bound form agrees`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              let s = seq::to_set { xs = xs }
              let back = set::to_seq { s = s }
              seq::size { xs = back }
            main {}
        """
        assertEquals(2L, eval(src))
    }

    @Test
    fun `size of a seq through a list`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              seq::size { xs = list::to_seq { xs = seq::to_list { xs = xs } } }
            main {}
        """
        // A list keeps every element, so all ten survive.
        assertEquals(10L, eval(src))
    }

    @Test
    fun `size of a seq through a deque`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              seq::size { xs = deque::to_seq { d = seq::to_deque { xs = xs } } }
            main {}
        """
        assertEquals(10L, eval(src))
    }

    @Test
    fun `size of a seq through a sorted set`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              seq::size { xs = sorted_set::to_seq { s = seq::to_sorted_set { xs = xs } } }
            main {}
        """
        assertEquals(2L, eval(src))
    }

    /**
     * A SUM over the round trip, not just a size: the size sink and the sum sink
     * are different emit paths, and both read the source the same wrong way.
     */
    @Test
    fun `sum of a seq through a set`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              seq::sum { xs = set::to_seq { s = seq::to_set { xs = xs } } }
            main {}
        """
        assertEquals(1L, eval(src)) // 0 + 1
    }

    /**
     * With a STAGE between the conversion and the sink, which is the shape that
     * reaches `promoteOpaqueSource` rather than the terminal classifier.
     */
    @Test
    fun `a map stage over a seq through a set`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 10, acc = [] }
              seq::sum { xs = seq::map {
                xs = set::to_seq { s = seq::to_set { xs = xs } },
                f = { x } -> x * 10,
              } }
            main {}
        """
        assertEquals(10L, eval(src)) // (0 + 1) * 10
    }

    /** And a filter, for the same reason. */
    @Test
    fun `a filter stage over a seq through a set`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = dups { i = 20, acc = [] }
              seq::size { xs = seq::filter {
                xs = set::to_seq { s = seq::to_set { xs = xs } },
                p = { x } -> x > 0,
              } }
            main {}
        """
        assertEquals(1L, eval(src)) // just the 1
    }
}
