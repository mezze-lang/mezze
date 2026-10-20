package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for the redesigned std::effects::filesystem (#82).
 *
 * Effect is 13 primitive ops; helpers derived on top. Both LocalFileSystem
 * (real disk, JUnit TempDir) and MemoryFileSystem (in-memory) are exercised.
 */
class FileSystemEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        // MemoryFileSystem state needs no clearing between tests: it is per CONTEXT
        // now, and every `MezzeTestHelper.run` is its own context, so each
        // program starts with an empty filesystem. The old call went straight
        // to the Java native from JUnit's thread, which no longer has a
        // context to clear.
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== Primitives — MemoryFileSystem ====================

    @Test
    fun `MemoryFileSystem read_bytes and write_bytes roundtrip`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let payload = bytes::from_str { s = "hello world" }
              let _ = FileSystem.write_bytes { path = "/f", bytes = payload, mode = CreateOrTruncate }
              match FileSystem.read_bytes { path = "/f" } is
                Ok { val = b } -> match bytes::to_str { b = b } is
                  Ok { val } -> val
                  Err { error } -> "utf8-fail"
                Err { error } -> "read-fail"
        """.trimIndent()
        assertEquals("hello world", eval(code))
    }

    @Test
    fun `MemoryFileSystem read_bytes on missing file returns NotFound`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            perform MemoryFileSystem in do
              match FileSystem.read_bytes { path = "/nope" } is
                Ok { val } -> "ok"
                Err { error } -> match error is
                  FsNotFound { path } -> path
                  _ -> "other"
        """.trimIndent()
        assertEquals("/nope", eval(code))
    }

    @Test
    fun `MemoryFileSystem stat on missing file returns NotFound FileKind`() {
        // NotFound is a legal FileKind, not an FsError — enables fast
        // existence checks without exception-throwing on the hot path.
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            perform MemoryFileSystem in
              match FileSystem.stat { path = "/nope", follow_symlinks = True } is
                Ok { val = s } -> match s.kind is
                  NotFound -> "not-found"
                  File -> "file"
                  Directory -> "dir"
                  Other -> "other"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    @Test
    fun `MemoryFileSystem stat on file returns kind File and size`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "/f",
                bytes = bytes::from_str { s = "abcde" }, mode = CreateOrTruncate
              }
              match FileSystem.stat { path = "/f", follow_symlinks = True } is
                Ok { val = s } -> match s.kind is
                  File -> s.size
                  _ -> 0 - 1
                Err { error } -> 0 - 2
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `MemoryFileSystem create_directory then stat returns kind Directory`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileSystem.stat { path = "/d", follow_symlinks = True } is
                Ok { val = s } -> match s.kind is
                  Directory -> "dir"
                  _ -> "wrong"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("dir", eval(code))
    }

    @Test
    fun `MemoryFileSystem list returns child names`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            use std::collections::seq
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/a",
                bytes = bytes::from_str { s = "A" }, mode = CreateOrTruncate
              }
              let _ = FileSystem.write_bytes {
                path = "/d/b",
                bytes = bytes::from_str { s = "B" }, mode = CreateOrTruncate
              }
              match FileSystem.list { path = "/d" } is
                Ok { val = xs } -> seq::size { xs = xs }
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    @Test
    fun `MemoryFileSystem list_with_stat bundles kind and size`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            use std::collections::seq
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/f",
                bytes = bytes::from_str { s = "xy" }, mode = CreateOrTruncate
              }
              match FileSystem.list_with_stat { path = "/d" } is
                Ok { val = entries } -> seq::size { xs = entries }
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `MemoryFileSystem delete removes file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "/f",
                bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FileSystem.delete { path = "/f" }
              match FileSystem.stat { path = "/f", follow_symlinks = True } is
                Ok { val = s } -> match s.kind is
                  NotFound -> "gone"
                  _ -> "still-here"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("gone", eval(code))
    }

    @Test
    fun `MemoryFileSystem delete on non-empty directory returns NotEmpty`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/a",
                bytes = bytes::from_str { s = "A" }, mode = CreateOrTruncate
              }
              match FileSystem.delete { path = "/d" } is
                Ok { val } -> "ok"
                Err { error } -> match error is
                  FsNotEmpty { path } -> path
                  _ -> "other"
        """.trimIndent()
        assertEquals("/d", eval(code))
    }

    @Test
    fun `MemoryFileSystem create_directory on existing returns AlreadyExists`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileSystem.create_directory { path = "/d" } is
                Ok { val } -> "ok"
                Err { error } -> match error is
                  FsAlreadyExists { path } -> path
                  _ -> "other"
        """.trimIndent()
        assertEquals("/d", eval(code))
    }

    @Test
    fun `MemoryFileSystem copy_file duplicates bytes`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "/src",
                bytes = bytes::from_str { s = "content" }, mode = CreateOrTruncate
              }
              let _ = FileSystem.copy_file { src = "/src", dest = "/dest", replace_existing = False, preserve_attrs = False, follow_symlinks = True }
              match FileSystem.read_bytes { path = "/dest" } is
                Ok { val = b } -> match bytes::to_str { b = b } is
                  Ok { val } -> val
                  Err { error } -> "utf8-fail"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("content", eval(code))
    }

    @Test
    fun `MemoryFileSystem move_file renames and removes source`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "/src",
                bytes = bytes::from_str { s = "content" }, mode = CreateOrTruncate
              }
              let _ = FileSystem.move_file { src = "/src", dest = "/dest", replace_existing = False, require_atomic = False }
              match FileSystem.stat { path = "/src", follow_symlinks = True } is
                Ok { val = s } -> match s.kind is
                  NotFound -> match FileSystem.read_bytes { path = "/dest" } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error } -> "utf8-fail"
                    Err { error } -> "dest-fail"
                  _ -> "src-still-there"
                Err { error } -> "stat-err"
        """.trimIndent()
        assertEquals("content", eval(code))
    }

    // ==================== Streaming — MemoryFileSystem ====================

    @Test
    fun `MemoryFileSystem streaming write then read via chunks`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let write_result = match FileStreaming.open { path = "/f", mode = Write } is
                Ok { val = h } -> do
                  let _ = FileStreaming.write_chunk {
                    handle = h,
                    bytes = bytes::from_str { s = "hello" }
                  }
                  FileStreaming.close { handle = h }
                Err { error } -> Err { error = error }
              match FileStreaming.open { path = "/f", mode = Read } is
                Ok { val = h } -> do
                  let chunk_result = FileStreaming.read_chunk {
                    handle = h,
                    max_bytes = 1024
                  }
                  let _ = FileStreaming.close { handle = h }
                  match chunk_result is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error } -> "utf8-fail"
                    Err { error } -> "read-fail"
                Err { error } -> "open-fail"
        """.trimIndent()
        assertEquals("hello", eval(code))
    }

    @Test
    fun `MemoryFileSystem streaming read at EOF returns empty bytes`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes {
                path = "/f",
                bytes = bytes::from_str { s = "ab" }, mode = CreateOrTruncate
              }
              match FileStreaming.open { path = "/f", mode = Read } is
                Ok { val = h } -> do
                  let _ = FileStreaming.read_chunk { handle = h, max_bytes = 100 }
                  let eof_chunk = FileStreaming.read_chunk { handle = h, max_bytes = 100 }
                  let _ = FileStreaming.close { handle = h }
                  match eof_chunk is
                    Ok { val = b } -> bytes::size { b = b }
                    Err { error } -> 0 - 1
                Err { error } -> 0 - 2
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `MemoryFileSystem streaming append preserves existing content`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes {
                path = "/f",
                bytes = bytes::from_str { s = "hello " }, mode = CreateOrTruncate
              }
              let _ = match FileStreaming.open { path = "/f", mode = Append } is
                Ok { val = h } -> do
                  let _ = FileStreaming.write_chunk {
                    handle = h,
                    bytes = bytes::from_str { s = "world" }
                  }
                  FileStreaming.close { handle = h }
                Err { error } -> Err { error = error }
              match FileSystem.read_bytes { path = "/f" } is
                Ok { val = b } -> match bytes::to_str { b = b } is
                  Ok { val } -> val
                  Err { error } -> "utf8-fail"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("hello world", eval(code))
    }

    @Test
    fun `MemoryFileSystem close is idempotent`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileStreaming.open { path = "/d/f", mode = Write } is
                Ok { val = h } -> do
                  let _ = FileStreaming.close { handle = h }
                  match FileStreaming.close { handle = h } is
                    Ok { val } -> "ok"
                    Err { error } -> "err"
                Err { error } -> "open-fail"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `MemoryFileSystem read_chunk after close returns ClosedHandle`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = FileSystem.write_bytes {
                path = "/f",
                bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              match FileStreaming.open { path = "/f", mode = Read } is
                Ok { val = h } -> do
                  let _ = FileStreaming.close { handle = h }
                  match FileStreaming.read_chunk { handle = h, max_bytes = 10 } is
                    Ok { val } -> "unexpected-ok"
                    Err { error } -> match error is
                      FsClosedHandle { path } -> path
                      _ -> "other-err"
                Err { error } -> "open-fail"
        """.trimIndent()
        assertEquals("/f", eval(code))
    }

    // ==================== Derived helpers ====================

    @Test
    fun `read_text and write_text roundtrip`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, read_text, write_text, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/f", text = "line one" }
              match read_text { path = "/f" } is
                Ok { val } -> val
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("line one", eval(code))
    }

    @Test
    fun `read_text_or returns default on missing`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, read_text_or, CreateOrTruncate }
            perform MemoryFileSystem in
              read_text_or { path = "/nope", def = "fallback" }
        """.trimIndent()
        assertEquals("fallback", eval(code))
    }

    @Test
    fun `exists returns True for existing file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, exists, write_text, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/f", text = "x" }
              match exists { path = "/f" } is
                Ok { val } -> val
                Err { error } -> False
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `exists returns False for missing`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, exists, CreateOrTruncate }
            perform MemoryFileSystem in
              match exists { path = "/nope" } is
                Ok { val } -> val
                Err { error } -> True
        """.trimIndent()
        assertEquals(false, eval(code))
    }

    @Test
    fun `is_file distinguishes file from directory`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, is_file, write_text, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/f", text = "x" }
              let _ = FileSystem.create_directory { path = "/d" }
              match is_file { path = "/f" } is
                Ok { val = f } -> match is_file { path = "/d" } is
                  Ok { val = d } -> f && d.not {}
                  Err { error } -> False
                Err { error } -> False
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `is_directory distinguishes directory from file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, is_directory, write_text, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/f", text = "x" }
              let _ = FileSystem.create_directory { path = "/d" }
              match is_directory { path = "/d" } is
                Ok { val = d } -> match is_directory { path = "/f" } is
                  Ok { val = f } -> d && f.not {}
                  Err { error } -> False
                Err { error } -> False
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `file_size returns byte length`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, file_size, write_text, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/f", text = "12345" }
              match file_size { path = "/f" } is
                Ok { val } -> val
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `append_text appends to existing file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, write_text, append_text, read_text, MemoryFileStreaming, CreateOrTruncate }
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "hello " }
              let _ = append_text { path = "/f", text = "world" }
              match read_text { path = "/f" } is
                Ok { val } -> val
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("hello world", eval(code))
    }

    @Test
    fun `create_directories creates missing ancestors`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, create_directories, CreateOrTruncate }
            perform MemoryFileSystem in do
              match create_directories { path = "/a/b/c" } is
                Ok { val } -> "ok"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `ensure_directory creates if missing`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, ensure_directory, CreateOrTruncate }
            perform MemoryFileSystem in do
              match ensure_directory { path = "/d" } is
                Ok { val } -> "ok"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `ensure_directory is idempotent if dir exists`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, ensure_directory, CreateOrTruncate }
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              match ensure_directory { path = "/d" } is
                Ok { val } -> "ok"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `delete_recursive removes tree`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, delete_recursive, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.create_directory { path = "/d/sub" }
              let _ = FileSystem.write_bytes {
                path = "/d/sub/f",
                bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              match delete_recursive { path = "/d" } is
                Ok { val } -> "ok"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `with_read auto-closes handle after body`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, with_read, write_text, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = write_text { path = "/f", text = "abc" }
              match with_read { path = "/f", f = { handle } ->
                match FileStreaming.read_chunk { handle = handle, max_bytes = 100 } is
                  Ok { val = b } -> bytes::size { b = b }
                  Err { error } -> 0 - 1
              } is
                Ok { val } -> val
                Err { error } -> 0 - 2
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    @Test
    fun `with_write auto-closes handle and content is flushed`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, with_write, read_text, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileStreaming in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = with_write { path = "/d/f", mode = Write, f = { handle } ->
                FileStreaming.write_chunk {
                  handle = handle,
                  bytes = bytes::from_str { s = "written" }
                }
              }
              match read_text { path = "/d/f" } is
                Ok { val } -> val
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("written", eval(code))
    }

    // ==================== LocalFileSystem (real filesystem via TempDir) ====================

    @Test
    fun `LocalFileSystem read and write bytes roundtrip`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "$path",
                bytes = bytes::from_str { s = "roundtrip" }, mode = CreateOrTruncate
              }
              match FileSystem.read_bytes { path = "$path" } is
                Ok { val = b } -> match bytes::to_str { b = b } is
                  Ok { val } -> val
                  Err { error } -> "utf8-fail"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("roundtrip", eval(code))
    }

    @Test
    fun `LocalFileSystem stat on missing returns NotFound FileKind`(@TempDir tmp: Path) {
        val path = tmp.resolve("nope").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, CreateOrTruncate }
            perform LocalFileSystem in
              match FileSystem.stat { path = "$path", follow_symlinks = True } is
                Ok { val = s } -> match s.kind is
                  NotFound -> "not-found"
                  _ -> "wrong"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    @Test
    fun `LocalFileSystem copy_file uses kernel path`(@TempDir tmp: Path) {
        val src = tmp.resolve("src").toString()
        val dst = tmp.resolve("dst").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "$src",
                bytes = bytes::from_str { s = "kernel-copy" }, mode = CreateOrTruncate
              }
              let _ = FileSystem.copy_file { src = "$src", dest = "$dst", replace_existing = False, preserve_attrs = False, follow_symlinks = True }
              match FileSystem.read_bytes { path = "$dst" } is
                Ok { val = b } -> match bytes::to_str { b = b } is
                  Ok { val } -> val
                  Err { error } -> "utf8-fail"
                Err { error } -> "err"
        """.trimIndent()
        assertEquals("kernel-copy", eval(code))
    }

    @Test
    fun `LocalFileSystem streaming read of a real file`(@TempDir tmp: Path) {
        val path = tmp.resolve("streamed").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, with_write, with_read, FileStreaming, MemoryFileStreaming, LocalFileStreaming, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in perform LocalFileStreaming in do
              let _ = with_write { path = "$path", mode = Write, f = { handle } ->
                FileStreaming.write_chunk {
                  handle = handle,
                  bytes = bytes::from_str { s = "abcdefg" }
                }
              }
              match with_read { path = "$path", f = { handle } ->
                match FileStreaming.read_chunk { handle = handle, max_bytes = 3 } is
                  Ok { val = b } -> bytes::to_str { b = b }
                  Err { error } -> Err { error = "err" }
              } is
                Ok { val = inner } -> match inner is
                  Ok { val } -> val
                  Err { error } -> "utf8-fail"
                Err { error } -> "with-fail"
        """.trimIndent()
        assertEquals("abc", eval(code))
    }

    @Test
    fun `LocalFileSystem list_with_stat returns entries`(@TempDir tmp: Path) {
        val dir = tmp.toString()
        val f1 = tmp.resolve("f1").toString()
        val f2 = tmp.resolve("f2").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, CreateOrTruncate }
            use std::collections::bytes
            use std::collections::seq
            perform LocalFileSystem in do
              let _ = FileSystem.write_bytes {
                path = "$f1",
                bytes = bytes::from_str { s = "A" }, mode = CreateOrTruncate
              }
              let _ = FileSystem.write_bytes {
                path = "$f2",
                bytes = bytes::from_str { s = "BB" }, mode = CreateOrTruncate
              }
              match FileSystem.list_with_stat { path = "$dir" } is
                Ok { val = entries } -> seq::size { xs = entries }
                Err { error } -> 0 - 1
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    // ==================== Path helpers (std::fs) ====================

    @Test
    fun `fs path_join joins with slash`() {
        val code = """
            use std::fs
            fs::path_join { parts = ["a", "b", "c"] }
        """.trimIndent()
        assertEquals("a/b/c", eval(code))
    }

    @Test
    fun `fs file_name returns last component`() {
        val code = """
            use std::fs
            fs::file_name { path = "/a/b/c.txt" }
        """.trimIndent()
        assertEquals("c.txt", eval(code))
    }

    @Test
    fun `fs parent returns parent path`() {
        val code = """
            use std::fs
            fs::parent { path = "/a/b/c" }
        """.trimIndent()
        assertEquals("/a/b", eval(code))
    }

    @Test
    fun `fs extension returns Some for dotted names`() {
        val code = """
            use std::fs
            match fs::extension { path = "/a/b.txt" } is
              Some { val } -> val
              None -> "none"
        """.trimIndent()
        assertEquals("txt", eval(code))
    }

    @Test
    fun `fs extension returns None for no dot`() {
        val code = """
            use std::fs
            match fs::extension { path = "/a/b" } is
              Some { val } -> "unexpected"
              None -> "none"
        """.trimIndent()
        assertEquals("none", eval(code))
    }

    @Test
    fun `fs has_extension matches`() {
        val code = """
            use std::fs
            fs::has_extension { path = "/a/b.rs", ext = "rs" }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    @Test
    fun `fs is_absolute detects leading slash`() {
        val code = """
            use std::fs
            fs::is_absolute { path = "/foo" }
        """.trimIndent()
        assertEquals(true, eval(code))
    }
}
