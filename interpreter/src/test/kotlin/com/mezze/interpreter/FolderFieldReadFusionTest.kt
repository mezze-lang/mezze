package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A FOLD WHOSE FOLDER READS A FIELD of its element now fuses, and these are the
 * shapes the emitted tree has to get right.
 *
 * WHAT IT COST, and it is the most ordinary pipeline there is.
 * `ChainCanonicalizer.isPureArithTree` admitted a local and a literal as leaves
 * and nothing else, so `{ acc, x } -> acc + x.v` was refused — and the file's
 * own comment on that predicate says what a refused folder does: **it declines
 * the WHOLE chain.** Any pipeline whose elements are RECORDS therefore ran
 * element-at-a-time the moment the folder read a field instead of the whole
 * element. Measured over 1e7, a range mapped to `{ v = x }`, eight interleaved
 * processes per arm with one kernel per process:
 *
 * | row | base | fused | |
 * |---|---|---|---|
 * | field read in the FOLDER | 151 786 901 ns | **4 168 111** | **36.42x** |
 * | field read in a MAP stage (same work) | 4 056 585 | 4 099 013 | 0.99x |
 *
 * The two rows do the same work and now cost the same, which is what a fixed
 * cliff looks like. The base distributions are 151.8-155.8 ms against
 * 4.17-4.55 ms: no overlap at all.
 *
 * WHY THESE ARE VALUE TESTS. Nothing can fail without the canonicalizer half:
 * refusing the chain produces the same ANSWER, just slowly, which is why the
 * cliff sat there unnoticed. The evidence for that half is the measurement.
 * What these tests do catch is the emit half, and they catch it hard: the new
 * leaf has to load the right one of the two loop locals, and swapping them —
 * reading the accumulator where the element was meant — fails
 * `a folder reading one field` with 45 150 against 0.
 *
 * A NESTED FIELD READ IS NOT IN THE GRAMMAR, deliberately: `x.inner.v` is a
 * field read of a field read, not of a param, so the canonicalizer still
 * refuses it and the chain runs unfused. `a nested field read still answers
 * correctly` pins that the refusal is a slowdown and never a wrong answer,
 * which is the property that makes a narrow grammar safe to widen later.
 */
class FolderFieldReadFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /** sum 1..300 through `{ v = x }`, read back in the folder: 45 150. */
    @Test
    fun `a folder reading one field`() = assertEquals(
        45150L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 301 }, f = {x} -> { v = x } },
              init = 0,
              f = {acc, x} -> acc + x.v,
            }
            """,
        ),
    )

    /**
     * TWO FIELDS OF THE SAME ELEMENT in one arithmetic tree, which is where a
     * leaf that resolved to the wrong local would still look plausible: both
     * operands are fields, so only the VALUES tell them apart.
     * sum of (x * 10) - x over 1..100 is 9 * 5050 = 45 450.
     */
    @Test
    fun `a folder reading two fields of its element`() = assertEquals(
        45450L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map {
                it = iter::range { start = 1, end = 101 },
                f = {x} -> { big = x * 10, small = x },
              },
              init = 0,
              f = {acc, x} -> acc + x.big - x.small,
            }
            """,
        ),
    )

    /**
     * A FIELD AND A LITERAL, so the tree mixes the new leaf with the old ones.
     * sum of (x + 2) over 1..50 is 1275 + 100 = 1375.
     */
    @Test
    fun `a folder mixing a field read with a literal`() = assertEquals(
        1375L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 51 }, f = {x} -> { v = x } },
              init = 0,
              f = {acc, x} -> acc + x.v + 2,
            }
            """,
        ),
    )

    /**
     * A FILTER BEFORE THE FOLD, so the fused loop has a stage AND the new leaf.
     * The even numbers of 1..100 sum to 2550.
     */
    @Test
    fun `a filtered chain whose folder reads a field`() = assertEquals(
        2550L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::filter {
                it = iter::map { it = iter::range { start = 1, end = 101 }, f = {x} -> { v = x } },
                p = {x} -> x.v % 2 == 0,
              },
              init = 0,
              f = {acc, x} -> acc + x.v,
            }
            """,
        ),
    )

    /**
     * NOT IN THE GRAMMAR — a field of a field. The canonicalizer refuses, the
     * chain runs unfused, and the answer must still be right: 1..100 is 5050.
     */
    @Test
    fun `a nested field read still answers correctly`() = assertEquals(
        5050L,
        eval(
            """
            use std::collections::iter
            iter::fold {
              it = iter::map {
                it = iter::range { start = 1, end = 101 },
                f = {x} -> { inner = { v = x } },
              },
              init = 0,
              f = {acc, x} -> acc + x.inner.v,
            }
            """,
        ),
    )

    /**
     * A WARM LOOP, because the emitted tree is a compiled path: 1..100 summed
     * through a record, two hundred times, is 1 010 000.
     */
    @Test
    fun `a folder field read stays correct in a warm loop`() = assertEquals(
        1010000L,
        eval(
            """
            use std::collections::iter
            let once = {} -> iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 101 }, f = {x} -> { v = x } },
              init = 0,
              f = {acc, x} -> acc + x.v,
            }
            let go = { i, acc } ->
              if i <= 0 then acc
              else go { i = i - 1, acc = acc + once {} }
            go { i = 200, acc = 0 }
            """,
        ),
    )
}
