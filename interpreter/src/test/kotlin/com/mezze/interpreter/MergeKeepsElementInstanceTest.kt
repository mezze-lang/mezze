package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `set::union` and `map::merge` collapsed by STRUCTURAL equality, not the
 * author's.
 *
 * A hash set stores no equivalence of its own — it is a CHAMP trie keyed on
 * the compiler's hashing — EXCEPT when built through the evidence route, which
 * gives it an `EvidenceSetStorage`, whose own comment says a set with evidence
 * keeps it. Both merges reached that storage and threw it away: they copy both
 * sides into arrays and rebuild through the STRUCTURAL `fromElements` /
 * `fromEntries`. `MezzeSet.storage()` exists to be asked this exact question
 * before a rebuild — its javadoc says so — and neither merge asked.
 *
 * WHY IT SURVIVED, and why these tests assert SIZE. `contains` and `get` take
 * their evidence from the CALL SITE, not from the collection, so the merged
 * result still answered correctly for a differently-spelled member. Every
 * probe of the RESULT looked right. Only the count of what the merge collapsed
 * gave it away.
 *
 * FOUND BY SWEEP, not by a report: a mechanical probe that gives a newtype an
 * `Eq` deliberately coarser than its representation, then asks every operation
 * on the container a question whose answer differs under the two equalities.
 * Nine of the ten set operations with no `_with_evidence` form were already
 * correct — `intersection`, `difference`, `is_subset`, `equals`,
 * `symmetric_difference` and the rest — because they return a subset of one
 * side and never have to collapse ACROSS sides. Union is the one that does.
 *
 * `P` is equal BY TENS: 10, 11 and 19 are one element, 20 and 29 another. The
 * hash agrees with the Eq, so the pair is lawful. Parity was tried first and
 * is too coarse — two classes cannot make a partial result.
 */
class MergeKeepsElementInstanceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val byTens = """
        use std::collections::set
        use std::collections::map
        use std::collections::seq
        use std::collections::list
        use std::collections::deque
        use std::collections::sorted_set
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype P = Int
        let un: { v: P } -> Int = { v } -> match v is
          P.(n) -> n
        let cls: { v: P } -> Int = { v } -> un { v = v } / 10
        impl Eq for P where {
          eq = { it, r } -> cls { v = it } == cls { v = r },
          neq = { it, r } -> if cls { v = it } == cls { v = r } then False else True,
        }
        impl Hash for P where {
          hash = { it } -> cls { v = it }
        }
        ## ORD ON THE RAW VALUE, deliberately finer than the Eq. A SortedSet of
        ## P(10) and P(11) then holds TWO elements — they differ in order — and
        ## converting it to a hash Set must collapse them to ONE, because they
        ## are the same element under the Eq. A class-wide Ord would make the
        ## source hold one already and the test would prove nothing.
        impl Ord for P where {
          lt = { it, r } -> un { v = it } < un { v = r },
          le = { it, r } -> un { v = it } <= un { v = r },
          gt = { it, r } -> un { v = it } > un { v = r },
          ge = { it, r } -> un { v = it } >= un { v = r },
        }
    """

    /** THE BASELINE. If the evidence route itself is broken, nothing below
     *  means anything: 10 and 11 are one element. */
    @Test
    fun `the evidence route collapses equal elements on construction`() = assertEquals(
        1L,
        eval("$byTens\nset::size { s = set::from_seq { xs = [P.(10), P.(11)] } }"),
    )

    /**
     * {class 1, class 2} union {class 1, class 3} is THREE elements. It was
     * four: `P.(10)` and `P.(11)` are one element under this `Eq` and the
     * structural rebuild kept both.
     */
    @Test
    fun `set union collapses across sides by the written Eq`() = assertEquals(
        3L,
        eval(
            """
            $byTens
            set::size { s = set::union {
              s1 = set::from_seq { xs = [P.(10), P.(20)] },
              s2 = set::from_seq { xs = [P.(11), P.(30)] },
            } }
            """,
        ),
        "4 means the union collapsed by representation and ignored the author's Eq",
    )

    /** The merged set must still bucket by that instance afterwards, or the
     *  next operation on it inherits the wrong equality. */
    @Test
    fun `the union result still buckets by the written Eq`() = assertEquals(
        2L,
        eval(
            """
            $byTens
            set::size { s = set::insert {
              s = set::union {
                s1 = set::from_seq { xs = [P.(10), P.(20)] },
                s2 = set::from_seq { xs = [P.(11)] },
              },
              x = P.(19),
            } }
            """,
        ),
        "inserting another member of class 1 must not grow the set",
    )

    /** The twin, in the sibling container: `MezzeMap.putAll` is the same four
     *  arrays and the same structural rebuild. */
    @Test
    fun `map merge collapses across sides by the written Eq`() = assertEquals(
        3L,
        eval(
            """
            $byTens
            map::size { m = map::merge {
              m1 = map::of { of = [{ k = P.(10), v = 1 }, { k = P.(20), v = 2 }] },
              m2 = map::of { of = [{ k = P.(11), v = 3 }, { k = P.(30), v = 4 }] },
            } }
            """,
        ),
        "4 means the merge bucketed by representation",
    )

    /**
     * AND THE VALUE SEMANTICS ARE UNCHANGED BY THE FIX: `other` goes in
     * second, so a shared key keeps ITS value. Class 1 is written 1 by `m1`
     * and 3 by `m2`, and 3 must survive — a fix that collapsed correctly but
     * kept the wrong value would pass the size test above.
     */
    @Test
    fun `a shared key keeps the second map's value`() = assertEquals(
        3L,
        eval(
            """
            $byTens
            match map::get {
              m = map::merge {
                m1 = map::of { of = [{ k = P.(10), v = 1 }, { k = P.(20), v = 2 }] },
                m2 = map::of { of = [{ k = P.(11), v = 3 }, { k = P.(30), v = 4 }] },
              },
              k = P.(19),
            } is
              Some { val } -> val
              None -> 0 - 1
            """,
        ),
    )

    /**
     * `set::map` COLLAPSES BY THE TARGET'S EQUALITY, which it could not do
     * because it declared no constraint at all: `{ s: Set 'a, f } -> Set 'b`.
     * Its sibling `sorted_set::map` already carried `with Ord 'b` for exactly
     * this reason — the result's elements are `'b`, so the SOURCE's instance
     * says nothing about which of them are the same element.
     *
     * The distinct Ints 10 and 11 map to one element under `P`'s by-tens `Eq`.
     * Structurally they are two, and two is what it answered.
     *
     * Found while fixing BUG 46's fusion half, by asking what `set::map` does
     * about identity at all — not from a report.
     */
    @Test
    fun `set map collapses by the target's Eq`() = assertEquals(
        1L,
        eval(
            """
            $byTens
            set::size { s = set::map {
              s = set::from_seq { xs = [10, 11] },
              f = { val } -> P.(val),
            } }
            """,
        ),
        "2 means the map collapsed by representation and ignored the target's Eq",
    )

    /** And the mapped set must still bucket by that instance afterwards. */
    @Test
    fun `the mapped set still buckets by the target's Eq`() = assertEquals(
        1L,
        eval(
            """
            $byTens
            set::size { s = set::insert {
              s = set::map { s = set::from_seq { xs = [10, 11] }, f = { val } -> P.(val) },
              x = P.(19),
            } }
            """,
        ),
    )

    /**
     * THE SAME SHAPE IN TWO MORE OPERATIONS, found by asking which others
     * produce a container whose elements or keys are the TARGET type and
     * declare nothing about it. Both did, and both collapsed by
     * representation. `sorted_set::filter_map` already carried `with Ord 'b`,
     * which is what showed the pattern was known and these two had been
     * missed.
     */
    @Test
    fun `set filter_map collapses by the target's Eq`() = assertEquals(
        1L,
        eval(
            """
            $byTens
            let go = { } -> set::size { s = set::filter_map {
              set = set::from_seq { xs = [10, 11] },
              f = { val } -> Some { val = P.(val) },
            } }
            go {}
            """,
        ),
        "2 means the kept elements collapsed by representation",
    )

    /** PROBE: the same top-level call with the callback's return ANNOTATED, so
     *  `'b` is concrete from the start rather than inferred through
     *  `Option 'b`. If this collapses where the inferred version does not, the
     *  fault is the call site's recorded type, not the constraint. */
    @Test
    fun `probe — top level with an annotated callback`() = assertEquals(
        1L,
        eval(
            """
            $byTens
            let toP: { val: Int } -> Option P = { val } -> Some { val = P.(val) }
            set::size { s = set::filter_map { set = set::from_seq { xs = [10, 11] }, f = toP } }
            """,
        ),
    )

    /** THE SAME CALL AS A BARE TOP-LEVEL EXPRESSION (BUG 48). The global solve
     *  strips HasConstructor before its ability check, so `'b` inside
     *  `Option 'b` is pinned only by the top-level re-solve in
     *  `ConstraintGenerator` — which used to read that solve's failures and
     *  throw its substitution away. `'b` then reached emit open, the per-site
     *  arrow was discarded as unresolved, mono seeded no spec, and the set was
     *  built under STRUCTURAL identity: 2, where P's own Eq says 1. The
     *  `let`-bound form above was always correct. */
    @Test
    fun `set filter_map at top level collapses by the target's Eq`() = assertEquals(
        1L,
        eval(
            """
            $byTens
            set::size { s = set::filter_map {
              set = set::from_seq { xs = [10, 11] },
              f = { val } -> Some { val = P.(val) },
            } }
            """,
        ),
        "2 means the top-level path still never pinned 'b to P",
    )

    @Test
    fun `map map_keys buckets by the target key's Eq`() = assertEquals(
        1L,
        eval(
            """
            $byTens
            map::size { m = map::map_keys {
              m = map::of { of = [{ k = 10, v = 1 }, { k = 11, v = 2 }] },
              f = { val } -> P.(val),
            } }
            """,
        ),
        "2 means the new keys bucketed by representation",
    )

    /** `map_keys` keeps "the later entry wins", which a fix that collapsed
     *  correctly but kept the first value would pass the size test above. */
    @Test
    fun `map_keys keeps the later entry's value`() = assertEquals(
        2L,
        eval(
            """
            $byTens
            match map::get {
              m = map::map_keys {
                m = map::of { of = [{ k = 10, v = 1 }, { k = 11, v = 2 }] },
                f = { val } -> P.(val),
              },
              k = P.(19),
            } is
              Some { val } -> val
              None -> 0 - 1
            """,
        ),
    )

    /**
     * EVERY CONVERSION INTO A HASH CONTAINER, which creates one and so must
     * bucket by the element's or key's own instance. None of the seven
     * declared anything about it, so all seven bucketed by REPRESENTATION.
     *
     * THIS IS A GAP IN THE EARLIER SWEEP, not a new family. The SORTED
     * conversions were fixed the same day under BUG 45 — `seq::to_sorted_set`,
     * `seq::to_sorted_map` and the deque pair, each of which declared no `Ord`
     * — and their hash twins were never checked. `set::from_seq` is the same
     * operation spelled from the other side and always carried
     * `with Eq 'a, Hash 'a`; that asymmetry is what found these.
     *
     * `sorted_set::to_set` is in here too: converting OUT of an ordered
     * container into a hashed one still needs the element's equality, and the
     * source's comparator says nothing about it.
     */
    @Test
    fun `seq to_set buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nset::size { s = seq::to_set { xs = [P.(10), P.(11)] } }"),
        "2 means it bucketed by representation",
    )

    @Test
    fun `list to_set buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nset::size { s = list::to_set { xs = list::from_seq { xs = [P.(10), P.(11)] } } }"),
        "2 means it bucketed by representation",
    )

    @Test
    fun `deque to_set buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nset::size { s = deque::to_set { d = deque::from_seq { xs = [P.(10), P.(11)] } } }"),
        "2 means it bucketed by representation",
    )

    @Test
    fun `sorted_set to_set buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nset::size { s = sorted_set::to_set { s = sorted_set::from_seq { xs = [P.(10), P.(11)] } } }"),
        "2 means it bucketed by representation",
    )

    @Test
    fun `seq to_map buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nmap::size { m = seq::to_map { xs = [{ k = P.(10), v = 1 }, { k = P.(11), v = 2 }] } }"),
        "2 means it bucketed by representation",
    )

    @Test
    fun `list to_map buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nmap::size { m = list::to_map { xs = list::from_seq { xs = [{ k = P.(10), v = 1 }, { k = P.(11), v = 2 }] } } }"),
        "2 means it bucketed by representation",
    )

    @Test
    fun `deque to_map buckets by the element instance`() = assertEquals(
        1L,
        eval("$byTens\nmap::size { m = deque::to_map { d = deque::from_seq { xs = [{ k = P.(10), v = 1 }, { k = P.(11), v = 2 }] } } }"),
        "2 means it bucketed by representation",
    )

    /** Plain Int keys and elements are untouched: with no hand-written
     *  instance there is no evidence storage and the structural rebuild is
     *  exactly right. */
    @Test
    fun `plain Int merges are unaffected`() {
        assertEquals(
            3L,
            eval(
                """
                use std::collections::set
                set::size { s = set::union {
                  s1 = set::from_seq { xs = [1, 2] },
                  s2 = set::from_seq { xs = [1, 3] },
                } }
                """,
            ),
        )
        assertEquals(
            3L,
            eval(
                """
                use std::collections::map
                map::size { m = map::merge {
                  m1 = map::of { of = [{ k = 1, v = 1 }, { k = 2, v = 2 }] },
                  m2 = map::of { of = [{ k = 1, v = 3 }, { k = 3, v = 4 }] },
                } }
                """,
            ),
        )
    }
}
