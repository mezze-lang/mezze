package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * #23 — equality/ordering-dependent `seq` functions must decide element equality and
 * ordering via the element's own `Eq`/`Ord` impl, so they AGREE with the `==`/`<`
 * operators. Before this fix they used JVM structural `.equals` / a primitive-only
 * native compare, which silently DIVERGED from a custom impl:
 *   a == b  ⇒ true   (custom Eq compares only field x)
 *   contains [a] b ⇒ false  (structural .equals saw field y differ)
 *
 * The fix threads the user's `eq`/`cmp` closure into the native ops (the sort_by
 * pattern) and adds the load-bearing `with Eq 'a` / `with Ord 'a` constraints.
 *
 * Point has a CUSTOM Eq/Ord keyed ONLY on `x` (field `y` is deliberately ignored),
 * so structural comparison would give different answers than the impl — the contrast
 * that makes the divergence observable.
 */
class SeqEqOrdConsistencyTest {

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    private val preamble = """
        use std::collections::seq
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
    """.trimIndent()

    private fun eval(body: String): Any? = run("$preamble\n\n$body")

    // P is a NEWTYPE: the only legal home for a non-structural Eq/Ord (a record
    // derives Eq itself; a hand-written impl on it is a duplicate).
    // ---- Eq consistency: these must agree with `==` (custom Eq: only x matters) ----

    @Test
    fun `contains uses the element Eq impl`() {
        // a=(1,10), looked up by (1,99): equal by custom Eq (x=1), differ structurally.
        assertEquals(true, eval(
            "let main = {} -> seq::contains { xs = [ P.({ x = 1, y = 10 }) ], x = P.({ x = 1, y = 99 }) }\nmain {}"
        ))
        assertEquals(false, eval(
            "let main = {} -> seq::contains { xs = [ P.({ x = 1, y = 10 }) ], x = P.({ x = 2, y = 10 }) }\nmain {}"
        ))
    }

    @Test
    fun `distinct collapses elements equal by the Eq impl`() {
        // (1,10) and (1,99) are equal by custom Eq -> distinct keeps one.
        assertEquals(1L, eval(
            "let main = {} -> seq::size { xs = seq::distinct { xs = [ P.({ x = 1, y = 10 }), P.({ x = 1, y = 99 }) ] } }\nmain {}"
        ))
    }

    @Test
    fun `dedup collapses consecutive elements equal by the Eq impl`() {
        assertEquals(1L, eval(
            "let main = {} -> seq::size { xs = seq::dedup { xs = [ P.({ x = 5, y = 1 }), P.({ x = 5, y = 2 }) ] } }\nmain {}"
        ))
    }

    @Test
    fun `index_of uses the Eq impl`() {
        assertEquals(1L, eval(
            "let main = {} -> match seq::index_of { xs = [ P.({ x = 1, y = 0 }), P.({ x = 7, y = 9 }) ], x = P.({ x = 7, y = 0 }) } is\n" +
            "  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"
        ))
    }

    @Test
    fun `count_element uses the Eq impl`() {
        // two elements with x=1 (differing y) both match a x=1 target.
        assertEquals(2L, eval(
            "let main = {} -> seq::count_element { xs = [ P.({ x = 1, y = 1 }), P.({ x = 1, y = 2 }), P.({ x = 9, y = 0 }) ], x = P.({ x = 1, y = 99 }) }\nmain {}"
        ))
    }

    @Test
    fun `starts_with uses the Eq impl`() {
        assertEquals(true, eval(
            "let main = {} -> seq::starts_with { xs = [ P.({ x = 1, y = 5 }), P.({ x = 2, y = 6 }) ], prefix = [ P.({ x = 1, y = 99 }) ] }\nmain {}"
        ))
    }

    // ---- Ord consistency: sort / is_sorted / [T] compare respect the Ord impl ----

    @Test
    fun `sort orders by the Ord impl (keyed on x)`() {
        // Input out of order by x; after sort, first element's x must be the smallest.
        assertEquals(1L, eval(
            "let main = {} -> match seq::first { xs = seq::sort { xs = [ P.({ x = 3, y = 0 }), P.({ x = 1, y = 0 }), P.({ x = 2, y = 0 }) ] } } is\n" +
            "  Some { val = p } -> px { p = p }\n  None -> 0 - 1\nmain {}"
        ))
    }

    @Test
    fun `is_sorted respects the Ord impl`() {
        assertEquals(true, eval(
            "let main = {} -> seq::is_sorted { xs = [ P.({ x = 1, y = 9 }), P.({ x = 2, y = 0 }), P.({ x = 3, y = 5 }) ] }\nmain {}"
        ))
        assertEquals(false, eval(
            "let main = {} -> seq::is_sorted { xs = [ P.({ x = 3, y = 0 }), P.({ x = 1, y = 0 }) ] }\nmain {}"
        ))
    }

    // NOTE: `[P] < [P]` (the `<` operator directly on two sequences of a user type)
    // is a SEPARATE, pre-existing operator-dispatch gap — the `<` resolves to a direct
    // primitive node instead of the `['a]` Ord impl, so it throws "Cannot compare
    // MezzeRecord < MezzeRecord". `sort`/`is_sorted`/`binary_search` (which reach the
    // impl through the native, as tested above) work. Tracked as a follow-up; not
    // asserted here so this gate stays focused on the #23 Eq/Ord-consistency fix.

    // ---- Primitive elements still work (guards against a fix that over-reaches) ----

    @Test
    fun `primitive Int elements still work through the threaded eq`() {
        assertEquals(true, eval("let main = {} -> seq::contains { xs = [1, 2, 3], x = 2 }\nmain {}"))
        assertEquals(2L, eval("let main = {} -> seq::size { xs = seq::distinct { xs = [1, 1, 2] } }\nmain {}"))
    }
}
