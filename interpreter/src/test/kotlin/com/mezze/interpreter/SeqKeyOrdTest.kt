package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * #105 — KEY-comparing seq functions must order keys by the key type's Ord impl.
 * Before this fix, min_by/max_by/sort_by_key/is_sorted_by compared KEYS (and
 * lower_bound/upper_bound/equal_range compared ELEMENTS) with a primitive-only
 * native compare() that handles Long/Double/String and returns 0 for anything
 * else — so a custom-Ord key type made everything "equal" (min_by returned the
 * first element, lower_bound returned 0, is_sorted_by always true, ...).
 *
 * K has a CUSTOM Ord keyed ONLY on `x` (field `y` deliberately ignored), the
 * contrast that makes the divergence observable — same shape as
 * SeqUserTypeCompareTest / SeqEqOrdConsistencyTest.
 */
class SeqKeyOrdTest {
    private val head = """
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord }
        type K = { x: Int, y: Int }
        impl Eq for K where { eq = { it, r } -> it.x == r.x, neq = { it, r } -> if it.x == r.x then False else True, }
        impl Ord for K where { lt = { it, r } -> it.x < r.x, le = { it, r } -> it.x <= r.x, gt = { it, r } -> it.x > r.x, ge = { it, r } -> it.x >= r.x, }
        type E = { k: K, tag: Int }
    """.trimIndent()
    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")

    // es = elements tagged 30/10/20 whose keys have x=3/1/2 (y noise differs).
    private val es = "[ { k = { x = 3, y = 0 }, tag = 30 }, { k = { x = 1, y = 9 }, tag = 10 }, { k = { x = 2, y = 5 }, tag = 20 } ]"

    @Test fun minByCustomKey() { assertEquals(10L, run("let main = {} -> match seq::min_by { xs = $es, f = { x } -> x.k } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}")) }
    @Test fun maxByCustomKey() { assertEquals(30L, run("let main = {} -> match seq::max_by { xs = $es, f = { x } -> x.k } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}")) }
    @Test fun sortByKeyCustomKey() { assertEquals(10L, run("let main = {} -> match seq::first { xs = seq::sort_by_key { xs = $es, f = { x } -> x.k } } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}")) }
    @Test fun isSortedByCustomKeyFalse() { assertEquals(false, run("let main = {} -> seq::is_sorted_by { xs = $es, f = { x } -> x.k }\nmain {}")) }
    @Test fun isSortedByCustomKeyTrue() { assertEquals(true, run("let main = {} -> seq::is_sorted_by { xs = seq::sort_by_key { xs = $es, f = { x } -> x.k }, f = { x } -> x.k }\nmain {}")) }

    // ks = K values sorted by Ord (x = 1,2,2,3; y noise differs).
    private val ks = "[ { x = 1, y = 7 }, { x = 2, y = 0 }, { x = 2, y = 4 }, { x = 3, y = 1 } ]"
    private val probe = "{ x = 2, y = 99 }"

    @Test fun lowerBoundCustomOrd() { assertEquals(1L, run("let main = {} -> seq::lower_bound { xs = $ks, x = $probe }\nmain {}")) }
    @Test fun upperBoundCustomOrd() { assertEquals(3L, run("let main = {} -> seq::upper_bound { xs = $ks, x = $probe }\nmain {}")) }
    @Test fun equalRangeCustomOrd() { assertEquals(2L, run("let main = {} -> do\n  let r = seq::equal_range { xs = $ks, x = $probe }\n  r.end - r.start\nmain {}")) }

    // regressions — primitive keys must keep working
    @Test fun minByIntKey() { assertEquals(10L, run("let main = {} -> match seq::min_by { xs = [ { k = { x = 0, y = 0 }, tag = 30 }, { k = { x = 0, y = 0 }, tag = 10 } ], f = { x } -> x.tag } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}")) }
    @Test fun sortByKeyIntKey() { assertEquals(10L, run("let main = {} -> match seq::first { xs = seq::sort_by_key { xs = [ { k = { x = 0, y = 0 }, tag = 30 }, { k = { x = 0, y = 0 }, tag = 10 } ], f = { x } -> x.tag } } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}")) }
    @Test fun lowerBoundIntElems() { assertEquals(1L, run("let main = {} -> seq::lower_bound { xs = [1, 2, 2, 3], x = 2 }\nmain {}")) }
    @Test fun upperBoundIntElems() { assertEquals(3L, run("let main = {} -> seq::upper_bound { xs = [1, 2, 2, 3], x = 2 }\nmain {}")) }
    @Test fun isSortedByStrKey() { assertEquals(true, run("let main = {} -> seq::is_sorted_by { xs = [ { k = { x = 0, y = 0 }, tag = 1 }, { k = { x = 0, y = 0 }, tag = 2 } ], f = { x } -> x.tag }\nmain {}")) }
}
