package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BUG 8 — the fused LONG pipeline fires on a seq whose elements are not numbers.
 *
 * `FusedLongPipeline` is a numeric fast path: it reads each element with
 * `((Number) raw).longValue()`. The classifier that chooses it works from the
 * SHAPE of the call chain — which stages, which sink — and does not require the
 * element type to be numeric, so a chain over records reaches it and dies with
 * `MezzeRecord cannot be cast to Number`.
 *
 * Found while tracing BUG 7: a sorted-set round trip over records failed here
 * rather than in any comparison.
 */
class FusedPipelineElementTypeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `size of a seq of records`() {
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type P = { k: Int, tag: Int }
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                xs.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `fold over a seq of records`() {
        assertEquals(
            4L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type P = { k: Int, tag: Int }
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                xs.fold { init = 0, f = { acc, x } -> acc + x.k }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `filter then size over a seq of records`() {
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type P = { k: Int, tag: Int }
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                xs.filter { p = { x } -> x.k > 2 }.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `map to Int then sum over a seq of records`() {
        assertEquals(
            4L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type P = { k: Int, tag: Int }
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                xs.map { f = { x } -> x.k }.sum {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the numeric case the fusion exists for still works`() {
        assertEquals(
            6L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                let xs: [Int] = [1, 2, 3]
                xs.sum {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `size through a generic function whose element type is a variable`() {
        // The plain chains above all have a visible element type. This one does
        // not: inside `count`, `xs` is `['a]`. If the classifier treats "element
        // type unknown" as "element type numeric", this is where it shows.
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type P = { k: Int, tag: Int }
                let count: { xs: ['a] } -> Int = { xs } -> xs.size {}
                count { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `fold through a generic function whose element type is a variable`() {
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type P = { k: Int, tag: Int }
                let tally: { xs: ['a] } -> Int = { xs } -> xs.fold { init = 0, f = { acc, x } -> acc + 1 }
                tally { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                """.trimIndent(),
            ),
        )
    }

    private val ordP = """
        use std::abilities::compare::{ Eq, Ord }
        use std::collections::seq
        use std::collections::sorted_set
        type P = { k: Int, tag: Int }
        impl Ord for P where {
          lt = { it, r } -> it.k < r.k,
          le = { it, r } -> it.k <= r.k,
          gt = { it, r } -> it.k > r.k,
          ge = { it, r } -> it.k >= r.k
        }
    """.trimIndent()

    @Test
    fun `sorted set of Ints, to_seq then size - the control`() {
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                use std::collections::sorted_set
                let s = sorted_set::from_seq { xs = [3, 1] }
                s.to_seq {}.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `sorted set of records, to_seq then size - the reported crash`() {
        assertEquals(
            2L,
            MezzeTestHelper.run("$ordP\nlet s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }\ns.to_seq {}.size {}"),
        )
    }
}
