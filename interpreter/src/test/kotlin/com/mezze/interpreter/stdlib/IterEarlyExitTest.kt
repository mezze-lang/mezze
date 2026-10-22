package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * The `Iter` walk has to be STOPPABLE.
 *
 * `ElementConsumer.accept` returned void, so `take`, `find`, `any`, `all`,
 * `take_while` and `first` could only stop ACCEPTING — the source was walked
 * to the end regardless. Two consequences, one silent and one fatal:
 *
 *  - On a finite source it is O(n) where O(k) was asked for. `find` on a
 *    10M-element range scanned all 10M after matching element 0.
 *  - On an ENDLESS source it never returns. Measured before the fix:
 *    `iter::take { n = 5 }` over an endless unfold hung for 90 seconds and
 *    was still going. `unfold` is documented for exactly those sources —
 *    "trees, file handles, database cursors, paginated APIs".
 *
 * Every test here uses an endless `unfold`, because that is the only input
 * that can tell "stopped" from "finished". A finite source passes whether or
 * not the walk stops, which is why the 7,004-test suite was green before the
 * fix and is no evidence for it.
 *
 * `@Timeout` with `ThreadMode.SEPARATE_THREAD`, and the thread mode is the
 * whole point. The DEFAULT mode runs the test on the calling thread and only
 * compares elapsed time once it returns — so against a driver that cannot
 * stop, the first test here hangs forever and takes the task with it rather
 * than failing. Measured: reverting the driver and running this class was
 * killed twice, at 10 and at 25 minutes, with zero test results. A separate
 * thread can be aborted, so a future regression FAILS instead of wedging CI.
 *
 * (That hang is also the strongest evidence these tests bite: against the old
 * driver they do not pass slowly, they never finish.)
 */
class IterEarlyExitTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    /** An unfold that never says Done. */
    private val endless = """
        use std::collections::iter
        use std::int
    """.trimIndent()

    private val naturals =
        "iter::unfold { state = 0, step = { s } -> Yield { val = s, next = s + 1 } }"

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take bounds an endless source`() {
        val code = """
            $endless
            iter::count { it = iter::take { it = $naturals, n = 5 } }
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take over an endless source yields the right elements`() {
        val code = """
            $endless
            iter::sum { it = iter::take { it = $naturals, n = 5 } }
        """.trimIndent()
        // 0+1+2+3+4
        assertEquals(10L, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `first returns from an endless source`() {
        val code = """
            $endless
            match iter::first { it = $naturals } is
              Some { val = v } -> v
              None -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `any short-circuits out of an endless source`() {
        val code = """
            $endless
            iter::any { it = $naturals, p = { x } -> x == 3 }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `all short-circuits out of an endless source`() {
        val code = """
            $endless
            iter::all { it = $naturals, p = { x } -> x < 3 }
        """.trimIndent()
        assertEquals(false, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `find short-circuits out of an endless source`() {
        val code = """
            $endless
            match iter::find { it = $naturals, p = { x } -> x == 7 } is
              Some { val = v } -> v
              None -> 0 - 1
        """.trimIndent()
        assertEquals(7L, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take_while bounds an endless source`() {
        val code = """
            $endless
            iter::count { it = iter::take_while { it = $naturals, p = { x } -> x < 4 } }
        """.trimIndent()
        assertEquals(4L, eval(code))
    }

    /** A stop has to cross a stage, not just sit next to the source. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take bounds an endless source through map and filter`() {
        val code = """
            $endless
            iter::sum {
              it = iter::take {
                it = iter::filter {
                  it = iter::map { it = $naturals, f = { x } -> x * 2 },
                  p = { x } -> x % 4 == 0
                },
                n = 3,
              }
            }
        """.trimIndent()
        // doubled: 0 2 4 6 8 10 ... ; kept (x%4==0): 0 4 8 ... ; first three sum to 12
        assertEquals(12L, eval(code))
    }

    /** And out of an inner iterator, through flat_map. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take bounds an endless source through flat_map`() {
        val code = """
            $endless
            iter::count {
              it = iter::take {
                it = iter::flat_map { it = $naturals, f = { x } -> iter::range { start = 0, end = 3 } },
                n = 7,
              }
            }
        """.trimIndent()
        assertEquals(7L, eval(code))
    }

    /** `drop` in front of an endless source still has to be bounded by take. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take bounds an endless source after drop`() {
        val code = """
            $endless
            iter::sum { it = iter::take { it = iter::drop { it = $naturals, n = 10 }, n = 3 } }
        """.trimIndent()
        // 10+11+12
        assertEquals(33L, eval(code))
    }

    /** Chaining a finite head onto an endless tail, bounded. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take bounds a chain whose tail is endless`() {
        val code = """
            $endless
            iter::count {
              it = iter::take {
                it = iter::chain { a = iter::range { start = 0, end = 2 }, b = $naturals },
                n = 5,
              }
            }
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    /** A stop in the first half of a chain must not start the second. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a stop in a chain's first half does not reach the second`() {
        val code = """
            $endless
            iter::sum {
              it = iter::take {
                it = iter::chain { a = iter::range { start = 0, end = 100 }, b = $naturals },
                n = 3,
              }
            }
        """.trimIndent()
        // 0+1+2 from the first side only
        assertEquals(3L, eval(code))
    }

    /** `enumerate` keeps its index while forwarding a stop. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take bounds an endless source through enumerate`() {
        val code = """
            $endless
            iter::fold {
              it = iter::take { it = iter::enumerate { it = $naturals }, n = 3 },
              init = 0,
              f = { acc, x } -> acc + x.i,
            }
        """.trimIndent()
        // indices 0+1+2
        assertEquals(3L, eval(code))
    }

    /** Finite sources must still produce every element — the stop must not over-trigger. */
    @Test
    fun `a finite source is still walked in full`() {
        val code = """
            $endless
            iter::sum { it = iter::range { start = 0, end = 100 } }
        """.trimIndent()
        assertEquals(4950L, eval(code))
    }

    /** `take` for more than a finite source holds gives all of it, not an error. */
    @Test
    fun `take beyond a finite source gives the whole source`() {
        val code = """
            $endless
            iter::count { it = iter::take { it = iter::range { start = 0, end = 4 }, n = 100 } }
        """.trimIndent()
        assertEquals(4L, eval(code))
    }

    /** `take { n = 0 }` must not pull from the source at all. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `take zero from an endless source is empty`() {
        val code = """
            $endless
            iter::count { it = iter::take { it = $naturals, n = 0 } }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }
}
