package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `map::count`, `any`, `all`, `none` and `find` must hand each predicate a key
 * together with ITS OWN value, and must stop walking once the answer is known.
 *
 * THE WALK HAS BEEN WRONG TWICE, in opposite directions, which is why the
 * pairing is tested rather than assumed.
 *
 * FIRST it built an `Entry` per element — `MezzeMap`'s iterator does
 * `new Entry<>(keys[index], values[index])` every step — so a walk over a
 * 1 000-key map allocated a thousand two-field objects before any predicate
 * ran. That became `keys()` and `values()` indexed in step, worth 1.26x on
 * `map::count`, and those two arrays are built by SEPARATE walks
 * (`TrieMapStorage.collect` with a flag, `LongTrieMapStorage.collectKeys` and
 * `collectValues`, and the flat storages skipping their EMPTY slots in both),
 * so correctness rested on the two traversals agreeing — which nothing checked.
 *
 * THEN the arrays turned out to be the cost they were meant to avoid. On a
 * 1 000-entry long-keyed map the two arrays measure 7.16 ns an entry, the Entry
 * iterator 7.25, and a single `forEach` walk 1.97 — so the Entry was never the
 * main cost, the two n-element arrays and the doubled traversal were. These
 * operations now take one `walkEntries` pass with no arrays at all, and the
 * four that can answer early take `walkEntriesUntil`.
 *
 * SO THE TESTS HOLD TWO THINGS. Every predicate couples the key to the value
 * (`v == k * 10`), at one entry, three, eight (the flat storage boundary) and a
 * hundred — a pairing that slips answers 0 where the answer is the whole map,
 * under either mechanism. And the early exits are counted, because a predicate
 * returning false now has to stop a RECURSIVE trie walk where it used to
 * `return` out of a flat loop: `any` over an always-true predicate must call it
 * exactly ONCE however large the map.
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

    // ── EARLY EXIT. The predicate's `false` has to stop a recursive walk. ──

    /** The number of predicate calls, counted in an atom. `swap` answers
     *  with the NEW value, so `bump {} > 0` both counts and yields True and
     *  `bump {} < 0` both counts and yields False — no `do` block, which a
     *  one-line predicate cannot lay out. */
    private fun callsDuring(op: String) = """
        use std::collections::map
        use std::effects::async::atom::{ atom, deref, swap }
        use std::effects::async::{ Async, LoomAsync }
        let build = { i, m } ->
          if i <= 0 then m
          else build { i = i - 1, m = map::insert { m = m, k = i, v = i * 10 } }
        let main = {} -> perform LoomAsync in do
          let m = build { i = 100, m = map::empty {} }
          let calls = atom { val = 0 }
          let bump = { } -> swap { atom = calls, f = { current } -> current + 1 }
          let _ = $op
          deref { atom = calls }
        main {}
    """

    @Test
    fun `any stops at the first matching entry`() = assertEquals(
        1L,
        eval(callsDuring(
            "map::any { m = m, p = { k, v } -> bump {} > 0 }")),
    )

    @Test
    fun `all stops at the first failing entry`() = assertEquals(
        1L,
        eval(callsDuring(
            "map::all { m = m, p = { k, v } -> bump {} < 0 }")),
    )

    @Test
    fun `find stops at the first match`() = assertEquals(
        1L,
        eval(callsDuring(
            "map::find { m = m, p = { k, v } -> bump {} > 0 }")),
    )

    /** And a predicate that never matches must see every entry — the walk must
     *  not stop EARLY of its own accord. */
    @Test
    fun `any with no match visits every entry`() = assertEquals(
        100L,
        eval(callsDuring(
            "map::any { m = m, p = { k, v } -> bump {} < 0 }")),
    )
}
