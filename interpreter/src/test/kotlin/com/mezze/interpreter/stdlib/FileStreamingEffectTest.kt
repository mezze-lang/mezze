package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `FileStreaming` — random access, split out of `FileSystem`.
 *
 * This split is the one that makes an object-store backend clean: S3 has no
 * seek and no partial write, so a unified effect would force it to stub half
 * its ops. It implements `FileSystem` only, and the typechecker refuses it
 * at the `perform` site for anything needing `<FileStreaming>`.
 *
 * `read_at`/`write_at`/`seek`/`truncate` are what a database or a
 * binary-format tool needs, and what Mezze previously had no way to say.
 */
class FileStreamingEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== MemoryFS ====================

    @Test
    fun `MemoryFS read_at reads from an offset`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Read }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "/f", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> match FileStreaming.read_at { handle = h, offset = 3, max_bytes = 4 } is
                  Ok { val = b } -> match bytes::to_str { b = b } is
                    Ok { val } -> val
                    Err { error = _ } -> "utf8-fail"
                  Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("3456", eval(code))
    }

    /**
     * The property that justifies `read_at` existing rather than being
     * seek-then-read: a positional read must NOT disturb the handle's own
     * position, so an index lookup cannot corrupt a sequential scan.
     */
    @Test
    fun `MemoryFS read_at leaves the sequential position alone`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Read }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "/f", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.read_chunk { handle = h, max_bytes = 2 }
                  let _ = FileStreaming.read_at { handle = h, offset = 8, max_bytes = 2 }
                  match FileStreaming.read_chunk { handle = h, max_bytes = 2 } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("23", eval(code))
    }

    @Test
    fun `MemoryFS read_at past the end returns empty`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Read }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "abc" } }
              match FileStreaming.open { path = "/f", mode = Read } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> match FileStreaming.read_at { handle = h, offset = 99, max_bytes = 4 } is
                  Ok { val = b } -> bytes::size { b = b }
                  Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `MemoryFS write_at patches in place`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Write }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "aaaa" } }
              match FileStreaming.open { path = "/f", mode = Write } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.write_at { handle = h, offset = 1, bytes = bytes::from_str { s = "bb" } }
                  let _ = FileStreaming.close { handle = h }
                  match FileSystem.read_bytes { path = "/f" } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "read-failed"
        """.trimIndent()
        // `Write` truncates on open — in BOTH backends — so the patch lands
        // in an empty file: offset 1 extends with a zero byte, then "bb".
        // My first expectation here was "abba", which assumed Write preserved
        // the old content. LocalDisk never did; memfs now agrees.
        assertEquals("\u0000bb", eval(code))
    }

    /** Writing past the end extends, zero-filling the gap — a sparse hole. */
    @Test
    fun `MemoryFS write_at past the end extends with a zero-filled gap`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Write }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "ab" } }
              match FileStreaming.open { path = "/f", mode = Write } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                  let _ = FileStreaming.write_at { handle = h, offset = 5, bytes = bytes::from_str { s = "z" } }
                  let _ = FileStreaming.close { handle = h }
                  match FileSystem.stat { path = "/f" } is
                    Ok { val = s } -> s.size
                    Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(6L, eval(code))
    }

    @Test
    fun `MemoryFS truncate cuts the file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Append }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "/f", mode = Append } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                  let _ = FileStreaming.truncate { handle = h, size = 4 }
                  let _ = FileStreaming.close { handle = h }
                  match FileSystem.stat { path = "/f" } is
                    Ok { val = s } -> s.size
                    Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(4L, eval(code))
    }

    /** truncate does NOT grow — matching FileChannel.truncate. */
    @Test
    fun `MemoryFS truncate does not grow a short file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Append }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "abc" } }
              match FileStreaming.open { path = "/f", mode = Append } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                  let _ = FileStreaming.truncate { handle = h, size = 100 }
                  let _ = FileStreaming.close { handle = h }
                  match FileSystem.stat { path = "/f" } is
                    Ok { val = s } -> s.size
                    Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    @Test
    fun `MemoryFS seek moves the sequential position`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Read }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "/f", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.seek { handle = h, offset = 7 }
                  match FileStreaming.read_chunk { handle = h, max_bytes = 3 } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("789", eval(code))
    }

    @Test
    fun `MemoryFS a negative offset is rejected`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Read }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "abc" } }
              match FileStreaming.open { path = "/f", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> match FileStreaming.read_at { handle = h, offset = 0 - 1, max_bytes = 2 } is
                  Ok { val = _ } -> "unexpectedly-ok"
                  Err { error = FsIOError { path = _, message = _ } } -> "rejected"
                  Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("rejected", eval(code))
    }

    @Test
    fun `MemoryFS read_at on a write handle is refused`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, MemoryFS, MemoryFileStreaming, Write }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "abc" } }
              match FileStreaming.open { path = "/f", mode = Write } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> match FileStreaming.read_at { handle = h, offset = 0, max_bytes = 2 } is
                  Ok { val = _ } -> "unexpectedly-ok"
                  Err { error = _ } -> "refused"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    // ==================== LocalDisk ====================

    @Test
    fun `LocalDisk read_at reads from an offset`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalDisk, LocalFileStreaming, Read }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "$path", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> match FileStreaming.read_at { handle = h, offset = 3, max_bytes = 4 } is
                  Ok { val = b } -> match bytes::to_str { b = b } is
                    Ok { val } -> val
                    Err { error = _ } -> "utf8-fail"
                  Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("3456", eval(code))
    }

    @Test
    fun `LocalDisk read_at leaves the sequential position alone`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalDisk, LocalFileStreaming, Read }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "$path", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.read_chunk { handle = h, max_bytes = 2 }
                  let _ = FileStreaming.read_at { handle = h, offset = 8, max_bytes = 2 }
                  match FileStreaming.read_chunk { handle = h, max_bytes = 2 } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("23", eval(code))
    }

    @Test
    fun `LocalDisk write_at patches in place`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalDisk, LocalFileStreaming, Write }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "aaaa" } }
              match FileStreaming.open { path = "$path", mode = Write } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.write_at { handle = h, offset = 1, bytes = bytes::from_str { s = "bb" } }
                  let _ = FileStreaming.close { handle = h }
                  match FileSystem.read_bytes { path = "$path" } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "read-failed"
        """.trimIndent()
        // Write mode truncates on open, so the patch lands in an empty file:
        // offset 1 extends with a zero byte, then "bb". The point is that the
        // positional write happened at the offset asked for.
        assertEquals("\u0000bb", eval(code))
    }

    @Test
    fun `LocalDisk truncate cuts the file`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalDisk, LocalFileStreaming, Append }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "$path", mode = Append } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                  let _ = FileStreaming.truncate { handle = h, size = 4 }
                  let _ = FileStreaming.close { handle = h }
                  match FileSystem.stat { path = "$path" } is
                    Ok { val = s } -> s.size
                    Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(4L, eval(code))
    }

    @Test
    fun `LocalDisk seek moves the sequential position`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalDisk, LocalFileStreaming, Read }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "0123456789" } }
              match FileStreaming.open { path = "$path", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.seek { handle = h, offset = 7 }
                  match FileStreaming.read_chunk { handle = h, max_bytes = 3 } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("789", eval(code))
    }

    @Test
    fun `LocalDisk read_at on a closed handle reports ClosedHandle`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileStreaming, LocalDisk, LocalFileStreaming, Read }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "abc" } }
              match FileStreaming.open { path = "$path", mode = Read } is
                Err { error = _ } -> "open-failed"
                Ok { val = h } -> do
                  let _ = FileStreaming.close { handle = h }
                  match FileStreaming.read_at { handle = h, offset = 0, max_bytes = 1 } is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = FsClosedHandle { path = _ } } -> "closed"
                    Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("closed", eval(code))
    }
}
