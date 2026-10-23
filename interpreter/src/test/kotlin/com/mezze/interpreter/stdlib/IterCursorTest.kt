package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * `iter::cursor` — the resumable pull view, and the two things it unlocks.
 *
 * The push walk owns its loop. Since the early-exit fix it can be told to
 * STOP, but it still cannot be RESUMED, and that blocked two things: yielding
 * an element downstream and coming back later, and zipping two sources without
 * materialising either. A cursor is the pull view that makes both expressible.
 *
 * Every endless test uses an `unfold` that never says Done, because a finite
 * source cannot distinguish "suspended correctly" from "ran to completion and
 * happened to be short". SEPARATE_THREAD timeouts because the failure mode of
 * a cursor bug is a hang, and the default thread mode cannot abort one.
 */
class IterCursorTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val head = """
        use std::collections::{ iter, seq }
        use std::int
    """.trimIndent()

    private val naturals =
        "iter::unfold { state = 0, step = { s } -> Yield { val = s, next = s + 1 } }"

    // ==================== the cursor itself ====================

    @Test
    fun `cursor pulls elements in order`() {
        val code = """
            $head
            do
              let c = iter::cursor { it = iter::range { start = 10, end = 13 } }
              let a = iter::cursor_next { c = c }
              let b = iter::cursor_next { c = c }
              let d = iter::cursor_next { c = c }
              match a is
                Some { val = x } -> match b is
                  Some { val = y } -> match d is
                    Some { val = z } -> "$(int::to_str { n = x })$(int::to_str { n = y })$(int::to_str { n = z })"
                    None -> "third-missing"
                  None -> "second-missing"
                None -> "first-missing"
        """.trimIndent()
        assertEquals("101112", eval(code))
    }

    /** Past the end it answers None, and keeps answering None. */
    @Test
    fun `cursor reports None at the end and stays there`() {
        val code = """
            $head
            use std::bool
            do
              let c = iter::cursor { it = iter::range { start = 0, end = 1 } }
              let _ = iter::cursor_next { c = c }
              let second = iter::cursor_next { c = c }
              let third = iter::cursor_next { c = c }
              let a = match second is
                None -> True
                Some { val = _ } -> False
              let b = match third is
                None -> True
                Some { val = _ } -> False
              "$(bool::to_str { b = a })$(bool::to_str { b = b })"
        """.trimIndent()
        assertEquals("TrueTrue", eval(code))
    }

    /**
     * A cursor is a HANDLE, not a value: two cursors over one `Iter` are
     * independent positions, and pulling from one does not move the other.
     */
    @Test
    fun `two cursors over one Iter are independent`() {
        val code = """
            $head
            do
              let it = iter::range { start = 0, end = 5 }
              let a = iter::cursor { it = it }
              let b = iter::cursor { it = it }
              let _ = iter::cursor_next { c = a }
              let _ = iter::cursor_next { c = a }
              match iter::cursor_next { c = a } is
                Some { val = x } -> match iter::cursor_next { c = b } is
                  Some { val = y } -> "$(int::to_str { n = x })-$(int::to_str { n = y })"
                  None -> "b-exhausted"
                None -> "a-exhausted"
        """.trimIndent()
        // a has advanced to 2; b is still at 0
        assertEquals("2-0", eval(code))
    }

    /** THE point: a cursor over an endless source returns, one element at a time. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `cursor pulls from an endless source without draining it`() {
        val code = """
            $head
            do
              let c = iter::cursor { it = $naturals }
              let _ = iter::cursor_next { c = c }
              let _ = iter::cursor_next { c = c }
              match iter::cursor_next { c = c } is
                Some { val = x } -> x
                None -> 0 - 1
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    /** Stages compose through a cursor, including over an endless source. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `cursor runs map and filter stages lazily`() {
        val code = """
            $head
            do
              let c = iter::cursor {
                it = iter::filter {
                  it = iter::map { it = $naturals, f = { x } -> x * 3 },
                  p = { x } -> x % 2 == 0
                }
              }
              let a = iter::cursor_next { c = c }
              let b = iter::cursor_next { c = c }
              match a is
                Some { val = x } -> match b is
                  Some { val = y } -> x + y
                  None -> 0 - 1
                None -> 0 - 1
        """.trimIndent()
        // multiples of 3 that are even: 0, 6
        assertEquals(6L, eval(code))
    }

    /** A cursor stage may PERFORM — the FS case the streaming work is for. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `cursor stage can perform FileSystem`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, read_text, write_text }
            use std::collections::bytes
            use std::str
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/p" }
              let _ = write_text { path = "/p/0", text = "aa" }
              let _ = write_text { path = "/p/1", text = "bbb" }
              let c = iter::cursor {
                it = iter::unfold {
                  state = 0,
                  step = { s } -> match read_text { path = "/p/$(int::to_str { n = s })" } is
                    Ok { val = t } -> Yield { val = str::size { s = t }, next = s + 1 }
                    Err { error = _ } -> Done
                }
              }
              let a = iter::cursor_next { c = c }
              let b = iter::cursor_next { c = c }
              match a is
                Some { val = x } -> match b is
                  Some { val = y } -> x + y
                  None -> 0 - 1
                None -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    // ==================== zip no longer materialises ====================

    /**
     * `zip { a = finite, b = endless }` is an ordinary thing to write, and it
     * used to exhaust memory: the push walk collected BOTH sides into lists
     * and indexed them. With a cursor per side it holds one pair.
     *
     * The endless side is what makes this a real test — a finite-by-finite zip
     * passed before the change too.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `zip with an endless right side stops at the shorter one`() {
        val code = """
            $head
            iter::fold {
              it = iter::zip { a = iter::range { start = 0, end = 3 }, b = $naturals },
              init = 0,
              f = { acc, x } -> acc + x.fst + x.snd,
            }
        """.trimIndent()
        // pairs (0,0) (1,1) (2,2)
        assertEquals(6L, eval(code))
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `zip with an endless left side stops at the shorter one`() {
        val code = """
            $head
            iter::count {
              it = iter::zip { a = $naturals, b = iter::range { start = 0, end = 4 } }
            }
        """.trimIndent()
        assertEquals(4L, eval(code))
    }

    /** Both sides endless, bounded by take — holds nothing. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `zip of two endless sources is bounded by take`() {
        val code = """
            $head
            iter::count { it = iter::take { it = iter::zip { a = $naturals, b = $naturals }, n = 6 } }
        """.trimIndent()
        assertEquals(6L, eval(code))
    }

    /** Finite zip still pairs correctly — the stop must not over-trigger. */
    @Test
    fun `zip of two finite sources is unchanged`() {
        val code = """
            $head
            iter::fold {
              it = iter::zip {
                a = iter::range { start = 1, end = 4 },
                b = iter::range { start = 10, end = 13 },
              },
              init = 0,
              f = { acc, x } -> acc + (x.fst * x.snd),
            }
        """.trimIndent()
        // 1*10 + 2*11 + 3*12
        assertEquals(68L, eval(code))
    }

    // ==================== map_concurrent_lazy ====================

    /** Results come back, in source order, with the window bounded. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent_lazy yields every result in source order`() {
        val code = """
            $head
            use std::effects::async::{ Async, LoomAsync, map_concurrent_lazy }
            perform LoomAsync in
              iter::fold {
                it = map_concurrent_lazy {
                  it = iter::range { start = 0, end = 6 },
                  f = { x } -> x,
                  max_in_flight = 3,
                },
                init = "",
                f = { acc, x } -> match x is
                  Ok { val = v } -> "$(acc)$(int::to_str { n = v })"
                  Err { error = _ } -> "$(acc)?",
              }
        """.trimIndent()
        assertEquals("012345", eval(code))
    }

    /**
     * THE property the eager form cannot have: `take` from the output and the
     * rest is never computed. Over an ENDLESS source, so a strict
     * implementation cannot finish at all.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent_lazy over an endless source is bounded by take`() {
        val code = """
            $head
            use std::effects::async::{ Async, LoomAsync, map_concurrent_lazy }
            perform LoomAsync in
              iter::fold {
                it = iter::take {
                  it = map_concurrent_lazy { it = $naturals, f = { x } -> x * 2, max_in_flight = 2 },
                  n = 4,
                },
                init = 0,
                f = { acc, x } -> match x is
                  Ok { val = v } -> acc + v
                  Err { error = _ } -> acc,
              }
        """.trimIndent()
        // (0+1+2+3) * 2
        assertEquals(12L, eval(code))
    }

    /**
     * LAZINESS, measured as work done rather than as the answer.
     *
     * This is the test the obvious one is not. `map_concurrent_lazy over an
     * endless source is bounded by take` asserts a SUM, and lazy and eager
     * produce the same sum by definition — that is what laziness means. It
     * passes with the window widened to 1,000,000: verified, 15/15 green,
     * because a million Loom spawns at ~14us still finish inside the timeout.
     * The result was never the evidence.
     *
     * So count the mapper's INVOCATIONS instead, one marker file each. Taking
     * 4 elements with a window of 2 should invoke it a handful of times, not a
     * million. The ceiling of 32 is deliberately loose — the window may be
     * topped up a step ahead — while still sitting four orders of magnitude
     * below what an eager implementation does.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent_lazy does bounded work for a bounded take`() {
        val code = """
            $head
            use std::effects::async::{ Async, LoomAsync, map_concurrent_lazy }
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text }
            use std::collections::bytes
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/seen" }
              let _ = iter::count {
                it = iter::take {
                  it = map_concurrent_lazy {
                    it = ${naturals},
                    f = { x } -> write_text { path = "/seen/$(int::to_str { n = x })", text = "1" },
                    max_in_flight = 2,
                  },
                  n = 4,
                }
              }
              match FileSystem.list { path = "/seen" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        val invocations = MezzeTestHelper.asLong(eval(code))
        assert(invocations in 4..32) {
            "mapper ran $invocations times for a take of 4 with a window of 2; " +
                "expected 4..32 (far more means the source was drained eagerly, " +
                "fewer than 4 means the take did not get its elements)"
        }
    }

    /** The mapper may perform, which is the whole purpose. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent_lazy mapper can perform FileSystem`() {
        val code = """
            $head
            use std::effects::async::{ Async, LoomAsync, map_concurrent_lazy }
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, read_text, write_text }
            use std::collections::bytes
            use std::str
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/in" }
              let _ = seq::for_each { xs = seq::range { start = 0, end = 5 }, f = { x } -> write_text { path = "/in/$(int::to_str { n = x })", text = "ab" } }
              iter::fold {
                it = map_concurrent_lazy {
                  it = iter::range { start = 0, end = 5 },
                  f = { x } -> match read_text { path = "/in/$(int::to_str { n = x })" } is
                    Ok { val = t } -> str::size { s = t }
                    Err { error = _ } -> 0
                  ,
                  max_in_flight = 2,
                },
                init = 0,
                f = { acc, x } -> match x is
                  Ok { val = v } -> acc + v
                  Err { error = _ } -> acc,
              }
        """.trimIndent()
        // five files of two characters
        assertEquals(10L, eval(code))
    }

    /** An empty source yields nothing rather than hanging on an empty window. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent_lazy on an empty source is empty`() {
        val code = """
            $head
            use std::effects::async::{ Async, LoomAsync, map_concurrent_lazy }
            perform LoomAsync in
              iter::count {
                it = map_concurrent_lazy {
                  it = iter::range { start = 0, end = 0 },
                  f = { x } -> x,
                  max_in_flight = 4,
                }
              }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    /** A source shorter than the window drains cleanly. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent_lazy drains a source smaller than the window`() {
        val code = """
            $head
            use std::effects::async::{ Async, LoomAsync, map_concurrent_lazy }
            perform LoomAsync in
              iter::count {
                it = map_concurrent_lazy {
                  it = iter::range { start = 0, end = 2 },
                  f = { x } -> x,
                  max_in_flight = 16,
                }
              }
        """.trimIndent()
        assertEquals(2L, eval(code))
    }
}
