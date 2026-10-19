package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `FileWatch` — change notification.
 *
 * The backends differ more here than anywhere else: inotify/kqueue on
 * LocalFileSystem, synthesised from its own mutations in MemoryFileSystem, and nothing at
 * all for an object store, which declines the effect entirely.
 *
 * MemoryFileSystem is the one worth testing hard, because its events are
 * deterministic: a write produces exactly one event, immediately, with no
 * OS coalescing to race against. The LocalFileSystem tests are deliberately
 * weaker — a real WatchService may coalesce or delay, so asserting an exact
 * event sequence there would be a flake generator.
 */
class FileWatchEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== MemoryFileSystem ====================

    @Test
    fun `MemoryFileSystem reports a Created event for a new file`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/new", bytes = bytes::from_str { s = "x" } }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> match seq::first { xs = evs } is
                    Some { val = Created { path = p } } -> p
                    Some { val = _ } -> "wrong-kind"
                    None -> "no-events"
                  Err { error = _ } -> "poll-failed"
        """.trimIndent()
        assertEquals("/d/new", eval(code))
    }

    @Test
    fun `MemoryFileSystem reports Modified when an existing file is rewritten`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes { path = "/d/f", bytes = bytes::from_str { s = "a" } }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/f", bytes = bytes::from_str { s = "b" } }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> match seq::first { xs = evs } is
                    Some { val = Modified { path = _ } } -> "modified"
                    Some { val = Created { path = _ } } -> "said-created"
                    Some { val = _ } -> "wrong-kind"
                    None -> "no-events"
                  Err { error = _ } -> "poll-failed"
        """.trimIndent()
        assertEquals("modified", eval(code))
    }

    @Test
    fun `MemoryFileSystem reports Deleted`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes { path = "/d/f", bytes = bytes::from_str { s = "a" } }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileSystem.delete { path = "/d/f" }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> match seq::first { xs = evs } is
                    Some { val = Deleted { path = p } } -> p
                    Some { val = _ } -> "wrong-kind"
                    None -> "no-events"
                  Err { error = _ } -> "poll-failed"
        """.trimIndent()
        assertEquals("/d/f", eval(code))
    }

    /** Polling twice must not redeliver — the queue drains. */
    @Test
    fun `MemoryFileSystem a second poll returns nothing new`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/f", bytes = bytes::from_str { s = "x" } }
                let _ = FileWatch.poll { handle = h, timeout_ms = 0 }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> seq::size { xs = evs }
                  Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    /** A non-recursive watch sees direct children only. */
    @Test
    fun `MemoryFileSystem non-recursive watch ignores a nested write`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.create_directory { path = "/d/sub" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/sub/f", bytes = bytes::from_str { s = "x" } }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> seq::size { xs = evs }
                  Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `MemoryFileSystem recursive watch sees a nested write`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.create_directory { path = "/d/sub" }
              match FileWatch.watch { path = "/d", recursive = True } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/sub/f", bytes = bytes::from_str { s = "x" } }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> seq::size { xs = evs }
                  Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `MemoryFileSystem watching a missing directory reports NotFound`() {
        val code = """
            use std::effects::filesystem::{ FileWatch, MemoryFileSystem, MemoryFileWatch }
            perform MemoryFileSystem in perform MemoryFileWatch in
              match FileWatch.watch { path = "/nope", recursive = False } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsNotFound { path = _ } } -> "not-found"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    @Test
    fun `MemoryFileSystem unwatch then poll reports the handle is gone`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileWatch.unwatch { handle = h }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = _ } -> "still-polling"
                  Err { error = _ } -> "unregistered"
        """.trimIndent()
        assertEquals("unregistered", eval(code))
    }

    // ==================== LocalFileSystem ====================

    /**
     * Deliberately weak: that a watch registers, polls without error and
     * unwatches. A real WatchService may coalesce or delay events, and
     * asserting an exact sequence here would be a flake generator — the
     * event semantics are pinned on MemoryFileSystem instead, where they are
     * deterministic.
     */
    @Test
    fun `LocalFileSystem watch registers polls and unwatches`(@TempDir tmp: Path) {
        val code = """
            use std::effects::filesystem::{ FileWatch, LocalFileWatch }
            perform LocalFileWatch in do
              match FileWatch.watch { path = "${tmp.toString()}", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileWatch.poll { handle = h, timeout_ms = 0 }
                match FileWatch.unwatch { handle = h } is
                  Ok { val = _ } -> "ok"
                  Err { error = _ } -> "unwatch-failed"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    @Test
    fun `LocalFileSystem watching a file rather than a directory is an error`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, LocalFileSystem, LocalFileWatch }
            use std::collections::bytes
            perform LocalFileSystem in perform LocalFileWatch in do
              let _ = FileSystem.write_bytes { path = "$path", bytes = bytes::from_str { s = "x" } }
              match FileWatch.watch { path = "$path", recursive = False } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsNotADirectory { path = _ } } -> "not-a-directory"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-a-directory", eval(code))
    }

    @Test
    fun `LocalFileSystem poll after unwatch reports a closed handle`(@TempDir tmp: Path) {
        val code = """
            use std::effects::filesystem::{ FileWatch, LocalFileWatch }
            perform LocalFileWatch in do
              match FileWatch.watch { path = "${tmp.toString()}", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileWatch.unwatch { handle = h }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = _ } -> "still-polling"
                  Err { error = FsClosedHandle { path = _ } } -> "closed"
                  Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("closed", eval(code))
    }

    /**
     * A program that watches a directory and never calls `unwatch` must not
     * cost a thread for the life of the PROCESS.
     *
     * This is the one resource in the seven effects whose leak GC cannot
     * mask, which is why the per-context dispose registry is pinned here and
     * not on `FileLock`. A NIO `WatchService` runs a daemon thread named
     * `FileSystemWatchService`; that thread is a GC ROOT and holds the
     * service, so an orphaned service is never unreachable and never
     * collected. Measured directly: open → 1 thread, close → 0, but
     * leak + 5 forced GCs → still 1. A leaked lock or fd, by contrast, is
     * released by FileDescriptor's Cleaner on the next GC, so it cannot
     * witness the fix.
     *
     * `MezzeTestHelper.run` closes the context, and `OpenResources.close()`
     * in FilesystemNatives is what must then close the service. With that
     * registry removed this fails: the count stays up permanently.
     */
    @Test
    fun `LocalFileSystem a watch leaked by one program is closed when its context goes`(@TempDir tmp: Path) {
        val baseline = watchThreads()
        val code = """
            use std::effects::filesystem::{ FileWatch, LocalFileWatch }
            perform LocalFileWatch in
              match FileWatch.watch { path = "${tmp.toString()}", recursive = False } is
                Ok { val = _ } -> "watching"
                Err { error = _ } -> "watch-failed"
        """.trimIndent()
        // Deliberately no unwatch.
        assertEquals("watching", eval(code), "precondition: the watch must register")

        // The thread exits asynchronously once the service is closed, so give
        // it a bounded window rather than asserting on the instant.
        val deadline = System.currentTimeMillis() + 5_000
        while (watchThreads() > baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(
            baseline, watchThreads(),
            "a WatchService thread outlived its context — the leaked watch was never closed",
        )
    }

    /** Live NIO watcher threads. `AbstractPoller` names them all the same. */
    private fun watchThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name == "FileSystemWatchService" }
}
