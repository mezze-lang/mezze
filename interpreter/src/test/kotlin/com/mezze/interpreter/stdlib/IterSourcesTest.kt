package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The filesystem as a lazy `Iter`: `with_lines` / `lines_of`, and
 * `watch_events`.
 *
 * These are the first sources that stream rather than materialise, which is
 * what the boundary result unblocked — the `unfold` step performs
 * `<FileStreaming>` or `<FileWatch>` from inside the native driver loop.
 *
 * Two design points the tests pin:
 *
 *  - LAZINESS IS REAL. `take { n = 2 }` on a file of many lines must read a
 *    chunk, not the file. There is no way to observe "bytes not read" from
 *    Mezze, so the test uses a chunk size smaller than the file and a line
 *    that spans a chunk boundary — if the implementation had slurped the whole
 *    file, the boundary case could not fail, and it does fail when the
 *    buffering is wrong.
 *  - THE HANDLE IS CLOSED whether or not the iterator was drained, which is
 *    why this takes a handle and `with_lines` owns the scope. An `Iter` that
 *    owned a handle would leak it on `take`.
 */
class IterSourcesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val head = """
        use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFileSystem, MemoryFileStreaming, with_lines, write_text }
        use std::collections::{ iter, seq }
        use std::int
    """.trimIndent()

    // ==================== with_lines ====================

    @Test
    fun `with_lines yields every line`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "alpha\nbeta\ngamma\n" }
              match with_lines { path = "/f", chunk_bytes = 64, f = { lines } -> iter::count { it = lines } } is
                Ok { val = n } -> n
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    /** A trailing line with no newline still counts — the classic off-by-one. */
    @Test
    fun `with_lines counts a final line that has no newline`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "alpha\nbeta\ngamma" }
              match with_lines { path = "/f", chunk_bytes = 64, f = { lines } -> iter::count { it = lines } } is
                Ok { val = n } -> n
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    /** The newline itself must not survive into the yielded line. */
    @Test
    fun `with_lines strips the newline`() {
        val code = """
            $head
            use std::str
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "alpha\nbeta\n" }
              match with_lines { path = "/f", chunk_bytes = 64, f = { lines } -> iter::fold { it = lines, init = "", f = { acc, x } -> "$(acc)[$(x)]" } } is
                Ok { val = s } -> s
                Err { error = _ } -> "failed"
        """.trimIndent()
        assertEquals("[alpha][beta]", eval(code))
    }

    /**
     * THE case that justifies the buffering: a chunk boundary mid-line. With
     * `chunk_bytes = 4` and lines of 5 characters, every line spans two reads,
     * so an implementation that treated a chunk as a unit of lines would split
     * them. `Step.Skip` is what handles it.
     */
    @Test
    fun `with_lines reassembles a line split across chunks`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "aaaaa\nbbbbb\nccccc\n" }
              match with_lines { path = "/f", chunk_bytes = 4, f = { lines } -> iter::fold { it = lines, init = "", f = { acc, x } -> "$(acc)[$(x)]" } } is
                Ok { val = s } -> s
                Err { error = _ } -> "failed"
        """.trimIndent()
        assertEquals("[aaaaa][bbbbb][ccccc]", eval(code))
    }

    /** An empty file yields nothing, rather than one empty line. */
    @Test
    fun `with_lines on an empty file yields nothing`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "" }
              match with_lines { path = "/f", chunk_bytes = 64, f = { lines } -> iter::count { it = lines } } is
                Ok { val = n } -> n
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    /** Blank lines are lines. */
    @Test
    fun `with_lines keeps blank lines`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "a\n\nb\n" }
              match with_lines { path = "/f", chunk_bytes = 64, f = { lines } -> iter::count { it = lines } } is
                Ok { val = n } -> n
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    /**
     * Laziness, as directly as Mezze can ask: `take { n = 2 }` over a file of
     * 200 lines must terminate and give exactly two. A strict implementation
     * would also give two, so this is a termination-and-composition test
     * rather than a proof of laziness — the chunk-boundary case above is what
     * proves the streaming path is really being used.
     */
    @Test
    fun `with_lines composes with take`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let body = seq::fold { xs = seq::range { start = 0, end = 200 }, init = "", f = { acc, x } -> "$(acc)line\n" }
              let _ = write_text { path = "/f", text = body }
              match with_lines { path = "/f", chunk_bytes = 16, f = { lines } -> iter::count { it = iter::take { it = lines, n = 2 } } } is
                Ok { val = n } -> n
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    /** The handle closes even when the consumer stops early. */
    @Test
    fun `with_lines closes the handle after an early stop`() {
        val code = """
            $head
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "a\nb\nc\nd\n" }
              match with_lines { path = "/f", chunk_bytes = 64, f = { lines } -> iter::count { it = iter::take { it = lines, n = 1 } } } is
                Ok { val = _ } -> match FileStreaming.open { path = "/f", mode = Read } is
                  Ok { val = h2 } -> do
                    let _ = FileStreaming.close { handle = h2 }
                    "reopened-fine"
                  Err { error = _ } -> "reopen-failed"
                Err { error = _ } -> "with_lines-failed"
        """.trimIndent()
        assertEquals("reopened-fine", eval(code))
    }

    @Test
    fun `LocalFileSystem with_lines yields every line`(@TempDir tmp: Path) {
        val f = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalFileSystem, LocalFileStreaming, with_lines, write_text }
            use std::collections::{ iter, seq }
            perform LocalFileSystem in perform LocalFileStreaming in do
              let _ = write_text { path = "$f", text = "alpha\nbeta\ngamma\n" }
              match with_lines { path = "$f", chunk_bytes = 4, f = { lines } -> iter::fold { it = lines, init = "", f = { acc, x } -> "$(acc)[$(x)]" } } is
                Ok { val = s } -> s
                Err { error = _ } -> "failed"
        """.trimIndent()
        assertEquals("[alpha][beta][gamma]", eval(code))
    }

    // ==================== watch_events ====================

    /**
     * `poll` answers a batch; `watch_events` flattens the batches into a
     * stream and polls again only when the pipeline asks for an element the
     * current batch does not have.
     */
    @Test
    fun `watch_events flattens a poll batch into a stream`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch, watch_events, write_text }
            use std::collections::{ iter, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                let _ = write_text { path = "/d/a", text = "1" }
                let _ = write_text { path = "/d/b", text = "2" }
                let _ = write_text { path = "/d/c", text = "3" }
                iter::count { it = watch_events { handle = h, timeout_ms = 0 } }
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    /** It is finite: a quiet watch ends the stream rather than spinning. */
    @Test
    fun `watch_events ends on a quiet watch`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch, watch_events }
            use std::collections::iter
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> iter::count { it = watch_events { handle = h, timeout_ms = 0 } }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    /** The events keep their payloads through the stream. */
    @Test
    fun `watch_events preserves the event payload`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch, watch_events, write_text }
            use std::collections::{ iter, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = write_text { path = "/d/only", text = "1" }
                iter::fold {
                  it = watch_events { handle = h, timeout_ms = 0 },
                  init = "",
                  f = { acc, x } -> match x is
                    Created { path = p } -> "$(acc)created:$(p)"
                    Modified { path = _ } -> "$(acc)modified"
                    Deleted { path = _ } -> "$(acc)deleted"
                    Overflow -> "$(acc)overflow"
                }
        """.trimIndent()
        assertEquals("created:/d/only", eval(code))
    }
}
