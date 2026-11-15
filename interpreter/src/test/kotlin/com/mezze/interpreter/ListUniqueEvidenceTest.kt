package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `list::unique` honours a hand-written `Eq`/`Hash`.
 *
 * WHAT WAS WRONG. It carried NO constraint at all — not `Eq`, not `Hash` — and
 * deduped structurally through a `LinkedHashSet` with no evidence route. So a
 * `newtype` whose `Eq` calls two differently-represented values equal kept
 * BOTH of them, because structural bucketing never put them in one bucket to
 * compare. `seq::unique` closed that hole this session; `list::unique` and
 * `seq::unique` therefore disagreed about what a duplicate is, under the same
 * name.
 *
 * NO SPEED IS CLAIMED HERE. It was already O(n) via `LinkedHashSet`. The change
 * buys the right answer and agreement between the two.
 *
 * `distinct` now shares the same native: `__native_list_distinct` existed only
 * to delegate to `unique`, which is a second name for one operation.
 */
class ListUniqueEvidenceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elemsOf(source: String): List<Any?> = (eval(source) as Iterable<*>).toList()

    private val prelude = """
        use std::collections::list
        use std::collections::seq
    """

    @Test
    fun `unique keeps the first occurrence in order`() = assertEquals(
        listOf(3L, 1L, 2L, 4L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::unique { xs = list::from_seq { xs = [3, 1, 3, 2, 1, 4, 2] } } }
            """,
        ),
    )

    @Test
    fun `distinct agrees with unique`() = assertEquals(
        listOf(3L, 1L, 2L, 4L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::distinct { xs = list::from_seq { xs = [3, 1, 3, 2, 1, 4, 2] } } }
            """,
        ),
    )

    @Test
    fun `an already-unique list is unchanged`() = assertEquals(
        listOf(5L, 4L, 3L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::unique { xs = list::from_seq { xs = [5, 4, 3] } } }
            """,
        ),
    )

    @Test
    fun `empty and single-element lists`() {
        assertEquals(0L, eval("$prelude\nlist::size { xs = list::unique { xs = list::empty {} } }"))
        assertEquals(
            listOf(9L),
            elemsOf("$prelude\nlist::to_seq { xs = list::unique { xs = list::from_seq { xs = [9] } } }"),
        )
    }

    /**
     * THE CASE THE CONSTRAINT EXISTS FOR, and the one that failed before it.
     *
     * `P` is a newtype over `{ x, y }` whose `Eq` and `Hash` read `x` ALONE, so
     * two points sharing an x are duplicates however their y differs — and are
     * NOT duplicates structurally. Four points, two distinct x values: two
     * elements under the author's `Eq`, four without it.
     */
    private val customInstance = """
        use std::collections::list
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype P = { x: Int, y: Int }
        let px: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> x
        impl Hash for P where {
          hash = { it } -> px { p = it }
        }
        impl Eq for P where {
          eq = { it, r } -> px { p = it } == px { p = r },
          neq = { it, r } -> if px { p = it } == px { p = r } then False else True,
        }
        let p: { x: Int, y: Int } -> P = { x, y } -> P.({ x = x, y = y })
    """

    @Test
    fun `a hand-written Eq and Hash decide what is a duplicate`() = assertEquals(
        2L,
        eval(
            """
            $customInstance
            list::size { xs = list::unique { xs = list::from_seq { xs = [
              p { x = 1, y = 100 },
              p { x = 2, y = 200 },
              p { x = 1, y = 999 },
              p { x = 2, y = 888 },
            ] } } }
            """,
        ),
        "the author's Eq compares on x alone, so this is two elements — four is" +
            " the old behaviour, where the instance was ignored entirely",
    )

    /** `distinct` shares the native, so it must honour the instance too — a
     *  separate test because they were separate natives until now. */
    @Test
    fun `distinct honours the hand-written instance as well`() = assertEquals(
        2L,
        eval(
            """
            $customInstance
            list::size { xs = list::distinct { xs = list::from_seq { xs = [
              p { x = 1, y = 100 },
              p { x = 2, y = 200 },
              p { x = 1, y = 999 },
              p { x = 2, y = 888 },
            ] } } }
            """,
        ),
    )

    /**
     * `seq::unique` and `list::unique` must now give the SAME count for the
     * same elements. They did not before: seq honoured the instance and list
     * ignored it, under the same name.
     */
    @Test
    fun `seq and list agree about what a duplicate is`() {
        val both = eval(
            """
            $customInstance
            do
              let xs = [p { x = 1, y = 100 }, p { x = 2, y = 200 },
                        p { x = 1, y = 999 }, p { x = 2, y = 888 }]
              { s = seq::size { xs = seq::unique { xs = xs } },
                l = list::size { xs = list::unique { xs = list::from_seq { xs = xs } } } }
            """,
        ) as com.mezze.interpreter.values.MezzeRecord
        assertEquals(both.getField("s"), both.getField("l"),
            "seq::unique and list::unique disagree about duplicates")
        assertEquals(2L, both.getField("s"))
    }
}
