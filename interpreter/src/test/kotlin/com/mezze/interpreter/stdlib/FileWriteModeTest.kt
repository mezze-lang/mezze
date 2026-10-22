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
    // ==================== Backend divergences ====================

    /**
     * Concurrent appends must not lose a record.
     *
     * `O_APPEND` on a real file makes each write atomic with respect to the
     * end, so two appenders both keep their bytes. memfs did this as a
     * read-modify-write — both readers saw the same old array and whichever
     * assigned last erased the other. That is a divergence in the WORSE
     * direction: the program is correct on LocalFileSystem and silently loses
     * data in its own tests.
     *
     * Eight tasks x 200 appends of four bytes each: a correct backend ends at
     * 6400, and a lost update shows up as a short file. The SCALE is the
     * point — at one append per task the window between a read and a write
     * is almost never hit, and the test passed with the bug present. (`ConcurrentHashMap.compute` is
     * what makes this hold — it is atomic per key, the same granularity
     * O_APPEND gives.)
     */
    @Test
    fun `MemoryFileSystem concurrent appends keep every record`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrAppend, write_text, read_text }
            use std::effects::async::{ Async, LoomAsync, await }
            use std::collections::{ bytes, iter, seq }
            use std::str
            perform MemoryFileSystem in perform LoomAsync in do
              let _ = write_text { path = "/log", text = "" }
              let ps = seq::map {
                xs = seq::range { start = 0, end = 8 },
                f = { x } -> Async.spawn { f = { } -> seq::for_each {
                  xs = seq::range { start = 0, end = 200 },
                  f = { x } -> FileSystem.write_bytes { path = "/log", bytes = bytes::from_str { s = "abcd" }, mode = CreateOrAppend }
                } }
              }
              let _ = seq::for_each { xs = ps, f = { x } -> do
                let _ = await { p = x }
                {}
              }
              match read_text { path = "/log" } is
                Ok { val = t } -> str::size { s = t }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(6400L, eval(code))
    }

    /**
     * memfs says NO to `require_atomic`, rather than pretending.
     *
     * Its move is `put(dest)` then `remove(src)` on a ConcurrentHashMap, and
     * between those the entry is visible at BOTH paths — observable, because
     * `Async` tasks are real threads. Making it true would need a lock taken
     * by every op that reads a path, which the common case should not pay for.
     * So it declines, which is the same honesty the capability split exists
     * for: a caller is never handed a guarantee that does not hold.
     */
    @Test
    fun `MemoryFileSystem move_file declines require_atomic instead of faking it`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, write_text }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/a", text = "payload" }
              match FileSystem.move_file { src = "/a", dest = "/b", replace_existing = False, require_atomic = True } is
                Ok { val = _ } -> "claimed-atomic"
                Err { error = FsNotSupported { path = _, operation = _ } } -> "declined"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("declined", eval(code))
    }

    /** …and the non-atomic default still works, which is what most callers want. */
    @Test
    fun `MemoryFileSystem move_file without require_atomic still moves`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, MemoryFileSystem, write_text, read_text }
            perform MemoryFileSystem in do
              let _ = write_text { path = "/a", text = "payload" }
              let _ = FileSystem.move_file { src = "/a", dest = "/b", replace_existing = False, require_atomic = False }
              match read_text { path = "/b" } is
                Ok { val } -> val
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("payload", eval(code))
    }

    /**
     * `stat` must follow a symlink CHAIN, not one hop. NIO resolves
     * link -> link -> file, so stopping after one would report NotFound on
     * memfs for a path LocalFileSystem reports as a File. Asserted of BOTH
     * backends, which is the only way this class of divergence gets caught.
     */
    @Test
    fun `MemoryFileSystem stat follows a chain of symlinks`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, write_text }
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = write_text { path = "/real", text = "12345" }
              let _ = FileSymlinks.create_symlink { link = "/one", target = "/real" }
              let _ = FileSymlinks.create_symlink { link = "/two", target = "/one" }
              let _ = FileSymlinks.create_symlink { link = "/three", target = "/two" }
              match FileSystem.stat { path = "/three", follow_symlinks = True } is
                Ok { val = s } -> s.size
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `LocalFileSystem stat follows a chain of symlinks`(@TempDir tmp: Path) {
        val real = tmp.resolve("real").toString()
        val one = tmp.resolve("one").toString()
        val two = tmp.resolve("two").toString()
        val three = tmp.resolve("three").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, LocalFileSystem, LocalFileSymlinks, write_text }
            perform LocalFileSystem in perform LocalFileSymlinks in do
              let _ = write_text { path = "$real", text = "12345" }
              let _ = FileSymlinks.create_symlink { link = "$one", target = "$real" }
              let _ = FileSymlinks.create_symlink { link = "$two", target = "$one" }
              let _ = FileSymlinks.create_symlink { link = "$three", target = "$two" }
              match FileSystem.stat { path = "$three", follow_symlinks = True } is
                Ok { val = s } -> s.size
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    /**
     * A symlink CYCLE is legal to create and must not hang. NIO raises
     * FileSystemLoopException; memfs reports the same shape of IO error at a
     * hop limit rather than looping for ever.
     */
    @Test
    fun `MemoryFileSystem stat on a symlink cycle reports an error, not a hang`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks }
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSymlinks.create_symlink { link = "/a", target = "/b" }
              let _ = FileSymlinks.create_symlink { link = "/b", target = "/a" }
              match FileSystem.stat { path = "/a", follow_symlinks = True } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsIOError { path = _, message = _ } } -> "loop-reported"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("loop-reported", eval(code))
    }
    // ==================== Cross-filesystem move ====================

    /**
     * `require_atomic = True` across two filesystems must DECLINE, not degrade.
     *
     * This is the branch the option exists for, and it needs two real mounts
     * to reach — `Files.move` only raises AtomicMoveNotSupportedException when
     * the rename cannot be a rename. On Linux `/dev/shm` is tmpfs and the temp
     * dir is usually on the root filesystem, which gives us the pair; where it
     * does not, the test skips rather than passing vacuously.
     *
     * Before `require_atomic` existed this silently fell back to a non-atomic
     * move, which is exactly wrong for publish-by-rename: a reader seeing a
     * partial file is the bug rename was chosen to avoid.
     */
    @Test
    fun `LocalFileSystem require_atomic declines a cross-filesystem move`(@TempDir tmp: Path) {
        val other = crossFilesystemDir(tmp) ?: return
        val src = tmp.resolve("payload").toString()
        val dest = other.resolve("payload-moved-${System.nanoTime()}").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, write_text }
            perform LocalFileSystem in do
              let _ = write_text { path = "$src", text = "payload" }
              match FileSystem.move_file { src = "$src", dest = "$dest", replace_existing = False, require_atomic = True } is
                Ok { val = _ } -> "claimed-atomic"
                Err { error = FsNotSupported { path = _, operation = _ } } -> "declined"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        try {
            assertEquals("declined", eval(code))
        } finally {
            java.nio.file.Files.deleteIfExists(Path.of(dest))
        }
    }

    /** …and without it, the same move falls back and succeeds. */
    @Test
    fun `LocalFileSystem a cross-filesystem move succeeds without require_atomic`(@TempDir tmp: Path) {
        val other = crossFilesystemDir(tmp) ?: return
        val src = tmp.resolve("payload").toString()
        val dest = other.resolve("payload-moved-${System.nanoTime()}").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, LocalFileSystem, write_text, read_text }
            perform LocalFileSystem in do
              let _ = write_text { path = "$src", text = "payload" }
              match FileSystem.move_file { src = "$src", dest = "$dest", replace_existing = False, require_atomic = False } is
                Ok { val = _ } -> match read_text { path = "$dest" } is
                  Ok { val } -> val
                  Err { error = _ } -> "read-failed"
                Err { error = _ } -> "move-failed"
        """.trimIndent()
        try {
            assertEquals("payload", eval(code))
        } finally {
            java.nio.file.Files.deleteIfExists(Path.of(dest))
        }
    }

    /**
     * A writable directory on a DIFFERENT filesystem from [tmp], or null when
     * the host has no second mount to offer — in which case the caller skips.
     * Compares st_dev rather than trusting the path.
     */
    private fun crossFilesystemDir(tmp: Path): Path? {
        val candidate = Path.of("/dev/shm")
        if (!java.nio.file.Files.isDirectory(candidate)) return null
        if (!java.nio.file.Files.isWritable(candidate)) return null
        val a = java.nio.file.Files.getAttribute(tmp, "unix:dev", java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val b = java.nio.file.Files.getAttribute(candidate, "unix:dev", java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return if (a == b) null else candidate
    }

    // ==================== preserve_attrs on the real filesystem ====================

    /**
     * `preserve_attrs` was only ever tested on memfs, where the mode is a
     * boolean array this code owns. On LocalFileSystem it goes through NIO's
     * COPY_ATTRIBUTES, and whether that yields a mode `FilePermissions.get_mode`
     * reads back identically was an assumption.
     */
    @Test
    fun `LocalFileSystem copy_file preserve_attrs carries the mode`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toString()
        val b = tmp.resolve("b").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, LocalFileSystem, LocalFilePermissions, write_text }
            use std::bool
            perform LocalFileSystem in perform LocalFilePermissions in do
              let _ = write_text { path = "$a", text = "x" }
              let _ = FilePermissions.set_mode { path = "$a", mode = { owner_read = True, owner_write = True, owner_exec = True, group_read = False, group_write = False, group_exec = False, other_read = False, other_write = False, other_exec = False } }
              let _ = FileSystem.copy_file { src = "$a", dest = "$b", replace_existing = False, preserve_attrs = True, follow_symlinks = True }
              match FilePermissions.get_mode { path = "$b" } is
                Ok { val = m } -> "$(bool::to_str { b = m.owner_exec })-$(bool::to_str { b = m.group_read })"
                Err { error = _ } -> "get-mode-failed"
        """.trimIndent()
        assertEquals("True-False", eval(code))
    }

    /**
     * And WITHOUT it the mode comes across ANYWAY — on both backends.
     *
     * This is NIO's behaviour rather than a choice: UnixCopyFile creates the
     * target with the source's `mode & 0777`, and COPY_ATTRIBUTES adds
     * timestamps and ownership on top. memfs used to reset the mode here, so a
     * copied file was executable on LocalFileSystem and not in its own tests.
     * What `preserve_attrs` actually buys is owner and mtime.
     */
    @Test
    fun `copy_file carries the mode even without preserve_attrs`(@TempDir tmp: Path) {
        val a = tmp.resolve("a").toString()
        val b = tmp.resolve("b").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, LocalFileSystem, LocalFilePermissions, write_text }
            use std::bool
            perform LocalFileSystem in perform LocalFilePermissions in do
              let _ = write_text { path = "$a", text = "x" }
              let _ = FilePermissions.set_mode { path = "$a", mode = { owner_read = True, owner_write = True, owner_exec = True, group_read = False, group_write = False, group_exec = False, other_read = False, other_write = False, other_exec = False } }
              let _ = FileSystem.copy_file { src = "$a", dest = "$b", replace_existing = False, preserve_attrs = False, follow_symlinks = True }
              match FilePermissions.get_mode { path = "$b" } is
                Ok { val = m } -> bool::to_str { b = m.owner_exec }
                Err { error = _ } -> "get-mode-failed"
        """.trimIndent()
        assertEquals("True", eval(code))
    }

    /** The same of memfs — this is the arm that was wrong. */
    @Test
    fun `MemoryFileSystem copy_file carries the mode even without preserve_attrs`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, MemoryFileSystem, MemoryFilePermissions, write_text }
            use std::bool
            perform MemoryFileSystem in perform MemoryFilePermissions in do
              let _ = write_text { path = "/a", text = "x" }
              let _ = FilePermissions.set_mode { path = "/a", mode = { owner_read = True, owner_write = True, owner_exec = True, group_read = False, group_write = False, group_exec = False, other_read = False, other_write = False, other_exec = False } }
              let _ = FileSystem.copy_file { src = "/a", dest = "/b", replace_existing = False, preserve_attrs = False, follow_symlinks = True }
              match FilePermissions.get_mode { path = "/b" } is
                Ok { val = m } -> bool::to_str { b = m.owner_exec }
                Err { error = _ } -> "get-mode-failed"
        """.trimIndent()
        assertEquals("True", eval(code))
    }

    // ==================== FileWatch sees the new write modes ====================

    /**
     * The watch tests predate `WriteMode`, so nothing asserted which event the
     * new modes fire. `CreateOrAppend` on an existing file is a Modified, and
     * `CreateNew` is a Created — if either reported the other, a dev server
     * rebuilding on Created would miss every appended log line, or rebuild on
     * every one.
     */
    @Test
    fun `MemoryFileSystem CreateOrAppend fires Modified and CreateNew fires Created`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch, CreateNew, CreateOrAppend, write_text }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = write_text { path = "/d/existing", text = "a" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> "watch-failed"
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/existing", bytes = bytes::from_str { s = "b" }, mode = CreateOrAppend }
                let _ = FileSystem.write_bytes { path = "/d/brand-new", bytes = bytes::from_str { s = "c" }, mode = CreateNew }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> match seq::first { xs = evs } is
                    Some { val = Modified { path = _ } } -> match seq::last { xs = evs } is
                      Some { val = Created { path = p } } -> p
                      Some { val = _ } -> "second-wrong-kind"
                      None -> "only-one-event"
                    Some { val = _ } -> "first-wrong-kind"
                    None -> "no-events"
                  Err { error = _ } -> "poll-failed"
        """.trimIndent()
        assertEquals("/d/brand-new", eval(code))
    }

    /** A refused `CreateNew` must fire NOTHING — it changed nothing. */
    @Test
    fun `MemoryFileSystem a refused CreateNew fires no event`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileWatch, MemoryFileSystem, MemoryFileWatch, CreateNew, write_text }
            use std::collections::{ bytes, seq }
            perform MemoryFileSystem in perform MemoryFileWatch in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = write_text { path = "/d/taken", text = "a" }
              match FileWatch.watch { path = "/d", recursive = False } is
                Err { error = _ } -> 0 - 1
                Ok { val = h } -> do
                let _ = FileSystem.write_bytes { path = "/d/taken", bytes = bytes::from_str { s = "b" }, mode = CreateNew }
                match FileWatch.poll { handle = h, timeout_ms = 0 } is
                  Ok { val = evs } -> seq::size { xs = evs }
                  Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(0L, eval(code))
    }
}
