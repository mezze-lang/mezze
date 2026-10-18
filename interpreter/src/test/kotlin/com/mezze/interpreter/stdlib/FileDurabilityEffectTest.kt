package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `FileDurability` — the first of the capability-split FS effects.
 *
 * It is its own effect because of what a backend can honestly promise:
 * `close` flushes to the OS and says nothing about the storage device. Only
 * a WAL, a queue or a database needs the difference, so a function that
 * needs it declares `<FileDurability>` and everyone else carries nothing.
 *
 * The interesting case is MemoryFS. A memfs write handle buffers into
 * `writeBuf` and only publishes to the file map on `close`, so a no-op
 * `fsync` would mean something different from LocalDisk's — fsync-then-read
 * would see the bytes on disk and nothing in memory. The memfs native
 * therefore publishes its buffer, and `fsync publishes a buffered write`
 * below is what pins that. A backend quietly meaning something different by
 * the same op is the exact failure the split exists to prevent.
 */
class FileDurabilityEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
        // MemoryFS state is per CONTEXT and every run is its own context.
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== MemoryFS ====================

    @Test
    fun `MemoryFS fsync on a write handle succeeds`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileDurability, MemoryFS, MemoryFileDurability, Write, FileStreaming, MemoryFileStreaming, LocalFileStreaming }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in perform MemoryFileDurability in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileStreaming.open { path = "/d/wal", mode = Write } is
                Ok { val = h } -> do
                  let _ = FileStreaming.write_chunk {
                    handle = h, bytes = bytes::from_str { s = "entry" }
                  }
                  match FileDurability.fsync { handle = h } is
                    Ok { val = _ } -> "synced"
                    Err { error = _ } -> "fsync-failed"
                Err { error = _ } -> "open-failed"
        """.trimIndent()
        assertEquals("synced", eval(code))
    }

    /**
     * The divergence guard. After `fsync` and BEFORE `close`, the bytes must
     * be readable — that is what durability means to a caller. Without the
     * publish in the memfs native this reads back empty while LocalDisk
     * reads back the data.
     */
    @Test
    fun `MemoryFS fsync publishes a buffered write before close`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileDurability, MemoryFS, MemoryFileDurability, Write, FileStreaming, MemoryFileStreaming, LocalFileStreaming }
            use std::collections::bytes
            perform MemoryFS in perform MemoryFileStreaming in perform MemoryFileDurability in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileStreaming.open { path = "/d/wal", mode = Write } is
                Ok { val = h } -> do
                  let _ = FileStreaming.write_chunk {
                    handle = h, bytes = bytes::from_str { s = "committed" }
                  }
                  let _ = FileDurability.fsync { handle = h }
                  # Deliberately NOT closed: the read must see the synced bytes.
                  match FileSystem.read_bytes { path = "/d/wal" } is
                    Ok { val = b } -> match bytes::to_str { b = b } is
                      Ok { val } -> val
                      Err { error = _ } -> "utf8-fail"
                    Err { error = _ } -> "not-visible"
                Err { error = _ } -> "open-failed"
        """.trimIndent()
        assertEquals("committed", eval(code))
    }

    @Test
    fun `MemoryFS fsync_dir succeeds for a directory and reports a missing one`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileDurability, MemoryFS, MemoryFileDurability }
            perform MemoryFS in perform MemoryFileDurability in do
              let _ = FileSystem.create_directory { path = "/d" }
              let here = match FileDurability.fsync_dir { path = "/d" } is
                Ok { val = _ } -> "ok"
                Err { error = _ } -> "err"
              let gone = match FileDurability.fsync_dir { path = "/nope" } is
                Ok { val = _ } -> "ok"
                Err { error = _ } -> "err"
              "$(here)-$(gone)"
        """.trimIndent()
        assertEquals("ok-err", eval(code))
    }

    // ==================== LocalDisk ====================

    @Test
    fun `LocalDisk fsync forces a written handle`(@TempDir tmp: Path) {
        val path = tmp.resolve("wal").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileDurability, LocalDisk, LocalFileDurability, Write, FileStreaming, MemoryFileStreaming, LocalFileStreaming }
            use std::collections::bytes
            perform LocalDisk in perform LocalFileStreaming in perform LocalFileDurability in do
              match FileStreaming.open { path = "$path", mode = Write } is
                Ok { val = h } -> do
                  let _ = FileStreaming.write_chunk {
                    handle = h, bytes = bytes::from_str { s = "durable" }
                  }
                  let synced = match FileDurability.fsync { handle = h } is
                    Ok { val = _ } -> "synced"
                    Err { error = _ } -> "fsync-failed"
                  let _ = FileStreaming.close { handle = h }
                  synced
                Err { error = _ } -> "open-failed"
        """.trimIndent()
        assertEquals("synced", eval(code))
    }

    @Test
    fun `LocalDisk fsync on a closed handle reports ClosedHandle`(@TempDir tmp: Path) {
        val path = tmp.resolve("wal").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileDurability, LocalDisk, LocalFileDurability, Write, FileStreaming, MemoryFileStreaming, LocalFileStreaming }
            perform LocalDisk in perform LocalFileStreaming in perform LocalFileDurability in do
              match FileStreaming.open { path = "$path", mode = Write } is
                Ok { val = h } -> do
                  let _ = FileStreaming.close { handle = h }
                  match FileDurability.fsync { handle = h } is
                    Ok { val = _ } -> "unexpectedly-ok"
                    Err { error = FsClosedHandle { path = _ } } -> "closed"
                    Err { error = _ } -> "other-error"
                Err { error = _ } -> "open-failed"
        """.trimIndent()
        assertEquals("closed", eval(code))
    }

    @Test
    fun `LocalDisk fsync_dir succeeds on a real directory`(@TempDir tmp: Path) {
        val code = """
            use std::effects::filesystem::{ FileDurability, LocalFileDurability }
            perform LocalFileDurability in
              match FileDurability.fsync_dir { path = "${tmp.toString()}" } is
                Ok { val = _ } -> "ok"
                Err { error = _ } -> "err"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `LocalDisk fsync_dir on a missing path reports NotFound`(@TempDir tmp: Path) {
        val path = tmp.resolve("absent").toString()
        val code = """
            use std::effects::filesystem::{ FileDurability, LocalFileDurability }
            perform LocalFileDurability in
              match FileDurability.fsync_dir { path = "$path" } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsNotFound { path = _ } } -> "not-found"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }
}
