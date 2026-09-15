package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The T1.2/T1.3 property, asserted UNIFORMLY across every container: an operation
 * that compares elements uses the ELEMENT'S instance, not a structural comparison
 * in Java.
 *
 * Each case uses a newtype `P` whose Eq and Ord look only at `x`, so `P(1,10)` and
 * `P(1,99)` are EQUAL and `P(1,·) < P(2,·)` — while a structural comparison would
 * say the opposite for the first and throw for the second. Every expectation here
 * is therefore a different answer from the pre-fix behaviour, not just a smoke test.
 *
 * ContainerElementInstanceTest holds the per-container detail; this is the sweep
 * that catches "container X quietly went back to Java comparison".
 */
class ElementInstanceMatrixTest {
    companion object {
        private val HEAD = """
            use std::collections::seq
            use std::collections::list
            use std::collections::deque
            use std::collections::set
            use std::collections::map
            use std::collections::sorted_set
            use std::collections::nonemptylist
            use std::abilities::compare::{ Eq, Ord }
            newtype P = { x: Int, y: Int }
            let px: { p: P } -> Int = { p } -> match p is
              P.({ x, y }) -> x
            impl Eq for P where {
              eq = { it, r } -> px { p = it } == px { p = r },
              neq = { it, r } -> if px { p = it } == px { p = r } then False else True,
            }
            impl Ord for P where {
              lt = { it, r } -> px { p = it } < px { p = r },
              le = { it, r } -> px { p = it } <= px { p = r },
              gt = { it, r } -> px { p = it } > px { p = r },
              ge = { it, r } -> px { p = it } >= px { p = r },
            }
            let p: { x: Int, y: Int } -> P = { x, y } -> P.({ x = x, y = y })
            let ps = [ p { x = 1, y = 10 }, p { x = 1, y = 20 }, p { x = 2, y = 30 } ]
            let probe = p { x = 2, y = 99 }
        """.trimIndent()

        /** MEMBERSHIP: `probe` is Eq-equal to an element but structurally different. */
        @JvmStatic
        fun membership(): List<Array<Any>> = listOf(
            arrayOf("seq::contains", "seq::contains { xs = ps, x = probe }", true),
            arrayOf("list::contains", "list::contains { xs = list::from_seq { xs = ps }, x = probe }", true),
            arrayOf("deque::contains", "deque::contains { d = deque::from_seq { xs = ps }, x = probe }", true),
            arrayOf("nel::contains", "match nonemptylist::from_seq { xs = ps } is\n  Some { val = n } -> nonemptylist::contains { xs = n, x = probe }\n  None -> False", true),
            arrayOf("sorted_set::contains", "sorted_set::contains { s = sorted_set::from_seq { xs = ps }, x = probe }", true),
            arrayOf("seq::index_of", "match seq::index_of { xs = ps, x = probe } is\n  Some { val = i } -> i\n  None -> 0 - 1", 2L),
            arrayOf("list::index_of", "match list::index_of { xs = list::from_seq { xs = ps }, x = probe } is\n  Some { val = i } -> i\n  None -> 0 - 1", 2L),
            arrayOf("deque::index_of", "match deque::index_of { d = deque::from_seq { xs = ps }, x = probe } is\n  Some { val = i } -> i\n  None -> 0 - 1", 2L),
        )

        /** COLLAPSING: the first two elements are Eq-equal, so they fold into one. */
        @JvmStatic
        fun collapsing(): List<Array<Any>> = listOf(
            arrayOf("seq::dedup", "seq::size { xs = seq::dedup { xs = ps } }", 2L),
            arrayOf("seq::distinct", "seq::size { xs = seq::distinct { xs = ps } }", 2L),
            arrayOf("seq::group_consecutive", "seq::size { xs = seq::group_consecutive { xs = ps } }", 2L),
            arrayOf("list::dedup", "list::size { xs = list::dedup { xs = list::from_seq { xs = ps } } }", 2L),
            arrayOf("list::group_consecutive", "list::size { xs = list::group_consecutive { xs = list::from_seq { xs = ps } } }", 2L),
            arrayOf("deque::dedup", "deque::size { d = deque::dedup { d = deque::from_seq { xs = ps } } }", 2L),
            arrayOf("sorted_set::from_seq", "sorted_set::size { s = sorted_set::from_seq { xs = ps } }", 2L),
        )

        /** ORDERING: sorted by x, with y deliberately out of order. */
        @JvmStatic
        fun ordering(): List<Array<Any>> = listOf(
            arrayOf("seq ==", "[ p { x = 1, y = 0 } ] == [ p { x = 1, y = 9 } ]", true),
            arrayOf("list ==", "list::from_seq { xs = [ p { x = 1, y = 0 } ] } == list::from_seq { xs = [ p { x = 1, y = 9 } ] }", true),
            arrayOf("deque <", "deque::from_seq { xs = [ p { x = 1, y = 0 } ] } < deque::from_seq { xs = [ p { x = 2, y = 0 } ] }", true),
            arrayOf("seq <", "[ p { x = 1, y = 0 } ] < [ p { x = 2, y = 0 } ]", true),
            arrayOf("seq::min_by", "match seq::min_by { xs = ps, f = { x } -> x } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1", 1L),
            arrayOf("list::min_by", "match list::min_by { xs = list::from_seq { xs = ps }, f = { x } -> x } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1", 1L),
            arrayOf("deque::max_by", "match deque::max_by { d = deque::from_seq { xs = ps }, f = { val } -> val } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1", 2L),
            arrayOf("set::min_by", "match set::min_by { s = set::from_seq { xs = ps }, f = { val } -> val } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1", 1L),
            arrayOf("list::binary_search", "match list::binary_search { xs = list::from_seq { xs = [ p { x = 1, y = 5 }, p { x = 2, y = 5 } ] }, target = probe } is\n  Some { val = i } -> i\n  None -> 0 - 1", 1L),
        )
    }

    private fun run(body: String) = MezzeTestHelper.run("$HEAD\nlet main = {} -> $body\nmain {}")

    @ParameterizedTest(name = "membership honours element Eq: {0}")
    @MethodSource("membership")
    fun membershipHonoursElementEq(name: String, expr: String, expected: Any) = assertEquals(expected, run(expr))

    @ParameterizedTest(name = "equal elements collapse by element Eq: {0}")
    @MethodSource("collapsing")
    fun collapsingHonoursElementEq(name: String, expr: String, expected: Any) = assertEquals(expected, run(expr))

    @ParameterizedTest(name = "ordering honours element Ord: {0}")
    @MethodSource("ordering")
    fun orderingHonoursElementOrd(name: String, expr: String, expected: Any) = assertEquals(expected, run(expr))
}
