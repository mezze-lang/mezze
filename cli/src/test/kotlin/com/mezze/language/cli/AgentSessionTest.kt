package com.mezze.language.cli

import com.mezze.language.cli.commands.AgentApplyResult
import com.mezze.language.cli.commands.AgentSession
import com.mezze.language.cli.commands.Changeset
import com.mezze.language.cli.commands.SelfWrites
import com.mezze.language.typechecker.World
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * A session an agent drives: batched, warm, machine-readable.
 *
 * The claim worth testing hardest is ONE RELOAD. A duplicate reload is invisible
 * except as latency, so nothing about it shows up in ordinary use — which is exactly
 * why it needs an assertion rather than a look. `reload` is a function here precisely
 * so the count is observable without booting a runtime.
 */
class AgentSessionTest {

    @TempDir
    lateinit var projectDir: Path

    private val sessions = mutableListOf<AgentSession>()

    @AfterEach
    fun cleanUp() {
        sessions.forEach { it.close() }
        sessions.clear()
        SelfWrites.clear()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        com.mezze.language.typechecker.ModuleResolver.clearOverlay(World.DEFAULT)
    }

    /** A session whose reloads are counted. */
    private fun session(reloads: MutableList<Set<String>>? = null): AgentSession =
        AgentSession(
            projectRoot = projectDir.toFile(),
            reload = reloads?.let { list -> { paths -> list.add(paths) } },
        ).also { sessions.add(it) }

    private val helper = "pub let bump = { x } -> x"
    private fun main(n: Int) = "use helper::{bump}\n\nlet u$n = { v } -> bump { x = v }"

    private fun goodBatch(n: Int = 1) =
        Changeset(mapOf("helper" to helper, "main" to main(n)))

    // ---- exactly one reload -------------------------------------------------

    @Test
    fun `a committed batch of many files reloads ONCE`() {
        // The whole point. Five files through the watcher would have been several
        // coalescing rounds; declared, it is one.
        val reloads = mutableListOf<Set<String>>()
        val batch = Changeset(
            mapOf("helper" to helper) +
                (1..4).associate { "m$it" to "use helper::{bump}\n\nlet u$it = { v } -> bump { x = v }" }
        )
        val result = session(reloads).apply(batch)

        assertTrue(result.ok, "${result.diagnostics}")
        assertEquals(1, reloads.size, "expected one reload, got ${reloads.size}")
        assertEquals(batch.modules.keys, reloads.single())
        assertTrue(result.reloaded)
    }

    @Test
    fun `a dry run reloads nothing`() {
        val reloads = mutableListOf<Set<String>>()
        val result = session(reloads).apply(goodBatch(), dryRun = true)

        assertTrue(result.ok, "${result.diagnostics}")
        assertEquals(0, reloads.size, "a dry run reloaded the runtime")
        assertFalse(result.reloaded)
        assertTrue(result.written.isEmpty())
    }

    @Test
    fun `a FAILED batch reloads nothing`() {
        // It wrote nothing, so reloading would re-run what was already loaded — work
        // for no change, reported as though something had happened.
        val reloads = mutableListOf<Set<String>>()
        val result = session(reloads).apply(
            Changeset(mapOf("helper" to helper, "broken" to "let a: Int = True"))
        )
        assertFalse(result.ok)
        assertEquals(0, reloads.size, "a failed batch triggered a reload")
        assertFalse(result.reloaded)
    }

    @Test
    fun `a session with no runtime writes files and says it did not reload`() {
        // Reported rather than inferred: files were written AND nothing reloaded, and a
        // caller should not have to guess which happened from `written` being non-empty.
        val result = session(reloads = null).apply(goodBatch())

        assertTrue(result.ok, "${result.diagnostics}")
        assertTrue(result.written.isNotEmpty())
        assertFalse(result.reloaded)
    }

    // ---- warm --------------------------------------------------------------

    @Test
    fun `a session serves several applies in a row`() {
        // Warm means reusable. A world is not single-shot, and a session that broke on
        // its second request would be useless for the case it exists for.
        val reloads = mutableListOf<Set<String>>()
        val s = session(reloads)

        assertTrue(s.apply(goodBatch(1)).ok)
        assertTrue(s.apply(goodBatch(2)).ok)
        assertTrue(s.apply(goodBatch(3)).ok)
        assertEquals(3, reloads.size, "each committed apply should reload once")
    }

    @Test
    fun `two sessions do not interfere`() {
        // Each gets its own world by default, so they cannot make each other look like
        // an import cycle — the mistake ModuleTypeChecker refuses outright.
        val a = session()
        val b = session()
        assertTrue(a.apply(goodBatch(1), dryRun = true).ok)
        assertTrue(b.apply(goodBatch(2), dryRun = true).ok)
    }

    // ---- machine-readable ---------------------------------------------------

    @Test
    fun `the result round-trips as JSON derived from the type`() {
        // Not a hand-rolled encoder: there are already four of those in this module and
        // a fifth would be free to drift. This is `@Serializable`.
        val result = session().apply(goodBatch())
        val decoded = Json.decodeFromString(AgentApplyResult.serializer(), result.toJson())
        assertEquals(result, decoded)
    }

    @Test
    fun `written paths are project-relative, not this machine's layout`() {
        // The result crosses a wire; an absolute path from the build machine means
        // nothing on the other side.
        val result = session().apply(goodBatch())
        assertTrue(result.written.isNotEmpty())
        for (path in result.written) {
            assertFalse(
                File(path).isAbsolute,
                "an absolute path leaked into the result: $path",
            )
            assertTrue(path.startsWith("src"), "expected a src-relative path, got $path")
        }
    }

    @Test
    fun `a clean module appears in the diagnostics with an empty list`() {
        // So a caller never has to infer "absent means fine".
        val result = session().apply(goodBatch())
        assertEquals(setOf("helper", "main"), result.diagnostics.keys)
        assertEquals(emptyList<String>(), result.diagnostics["helper"])
    }

    @Test
    fun `a failed batch reports every module, not just the first`() {
        val result = session().apply(
            Changeset(mapOf(
                "helper" to helper,
                "bad_one" to "let a: Int = True",
                "bad_two" to "let b: Int = \"no\"",
            ))
        )
        assertFalse(result.ok)
        assertEquals(
            setOf("bad_one", "bad_two"),
            result.diagnostics.filterValues { it.isNotEmpty() }.keys,
        )
    }

    // ---- the watcher does not also act -------------------------------------

    @Test
    fun `a committed apply records its writes so the watcher stays quiet`() {
        // Without this the watcher fires for writes the session just made and re-runs
        // the whole cycle — the duplicated work a declared batch exists to remove,
        // arriving by another route.
        val result = session().apply(goodBatch())
        assertTrue(result.ok, "${result.diagnostics}")
        for (path in result.written) {
            val f = File(projectDir.toFile(), path)
            assertTrue(
                SelfWrites.wasSelfWritten(f),
                "$path was written but not recorded, so the watcher will act on it",
            )
        }
    }
}
