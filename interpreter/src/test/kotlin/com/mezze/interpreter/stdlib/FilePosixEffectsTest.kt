package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `FileSymlinks` and `FilePermissions` — the two POSIX-only effects.
 *
 * Both are admitted as POSIX-only rather than faked: a non-POSIX volume or
 * an object store declines the whole effect, and the odd op that a backend
 * cannot do returns `FsNotSupported` instead of inventing an answer.
 *
 * MemoryFileSystem RECORDS this metadata rather than ENFORCING it. `set_mode`
 * stores what you asked for and nothing checks it before a read, because
 * enforcement is a kernel's job; the backend exists so a test can assert
 * the code set what it meant to set.
 */
class FilePosixEffectsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== Symlinks — MemoryFileSystem ====================

    @Test
    fun `MemoryFileSystem symlink roundtrips its target`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/real", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FileSymlinks.create_symlink { link = "/d/link", target = "/d/real" }
              match FileSymlinks.read_symlink { path = "/d/link" } is
                Ok { val = t } -> t
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("/d/real", eval(code))
    }

    /** A dangling symlink is legal — it is how a blue/green swap stages. */
    @Test
    fun `MemoryFileSystem allows a dangling symlink`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, CreateOrTruncate }
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileSymlinks.create_symlink { link = "/d/next", target = "/d/not-yet" } is
                Ok { val = _ } -> match FileSymlinks.is_symlink { path = "/d/next" } is
                  Ok { val = b } -> if b then "dangling-ok" else "not-a-link"
                  Err { error = _ } -> "is-symlink-failed"
                Err { error = _ } -> "create-failed"
        """.trimIndent()
        assertEquals("dangling-ok", eval(code))
    }

    @Test
    fun `MemoryFileSystem read_symlink on a regular file is an error`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/plain", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              match FileSymlinks.read_symlink { path = "/d/plain" } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsIOError { path = _, message = _ } } -> "not-a-link"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-a-link", eval(code))
    }

    /** A hard link shares the entry, so a later write is visible via both. */
    @Test
    fun `MemoryFileSystem hardlink shares content with its target`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/a", bytes = bytes::from_str { s = "shared" }, mode = CreateOrTruncate
              }
              let _ = FileSymlinks.create_hardlink { link = "/d/b", target = "/d/a" }
              match FileSystem.read_bytes { path = "/d/b" } is
                Ok { val = b } -> match bytes::to_str { b = b } is
                  Ok { val } -> val
                  Err { error = _ } -> "utf8-fail"
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals("shared", eval(code))
    }

    @Test
    fun `MemoryFileSystem hardlink to a missing target reports NotFound`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, MemoryFileSystem, MemoryFileSymlinks, CreateOrTruncate }
            perform MemoryFileSystem in perform MemoryFileSymlinks in do
              let _ = FileSystem.create_directory { path = "/d" }
              match FileSymlinks.create_hardlink { link = "/d/b", target = "/d/nope" } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsNotFound { path = _ } } -> "not-found"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    /** A symlink cycle must error, not hang the suite. */
    @Test
    fun `MemoryFileSystem real_path refuses a symlink cycle`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, FilePermissions,
                                            MemoryFileSystem, MemoryFileSymlinks, MemoryFilePermissions, CreateOrTruncate }
            perform MemoryFileSystem in perform MemoryFileSymlinks in perform MemoryFilePermissions in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSymlinks.create_symlink { link = "/d/a", target = "/d/b" }
              let _ = FileSymlinks.create_symlink { link = "/d/b", target = "/d/a" }
              match FilePermissions.real_path { path = "/d/a", follow_symlinks = True } is
                Ok { val = _ } -> "unexpectedly-resolved"
                Err { error = _ } -> "cycle-refused"
        """.trimIndent()
        assertEquals("cycle-refused", eval(code))
    }

    // ==================== Permissions — MemoryFileSystem ====================

    @Test
    fun `MemoryFileSystem set_mode then get_mode roundtrips the bits`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, MemoryFileSystem, MemoryFilePermissions, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFilePermissions in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/secret", bytes = bytes::from_str { s = "s" }, mode = CreateOrTruncate
              }
              # 0600 — the mode a deployed secret should have.
              let _ = FilePermissions.set_mode { path = "/d/secret", mode = { owner_read = True, owner_write = True, owner_exec = False, group_read = False, group_write = False, group_exec = False, other_read = False, other_write = False, other_exec = False } }
              match FilePermissions.get_mode { path = "/d/secret" } is
                Ok { val = m } -> "$(m.owner_read)$(m.owner_write)$(m.group_read)$(m.other_read)"
                Err { error = _ } -> "get-failed"
        """.trimIndent()
        assertEquals("truetruefalsefalse", eval(code))
    }

    @Test
    fun `MemoryFileSystem set_owner then get_owner roundtrips`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, MemoryFileSystem, MemoryFilePermissions, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFilePermissions in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/f", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FilePermissions.set_owner {
                path = "/d/f", owner = { user = "deploy", group = "web" }
              }
              match FilePermissions.get_owner { path = "/d/f" } is
                Ok { val = o } -> "$(o.user):$(o.group)"
                Err { error = _ } -> "get-failed"
        """.trimIndent()
        assertEquals("deploy:web", eval(code))
    }

    @Test
    fun `MemoryFileSystem set_modified is visible through stat`() {
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, MemoryFileSystem, MemoryFilePermissions, CreateOrTruncate }
            use std::collections::bytes
            perform MemoryFileSystem in perform MemoryFilePermissions in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = FileSystem.write_bytes {
                path = "/d/f", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FilePermissions.set_modified { path = "/d/f", millis = 1234567 }
              match FileSystem.stat { path = "/d/f", follow_symlinks = True } is
                Ok { val = s } -> s.modified
                Err { error = _ } -> 0
        """.trimIndent()
        assertEquals(1234567L, eval(code))
    }

    @Test
    fun `MemoryFileSystem get_mode on a missing path reports NotFound`() {
        val code = """
            use std::effects::filesystem::{ FilePermissions, MemoryFileSystem, MemoryFilePermissions, CreateOrTruncate }
            perform MemoryFileSystem in perform MemoryFilePermissions in
              match FilePermissions.get_mode { path = "/nope" } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsNotFound { path = _ } } -> "not-found"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    // ==================== LocalFileSystem ====================

    @Test
    fun `LocalFileSystem symlink roundtrips and is_symlink agrees`(@TempDir tmp: Path) {
        val real = tmp.resolve("real").toString()
        val link = tmp.resolve("link").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, LocalFileSystem, LocalFileSymlinks, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in perform LocalFileSymlinks in do
              let _ = FileSystem.write_bytes {
                path = "$real", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FileSymlinks.create_symlink { link = "$link", target = "$real" }
              match FileSymlinks.read_symlink { path = "$link" } is
                Ok { val = t } -> match FileSymlinks.is_symlink { path = "$link" } is
                  Ok { val = b } -> if b then t else "not-a-link"
                  Err { error = _ } -> "is-symlink-failed"
                Err { error = _ } -> "read-failed"
        """.trimIndent()
        assertEquals(real, eval(code))
    }

    @Test
    fun `LocalFileSystem is_symlink on a missing path reports NotFound`(@TempDir tmp: Path) {
        val path = tmp.resolve("absent").toString()
        val code = """
            use std::effects::filesystem::{ FileSymlinks, LocalFileSymlinks, CreateOrTruncate }
            perform LocalFileSymlinks in
              match FileSymlinks.is_symlink { path = "$path" } is
                Ok { val = _ } -> "unexpectedly-ok"
                Err { error = FsNotFound { path = _ } } -> "not-found"
                Err { error = _ } -> "other-error"
        """.trimIndent()
        assertEquals("not-found", eval(code))
    }

    @Test
    fun `LocalFileSystem set_mode then get_mode roundtrips on a real file`(@TempDir tmp: Path) {
        val path = tmp.resolve("secret").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, LocalFileSystem, LocalFilePermissions, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in perform LocalFilePermissions in do
              let _ = FileSystem.write_bytes {
                path = "$path", bytes = bytes::from_str { s = "s" }, mode = CreateOrTruncate
              }
              let _ = FilePermissions.set_mode { path = "$path", mode = { owner_read = True, owner_write = True, owner_exec = False, group_read = False, group_write = False, group_exec = False, other_read = False, other_write = False, other_exec = False } }
              match FilePermissions.get_mode { path = "$path" } is
                Ok { val = m } -> "$(m.owner_read)$(m.owner_write)$(m.group_read)$(m.other_read)"
                Err { error = _ } -> "get-failed"
        """.trimIndent()
        assertEquals("truetruefalsefalse", eval(code))
    }

    @Test
    fun `LocalFileSystem real_path resolves a symlink`(@TempDir tmp: Path) {
        val real = tmp.resolve("real").toString()
        val link = tmp.resolve("link").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FileSymlinks, FilePermissions,
                                            LocalFileSystem, LocalFileSymlinks, LocalFilePermissions, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in perform LocalFileSymlinks in perform LocalFilePermissions in do
              let _ = FileSystem.write_bytes {
                path = "$real", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FileSymlinks.create_symlink { link = "$link", target = "$real" }
              match FilePermissions.real_path { path = "$link", follow_symlinks = True } is
                Ok { val = p } -> if p == "$real" then "resolved" else "unexpected: $(p)"
                Err { error = _ } -> "real-path-failed"
        """.trimIndent()
        assertEquals("resolved", eval(code))
    }

    @Test
    fun `LocalFileSystem set_modified is visible through stat`(@TempDir tmp: Path) {
        val path = tmp.resolve("f").toString()
        val code = """
            use std::effects::filesystem::{ FileSystem, FilePermissions, LocalFileSystem, LocalFilePermissions, CreateOrTruncate }
            use std::collections::bytes
            perform LocalFileSystem in perform LocalFilePermissions in do
              let _ = FileSystem.write_bytes {
                path = "$path", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate
              }
              let _ = FilePermissions.set_modified { path = "$path", millis = 1700000000000 }
              match FileSystem.stat { path = "$path", follow_symlinks = True } is
                Ok { val = s } -> s.modified
                Err { error = _ } -> 0
        """.trimIndent()
        assertEquals(1700000000000L, eval(code))
    }
}
