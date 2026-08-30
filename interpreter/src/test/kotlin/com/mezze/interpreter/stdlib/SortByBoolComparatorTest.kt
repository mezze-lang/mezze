package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * #342: sort_by / is_sorted_by take a `less-or-equal` Bool comparator
 * (`cmp { a, b }` is True when `a` should come before `b`) — matching how the
 * whole language expresses ordering (Ord's lt/le, the </<= operators), instead of
 * a three-way Int (-1/0/1).
 */
class SortByBoolComparatorTest {

    @Test
    fun `list sort_by ascending with a Bool comparator`() {
        val r = MezzeTestHelper.run(
            """
            use std::collections::list
            let xs = list::from_seq { xs = [3, 1, 4, 1, 5, 9, 2] }
            let sorted = list::sort_by { xs = xs, cmp = { a, b } -> a <= b }
            match list::head { xs = sorted } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        )
        assertEquals(1L, MezzeTestHelper.asLong(r))
    }

    @Test
    fun `list sort_by descending with a Bool comparator`() {
        val r = MezzeTestHelper.run(
            """
            use std::collections::list
            let xs = list::from_seq { xs = [3, 1, 4, 1, 5, 9, 2] }
            let sorted = list::sort_by { xs = xs, cmp = { a, b } -> a >= b }
            match list::head { xs = sorted } is
              Some { val } -> val
              None -> 0 - 1
            """.trimIndent()
        )
        assertEquals(9L, MezzeTestHelper.asLong(r))
    }

    @Test
    fun `is_sorted_by with a Bool comparator`() {
        val r = MezzeTestHelper.run(
            """
            use std::collections::list
            let xs = list::from_seq { xs = [1, 2, 3, 4] }
            if list::is_sorted_by { xs = xs, cmp = { a, b } -> a <= b } then 1 else 0
            """.trimIndent()
        )
        assertEquals(1L, MezzeTestHelper.asLong(r))
    }
}
