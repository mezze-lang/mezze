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

    // ---- regressions: primitives still work --------------------------------

    @Test fun listContainsInt() = assertEquals(true, run("let main = {} -> list::contains { xs = list::from_seq { xs = [1, 2, 3] }, x = 2 }\nmain {}"))
    @Test fun listDedupInt() = assertEquals(2L, run("let main = {} -> list::size { xs = list::dedup { xs = list::from_seq { xs = [1, 1, 2] } } }\nmain {}"))
    @Test fun listIndexOfStr() = assertEquals(1L, run("let main = {} -> match list::index_of { xs = list::from_seq { xs = [\"a\", \"b\"] }, x = \"b\" } is\n  Some { val = i } -> i\n  None -> 0 - 1\nmain {}"))
}
