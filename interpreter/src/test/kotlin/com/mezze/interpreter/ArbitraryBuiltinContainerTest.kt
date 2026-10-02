package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Generating the BUILTIN CONTAINERS — `Set`, `SortedSet`, `Deque`, `NonEmptyList` —
 * and `Frac`.
 *
 * ## Why these are written impls and not derived ones
 *
 * A container's identity is a reserved NAME (`BuiltinTypes`) rather than a
 * declaration, so there is no structure for the deriver to walk. The same absence
 * is what makes writing them legal: the coherence rule keys on a declaration, finds
 * none, and does not apply. `Option` and `Result` DO have declarations, which is
 * the entire reason they are still blocked (S-4) while these are not.
 *
 * ## Why every draw goes through a RECORD FIELD
 *
 * Not incidental, and not the shape you would write by hand. A direct
 * `let s: Set Bool = Arbitrary.arbitrary {}` does NOT currently pin the impl — it
 * fails with "nothing here determines that type" even though the annotation says
 * exactly what the type is. That gap is older than these impls and is not about
 * containers: it reproduces on `let xs: [Int] = …` against the long-standing
 * `impl Arbitrary for ['a]`. It has its own task; `ArbitraryDirectAnnotationTest`
 * pins it so it cannot be forgotten.
 *
 * A record field is the form that works, and it is also the form that matters: a
 * Mezze function takes one record, so "generate arguments for `f`" is `arbitrary`
 * at a record type, which is what `mez sample` actually emits.
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
 *
 * `Map` and `SortedMap` are NOT here: their impls are written but withheld, because
 * an impl head binding two type variables currently breaks resolution for every
 * `Arbitrary` call in the program, `Int` included. Also its own task.
 */
class ArbitraryBuiltinContainerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, with_seed }
        use std::abilities::arbitrary::{ Arbitrary, int_min }
        use std::collections::seq
        use std::collections::set
        use std::collections::deque
        use std::collections::sorted_set
        use std::collections::nonemptylist
        use std::frac
        use std::bool
    """.trimIndent()

    /**
     * Runs `body` under a seed and returns the Int it produced. `decls` is spliced
     * at MODULE level, because a `type` declaration is not an expression and a do
     * block rejects it.
     */
    private fun drawInt(seed: Int, decls: String, body: String): Long {
        val source = "$imports\n\n$decls\n\nwith_seed { seed = $seed, call = {} -> do\n$body\n}"
        val v = runCatching { MezzeTestHelper.run(source) }
        assertTrue(v.isSuccess, "failed to generate: ${v.exceptionOrNull()?.message}")
        return MezzeTestHelper.asLong(v.getOrNull())
            ?: error("expected an Int, got ${v.getOrNull()}")
    }

    /** The body must evaluate to `1`; `0` means the invariant was violated. */
    private fun holds(label: String, seed: Int, decls: String, body: String) {
        assertEquals(1L, drawInt(seed, decls, body), "$label — the invariant does not hold")
    }

    @Test
    fun `a Set of Bool has at most two elements`() {
        // Across seeds: one draw could be short by luck, and the bug this catches
        // (handing back the undeduplicated sequence) needs a repeat to show.
        for (seed in 1..6) {
            val size = drawInt(
                seed,
                "type W = { s: Set Bool }",
                """
                let w: W = Arbitrary.arbitrary {}
                set::size { s = w.s }
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
                "type W = { xs: NonEmptyList Int }",
                """
                let w: W = Arbitrary.arbitrary {}
                nonemptylist::size { xs = w.xs }
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
            "type W = { s: SortedSet Int }",
            """
            let w: W = Arbitrary.arbitrary {}
            let r = seq::fold {
              xs = sorted_set::to_seq { s = w.s },
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
     * S-6, in `frac::make` rather than in the generator.
     */
    @Test
    fun `a Frac never has a zero denominator`() {
        holds(
            "Frac denominator", 13,
            "type W = { q: Frac }",
            """
            let r = seq::fold {
              xs = seq::range { start = 0, end = 40 },
              init = True,
              f = { acc, x } -> do
                let w: W = Arbitrary.arbitrary {}
                bool::and { l = acc, r = frac::denominator { n = w.q } != 0 }
            }
            if r then 1 else 0
            """.trimIndent(),
        )
    }

    @Test
    fun `a Deque's size agrees with its own sequence`() {
        holds(
            "Deque size", 17,
            "type W = { d: Deque Int }",
            """
            let w: W = Arbitrary.arbitrary {}
            if deque::size { d = w.d } == seq::size { xs = deque::to_seq { d = w.d } }
              then 1
              else 0
            """.trimIndent(),
        )
    }

    /**
     * Two container layers with no special case anywhere for the combination — the
     * element draw is an ordinary recursive `arbitrary {}`, which is the whole claim.
     * `[[Set Int]]` exercises three levels of peeling.
     */
    @Test
    fun `containers nest`() {
        holds(
            "[Set Bool] and [[Set Int]] nesting", 19,
            "type Nested = { tags: [Set Bool], deep: [[Set Int]] }",
            """
            let n: Nested = Arbitrary.arbitrary {}
            let outer = seq::fold {
              xs = n.tags,
              init = True,
              f = { acc, x } -> bool::and { l = acc, r = set::size { s = x } <= 2 },
            }
            let inner = seq::fold {
              xs = n.deep,
              init = True,
              f = { acc, x } -> bool::and {
                l = acc,
                r = seq::fold {
                  xs = x,
                  init = True,
                  f = { acc, x } -> bool::and {
                    l = acc,
                    r = set::size { s = x } == seq::size { xs = set::to_seq { s = x } },
                  },
                },
              },
            }
            if bool::and { l = outer, r = inner } then 1 else 0
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
        val decls = "type W = { s: Set Int }"
        val body = """
            let w: W = Arbitrary.arbitrary {}
            seq::fold {
              xs = set::to_seq { s = w.s },
              init = 0,
              f = { acc, x } -> acc + x / 1000000000,
            }
        """.trimIndent()
        assertEquals(
            drawInt(23, decls, body), drawInt(23, decls, body),
            "the same seed produced two different Sets",
        )
    }
}
