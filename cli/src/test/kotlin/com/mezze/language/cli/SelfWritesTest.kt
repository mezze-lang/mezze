package com.mezze.language.cli

import com.mezze.language.cli.commands.Changeset
import com.mezze.language.cli.commands.ChangesetApply
import com.mezze.language.cli.commands.SelfWrites
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.World
import com.mezze.language.typechecker.module.ModuleTypeChecker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * The watcher must not react to writes this process just made.
 *
 * A committed changeset writes the files it type-checked; the watcher then fires and
 * re-runs invalidate → recompile → reload → re-diagnose for a change the system made
 * itself. That is the duplicated work a declared changeset exists to remove, arriving
 * by another route.
 *
 * The suppression is keyed on CONTENT, not on a time window, and these tests are
 * mostly about what that buys: duplicate events for one write are all dropped, and a
 * real edit is never swallowed no matter when it arrives.
 */
class SelfWritesTest {

    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun reset() {
        SelfWrites.clear()
        ModuleResolver.clearOverlay(World.DEFAULT)
        ModuleTypeChecker.clearCache()
    }

    private fun file(name: String) = File(dir.toFile(), name)

    private fun writeOurs(name: String, text: String): File {
        val f = file(name)
        f.writeText(text)
        SelfWrites.record(f, text)
        return f
    }

    @Test
    fun `a file we wrote is recognised as ours`() {
        val f = writeOurs("a.mz", "let a = 1")
        assertTrue(SelfWrites.wasSelfWritten(f))
    }

    @Test
    fun `a file we never wrote is not ours`() {
        val f = file("b.mz").apply { writeText("let b = 2") }
        assertFalse(SelfWrites.wasSelfWritten(f))
    }

    @Test
    fun `EVERY duplicate event for one write is dropped`() {
        // The reason a record is not consumed on first match: macOS fires several
        // events per save, so consuming would suppress one and let the rest through —
        // which is the double-reload being prevented.
        val f = writeOurs("c.mz", "let c = 3")
        repeat(5) { i ->
            assertTrue(SelfWrites.wasSelfWritten(f), "duplicate event $i was not dropped")
        }
    }

    @Test
    fun `a real edit after our write is NOT suppressed`() {
        // The property that matters most: suppression can never swallow a change.
        val f = writeOurs("d.mz", "let d = 4")
        assertTrue(SelfWrites.wasSelfWritten(f))

        f.writeText("let d = 5")   // somebody else edited it
        assertFalse(SelfWrites.wasSelfWritten(f), "a real edit was suppressed")
    }

    @Test
    fun `a stale record is dropped once the content moves on`() {
        // Not just "answers false" — the record must go, or it would sit in the map
        // for the life of the process describing content that no longer exists.
        val f = writeOurs("e.mz", "let e = 6")
        assertEquals(1, SelfWrites.size())
        f.writeText("edited")
        assertFalse(SelfWrites.wasSelfWritten(f))
        assertEquals(0, SelfWrites.size(), "the stale record was kept")
    }

    @Test
    fun `once a record is dropped, re-writing that content is a normal change again`() {
        // While a record is LIVE, content equal to ours is suppressed — that edit
        // changes nothing, so there is nothing to reload. This pins the other half:
        // after the record has gone, identical content is an ordinary change, so the
        // suppression cannot become permanent for a path.
        val f = writeOurs("g.mz", "let g = 7")
        f.writeText("something else")
        assertFalse(SelfWrites.wasSelfWritten(f))   // and the record is dropped here
        f.writeText("let g = 7")
        assertFalse(
            SelfWrites.wasSelfWritten(f),
            "once the record is gone a matching edit is a normal change again",
        )
    }

    @Test
    fun `an unreadable file is not treated as ours`() {
        // If we cannot tell, the event must get through: dropping a real change is the
        // failure that matters, while a redundant reload is only slow.
        val f = writeOurs("h.mz", "let h = 8")
        f.delete()
        assertFalse(SelfWrites.wasSelfWritten(f))
    }

    @Test
    fun `the same file named two ways is one record`() {
        // `./src/a.mz` and the absolute path must not be two entries, or a write
        // recorded one way is invisible to a check made the other.
        val f = writeOurs("i.mz", "let i = 9")
        val viaRelative = File(dir.toFile().path + File.separator + "." + File.separator + "i.mz")
        assertTrue(
            SelfWrites.wasSelfWritten(viaRelative),
            "the same file under a different spelling was not recognised",
        )
    }

    // ---- through a real changeset apply -------------------------------------

    @Test
    fun `a committed changeset records every file it wrote`() {
        val w = World.fresh("selfwrite")
        try {
            val result = ChangesetApply.apply(
                dir.toFile(),
                Changeset(mapOf(
                    "helper" to "pub let bump = { x } -> x",
                    "main" to "use helper::{bump}\n\nlet u = { v } -> bump { x = v }",
                )),
                dryRun = false,
                world = w,
            )
            assertTrue(result.ok, "${result.diagnostics}")
            for (f in result.written) {
                assertTrue(
                    SelfWrites.wasSelfWritten(f),
                    "${f.name} was written but not recorded, so the watcher will re-run the cycle",
                )
            }
        } finally {
            ModuleResolver.forgetWorld(w)
            ModuleTypeChecker.forgetWorld(w)
        }
    }

    @Test
    fun `a dry run records nothing, having written nothing`() {
        val w = World.fresh("selfwrite-dry")
        try {
            ChangesetApply.apply(
                dir.toFile(),
                Changeset(mapOf("helper" to "pub let bump = { x } -> x")),
                dryRun = true,
                world = w,
            )
            assertEquals(0, SelfWrites.size(), "a dry run recorded a write")
        } finally {
            ModuleResolver.forgetWorld(w)
            ModuleTypeChecker.forgetWorld(w)
        }
    }
}
