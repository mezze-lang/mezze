package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Container operations dispatch the ELEMENT'S instance, not a structural
 * comparison in Java. The element is a newtype whose Eq/Ord look only at `x`,
 * so every case below has a different answer under structural comparison —
 * these are the silently-wrong results, not crashes.
 *
 * Grows with task T1.2 as each container's natives are converted; the
 * companion ratchet is NativeEvidenceGateTest.
 */
class ContainerElementInstanceTest {
    private val head = """
        use std::collections::seq
        use std::collections::list
        use std::abilities::compare::{ Eq, Ord }
        newtype P = { x: Int, y: Int }
        let px: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> x
        let py: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> y
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
    """.trimIndent()
    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")
    private fun runL(body: String) = run("let xs = list::from_seq { xs = [ p { x = 1, y = 10 }, p { x = 1, y = 20 }, p { x = 2, y = 30 } ] }\n$body")

    // ---- List, Eq-driven ----------------------------------------------------

    // (1,99) is EQUAL to (1,10) by P's Eq, and absent structurally.
    @Test fun listContains() = assertEquals(true, runL("let main = {} -> list::contains { xs = xs, x = p { x = 1, y = 99 } }\nmain {}"))
    @Test fun listContainsFalse() = assertEquals(false, runL("let main = {} -> list::contains { xs = xs, x = p { x = 9, y = 10 } }\nmain {}"))

    @Test fun listIndexOf() = assertEquals(0L, runL("let main = {} -> match list::index_of { xs = xs, x = p { x = 1, y = 99 } } is\n  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"))
    @Test fun listLastIndexOf() = assertEquals(1L, runL("let main = {} -> match list::last_index_of { xs = xs, x = p { x = 1, y = 99 } } is\n  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"))

    // (1,10) and (1,20) are consecutive duplicates by P's Eq → collapse to one.
    @Test fun listDedup() = assertEquals(2L, runL("let main = {} -> list::size { xs = list::dedup { xs = xs } }\nmain {}"))
    @Test fun listGroupConsecutive() = assertEquals(2L, runL("let main = {} -> list::size { xs = list::group_consecutive { xs = xs } }\nmain {}"))

    // Key-function variants compare the KEYS by the key type's Eq.
    @Test fun listDedupBy() = assertEquals(2L, runL("let main = {} -> list::size { xs = list::dedup_by { xs = xs, f = { x } -> x } }\nmain {}"))
    @Test fun listGroupConsecutiveBy() = assertEquals(2L, runL("let main = {} -> list::size { xs = list::group_consecutive_by { xs = xs, f = { x } -> x } }\nmain {}"))

    // ---- Deque, Ord-driven on a KEY type ------------------------------------
    // The key is a P whose Ord compares only x, so the minimum by key is the
    // element with the smallest x — a structural key compare returns 0 for
    // records and would just yield the first element.
    // Element is a record carrying a P key; `f = { val } -> val.k` is the
    // field-access shape the call-site key recovery understands.
    private fun runD(body: String) = run("use std::collections::deque\ntype E = { k: P, tag: Int }\nlet mk: { x: Int, t: Int } -> E = { x, t } -> { k = p { x = x, y = 0 }, tag = t }\nlet d = deque::from_seq { xs = [ mk { x = 3, t = 30 }, mk { x = 1, t = 10 }, mk { x = 2, t = 20 } ] }\n$body")

    @Test fun dequeMinByKey() = assertEquals(10L, runD("let main = {} -> match deque::min_by { d = d, f = { val } -> val.k } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}"))
    @Test fun dequeMaxByKey() = assertEquals(30L, runD("let main = {} -> match deque::max_by { d = d, f = { val } -> val.k } is\n  Some { val = e } -> e.tag\n  None -> 0 - 1\nmain {}"))

    // Identity key function: the key IS the element. The key type var is bound
    // from the container arg, not from a field access — see recoverLambdaReturnType.
    @Test fun dequeMinByIdentity() = assertEquals(1L, run("use std::collections::deque\nlet d = deque::from_seq { xs = [ p { x = 3, y = 1 }, p { x = 1, y = 2 } ] }\nlet main = {} -> match deque::min_by { d = d, f = { val } -> val } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1\nmain {}"))

    // ---- Deque / NonEmptyList / Set / Map -----------------------------------

    private fun runDq(body: String) = run("use std::collections::deque\nlet d = deque::from_seq { xs = [ p { x = 1, y = 10 }, p { x = 1, y = 20 }, p { x = 2, y = 30 } ] }\n$body")

    @Test fun dequeContains() = assertEquals(true, runDq("let main = {} -> deque::contains { d = d, x = p { x = 2, y = 99 } }\nmain {}"))
    @Test fun dequeIndexOf() = assertEquals(0L, runDq("let main = {} -> match deque::index_of { d = d, x = p { x = 1, y = 99 } } is\n  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"))
    @Test fun dequeDedup() = assertEquals(2L, runDq("let main = {} -> deque::size { d = deque::dedup { d = d } }\nmain {}"))
    // Deque's Ord threads the element Ord; the native comparator returned 0 for
    // every record, so `<` on two deques of records was always False.
    @Test fun dequeLt() = assertEquals(true, run("use std::collections::deque\nlet main = {} -> deque::from_seq { xs = [ p { x = 1, y = 0 } ] } < deque::from_seq { xs = [ p { x = 2, y = 0 } ] }\nmain {}"))

    private fun runN(body: String) = run("use std::collections::nonemptylist\n$body")
    @Test fun nelEq() = assertEquals(true, runN("let main = {} -> match nonemptylist::from_seq { xs = [ p { x = 1, y = 10 } ] } is\n  Some { val = a } -> match nonemptylist::from_seq { xs = [ p { x = 1, y = 99 } ] } is\n    Some { val = b } -> a == b\n    None -> False\n  None -> False\nmain {}"))
    @Test fun nelContains() = assertEquals(true, runN("let main = {} -> match nonemptylist::from_seq { xs = [ p { x = 1, y = 10 } ] } is\n  Some { val = a } -> nonemptylist::contains { xs = a, x = p { x = 1, y = 99 } }\n  None -> False\nmain {}"))

    // Map min_key / min_value order by the key's / value's own Ord.
    private fun runM(body: String) = run("use std::collections::map\n$body")
    private val twoEntryMap = "let m = map::insert { m = map::insert { m = map::empty {}, k = \"a\", v = p { x = 3, y = 0 } }, k = \"b\", v = p { x = 1, y = 0 } }"
    @Test fun mapMinValue() = assertEquals(1L, runM("$twoEntryMap\nlet main = {} -> match map::min_value { m = m } is\n  Some { val = v } -> px { p = v }\n  None -> 0 - 1\nmain {}"))
    @Test fun mapContainsValue() = assertEquals(true, runM("$twoEntryMap\nlet main = {} -> map::contains_value { m = m, v = p { x = 3, y = 99 } }\nmain {}"))

    // ---- Seq: the remaining Eq-driven ops -----------------------------------
    private val ps = "[ p { x = 1, y = 10 }, p { x = 1, y = 20 }, p { x = 2, y = 30 } ]"

    @Test fun seqGroupConsecutive() = assertEquals(2L, run("let main = {} -> seq::size { xs = seq::group_consecutive { xs = $ps } }\nmain {}"))
    @Test fun seqDedupBy() = assertEquals(2L, run("let main = {} -> seq::size { xs = seq::dedup_by { xs = $ps, f = { x } -> x } }\nmain {}"))
    @Test fun seqGroupConsecutiveBy() = assertEquals(2L, run("let main = {} -> seq::size { xs = seq::group_consecutive_by { xs = $ps, f = { x } -> x } }\nmain {}"))
    // Splitting on (2,99) finds the (2,30) separator by P's Eq → two pieces.
    @Test fun seqSplit() = assertEquals(2L, run("let main = {} -> seq::size { xs = seq::split { xs = $ps, sep = p { x = 2, y = 99 } } }\nmain {}"))
    @Test fun dequeDedupBy() = assertEquals(2L, run("use std::collections::deque\nlet main = {} -> deque::size { d = deque::dedup_by { d = deque::from_seq { xs = $ps }, f = { val } -> val } }\nmain {}"))

    // ---- List, Ord-driven ---------------------------------------------------
    // Sorted by P's Ord (x only); the y values are deliberately out of order so a
    // structural comparison would disagree.
    private fun runLo(body: String) = run("let xs = list::from_seq { xs = [ p { x = 1, y = 30 }, p { x = 2, y = 20 }, p { x = 3, y = 10 } ] }\n$body")

    @Test fun listMinBy() = assertEquals(1L, runLo("let main = {} -> match list::min_by { xs = xs, f = { x } -> x } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1\nmain {}"))
    @Test fun listMaxBy() = assertEquals(3L, runLo("let main = {} -> match list::max_by { xs = xs, f = { x } -> x } is\n  Some { val = e } -> px { p = e }\n  None -> 0 - 1\nmain {}"))
    @Test fun listBinarySearch() = assertEquals(1L, runLo("let main = {} -> match list::binary_search { xs = xs, target = p { x = 2, y = 99 } } is\n  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"))
    @Test fun listLowerBound() = assertEquals(1L, runLo("let main = {} -> list::lower_bound { xs = xs, target = p { x = 2, y = 99 } }\nmain {}"))
    @Test fun listUpperBound() = assertEquals(2L, runLo("let main = {} -> list::upper_bound { xs = xs, target = p { x = 2, y = 99 } }\nmain {}"))
    @Test fun listSortByKey() = assertEquals(30L, runLo("let main = {} -> match list::head { xs = list::sort_by_key { xs = list::reverse { xs = xs }, f = { x } -> x } } is\n  Some { val = e } -> py { p = e }\n  None -> 0 - 1\nmain {}"))

    // ---- SortedSet: the structure carries the element's comparator -----------
    // Before T1.3 every one of these threw ClassCastException: the structure's
    // default ordering is `((Comparable) a).compareTo(b)`, which no Mezze record or
    // variant satisfies — sorted containers were usable with primitives only.
    private fun runS(body: String) = run("use std::collections::sorted_set\n$body")
    private val threeP = "[ p { x = 3, y = 0 }, p { x = 1, y = 0 }, p { x = 2, y = 0 } ]"

    // Three elements DISTINCT by P's Ord survive as three (a comparator that
    // returned 0 for every record would collapse them to one).
    @Test fun sortedSetKeepsDistinctRecords() = assertEquals(3L,
        runS("let main = {} -> sorted_set::size { s = sorted_set::from_seq { xs = $threeP } }\nmain {}"))
    @Test fun sortedSetDedupesByElementOrd() = assertEquals(1L,
        runS("let main = {} -> sorted_set::size { s = sorted_set::from_seq { xs = [ p { x = 1, y = 10 }, p { x = 1, y = 99 } ] } }\nmain {}"))
    @Test fun sortedSetContainsUsesElementOrd() = assertEquals(true,
        runS("let main = {} -> sorted_set::contains { s = sorted_set::from_seq { xs = $threeP }, x = p { x = 2, y = 99 } }\nmain {}"))
    @Test fun sortedSetAddThenContains() = assertEquals(true,
        runS("let main = {} -> sorted_set::contains { s = sorted_set::add { s = sorted_set::empty {}, x = p { x = 7, y = 1 } }, x = p { x = 7, y = 2 } }\nmain {}"))
    @Test fun sortedSetRemoveUsesElementOrd() = assertEquals(2L,
        runS("let main = {} -> sorted_set::size { s = sorted_set::remove { s = sorted_set::from_seq { xs = $threeP }, x = p { x = 2, y = 99 } } }\nmain {}"))
    // Primitive elements keep working, including reading values back out.
    @Test fun sortedSetIntsStillWork() = assertEquals(3L,
        runS("let main = {} -> sorted_set::size { s = sorted_set::from_seq { xs = [ 3, 1, 2 ] } }\nmain {}"))
    @Test fun sortedSetIntMin() = assertEquals(1L,
        runS("let main = {} -> match sorted_set::min { s = sorted_set::from_seq { xs = [ 3, 1, 2 ] } } is\n  Some { val = v } -> v\n  None -> 0 - 1\nmain {}"))

    // ---- SortedMap: keys ordered by the KEY's own Ord ------------------------
    private fun runSm(body: String) = run("use std::collections::sorted_map\n$body")
    private val pKeyMap = "let m = sorted_map::insert { m = sorted_map::insert { m = sorted_map::empty {}, k = p { x = 3, y = 0 }, v = 30 }, k = p { x = 1, y = 0 }, v = 10 }"

    @Test fun sortedMapKeepsDistinctRecordKeys() = assertEquals(2L, runSm("$pKeyMap\nlet main = {} -> sorted_map::size { m = m }\nmain {}"))
    // Looking up (1,99) finds the (1,0) key by P's Ord.
    @Test fun sortedMapGetUsesKeyOrd() = assertEquals(10L, runSm("$pKeyMap\nlet main = {} -> match sorted_map::get { m = m, k = p { x = 1, y = 99 } } is\n  Some { val = v } -> v\n  None -> 0 - 1\nmain {}"))
    @Test fun sortedMapContainsKeyUsesKeyOrd() = assertEquals(true, runSm("$pKeyMap\nlet main = {} -> sorted_map::contains_key { m = m, k = p { x = 3, y = 42 } }\nmain {}"))
    @Test fun sortedMapRemoveUsesKeyOrd() = assertEquals(1L, runSm("$pKeyMap\nlet main = {} -> sorted_map::size { m = sorted_map::remove { m = m, k = p { x = 3, y = 42 } } }\nmain {}"))
    @Test fun sortedMapIntKeysStillWork() = assertEquals(20L, runSm("let main = {} -> match sorted_map::get { m = sorted_map::insert { m = sorted_map::empty {}, k = 2, v = 20 }, k = 2 } is\n  Some { val = v } -> v\n  None -> 0 - 1\nmain {}"))

    // ---- regressions: primitives still work --------------------------------

    @Test fun listContainsInt() = assertEquals(true, run("let main = {} -> list::contains { xs = list::from_seq { xs = [1, 2, 3] }, x = 2 }\nmain {}"))
    @Test fun listDedupInt() = assertEquals(2L, run("let main = {} -> list::size { xs = list::dedup { xs = list::from_seq { xs = [1, 1, 2] } } }\nmain {}"))
    @Test fun listIndexOfStr() = assertEquals(1L, run("let main = {} -> match list::index_of { xs = list::from_seq { xs = [\"a\", \"b\"] }, x = \"b\" } is\n  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"))
}
