package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A fused `range → map → collect` builds for the ELEMENT's kind, not the
 * source's (BUG 29).
 *
 * `iter::range` yields longs, so the fused pipeline took a `long[]` builder to
 * avoid boxing. A `map` stage can change that, and nothing consulted it:
 * `iter::to_seq { it = iter::map { it = range …, f = { x } -> "s" } }` reached
 * `LongSeqBuilderAdd` with a String and threw
 * `UnsupportedSpecializationException … [long[], String]`.
 *
 * This is the `source_kind_is_not_element_kind` shape — a source's kind is no
 * evidence about its elements once a stage has run.
 *
 * Every assertion here reads the CONTENTS. Counting would not do: `seq::size`
 * never inspects an element, so a size assertion passes against a builder
 * holding the wrong thing, which is how this went unnoticed in the first place.
 */
class IterMapElementKindTest {
    private fun run(body: String): Any = MezzeTestHelper.run(
        """
        use std::collections::{ iter, seq, list }
        use std::int
        use std::str
        ${body.trimIndent()}
        """.trimIndent()
    )

    /** The case that threw. */
    @Test fun `a range mapped to Str collects into a seq of Str`() {
        assertEquals(
            "0a,1a,2a",
            run(
                """
                seq::join {
                  xs = iter::to_seq { it = iter::map {
                    it = iter::range { start = 0, end = 3 },
                    f = { x } -> str::concat { l = int::to_str { n = x }, r = "a" },
                  } },
                  sep = ",",
                }
                """
            ).toString(),
        )
    }

    /** The same through `to_list`, which took the same builder. */
    @Test fun `a range mapped to Str collects into a list of Str`() {
        assertEquals(
            "0a,1a,2a",
            run(
                """
                seq::join {
                  xs = iter::to_seq { it = iter::from_list { xs = iter::to_list { it = iter::map {
                    it = iter::range { start = 0, end = 3 },
                    f = { x } -> str::concat { l = int::to_str { n = x }, r = "a" },
                  } } } },
                  sep = ",",
                }
                """
            ).toString(),
        )
    }

    /**
     * The arithmetic map KEEPS the long builder — the fast path is not lost.
     *
     * The classifier only admits ADD/SUB/MUL/DIV/MOD as an inline map, so an
     * inline map is arithmetic on longs by construction and stays unboxed. This
     * asserts the values, which is all a test can see from here; the point is
     * that it still answers correctly after the guard was added.
     */
    @Test fun `a range mapped arithmetically still collects correctly`() {
        assertEquals(
            "1,2,3",
            run(
                """
                seq::join {
                  xs = seq::map { xs = iter::to_seq { it = iter::map {
                    it = iter::range { start = 0, end = 3 }, f = { x } -> x + 1,
                  } }, f = { x } -> int::to_str { n = x } },
                  sep = ",",
                }
                """
            ).toString(),
        )
    }

    /** The mirror direction, which always worked, kept as a guard. */
    @Test fun `a seq of Str mapped to Int collects correctly`() {
        assertEquals(
            6L,
            run(
                """
                iter::sum { it = iter::from_seq { xs = iter::to_seq { it = iter::map {
                  it = iter::from_seq { xs = ["a", "bb", "ccc"] },
                  f = { x } -> str::size { s = x },
                } } } }
                """
            ),
        )
    }

    /** With a filter between map and collect — the shape that masked the bug. */
    @Test fun `a filter between the map and the sink does not change the answer`() {
        assertEquals(
            "0a,2a",
            run(
                """
                let labelled = iter::map {
                  it = iter::range { start = 0, end = 4 },
                  f = { x } -> str::concat { l = int::to_str { n = x }, r = "a" },
                }
                let kept = iter::filter { it = labelled, p = { x } -> str::size { s = x } == 2 }
                let evens = iter::filter { it = kept, p = { x } -> x != "1a" }
                let final_xs = iter::filter { it = evens, p = { x } -> x != "3a" }
                seq::join { xs = iter::to_seq { it = final_xs }, sep = "," }
                """
            ).toString(),
        )
    }
}
