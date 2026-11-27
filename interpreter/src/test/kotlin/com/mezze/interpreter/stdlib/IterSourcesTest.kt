package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The filesystem as a streaming SOURCE: `lines` / `with_lines` and `watch`.
 *
 * A source is a FOLD that takes the consumer (`init`, `f`) and runs it during
 * the call, so its reads sit on the call's own arrow — the handler installed
 * around the call is the handler that is there when the reads happen. An
 * `Iter` holding the reads would hide them in data and could be pulled after
 * the handler was gone; see `IterStageCallbacksArePureTest`.
 *
 * Aggregation is the fold's accumulator (no mutable state), and an early stop
 * is a `return` from `std::effects::early_return`.
 */
class IterSourcesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val head = """
        use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFileSystem, MemoryFileStreaming, with_lines, lines, write_text }
        use std::collections::seq
        use std::int

        ## Count the lines `with_lines` folds over; -1 if it fails.
        let count_lines: { path: Str, chunk_bytes: Int } -> Int <FileStreaming> = { path, chunk_bytes } ->
          match with_lines { path = path, chunk_bytes = chunk_bytes, init = 0, f = { acc, x } -> acc + 1 } is
            Ok { val = n } -> n
            Err { ... } -> 0 - 1

        ## Every line bracketed, in order; "failed" if `with_lines` fails.
        let join_lines: { path: Str, chunk_bytes: Int } -> Str <FileStreaming> = { path, chunk_bytes } ->
          match with_lines { path = path, chunk_bytes = chunk_bytes, init = "", f = { acc, x } -> "$(acc)[$(x)]" } is
            Ok { val = s } -> s
            Err { ... } -> "failed"
    """.trimIndent()

    private val scopes = "perform MemoryFileSystem in perform MemoryFileStreaming in do"

    /** `head`, optional extra imports, the scopes, then `body` indented under the `do`. */
    private fun prog(body: String, extra: String = ""): String =
        head + "\n" + (if (extra.isEmpty()) "" else extra.trimIndent() + "\n") + scopes + "\n" +
            body.trimIndent().prependIndent("  ")

    // ==================== with_lines ====================

    @Test
    fun `with_lines folds over every line`() {
        val code = prog(
            """
            write_text { path = "/f", text = "alpha\nbeta\ngamma\n" }
            count_lines { path = "/f", chunk_bytes = 64 }
            """,
        )
        assertEquals(3L, eval(code))
    }

    /** A trailing line with no newline still counts — the classic off-by-one. */
    @Test
    fun `with_lines counts a final line that has no newline`() {
        val code = prog(
            """
            write_text { path = "/f", text = "alpha\nbeta\ngamma" }
            count_lines { path = "/f", chunk_bytes = 64 }
            """,
        )
        assertEquals(3L, eval(code))
    }

    /** The newline itself must not survive into the line handed to `f`. */
    @Test
    fun `with_lines strips the newline`() {
        val code = prog(
            """
            write_text { path = "/f", text = "alpha\nbeta\n" }
            join_lines { path = "/f", chunk_bytes = 64 }
            """,
        )
        assertEquals("[alpha][beta]", eval(code))
    }

    /**
     * THE case that justifies the buffering: a chunk boundary mid-line. With
     * `chunk_bytes = 4` and lines of 5 characters, every line spans two reads,
     * so an implementation that treated a chunk as a unit of lines would split
     * them.
     */
    @Test
    fun `with_lines reassembles a line split across chunks`() {
        val code = prog(
            """
            write_text { path = "/f", text = "aaaaa\nbbbbb\nccccc\n" }
            join_lines { path = "/f", chunk_bytes = 4 }
            """,
        )
        assertEquals("[aaaaa][bbbbb][ccccc]", eval(code))
    }

    /** An empty file folds to `init`, rather than over one empty line. */
    @Test
    fun `with_lines on an empty file answers init`() {
        val code = prog(
            """
            write_text { path = "/f", text = "" }
            count_lines { path = "/f", chunk_bytes = 64 }
            """,
        )
        assertEquals(0L, eval(code))
    }

    /** Blank lines are lines. */
    @Test
    fun `with_lines keeps blank lines`() {
        val code = prog(
            """
            write_text { path = "/f", text = "a\n\nb\n" }
            count_lines { path = "/f", chunk_bytes = 64 }
            """,
        )
        assertEquals(3L, eval(code))
    }

    /**
     * Early stop: `return` out of the fold after two lines of two hundred. The
     * consumer decides; the producer never knows. Done on a handle we own so
     * the close still runs after the abort.
     */
    @Test
    fun `lines stops early through early_return and the handle still closes`() {
        val code = prog(
            """
            let body = seq::fold { xs = seq::range { start = 0, end = 200 }, init = "", f = { acc, x } -> "$(acc)line\n" }
            write_text { path = "/f", text = body }
            match FileStreaming.open { path = "/f", mode = Read } is
              Err { ... } -> "open-failed"
              Ok { val = h } -> do
                let n = perform EarlyReturn in
                  lines { handle = h, chunk_bytes = 16, init = 0, f = { acc, x } -> if acc + 1 == 2 then return { val = 2 } else acc + 1 }
                FileStreaming.close { handle = h }
                match FileStreaming.open { path = "/f", mode = Read } is
                  Ok { val = h2 } -> do
                    FileStreaming.close { handle = h2 }
                    "$(int::to_str { n = n }) lines, reopened-fine"
                  Err { ... } -> "reopen-failed"
            """,
            extra = "use std::effects::early_return::{ EarlyReturn, return }",
        )
        assertEquals("2 lines, reopened-fine", eval(code))
    }

    /** `with_lines` closes its handle after a full read. */
    @Test
    fun `with_lines closes the handle when done`() {
        val code = prog(
            """
            write_text { path = "/f", text = "a\nb\nc\nd\n" }
            match with_lines { path = "/f", chunk_bytes = 64, init = {}, f = { acc, x } -> {} } is
              Ok { ... } -> match FileStreaming.open { path = "/f", mode = Read } is
                Ok { val = h2 } -> do
                  FileStreaming.close { handle = h2 }
                  "reopened-fine"
                Err { ... } -> "reopen-failed"
              Err { ... } -> "with_lines-failed"
            """,
        )
        assertEquals("reopened-fine", eval(code))
    }

    @Test
    fun `LocalFileSystem with_lines folds over every line`(@TempDir tmp: Path) {
        val f = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalFileSystem, LocalFileStreaming, with_lines, write_text }
            perform LocalFileSystem in perform LocalFileStreaming in do
              write_text { path = "$f", text = "alpha\nbeta\ngamma\n" }
              match with_lines { path = "$f", chunk_bytes = 4, init = "", f = { acc, x } -> "$(acc)[$(x)]" } is
                Ok { val = s } -> s
                Err { ... } -> "failed"
        """.trimIndent()
        assertEquals("[alpha][beta][gamma]", eval(code))
    }

    // ==================== watch ====================

    private val watchHead = """
        use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch, watch, write_text }
        use std::collections::seq
    """.trimIndent()

    /** `poll` answers a batch; `watch` folds over each event of the batch, then polls again. */
    @Test
    fun `watch flattens a poll batch into events`() {
        val code = """
            $watchHead
            perform MemoryFileSystem in perform MemoryFileWatch in do
              FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { ... } -> 0 - 1
                Ok { val = h } -> do
                  write_text { path = "/d/a", text = "1" }
                  write_text { path = "/d/b", text = "2" }
                  write_text { path = "/d/c", text = "3" }
                  watch { handle = h, timeout_ms = 0, init = 0, f = { acc, x } -> acc + 1 }
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    /** It is finite: a quiet watch answers `init` rather than spinning. */
    @Test
    fun `watch answers init on a quiet watch`() {
        val code = """
            $watchHead
            perform MemoryFileSystem in perform MemoryFileWatch in do
              FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { ... } -> 0 - 1
                Ok { val = h } -> watch { handle = h, timeout_ms = 0, init = 0, f = { acc, x } -> acc + 1 }
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    /** The events keep their payloads. */
    @Test
    fun `watch preserves the event payload`() {
        val code = """
            $watchHead
            perform MemoryFileSystem in perform MemoryFileWatch in do
              FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { ... } -> "watch-failed"
                Ok { val = h } -> do
                  write_text { path = "/d/only", text = "1" }
                  watch { handle = h, timeout_ms = 0, init = "", f = { acc, x } -> match x is
                    Created { path = p } -> "$(acc)created:$(p)"
                    Modified { ... } -> "$(acc)modified"
                    Deleted { ... } -> "$(acc)deleted"
                    Overflow -> "$(acc)overflow"
                  }
        """.trimIndent()
        assertEquals("created:/d/only", eval(code))
    }
}
