package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A `SortedMap`'s ordering consults the VALUE's `Ord`, not the key's.
 *
 * `compareMaps` compared the values with `order` — the map's own comparator,
 * which is the KEY's. That is wrong twice: the values are not ordered by the
 * key's `Ord`, and the value's own was never consulted, so a value type with a
 * hand-written ordering was silently ignored. The `impl Ord for SortedMap 'k 'v`
 * has declared `with Ord 'k, Ord 'v` the whole time; only `'k` was ever used.
 *
 * It was the last entry in `native-structural-compare-allowlist.txt`, the tail of
 * BUG 6's "natives still decide ordering of a Mezze value in Java".
 */
class SortedMapValueOrdTest {

    /** `V`'s author orders by the LAST DIGIT, which disagrees with the numeric order. */
    private val prelude = """
        use std::collections::sorted_map
        use std::abilities::compare::{ Eq, Ord, Hash }

        newtype V = Int
        impl Eq for V where {
          eq = { it, r } -> match it is V.(a) -> match r is V.(b) -> a % 10 == b % 10,
          neq = { it, r } -> match it is V.(a) -> match r is V.(b) -> if a % 10 == b % 10 then False else True,
        }
        impl Hash for V where { hash = { it } -> match it is V.(a) -> a % 10 }
        impl Ord for V where {
          lt = { it, r } -> match it is V.(a) -> match r is V.(b) -> a % 10 < b % 10,
          le = { it, r } -> match it is V.(a) -> match r is V.(b) -> a % 10 <= b % 10,
          gt = { it, r } -> match it is V.(a) -> match r is V.(b) -> a % 10 > b % 10,
          ge = { it, r } -> match it is V.(a) -> match r is V.(b) -> a % 10 >= b % 10,
        }

        let m = { v: Int } -> sorted_map::empty {}.insert { k = 1, v = V.(v) }
    """.trimIndent()

    @Test
    fun `a sorted map orders by the value's own Ord, not the key's`() {
        // 12 vs 3: numerically 12 > 3, but by the author's rule 2 < 3.
        assertEquals(true, MezzeTestHelper.run("$prelude\nm { v = 12 } < m { v = 3 }"),
            "the value's declared Ord says 12 sorts BEFORE 3 (last digit 2 < 3); " +
                "comparing the values with the KEY's comparator gets this backwards")
    }

    @Test
    fun `values the author calls equal leave the maps equal in order`() {
        // 4 and 24 share a last digit, so neither map is less than the other.
        assertEquals(false, MezzeTestHelper.run("$prelude\nm { v = 4 } < m { v = 24 }"))
        assertEquals(false, MezzeTestHelper.run("$prelude\nm { v = 24 } < m { v = 4 }"))
    }

    @Test
    fun `a shorter map sorts before a longer one with the same prefix`() {
        // `compareMaps` walked `getKeysInternal().length` — the BACKING ARRAY's
        // capacity, which runs ahead of the entry count — so it compared pairs of
        // nulls past the end, and its final length compare asked which CAPACITY
        // was bigger. A one-entry and a three-entry map both sit in a capacity-4
        // array, so they answered EQUAL.
        val src = prelude + "\n" +
            "let one = sorted_map::empty {}.insert { k = 1, v = V.(1) }\n" +
            "let three = sorted_map::empty {}\n" +
            "  .insert { k = 1, v = V.(1) }\n" +
            "  .insert { k = 2, v = V.(2) }\n" +
            "  .insert { k = 3, v = V.(3) }\n" +
            "one < three"
        assertEquals(true, MezzeTestHelper.run(src),
            "a one-entry map is a prefix of the three-entry one, so it sorts first; " +
                "comparing backing-array capacities calls them equal")
    }

    @Test
    fun `the key half still orders by the map's own comparator`() {
        val twoKeys = """
            $prelude
            let a = sorted_map::empty {}.insert { k = 1, v = V.(1) }
            let b = sorted_map::empty {}.insert { k = 2, v = V.(1) }
            a < b
        """.trimIndent()
        assertEquals(true, MezzeTestHelper.run(twoKeys), "key 1 sorts before key 2")
    }
}
