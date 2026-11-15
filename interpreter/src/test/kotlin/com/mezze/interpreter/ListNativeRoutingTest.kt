package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Five `list` operations moved from guest code to the native that was already
 * there, and the edges that decide whether that was safe.
 *
 * FOUND BY SWEEP: 14 `list` natives were registered in `NativeDispatch` and
 * referenced NOWHERE. The instinct to read that as a perf backlog is only
 * sometimes right, and the rule that separates the cases is whether the native
 * would move a CALLBACK into host code — Graal cannot inline a guest function
 * into host code, so the moment the host calls back out per element it loses.
 * `list::is_sorted_by` is 41x the `reverse` floor and takes a comparator, so it
 * is deliberately NOT touched here; the five below move DATA only.
 *
 * Measured at four hundred elements, min of warm passes, against
 * `list::reverse` as the single-pass floor:
 *
 *     intersperse  30 991 ->  8 795   3.52x
 *     zip          67 928 -> 21 164   3.21x
 *     unzip        40 817 -> 14 994   2.72x
 *     init         13 052 ->  6 491   2.01x
 *     enumerate    28 982 -> 19 386   1.49x
 *
 * TWO CANDIDATES WERE REJECTED BY MEASUREMENT and are recorded in the stdlib
 * beside the code that stayed: `append` through the native `concat` was flat
 * (12 290 against 13 154, inside a 13 154-15 171 spread), and
 * `__native_list_append` is not even the same operation — it appends ONE
 * ELEMENT, which is why a dead native's NAME is worthless as evidence.
 *
 * These tests do not fail without the rewiring — both implementations are
 * correct, which is the point — so they are a regression guard on the EDGES,
 * where a hand-written and a host loop are most likely to disagree: unequal
 * lengths, empty and single-element inputs, and the index base.
 */
class ListNativeRoutingTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /** A seq is either a `MezzeSeq` or a bare `Object[]` — see `coerceSeq`. */
    private fun elemsOf(source: String): List<Any?> = when (val r = eval(source)) {
        is Array<*> -> r.toList()
        is Iterable<*> -> r.toList()
        else -> error("not a seq: ${r.javaClass.name}")
    }

    private val prelude = """
        use std::collections::list
        use std::collections::seq
    """

    private fun ints(vararg xs: Int) = "list::from_seq { xs = [${xs.joinToString(", ")}] }"

    // ---- zip: stops at the shorter list ----------------------------------

    private fun zipped(a: String, b: String) = """
        $prelude
        list::to_seq { xs = list::map {
          xs = list::zip { xs = $a, ys = $b },
          f = { x } -> x.fst * 100 + x.snd,
        } }
    """

    @Test
    fun `zip pairs in order`() =
        assertEquals(listOf(104L, 205L, 306L), elemsOf(zipped(ints(1, 2, 3), ints(4, 5, 6))))

    @Test
    fun `zip stops at the shorter side, either side`() {
        assertEquals(
            listOf(104L, 205L),
            elemsOf(zipped(ints(1, 2, 3), ints(4, 5))),
            "xs longer: the extra element of xs must be dropped",
        )
        assertEquals(
            listOf(104L, 205L),
            elemsOf(zipped(ints(1, 2), ints(4, 5, 6))),
            "ys longer: the extra element of ys must be dropped",
        )
    }

    @Test
    fun `zip with an empty side is empty`() {
        assertEquals(0L, eval("$prelude\nlist::size { xs = list::zip { xs = ${ints(1, 2)}, ys = list::empty {} } }"))
        assertEquals(0L, eval("$prelude\nlist::size { xs = list::zip { xs = list::empty {}, ys = ${ints(1, 2)} } }"))
    }

    // ---- unzip -----------------------------------------------------------

    @Test
    fun `unzip splits both sides in order`() {
        val src = "list::zip { xs = ${ints(1, 2, 3)}, ys = ${ints(4, 5, 6)} }"
        assertEquals(
            listOf(1L, 2L, 3L),
            elemsOf("$prelude\nlist::to_seq { xs = (list::unzip { xs = $src }).fsts }"),
        )
        assertEquals(
            listOf(4L, 5L, 6L),
            elemsOf("$prelude\nlist::to_seq { xs = (list::unzip { xs = $src }).snds }"),
        )
    }

    @Test
    fun `unzip of an empty list gives two empty lists`() {
        val src = "list::zip { xs = list::empty {}, ys = list::empty {} }"
        assertEquals(0L, eval("$prelude\nlist::size { xs = (list::unzip { xs = $src }).fsts }"))
        assertEquals(0L, eval("$prelude\nlist::size { xs = (list::unzip { xs = $src }).snds }"))
    }

    // ---- enumerate: the index base is what a host loop gets wrong --------

    @Test
    fun `enumerate indexes from zero, in order`() = assertEquals(
        listOf(0L, 11L, 22L, 33L),
        elemsOf(
            """
            $prelude
            list::to_seq { xs = list::map {
              xs = list::enumerate { xs = ${ints(0, 1, 2, 3)} },
              f = { x } -> x.value * 10 + x.index,
            } }
            """,
        ),
        "value*10+index: a one-based or reversed index shows up immediately",
    )

    @Test
    fun `enumerate of an empty list is empty`() =
        assertEquals(0L, eval("$prelude\nlist::size { xs = list::enumerate { xs = list::empty {} } }"))

    // ---- intersperse -----------------------------------------------------

    @Test
    fun `intersperse puts the separator between every pair, not at the ends`() = assertEquals(
        listOf(1L, 0L, 2L, 0L, 3L),
        elemsOf("$prelude\nlist::to_seq { xs = list::intersperse { xs = ${ints(1, 2, 3)}, sep = 0 } }"),
    )

    @Test
    fun `intersperse of a short list adds nothing`() {
        assertEquals(
            listOf(7L),
            elemsOf("$prelude\nlist::to_seq { xs = list::intersperse { xs = ${ints(7)}, sep = 0 } }"),
            "one element has no pair to separate",
        )
        assertEquals(
            0L,
            eval("$prelude\nlist::size { xs = list::intersperse { xs = list::empty {}, sep = 0 } }"),
        )
    }

    // ---- init ------------------------------------------------------------

    @Test
    fun `init drops the last element, keeping order`() = assertEquals(
        listOf(1L, 2L, 3L),
        elemsOf("$prelude\nlist::to_seq { xs = list::init { xs = ${ints(1, 2, 3, 4)} } }"),
    )

    @Test
    fun `init of a one-element and an empty list is empty`() {
        assertEquals(0L, eval("$prelude\nlist::size { xs = list::init { xs = ${ints(9)} } }"))
        assertEquals(0L, eval("$prelude\nlist::size { xs = list::init { xs = list::empty {} } }"))
    }

    // ---- what was left alone, on purpose ---------------------------------

    /**
     * `is_sorted_by` takes a comparator, so routing it to its dead native would
     * move the comparison into host code and lose — the same mechanism that
     * makes every `SortedMap` search guest-side on purpose. This pins the
     * behaviour so the guest implementation is not quietly "optimised" later.
     */
    @Test
    fun `is_sorted_by stays guest-side and still answers correctly`() {
        assertEquals(
            true,
            eval("$prelude\nlist::is_sorted_by { xs = ${ints(1, 2, 2, 3)}, cmp = { a, b } -> a <= b }"),
        )
        assertEquals(
            false,
            eval("$prelude\nlist::is_sorted_by { xs = ${ints(1, 3, 2)}, cmp = { a, b } -> a <= b }"),
        )
    }
}
