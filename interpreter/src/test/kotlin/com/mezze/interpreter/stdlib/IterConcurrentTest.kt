package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * Bounded concurrency over a LAZY source: `for_each_concurrent` and
 * `map_concurrent`.
 *
 * What these buy over `par_map` is that the input is never materialised — the
 * source is walked once and only a window of `max_in_flight` promises is held.
 * That is the case `par_map` and `chunk_fold` cannot serve, because both need
 * the whole sequence up front.
 *
 * The two properties worth pinning are the ones a reader would doubt:
 *
 *  - BOUNDED really means bounded. A window that merely tended toward the
 *    limit would pass a result-shaped test while spawning without limit, so
 *    there is a test that counts concurrent occupancy directly, through
 *    MemoryFileSystem, and asserts the observed maximum.
 *  - ORDER is source order, not completion order. The test makes the FIRST
 *    element the slowest, so a completion-ordered implementation would put it
 *    last.
 *
 * Endless sources are bounded with `iter::take`, which only works because the
 * walk is stoppable — see IterEarlyExitTest. SEPARATE_THREAD timeouts for the
 * same reason as there: the failure mode of a concurrency bug here is a hang,
 * and the default thread mode cannot abort one.
 */
class IterConcurrentTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val head = """
        use std::collections::{ iter, seq }
        use std::effects::async::{ Async, LoomAsync, for_each_concurrent, map_concurrent, sleep }
        use std::effects::time::{ Time, SystemTime }
        use std::int
    """.trimIndent()

    // ==================== for_each_concurrent ====================

    /** Every element is visited exactly once. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `for_each_concurrent visits every element once`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text }
            use std::collections::bytes
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = for_each_concurrent {
                it = iter::range { start = 0, end = 20 },
                f = { x } -> write_text { path = "/d/f$(int::to_str { n = x })", text = "x" },
                max_in_flight = 4,
              }
              match FileSystem.list { path = "/d" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(20L, eval(code))
    }

    /**
     * THE bounded property, observed rather than inferred.
     *
     * Each task records its own arrival by appending a file, counts how many
     * are in flight by listing the directory, notes the maximum seen, then
     * removes its marker. If the window were unbounded — or merely "about"
     * the limit — the observed peak would exceed `max_in_flight`. The sleep
     * is what makes the overlap real; without it tasks would finish before
     * their neighbours start and the peak would read 1 whatever the window did.
     */
    /**
     * THE bounded property, observed rather than inferred.
     *
     * Each task marks itself live, counts how many are live by listing the
     * directory, and records that count by creating `/peak/<n>`. Afterwards
     * the set of filenames under `/peak` IS the set of occupancy levels that
     * were actually observed. The sleep is what makes the overlap real —
     * without it tasks finish before their neighbours start and every
     * observation reads 1 whatever the window does.
     *
     * Two assertions, and the second one matters as much as the first:
     *   - `/peak/5` must NOT exist: the window never exceeded 4.
     *   - `/peak/2` MUST exist: tasks really did overlap. Without this the
     *     test would pass just as happily against an implementation that ran
     *     everything sequentially, which is the vacuous pass this session has
     *     already produced twice.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `for_each_concurrent never exceeds max_in_flight`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text, exists }
            use std::collections::bytes
            use std::bool
            perform MemoryFileSystem in perform LoomAsync in perform SystemTime in do
              let _ = FileSystem.create_directory { path = "/live" }
              let _ = FileSystem.create_directory { path = "/peak" }
              let _ = for_each_concurrent {
                it = iter::range { start = 0, end = 24 },
                f = { x } -> do
                  let me = "/live/$(int::to_str { n = x })"
                  let _ = write_text { path = me, text = "1" }
                  let n = match FileSystem.list { path = "/live" } is
                    Ok { val = names } -> seq::size { xs = names }
                    Err { error = _ } -> 0 - 1
                  let _ = write_text { path = "/peak/$(int::to_str { n = n })", text = "1" }
                  let _ = sleep { ms = 5 }
                  FileSystem.delete { path = me }
                ,
                max_in_flight = 4,
              }
              ## The MAXIMUM observed occupancy, not a probe for one value.
              ## Checking `/peak/5` alone was a false negative: with the bound
              ## removed the counts observed were 1, 9, 17, ... and never
              ## exactly 5, so the test reported "within bounds" about a window
              ## holding 24 tasks. Take the max of the names instead.
              match FileSystem.list { path = "/peak" } is
                Ok { val = names } -> seq::fold {
                  xs = names,
                  init = 0,
                  f = { acc, x } -> match int::from_str { s = x } is
                    Some { val = n } -> int::max { l = acc, r = n }
                    None -> acc,
                }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        val peak = MezzeTestHelper.asLong(eval(code))
        // Bounded at 4, and at least 2 so the test is not passing against a
        // sequential implementation.
        assert(peak in 2..4) {
            "observed peak in-flight was $peak; expected 2..4 (>4 means the window leaked, " +
                "<2 means nothing actually overlapped and the test proves nothing)"
        }
    }

    /** It composes with `take` over an endless source — the whole point. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `for_each_concurrent bounded by take over an endless source`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text }
            use std::collections::bytes
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = for_each_concurrent {
                it = iter::take {
                  it = iter::unfold { state = 0, step = { s } -> Yield { val = s, next = s + 1 } },
                  n = 9,
                },
                f = { x } -> write_text { path = "/d/f$(int::to_str { n = x })", text = "x" },
                max_in_flight = 3,
              }
              match FileSystem.list { path = "/d" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(9L, eval(code))
    }

    /** A source shorter than the window still drains. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `for_each_concurrent drains a source smaller than the window`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text }
            use std::collections::bytes
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = for_each_concurrent {
                it = iter::range { start = 0, end = 2 },
                f = { x } -> write_text { path = "/d/f$(int::to_str { n = x })", text = "x" },
                max_in_flight = 16,
              }
              match FileSystem.list { path = "/d" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    /** An empty source does nothing and does not fail. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `for_each_concurrent on an empty source is a no-op`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text }
            use std::collections::bytes
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = for_each_concurrent {
                it = iter::range { start = 0, end = 0 },
                f = { x } -> write_text { path = "/d/f$(int::to_str { n = x })", text = "x" },
                max_in_flight = 4,
              }
              match FileSystem.list { path = "/d" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    // ==================== map_concurrent ====================

    /** Every result comes back, and the count matches the source. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent returns one result per element`() {
        val code = """
            $head
            perform LoomAsync in
              seq::size { xs = map_concurrent {
                it = iter::range { start = 0, end = 20 },
                f = { x } -> x * x,
                max_in_flight = 4,
              } }
        """.trimIndent()
        assertEquals(20L, eval(code))
    }

    /** The values are right, and summing them unwraps each Result. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent computes every element`() {
        val code = """
            $head
            perform LoomAsync in
              seq::fold {
                xs = map_concurrent {
                  it = iter::range { start = 1, end = 6 },
                  f = { x } -> x * x,
                  max_in_flight = 2,
                },
                init = 0,
                f = { acc, x } -> match x is
                  Ok { val = v } -> acc + v
                  Err { error = _ } -> acc,
              }
        """.trimIndent()
        // 1+4+9+16+25
        assertEquals(55L, eval(code))
    }

    /**
     * ORDER is source order, not completion order. The first element sleeps
     * longest, so a completion-ordered implementation would move it to the
     * back. Asserting the exact sequence catches that.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent preserves source order despite uneven durations`() {
        val code = """
            $head
            perform LoomAsync in perform SystemTime in
              seq::fold {
                xs = map_concurrent {
                  it = iter::range { start = 0, end = 5 },
                  f = { x } -> do
                    let _ = sleep { ms = (5 - x) * 10 }
                    x
                  ,
                  max_in_flight = 5,
                },
                init = "",
                f = { acc, x } -> match x is
                  Ok { val = v } -> "$(acc)$(int::to_str { n = v })"
                  Err { error = _ } -> "$(acc)?",
              }
        """.trimIndent()
        assertEquals("01234", eval(code))
    }

    /** Bounded by take over an endless source, same as for_each. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent bounded by take over an endless source`() {
        val code = """
            $head
            perform LoomAsync in
              seq::fold {
                xs = map_concurrent {
                  it = iter::take {
                    it = iter::unfold { state = 1, step = { s } -> Yield { val = s, next = s + 1 } },
                    n = 4,
                  },
                  f = { x } -> x * 10,
                  max_in_flight = 2,
                },
                init = 0,
                f = { acc, x } -> match x is
                  Ok { val = v } -> acc + v
                  Err { error = _ } -> acc,
              }
        """.trimIndent()
        // (1+2+3+4) * 10
        assertEquals(100L, eval(code))
    }

    /** A window larger than the source is fine. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent with a window larger than the source`() {
        val code = """
            $head
            perform LoomAsync in
              seq::size { xs = map_concurrent {
                it = iter::range { start = 0, end = 3 },
                f = { x } -> x,
                max_in_flight = 64,
              } }
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    /** An empty source gives an empty result. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent on an empty source is empty`() {
        val code = """
            $head
            perform LoomAsync in
              seq::size { xs = map_concurrent {
                it = iter::range { start = 0, end = 0 },
                f = { x } -> x,
                max_in_flight = 4,
              } }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    /** The mapper may perform an effect — it is the FS case this exists for. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `map_concurrent mapper can perform FileSystem`() {
        val code = """
            $head
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, read_text, write_text }
            use std::collections::bytes
            use std::str
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = FileSystem.create_directory { path = "/in" }
              let _ = seq::for_each { xs = seq::range { start = 0, end = 6 }, f = { x } -> write_text { path = "/in/$(int::to_str { n = x })", text = "ab" } }
              seq::fold {
                xs = map_concurrent {
                  it = iter::range { start = 0, end = 6 },
                  f = { x } -> match read_text { path = "/in/$(int::to_str { n = x })" } is
                    Ok { val = t } -> str::size { s = t }
                    Err { error = _ } -> 0
                  ,
                  max_in_flight = 3,
                },
                init = 0,
                f = { acc, x } -> match x is
                  Ok { val = v } -> acc + v
                  Err { error = _ } -> acc,
              }
        """.trimIndent()
        // six files of two characters
        assertEquals(12L, eval(code))
    }
}
