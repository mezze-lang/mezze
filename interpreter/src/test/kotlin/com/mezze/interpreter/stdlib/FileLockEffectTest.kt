package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `FileLock` — whole-file advisory locking as its own effect.
 *
 * The point of these tests is that contention is OBSERVABLE. MemoryFileSystem could
 * have reported success unconditionally — one program rarely contends with
 * itself — but then "a lock excludes someone" would be untestable without a
 * second process, and a backend that always succeeds is the kind of lie the
 * capability split exists to prevent. So memfs keeps a real holder count:
 * shared locks stack, exclusive excludes everything.
 */
class FileLockEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    // ==================== MemoryFileSystem ====================

    @Test
    fun `MemoryFileSystem exclusive lock then unlock`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = h } -> match FileLock.unlock { handle = h } is
                  Ok { ... } -> "locked-unlocked"
                  Err { ... } -> "unlock-failed"
                Err { ... } -> "lock-failed"
        """.trimIndent()
        assertEquals("locked-unlocked", eval(code))
    }

    /** The one that matters: a second exclusive acquire must be refused. */
    @Test
    fun `MemoryFileSystem exclusive lock excludes a second acquire`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { ... } ->
                  match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                    Ok { ... } -> "second-acquired"
                    Err { error = FsPermissionDenied { ... } } -> "excluded"
                    Err { ... } -> "other-error"
                Err { ... } -> "first-failed"
        """.trimIndent()
        assertEquals("excluded", eval(code))
    }

    /** Releasing must actually free it, or the count is wrong. */
    @Test
    fun `MemoryFileSystem unlock lets the next acquire succeed`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = h } -> do
                  FileLock.unlock { handle = h }
                  match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                    Ok { ... } -> "reacquired"
                    Err { ... } -> "still-held"
                Err { ... } -> "first-failed"
        """.trimIndent()
        assertEquals("reacquired", eval(code))
    }

    @Test
    fun `MemoryFileSystem shared locks stack and exclude an exclusive`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                Ok { ... } ->
                  match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                    Ok { ... } ->
                      match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                        Ok { ... } -> "exclusive-got-in"
                        Err { ... } -> "two-shared-one-excluded"
                    Err { ... } -> "second-shared-refused"
                Err { ... } -> "first-shared-refused"
        """.trimIndent()
        assertEquals("two-shared-one-excluded", eval(code))
    }

    /** A shared hold must be decremented, not cleared, by one unlock. */
    @Test
    fun `MemoryFileSystem one unlock does not release both shared holds`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                Ok { val = h1 } ->
                  match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                    Ok { ... } -> do
                      FileLock.unlock { handle = h1 }
                      match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                        Ok { ... } -> "released-too-much"
                        Err { ... } -> "still-held-by-second"
                    Err { ... } -> "second-shared-refused"
                Err { ... } -> "first-shared-refused"
        """.trimIndent()
        assertEquals("still-held-by-second", eval(code))
    }

    /** Blocking on a held lock can only deadlock in one context — refuse it. */
    @Test
    fun `MemoryFileSystem blocking acquire on a held lock reports rather than hanging`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { ... } ->
                  match FileLock.lock { path = "/l", kind = Exclusive, blocking = True } is
                    Ok { ... } -> "unexpectedly-acquired"
                    Err { error = FsIOError { ... } } -> "refused"
                    Err { ... } -> "other-error"
                Err { ... } -> "first-failed"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    @Test
    fun `MemoryFileSystem unlock is idempotent`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, LockKind }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = h } -> do
                  FileLock.unlock { handle = h }
                  match FileLock.unlock { handle = h } is
                    Ok { ... } -> "idempotent"
                    Err { ... } -> "second-unlock-failed"
                Err { ... } -> "lock-failed"
        """.trimIndent()
        assertEquals("idempotent", eval(code))
    }

    // ==================== LocalFileSystem ====================

    @Test
    fun `LocalFileSystem exclusive lock creates the lockfile and releases`(@TempDir tmp: Path) {
        val path = tmp.resolve("app.lock").toString()
        val code = """
            use std::effects::filesystem::{ FileLock, LocalFileLock, LockKind }
            perform LocalFileLock in
              match FileLock.lock { path = "$path", kind = Exclusive, blocking = False } is
                Ok { val = h } -> match FileLock.unlock { handle = h } is
                  Ok { ... } -> "ok"
                  Err { ... } -> "unlock-failed"
                Err { ... } -> "lock-failed"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    /**
     * A second acquire in the SAME JVM raises OverlappingFileLockException
     * rather than failing at the OS level. Indistinguishable to the caller,
     * and it must not escape as a host exception.
     */
    @Test
    fun `LocalFileSystem second acquire in one process is refused, not thrown`(@TempDir tmp: Path) {
        val path = tmp.resolve("app.lock").toString()
        val code = """
            use std::effects::filesystem::{ FileLock, LocalFileLock, LockKind }
            perform LocalFileLock in
              match FileLock.lock { path = "$path", kind = Exclusive, blocking = False } is
                Ok { ... } ->
                  match FileLock.lock { path = "$path", kind = Exclusive, blocking = False } is
                    Ok { ... } -> "second-acquired"
                    Err { error = FsPermissionDenied { ... } } -> "excluded"
                    Err { ... } -> "other-error"
                Err { ... } -> "first-failed"
        """.trimIndent()
        assertEquals("excluded", eval(code))
    }

    // A leaked lock IS released when its context is disposed — see
    // OpenResources in FilesystemNatives. There is deliberately no test for
    // it here, because a lock cannot witness the fix: drop the last reference
    // to the channel and FileDescriptor's Cleaner closes the fd on the next
    // GC, which releases the lock whether dispose did or not. A test for it
    // passes with the registry removed, which makes it worse than no test.
    // The leak is pinned where GC cannot mask it — on the WatchService thread,
    // in FileWatchEffectTest.
}
