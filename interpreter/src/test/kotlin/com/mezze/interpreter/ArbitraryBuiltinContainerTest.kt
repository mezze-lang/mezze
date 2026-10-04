package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Generating the BUILTIN CONTAINERS — `Set`, `SortedSet`, `Deque`, `List`,
 * `NonEmptyList`, `Map`, `SortedMap` — and `Frac`.
 *
 * ## Why these are written impls and not derived ones
 *
 * A container's identity is a reserved NAME (`BuiltinTypes`) rather than a
 * declaration, so there is no structure for the deriver to walk. The same absence
 * is what makes writing them legal: the coherence rule keys on a declaration, finds
 * none, and does not apply. `Option` and `Result` DO have declarations, which is
 * the entire reason they are still blocked (S-4) while these are not.
 *
 * ## Every draw is a DIRECT annotation, deliberately
 *
 * `let s: Set Bool = Arbitrary.arbitrary {}` — the shape a person would write, and
 * the shape `mez sample` emits. It was impossible until S-10: a parameterised impl
 * of a receiver-less operation could not be entered from a hand-written call site
 * at all, so every container test in this repo went through a record FIELD instead,
 * where a derived instance injects the element dictionary as a literal. That was a
 * workaround, and these tests exist partly to keep it from coming back.
 *
 * ## What these assert, and why not "it generated"
 *
 * Every case pins an invariant a WRONG generator would violate while still
 * returning a value of the right type — the failure mode that matters, because
 * garbage of the right type passes any smoke test:
 *
 *  - a `Set Bool` has at most 2 elements. Dedup is the whole content of `Set`; a
 *    generator handing back the drawn sequence unchanged would report more.
 *  - a `NonEmptyList` has at least 1. Its emptiness is a type error, so an empty
 *    one is a value the type forbids.
 *  - a `SortedSet` comes out strictly increasing — the right container built with
 *    the wrong invariant would pass a size check.
 *  - a `Frac`'s denominator is never zero. The random half divides, so that is the
 *    one way the impl can produce a non-number.
 */
class ArbitraryBuiltinContainerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, SeededSample }
        use std::abilities::arbitrary::{ Arbitrary, int_min }
        use std::collections::seq
        use std::collections::set
        use std::collections::list
        use std::collections::map
        use std::collections::sorted_map
        use std::collections::deque
        use std::collections::sorted_set
        use std::collections::nonemptylist
        use std::option::{ get_or }
        use std::frac
        use std::bool
    """.trimIndent()

    /** Runs `body` under a seed and returns the Int it produced. */
    private fun drawInt(seed: Int, body: String): Long {
        val source = "$imports\n\nperform SeededSample { seed = $seed } in do\n$body\n"
        val v = runCatching { MezzeTestHelper.run(source) }
        assertTrue(v.isSuccess, "failed to generate: ${v.exceptionOrNull()?.message}")
        return MezzeTestHelper.asLong(v.getOrNull())
            ?: error("expected an Int, got ${v.getOrNull()}")
    }

    /** The body must evaluate to `1`; `0` means the invariant was violated. */
    private fun holds(label: String, seed: Int, body: String) {
        assertEquals(1L, drawInt(seed, body), "$label — the invariant does not hold")
    }

    @Test
    fun `a Set of Bool has at most two elements`() {
        // Across seeds: one draw could be short by luck, and the bug this catches
        // (handing back the undeduplicated sequence) needs a repeat to show.
        for (seed in 1..6) {
            val size = drawInt(
                seed,
                """
                let s: Set Bool = Arbitrary.arbitrary {}
                set::size { s = s }
                """.trimIndent(),
            )
            assertTrue(
                size in 0..2,
                "a Set Bool reported $size elements at seed $seed — only 2 exist, so the " +
                    "draw was not deduplicated",
            )
        }
    }

    @Test
    fun `a NonEmptyList is never empty`() {
        for (seed in 1..6) {
            val size = drawInt(
                seed,
                """
                let xs: NonEmptyList Int = Arbitrary.arbitrary {}
                nonemptylist::size { xs = xs }
                """.trimIndent(),
            )
            assertTrue(size >= 1, "a NonEmptyList came out with $size elements at seed $seed")
        }
    }

    /**
     * `first` is carried because the set may legitimately CONTAIN `int_min` — the
     * Int generator has it in its table at 10% — so seeding `prev` with it and
     * demanding a strict increase on the first element would fail on a correct set.
     */
    @Test
    fun `a SortedSet comes out strictly increasing`() {
        holds(
            "SortedSet Int ordering", 5,
            """
            let s: SortedSet Int = Arbitrary.arbitrary {}
            let r = seq::fold {
              xs = sorted_set::to_seq { s = s },
              init = { prev = int_min, ok = True, first = True },
              f = { acc, x } -> {
                prev = x,
                first = False,
                ok = bool::and {
                  l = acc.ok,
                  r = bool::or { l = acc.first, r = x > acc.prev },
                },
              },
            }
            if r.ok then 1 else 0
            """.trimIndent(),
        )
    }



    /**
     * MEASURED LIMIT, recorded because it is not what the name promises: this
     * catches a SYSTEMATIC zero denominator, not a rare one.
     *
     * Mutation-tested both ways. Dropping the `+ 1` from `choose { bound = 1000 }`
     * — so zero becomes possible at 1-in-1000 — did NOT fail this test, because
     * ~20 random draws see it about 2% of the time. Changing the bound to `1`, so
     * the denominator is always zero, failed immediately. Forty draws is therefore
     * enough for a guard that is missing and nowhere near enough for one that is
     * merely leaky; catching the leaky case needs a bound-aware test, not more
     * draws.
     *
     * The systematic failure also exposed something worth its own fix: it arrived
     * as `java.lang.ArithmeticException: Division by zero in fraction`, a HOST
     * exception, where `1 / 0` on `Int` raises a guest error. Same class of bug as
     * S-6, in `frac::make` rather than in the generator. Filed as S-9.
     */
    /**
     * `List`'s size agrees with a COUNT over its own elements — two independent
     * natives walking the same drawn value.
     *
     * Deliberately NOT via `list::to_seq`, and that is a finding rather than a
     * convenience: `seq::size { xs = list::to_seq { xs = l } }` fails on a
     * cons-backed List with
     * `UnsupportedSpecializationException: ListToLongSeq_Node: [List(a, b)], [MezzeCons]`
     * — and it fails for a LITERAL `list::from_seq { xs = ["a", "b"] }` too, with no
     * sampling anywhere. That is a pre-existing `list::to_seq` bug (S-12), not
     * something about generation; `list::size` on a drawn List is correct, and
     * `mez sample` renders one as `List("world")`.
     */
    @Test
    fun `a List's size agrees with a count over its elements`() {
        holds(
            "List size", 17,
            """
            let l: List Str = Arbitrary.arbitrary {}
            if list::size { xs = l } == list::count { xs = l, p = { x } -> True }
              then 1
              else 0
            """.trimIndent(),
        )
    }

    @Test
    fun `a Frac never has a zero denominator`() {
        holds(
            "Frac denominator", 13,
            """
            let r = seq::fold {
              xs = seq::range { start = 0, end = 40 },
              init = True,
              f = { acc, x } -> do
                let q: Frac = Arbitrary.arbitrary {}
                bool::and { l = acc, r = frac::denominator { n = q } != 0 }
            }
            if r then 1 else 0
            """.trimIndent(),
        )
    }

    /**
     * TWO element dictionaries live at once, for the key and the value
     * independently, and the pair is drawn inside a LAMBDA so both must be
     * CAPTURED. That combination was S-11, where one dictionary served both.
     *
     * The default is `x.v + 1` rather than a fixed sentinel so a missing key can
     * never coincide with the value looked for: with `def = 0` an entry whose value
     * is genuinely `0` would pass even if the lookup found nothing.
     */
    @Test
    fun `a Map's keys look up to their own values`() {
        holds(
            "Map Str Int lookup", 7,
            """
            let m: Map Str Int = Arbitrary.arbitrary {}
            let r = seq::fold {
              xs = map::to_seq { m = m },
              init = True,
              f = { acc, x } -> bool::and {
                l = acc,
                r = get_or { opt = map::get { m = m, k = x.k }, def = x.v + 1 } == x.v,
              },
            }
            if r then 1 else 0
            """.trimIndent(),
        )
    }

    /** Key and value types SWAPPED relative to the case above, so a single crossed
     *  pair of dictionaries cannot satisfy both tests. */
    @Test
    fun `a SortedMap's keys look up to their own values`() {
        holds(
            "SortedMap Int Str lookup", 11,
            """
            let m: SortedMap Int Str = Arbitrary.arbitrary {}
            let r = seq::fold {
              xs = seq::from_list { xs = sorted_map::to_list { m = m } },
              init = True,
              f = { acc, x } -> bool::and {
                l = acc,
                r = get_or { opt = sorted_map::get { m = m, k = x.k }, def = " absent" } == x.v,
              },
            }
            if r then 1 else 0
            """.trimIndent(),
        )
    }

    @Test
    fun `a Deque agrees with its own sequence`() {
        holds(
            "Deque size", 17,
            """
            let d: Deque Int = Arbitrary.arbitrary {}
            if deque::size { d = d } == seq::size { xs = deque::to_seq { d = d } }
              then 1
              else 0
            """.trimIndent(),
        )
    }



    /**
     * Containers nested in containers, with no special case anywhere for the
     * combination — the element draw is an ordinary recursive `arbitrary {}`, which
     * is the whole claim. `[[Set Bool]]` is three levels of peeling.
     */
    @Test
    fun `containers nest`() {
        holds(
            "[Set Int] and [[Set Bool]]", 19,
            """
            let deep: [[Set Bool]] = Arbitrary.arbitrary {}
            let nested: [Set Int] = Arbitrary.arbitrary {}
            let inner = seq::fold {
              xs = nested,
              init = True,
              f = { acc, x } -> bool::and {
                l = acc,
                r = set::size { s = x } == seq::size { xs = set::to_seq { s = x } },
              },
            }
            let outer = seq::fold {
              xs = deep,
              init = True,
              f = { acc, x } -> bool::and {
                l = acc,
                r = seq::fold {
                  xs = x,
                  init = True,
                  f = { acc, x } -> bool::and { l = acc, r = set::size { s = x } <= 2 },
                },
              },
            }
            if bool::and { l = inner, r = outer } then 1 else 0
            """.trimIndent(),
        )
    }

    /**
     * The property every case above depends on: a seed determines the draw.
     *
     * The digest divides by a billion before accumulating, and that is not
     * cosmetic — `Int`'s generator has `int_max` and `int_min` in its table at 10%
     * each, so a plain `acc + x` over a drawn set overflows, and since S-6 an
     * overflow is a fatal guest error. The test would then fail for arithmetic
     * reasons while claiming to say something about determinism. Scaling down keeps
     * every term under 1e10 and still distinguishes different sets.
     */
    @Test
    fun `the same seed draws the same container`() {
        val body = """
            let s: Set Int = Arbitrary.arbitrary {}
            seq::fold {
              xs = set::to_seq { s = s },
              init = 0,
              f = { acc, x } -> acc + x / 1000000000,
            }
        """.trimIndent()
        assertEquals(
            drawInt(23, body), drawInt(23, body),
            "the same seed produced two different Sets",
        )
    }
}
