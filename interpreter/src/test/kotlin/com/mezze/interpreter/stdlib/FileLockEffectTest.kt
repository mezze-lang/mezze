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
 * The point of these tests is that contention is OBSERVABLE. MemoryFS could
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

    // ==================== MemoryFS ====================

    @Test
    fun `MemoryFS exclusive lock then unlock`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = h } -> match FileLock.unlock { handle = h } is
                  Ok { val = _ } -> "locked-unlocked"
                  Err { error = _ } -> "unlock-failed"
                Err { error = _ } -> "lock-failed"
        """.trimIndent()
        assertEquals("locked-unlocked", eval(code))
    }

    /** The one that matters: a second exclusive acquire must be refused. */
    @Test
    fun `MemoryFS exclusive lock excludes a second acquire`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = _ } ->
                  match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                    Ok { val = _ } -> "second-acquired"
                    Err { error = FsPermissionDenied { path = _, operation = _ } } -> "excluded"
                    Err { error = _ } -> "other-error"
                Err { error = _ } -> "first-failed"
        """.trimIndent()
        assertEquals("excluded", eval(code))
    }

    /** Releasing must actually free it, or the count is wrong. */
    @Test
    fun `MemoryFS unlock lets the next acquire succeed`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = h } -> do
                  let _ = FileLock.unlock { handle = h }
                  match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                    Ok { val = _ } -> "reacquired"
                    Err { error = _ } -> "still-held"
                Err { error = _ } -> "first-failed"
        """.trimIndent()
        assertEquals("reacquired", eval(code))
    }

    @Test
    fun `MemoryFS shared locks stack and exclude an exclusive`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Shared, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                Ok { val = _ } ->
                  match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                    Ok { val = _ } ->
                      match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                        Ok { val = _ } -> "exclusive-got-in"
                        Err { error = _ } -> "two-shared-one-excluded"
                    Err { error = _ } -> "second-shared-refused"
                Err { error = _ } -> "first-shared-refused"
        """.trimIndent()
        assertEquals("two-shared-one-excluded", eval(code))
    }

    /** A shared hold must be decremented, not cleared, by one unlock. */
    @Test
    fun `MemoryFS one unlock does not release both shared holds`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Shared, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                Ok { val = h1 } ->
                  match FileLock.lock { path = "/l", kind = Shared, blocking = False } is
                    Ok { val = _ } -> do
                      let _ = FileLock.unlock { handle = h1 }
                      match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                        Ok { val = _ } -> "released-too-much"
                        Err { error = _ } -> "still-held-by-second"
                    Err { error = _ } -> "second-shared-refused"
                Err { error = _ } -> "first-shared-refused"
        """.trimIndent()
        assertEquals("still-held-by-second", eval(code))
    }

    /** Blocking on a held lock can only deadlock in one context — refuse it. */
    @Test
    fun `MemoryFS blocking acquire on a held lock reports rather than hanging`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = _ } ->
                  match FileLock.lock { path = "/l", kind = Exclusive, blocking = True } is
                    Ok { val = _ } -> "unexpectedly-acquired"
                    Err { error = FsIOError { path = _, message = _ } } -> "refused"
                    Err { error = _ } -> "other-error"
                Err { error = _ } -> "first-failed"
        """.trimIndent()
        assertEquals("refused", eval(code))
    }

    @Test
    fun `MemoryFS unlock is idempotent`() {
        val code = """
            use std::effects::filesystem::{ FileLock, MemoryFileLock, Exclusive }
            perform MemoryFileLock in
              match FileLock.lock { path = "/l", kind = Exclusive, blocking = False } is
                Ok { val = h } -> do
                  let _ = FileLock.unlock { handle = h }
                  match FileLock.unlock { handle = h } is
                    Ok { val = _ } -> "idempotent"
                    Err { error = _ } -> "second-unlock-failed"
                Err { error = _ } -> "lock-failed"
        """.trimIndent()
        assertEquals("idempotent", eval(code))
    }

    // ==================== LocalDisk ====================

    @Test
    fun `LocalDisk exclusive lock creates the lockfile and releases`(@TempDir tmp: Path) {
        val path = tmp.resolve("app.lock").toString()
        val code = """
            use std::effects::filesystem::{ FileLock, LocalFileLock, Exclusive }
            perform LocalFileLock in
              match FileLock.lock { path = "$path", kind = Exclusive, blocking = False } is
                Ok { val = h } -> match FileLock.unlock { handle = h } is
                  Ok { val = _ } -> "ok"
                  Err { error = _ } -> "unlock-failed"
                Err { error = _ } -> "lock-failed"
        """.trimIndent()
        assertEquals("ok", eval(code))
    }

    /**
     * A second acquire in the SAME JVM raises OverlappingFileLockException
     * rather than failing at the OS level. Indistinguishable to the caller,
     * and it must not escape as a host exception.
     */
    @Test
    fun `LocalDisk second acquire in one process is refused, not thrown`(@TempDir tmp: Path) {
        val path = tmp.resolve("app.lock").toString()
        val code = """
            use std::effects::filesystem::{ FileLock, LocalFileLock, Exclusive }
            perform LocalFileLock in
              match FileLock.lock { path = "$path", kind = Exclusive, blocking = False } is
                Ok { val = _ } ->
                  match FileLock.lock { path = "$path", kind = Exclusive, blocking = False } is
                    Ok { val = _ } -> "second-acquired"
                    Err { error = FsPermissionDenied { path = _, operation = _ } } -> "excluded"
                    Err { error = _ } -> "other-error"
                Err { error = _ } -> "first-failed"
        """.trimIndent()
        assertEquals("excluded", eval(code))
    }
}
