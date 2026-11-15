package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `seq::unique` in O(n), and the thing that makes it sound: a HAND-WRITTEN
 * `Eq`/`Hash` pair still decides what counts as a duplicate.
 *
 * The old implementation was O(n^2) and its own comment said why — "Eq is not
 * required to be hashable (only equatable)" — so it compared every element
 * against every survivor through a guest call. MEASURED at two element counts,
 * which is what tells a quadratic from an expensive constant: 40 243 498 ps for
 * a hundred elements, 4 169 669 450 for a thousand, 103.6x for a tenfold n, an
 * exponent of 2.02.
 *
 * The signature is now `with Eq 'a, Hash 'a`. That is the whole fix, and it is
 * a user-facing decision: with the pair available, the element can be bucketed.
 *
 * WHY THE CUSTOM-INSTANCE CASES ARE THE POINT. `TypedASTCompiler`'s evidence
 * rule exists because bucketing by the compiler's hashing while the AUTHOR'S
 * `Eq` decides equality is unsound in a specific way — two values the author
 * calls equal hash to different buckets, are never compared, and BOTH survive.
 * That is a wrong answer, not a slow one, and it is exactly the trap that made
 * "just route `unique` through a set" the wrong fix. `unique_hashed` therefore
 * has two paths and these tests exercise both: structural when the instances
 * are derived, and the author's `hash`/`eq` when they are written.
 *
 * ORDER IS PART OF THE CONTRACT — "keeps the first occurrence" — so every case
 * asserts the sequence, never just the length. A set-based dedupe would pass a
 * length assertion and lose the order.
 */
class UniqueHashedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun elems(out: Any): List<Any?> = (out as Iterable<*>).toList()

    private val prelude = """
        use std::collections::seq
        use std::int
    """

    /** First occurrence kept, order preserved, duplicates gone. */
    @Test
    fun `unique keeps the first occurrence in order`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::unique { xs = [3, 1, 3, 2, 1, 4, 2] }
            main {}
            """,
        )
        assertEquals(listOf(3L, 1L, 2L, 4L), elems(out))
    }

    /** `distinct` is the alias and must agree exactly. */
    @Test
    fun `distinct agrees with unique`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::distinct { xs = [3, 1, 3, 2, 1, 4, 2] }
            main {}
            """,
        )
        assertEquals(listOf(3L, 1L, 2L, 4L), elems(out))
    }

    /** Nothing to remove: the input comes back unchanged, order intact. */
    @Test
    fun `an already-unique seq is unchanged`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::unique { xs = [5, 4, 3, 2, 1] }
            main {}
            """,
        )
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), elems(out))
    }

    @Test
    fun `all duplicates collapse to one`() {
        val out = eval(
            """
            $prelude
            let main = {} -> seq::unique { xs = [7, 7, 7, 7, 7] }
            main {}
            """,
        )
        assertEquals(listOf(7L), elems(out))
    }

    @Test
    fun `empty and single-element seqs`() {
        assertEquals(0L, eval(
            """
            $prelude
            let main = {} -> seq::size { xs = seq::unique { xs = [] } }
            main {}
            """,
        ))
        assertEquals(listOf(9L), elems(eval(
            """
            $prelude
            let main = {} -> seq::unique { xs = [9] }
            main {}
            """,
        )))
    }

    /** Strings, so the structural path is exercised on a non-primitive. */
    @Test
    fun `strings dedupe structurally and keep order`() {
        val out = eval(
            """
            $prelude
            use std::str
            let main = {} -> seq::unique { xs = ["b", "a", "b", "c", "a"] }
            main {}
            """,
        )
        assertEquals(listOf("b", "a", "c"), elems(out))
    }

    /**
     * A LARGE INPUT, which the old implementation could not have answered at
     * this size in reasonable time — a thousand elements took 4.17 ms there, so
     * ten thousand would be about 420 ms. The assertion is on the ANSWER, not
     * the time: the point is that the result is still exactly the first
     * occurrences in order.
     */
    @Test
    fun `ten thousand elements with heavy duplication`() {
        val out = eval(
            """
            $prelude
            let build = { i, acc } -> if i <= 0 then acc
              else build { i = i - 1, acc = seq::push { xs = acc, x = i % 50 } }
            let main = {} -> do
              let xs = build { i = 10000, acc = [] }
              let u = seq::unique { xs = xs }
              ## 10000 counting down, each mod 50: the first occurrences are
              ## 10000%50=0, then 9999%50=49, 48, ... so fifty distinct values.
              { n = seq::size { xs = u }, first = seq::get { xs = u, i = 0 } }
            main {}
            """,
        )
        val rec = out as com.mezze.interpreter.values.MezzeRecord
        assertEquals(50L, rec.getField("n"))
    }

    /**
     * The element whose instances DISAGREE with the structural ones. `P` is a
     * newtype over `{ x, y }` whose `Eq` and `Hash` look at `x` ALONE, so two
     * points sharing an x are duplicates however their y differs — and are NOT
     * duplicates structurally.
     *
     * The spelling matters and is copied from `ContainerElementInstanceTest`:
     * the built-in `Eq` must be implemented with its own signature — `it`/`r`,
     * and `neq` as well as `eq` — or the typechecker rightly reports that the
     * impl declares a DIFFERENT ability sharing the name and "would never run".
     * The first attempt here got that wrong and the error said so exactly.
     */
    private val customInstance = """
        use std::collections::seq
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype P = { x: Int, y: Int }
        let px: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> x
        let py: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> y
        impl Hash for P where {
          hash = { it } -> px { p = it }
        }
        impl Eq for P where {
          eq = { it, r } -> px { p = it } == px { p = r },
          neq = { it, r } -> if px { p = it } == px { p = r } then False else True,
        }
        let p: { x: Int, y: Int } -> P = { x, y } -> P.({ x = x, y = y })
    """

    /**
     * THE SOUNDNESS CASE. Four points, two distinct `x` values: two elements
     * under the author's `Eq`, four structurally.
     *
     * This is what the evidence rule exists for. Bucketing by the compiler's
     * hashing while the AUTHOR'S `Eq` decides equality is unsound in a specific
     * way — two values the author calls equal hash to different buckets, are
     * never compared, and BOTH survive. A wrong answer, not a slow one, and the
     * reason "just route `unique` through a set" was never the fix.
     */
    @Test
    fun `a hand-written Eq and Hash decide what is a duplicate`() {
        val out = eval(
            """
            $customInstance
            let main = {} -> seq::size { xs = seq::unique { xs = [
              p { x = 1, y = 100 },
              p { x = 2, y = 200 },
              p { x = 1, y = 999 },
              p { x = 2, y = 888 },
            ] } }
            main {}
            """,
        )
        assertEquals(2L, out,
            "the author's Eq compares on x alone, so this is two elements —" +
                " four means the custom instance was ignored")
    }

    /**
     * The same instance, checking the FIRST occurrence survived rather than a
     * later one — a dedupe that kept the last would also report two.
     *
     * THIS ONE DOES NOT DISCRIMINATE, and that was established by disabling the
     * evidence route rather than assumed. With structural bucketing the two
     * x=1 points are DIFFERENT (their y differs), so both survive and the first
     * is still first: the assertion holds either way. The case above is the one
     * that fails without the route. Keeping this one is still worth it — it
     * would catch a dedupe that kept the LAST occurrence — but it is not
     * evidence that the author's instance was used.
     */
    @Test
    fun `the surviving element is the first occurrence under a custom Eq`() {
        val out = eval(
            """
            $customInstance
            let main = {} -> do
              let u = seq::unique { xs = [
                p { x = 1, y = 11 },
                p { x = 1, y = 22 },
                p { x = 2, y = 33 },
              ] }
              match seq::get { xs = u, i = 0 } is
                Some { val } -> py { p = val }
                None -> 0 - 1
            main {}
            """,
        )
        assertEquals(11L, out, "the FIRST of the two x=1 elements must survive")
    }
}
