package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `map::count`, `any`, `all`, `none` and `find` walk the key and value arrays by
 * index instead of building an `Entry` per element.
 *
 * WHY. `MezzeMap`'s iterator does `new Entry<>(keys[index], values[index])` for
 * every element, so a walk over a 1 000-key map allocated a thousand two-field
 * objects before any predicate ran — and a `ScalarCall` takes the key and the
 * value positionally, so the Entry existed only to be taken apart again.
 * `map::count` went from 17 062 551 ps to 13 504 864 over a 1 000-key map,
 * **1.26x**, with `contains_value` (0.98x), `filter` (1.01x) and `keys` (0.99x)
 * flat beside it — none of those three goes through the five operations that
 * changed.
 *
 * WHAT THESE TESTS ARE FOR. Indexing two arrays in step is right only while
 * `keys()` and `values()` traverse the storage identically, and they are built
 * by SEPARATE walks: `TrieMapStorage.collect(root, out, idx, true/false)`,
 * `LongTrieMapStorage.collectKeys`/`collectValues`, and the flat storages skip
 * their EMPTY slots in both. The iterator this replaces read the same two
 * arrays the same way, so nothing new is being relied on — but nothing TESTED
 * the pairing either, and a misalignment would silently hand a predicate one
 * key with another key's value.
 *
 * So every predicate below couples the key to the value (`v == k * 10`). A
 * pairing that slipped by one answers 0 where the answer is the whole map, and
 * the sizes cross the storage boundaries — one entry, a small flat map, and one
 * large enough to be a trie.
 */
class MapWalkPairingTest {

    private fun eval(source: String): Any? = MezzeTestHelper.run(source.trimIndent())

    /** `v == k * 10` holds for every entry a builder of that shape makes. */
    private fun buildAndCount(n: Int) = """
        use std::collections::map
        let build = { i, m } ->
          if i <= 0 then m
          else build { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
        map::count { m = build { i = $n, m = map::empty {} }, p = { k, v } -> v == k * 10 }
    """

    @Test
    fun `count pairs key with value in a single-entry map`() =
        assertEquals(1L, eval(buildAndCount(1)))

    @Test
    fun `count pairs key with value in a small flat map`() =
        assertEquals(3L, eval(buildAndCount(3)))

    /** Eight entries is where the flat storage gives way. */
    @Test
    fun `count pairs key with value at the flat storage boundary`() =
        assertEquals(8L, eval(buildAndCount(8)))

    /** Large enough to be a trie, so the two separate collect walks matter. */
    @Test
    fun `count pairs key with value in a trie-sized map`() =
        assertEquals(100L, eval(buildAndCount(100)))

    /** `all` over the same coupling: every entry must satisfy it. */
    @Test
    fun `all sees matching pairs`() = assertEquals(
        true,
        eval(
            """
            use std::collections::map
            let build = { i, m } ->
              if i <= 0 then m
              else build { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            map::all { m = build { i = 50, m = map::empty {} }, p = { k, v } -> v == k * 10 }
            """
        ),
    )

    /** `any` for the one entry that breaks the coupling — a misalignment would
     *  find it everywhere or nowhere. */
    @Test
    fun `any finds the single entry whose value is wrong`() = assertEquals(
        true,
        eval(
            """
            use std::collections::map
            let build = { i, m } ->
              if i <= 0 then m
              else build { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            let m = map::insert { m = build { i = 50, m = map::empty {} }, k = 7, v = 0 }
            map::any { m = m, p = { k, v } -> v != k * 10 }
            """
        ),
    )

    /** `none` is the same question inverted, and delegates to `any`. */
    @Test
    fun `none holds when every pair matches`() = assertEquals(
        true,
        eval(
            """
            use std::collections::map
            let build = { i, m } ->
              if i <= 0 then m
              else build { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            map::none { m = build { i = 50, m = map::empty {} }, p = { k, v } -> v != k * 10 }
            """
        ),
    )

    /**
     * `find` returns the PAIR, so it reports both halves of what it matched:
     * key 7's value is 70, and a misalignment shows up as the wrong number
     * rather than as a missing answer.
     */
    @Test
    fun `find returns the value belonging to the key it matched`() = assertEquals(
        70L,
        eval(
            """
            use std::collections::map
            let build = { i, m } ->
              if i <= 0 then m
              else build { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
            match map::find { m = build { i = 50, m = map::empty {} }, p = { k, v } -> k == 7 } is
              Some { val } -> val.v
              None -> 0 - 1
            """
        ),
    )

    /** An empty map: the arrays are empty and every answer is the identity. */
    @Test
    fun `an empty map counts nothing and satisfies all`() = assertEquals(
        1L,
        eval(
            """
            use std::collections::map
            let m: Map Int Int = map::empty {}
            let c = map::count { m = m, p = { k, v } -> True }
            let a = map::all { m = m, p = { k, v } -> False }
            c + (if a then 1 else 0)
            """
        ),
    )
}
