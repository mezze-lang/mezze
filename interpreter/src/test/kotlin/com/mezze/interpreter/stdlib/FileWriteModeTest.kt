package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `WriteMode`, and the options on `copy_file` / `move_file` / `stat`.
 *
 * These three signature changes were deferred out of the original split
 * because each one migrates every caller. What they buy:
 *
 *  - `CreateNew` is the ATOMIC LOCKFILE primitive. Nothing else in the effect
 *    could express it: `exists` then `write_bytes` has a window between the
 *    two calls, and two processes racing through that window both think they
 *    won. This is the one genuinely missing capability of the three.
 *  - `CreateOrAppend` takes appending off `FileStreaming`, so a program that
 *    wants to add a log line no longer has to disclose random-access file
 *    I/O — the one capability an object store cannot offer.
 *  - `replace_existing` / `preserve_attrs` / `require_atomic` /
 *    `follow_symlinks` close the NIO gap, and `require_atomic` in particular
 *    stops a silent downgrade: `move_file` used to try an atomic rename and
 *    quietly fall back to a non-atomic one, which is exactly wrong for
 *    publish-by-rename.
 *
 * Both backends throughout, because the divergences are where the bugs were
 * last time.
 */
class FileWriteModeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val memHead = """
        use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, CreateNew, CreateOrAppend, read_text, write_text, append_text, create_exclusive }
        use std::collections::bytes
    """.trimIndent()

    private val localHead = """
        use std::effects::filesystem::{ FileSystem, LocalFileSystem, CreateOrTruncate, CreateNew, CreateOrAppend, read_text, write_text, append_text, create_exclusive }
        use std::collections::bytes
    """.trimIndent()

    // ==================== CreateOrTruncate ====================

    /** The default meaning of "write a file": replace what was there. */
    @Test
    fun `MemoryFileSystem CreateOrTruncate replaces existing content`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "aaaaaa" }, mode = CreateOrTruncate }
              let _ = FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "bb" }, mode = CreateOrTruncate }
              match read_text { path = "/f" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("bb", eval(code))
    }

    @Test
    fun `LocalFileSystem CreateOrTruncate replaces existing content`(@TempDir tmp: Path) {
        val f = tmp.resolve("f").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "aaaaaa" }, mode = CreateOrTruncate }
              let _ = FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "bb" }, mode = CreateOrTruncate }
              match read_text { path = "$f" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("bb", eval(code))
    }

    // ==================== CreateNew — the lockfile primitive ====================

    @Test
    fun `MemoryFileSystem CreateNew creates a missing file`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes { path = "/new", bytes = bytes::from_str { s = "hi" }, mode = CreateNew }
              match read_text { path = "/new" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("hi", eval(code))
    }

    /** The property that makes it a lock: the second caller is refused. */
    @Test
    fun `MemoryFileSystem CreateNew refuses an existing file`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = write_text { path = "/f", text = "first" }
              match FileSystem.write_bytes { path = "/f", bytes = bytes::from_str { s = "second" }, mode = CreateNew } is
                Ok { val = _ } -> "overwrote"
                Err { error = FsAlreadyExists { path = _ } } -> "refused"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    @Test
    fun `LocalFileSystem CreateNew refuses an existing file`(@TempDir tmp: Path) {
        val f = tmp.resolve("f").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$f", text = "first" }
              match FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "second" }, mode = CreateNew } is
                Ok { val = _ } -> "overwrote"
                Err { error = FsAlreadyExists { path = _ } } -> "refused"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    /** And the refusal must not have touched the content. */
    @Test
    fun `LocalFileSystem a refused CreateNew leaves the file alone`(@TempDir tmp: Path) {
        val f = tmp.resolve("f").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$f", text = "original" }
              let _ = FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "clobbered" }, mode = CreateNew }
              match read_text { path = "$f" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("original", eval(code))
    }

    /**
     * `create_exclusive` is the userland face of it: True if this caller made
     * the file, False if somebody already had it. Of two racers exactly one
     * gets True, which is what a lock means.
     */
    @Test
    fun `MemoryFileSystem create_exclusive reports who won`() {
        val code = """
            $memHead
            use std::bool
            perform MemoryFileSystem in do
              let first = create_exclusive { path = "/app.lock", bytes = bytes::from_str { s = "pid" } }
              let second = create_exclusive { path = "/app.lock", bytes = bytes::from_str { s = "pid" } }
              match first is
                Ok { val = a } -> match second is
                  Ok { val = b } -> "$(bool::to_str { b = a })-$(bool::to_str { b = b })"
                  Err { error = _ } -> "second-errored"
                Err { error = _ } -> "first-errored"
        """.trimIndent()
        assertEquals("True-False", eval(code))
    }

    @Test
    fun `LocalFileSystem create_exclusive reports who won`(@TempDir tmp: Path) {
        val f = tmp.resolve("app.lock").toString()
        val code = """
            $localHead
            use std::bool
            perform LocalFileSystem in do
              let first = create_exclusive { path = "$f", bytes = bytes::from_str { s = "pid" } }
              let second = create_exclusive { path = "$f", bytes = bytes::from_str { s = "pid" } }
              match first is
                Ok { val = a } -> match second is
                  Ok { val = b } -> "$(bool::to_str { b = a })-$(bool::to_str { b = b })"
                  Err { error = _ } -> "second-errored"
                Err { error = _ } -> "first-errored"
        """.trimIndent()
        assertEquals("True-False", eval(code))
    }

    // ==================== CreateOrAppend ====================

    @Test
    fun `MemoryFileSystem CreateOrAppend extends existing content`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = write_text { path = "/log", text = "one\n" }
              let _ = FileSystem.write_bytes { path = "/log", bytes = bytes::from_str { s = "two\n" }, mode = CreateOrAppend }
              let _ = FileSystem.write_bytes { path = "/log", bytes = bytes::from_str { s = "three\n" }, mode = CreateOrAppend }
              match read_text { path = "/log" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("one\ntwo\nthree\n", eval(code))
    }

    @Test
    fun `LocalFileSystem CreateOrAppend extends existing content`(@TempDir tmp: Path) {
        val f = tmp.resolve("log").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$f", text = "one\n" }
              let _ = FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "two\n" }, mode = CreateOrAppend }
              let _ = FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "three\n" }, mode = CreateOrAppend }
              match read_text { path = "$f" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("one\ntwo\nthree\n", eval(code))
    }

    /** Appending to a missing file creates it, like O_APPEND|O_CREAT. */
    @Test
    fun `MemoryFileSystem CreateOrAppend creates a missing file`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = FileSystem.write_bytes { path = "/fresh", bytes = bytes::from_str { s = "x" }, mode = CreateOrAppend }
              match read_text { path = "/fresh" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("x", eval(code))
    }

    @Test
    fun `LocalFileSystem CreateOrAppend creates a missing file`(@TempDir tmp: Path) {
        val f = tmp.resolve("fresh").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = FileSystem.write_bytes { path = "$f", bytes = bytes::from_str { s = "x" }, mode = CreateOrAppend }
              match read_text { path = "$f" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("x", eval(code))
    }

    /**
     * THE effect-row reduction, and the reason `CreateOrAppend` was worth
     * adding to `FileSystem` rather than leaving in `FileStreaming`.
     *
     * This program performs ONLY `MemoryFileSystem`. `append_text` used to
     * need `<FileStreaming>`, so this would not typecheck — the effect would
     * be unhandled. That it runs is the proof that adding a log line no
     * longer requires disclosing random-access I/O.
     */
    @Test
    fun `append_text needs only FileSystem now`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = write_text { path = "/log", text = "a" }
              let _ = append_text { path = "/log", text = "b" }
              let _ = append_text { path = "/log", text = "c" }
              match read_text { path = "/log" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("abc", eval(code))
    }

    // ==================== copy_file options ====================

    @Test
    fun `MemoryFileSystem copy_file refuses an existing destination by default`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = write_text { path = "/a", text = "from-a" }
              let _ = write_text { path = "/b", text = "from-b" }
              match FileSystem.copy_file { src = "/a", dest = "/b", replace_existing = False, preserve_attrs = False, follow_symlinks = True } is
                Ok { val = _ } -> "replaced"
                Err { error = FsAlreadyExists { path = _ } } -> "refused"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    @Test
    fun `MemoryFileSystem copy_file replace_existing overwrites`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = write_text { path = "/a", text = "from-a" }
              let _ = write_text { path = "/b", text = "from-b" }
              let _ = FileSystem.copy_file { src = "/a", dest = "/b", replace_existing = True, preserve_attrs = False, follow_symlinks = True }
              match read_text { path = "/b" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("from-a", eval(code))
    }

    @Test
    fun `LocalFileSystem copy_file replace_existing overwrites`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toString()
        val b = tmp.resolve("b").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$a", text = "from-a" }
              let _ = write_text { path = "$b", text = "from-b" }
              let _ = FileSystem.copy_file { src = "$a", dest = "$b", replace_existing = True, preserve_attrs = False, follow_symlinks = True }
              match read_text { path = "$b" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("from-a", eval(code))
    }

    @Test
    fun `LocalFileSystem copy_file refuses an existing destination by default`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toString()
        val b = tmp.resolve("b").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$a", text = "from-a" }
              let _ = write_text { path = "$b", text = "from-b" }
              match FileSystem.copy_file { src = "$a", dest = "$b", replace_existing = False, preserve_attrs = False, follow_symlinks = True } is
                Ok { val = _ } -> "replaced"
                Err { error = FsAlreadyExists { path = _ } } -> "refused"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    /**
     * `preserve_attrs` carries the mode over. Without it the copy gets the
     * backend's default mode, which is the behaviour `cp` has without `-p`.
     */
    @Test
    fun `MemoryFileSystem copy_file preserve_attrs carries the mode`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, MemoryFileSystem, MemoryFilePermissions, write_text }
            use std::bool
            perform MemoryFileSystem in perform MemoryFilePermissions in do
              let _ = write_text { path = "/a", text = "x" }
              let _ = FilePermissions.set_mode { path = "/a", mode = { owner_read = True, owner_write = True, owner_exec = True, group_read = False, group_write = False, group_exec = False, other_read = False, other_write = False, other_exec = False } }
              let _ = FileSystem.copy_file { src = "/a", dest = "/b", replace_existing = False, preserve_attrs = True, follow_symlinks = True }
              match FilePermissions.get_mode { path = "/b" } is
                Ok { val = m } -> "$(bool::to_str { b = m.owner_exec })-$(bool::to_str { b = m.group_read })"
                Err { error = _ } -> "get-mode-failed"
        """.trimIndent()
        assertEquals("True-False", eval(code))
    }

    // ==================== move_file options ====================

    @Test
    fun `MemoryFileSystem move_file refuses an existing destination by default`() {
        val code = """
            $memHead
            perform MemoryFileSystem in do
              let _ = write_text { path = "/a", text = "from-a" }
              let _ = write_text { path = "/b", text = "from-b" }
              match FileSystem.move_file { src = "/a", dest = "/b", replace_existing = False, require_atomic = False } is
                Ok { val = _ } -> "replaced"
                Err { error = FsAlreadyExists { path = _ } } -> "refused"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    @Test
    fun `MemoryFileSystem move_file replace_existing overwrites and removes the source`() {
        val code = """
            $memHead
            use std::bool
            use std::effects::filesystem::{ exists }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/a", text = "from-a" }
              let _ = write_text { path = "/b", text = "from-b" }
              let _ = FileSystem.move_file { src = "/a", dest = "/b", replace_existing = True, require_atomic = False }
              match read_text { path = "/b" } is
                Ok { val = t } -> match exists { path = "/a" } is
                  Ok { val = still } -> "$(t)-$(bool::to_str { b = still })"
                  Err { error = _ } -> "exists-failed"
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("from-a-False", eval(code))
    }

    @Test
    fun `LocalFileSystem move_file replace_existing overwrites`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toString()
        val b = tmp.resolve("b").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$a", text = "from-a" }
              let _ = write_text { path = "$b", text = "from-b" }
              let _ = FileSystem.move_file { src = "$a", dest = "$b", replace_existing = True, require_atomic = False }
              match read_text { path = "$b" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("from-a", eval(code))
    }

    /**
     * A same-filesystem rename IS atomic, so asking for it must succeed.
     * (The interesting case — asking for atomic across filesystems and
     * getting `FsNotSupported` instead of a silent downgrade — needs two
     * real mounts, so it is not testable here. The non-atomic fallback path
     * is still reached and still works, which is what the default covers.)
     */
    @Test
    fun `LocalFileSystem move_file require_atomic succeeds within one filesystem`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toString()
        val b = tmp.resolve("b").toString()
        val code = """
            $localHead
            perform LocalFileSystem in do
              let _ = write_text { path = "$a", text = "payload" }
              match FileSystem.move_file { src = "$a", dest = "$b", replace_existing = False, require_atomic = True } is
                Ok { val = _ } -> match read_text { path = "$b" } is
                  Ok { val } -> val
                  Err { error = _ } -> "read-failed"
                Err { error = _ } -> "move-failed"
        """.trimIndent()
        assertEquals("payload", eval(code))
    }

    // ==================== stat follow_symlinks ====================

    /**
     * The distinction that justifies the flag: for a DANGLING symlink,
     * following reports `NotFound` — indistinguishable from "no such path" —
     * while not following reports `Other`, which says "something is here,
     * and it is not a file or a directory".
     */
    @Test
    fun `LocalFileSystem stat tells a dangling symlink from a missing path`(@TempDir tmp: Path) {
        val link = tmp.resolve("link").toString()
        val missing = tmp.resolve("nowhere").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, LocalFileSystem, LocalFileSymlinks }
            perform LocalFileSystem in perform LocalFileSymlinks in do
              let _ = FileSymlinks.create_symlink { link = "$link", target = "$missing" }
              match FileSystem.stat { path = "$link", follow_symlinks = False } is
                Ok { val = unfollowed } -> match FileSystem.stat { path = "$link", follow_symlinks = True } is
                  Ok { val = followed } -> match unfollowed.kind is
                    Other -> match followed.kind is
                      NotFound -> "other-then-notfound"
                      _ -> "followed-wrong"
                    _ -> "unfollowed-wrong"
                  Err { error = _ } -> "followed-errored"
                Err { error = _ } -> "unfollowed-errored"
        """.trimIndent()
        assertEquals("other-then-notfound", eval(code))
    }

    @Test
    fun `MemoryFileSystem stat tells a dangling symlink from a missing path`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks }
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSymlinks.create_symlink { link = "/link", target = "/nowhere" }
              match FileSystem.stat { path = "/link", follow_symlinks = False } is
                Ok { val = unfollowed } -> match FileSystem.stat { path = "/link", follow_symlinks = True } is
                  Ok { val = followed } -> match unfollowed.kind is
                    Other -> match followed.kind is
                      NotFound -> "other-then-notfound"
                      _ -> "followed-wrong"
                    _ -> "unfollowed-wrong"
                  Err { error = _ } -> "followed-errored"
                Err { error = _ } -> "unfollowed-errored"
        """.trimIndent()
        assertEquals("other-then-notfound", eval(code))
    }

    /** Following a LIVE symlink reports on its target, including the size. */
    @Test
    fun `LocalFileSystem stat follows a live symlink to its target`(@TempDir tmp: Path) {
        val target = tmp.resolve("target").toString()
        val link = tmp.resolve("link").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, LocalFileSystem, LocalFileSymlinks, write_text }
            perform LocalFileSystem in perform LocalFileSymlinks in do
              let _ = write_text { path = "$target", text = "12345" }
              let _ = FileSymlinks.create_symlink { link = "$link", target = "$target" }
              match FileSystem.stat { path = "$link", follow_symlinks = True } is
                Ok { val = s } -> s.size
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    /** And `exists` follows, so it still answers about the target. */
    @Test
    fun `MemoryFileSystem exists follows a symlink`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, write_text, exists }
            use std::bool
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = write_text { path = "/real", text = "x" }
              let _ = FileSymlinks.create_symlink { link = "/live", target = "/real" }
              let _ = FileSymlinks.create_symlink { link = "/dead", target = "/gone" }
              match exists { path = "/live" } is
                Ok { val = a } -> match exists { path = "/dead" } is
                  Ok { val = b } -> "$(bool::to_str { b = a })-$(bool::to_str { b = b })"
                  Err { error = _ } -> "dead-errored"
                Err { error = _ } -> "live-errored"
        """.trimIndent()
        assertEquals("True-False", eval(code))
    }
}
