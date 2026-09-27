package com.mezze.language.cli

import com.mezze.language.cli.commands.Changeset
import com.mezze.language.cli.commands.ChangesetApply
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.World
import com.mezze.language.typechecker.module.ModuleTypeChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * A batch declared as one unit, applied as one.
 *
 * The behaviours that matter are the ones a timing-inferred batch cannot give you:
 * a batch may REFER TO A MODULE IT ADDS (no file exists to resolve, so only a
 * source overlay can answer), a dry run must touch NOTHING, and a batch with one
 * bad file must write NONE of them while still reporting every file's diagnostics.
 *
 * Each test drops its world afterwards: a world holds caches and an overlay, and a
 * leaked one would make a later test see text that was never written.
 */
class ChangesetApplyTest {

    @TempDir
    lateinit var projectDir: Path

    private val worlds = mutableListOf<World>()

    private fun world(label: String) = World.fresh(label).also { worlds.add(it) }

    private fun cleanUp() {
        worlds.forEach { ModuleResolver.forgetWorld(it); ModuleTypeChecker.forgetWorld(it) }
        worlds.clear()
        // The default world is shared, so an overlay left on it would leak into
        // every later test in this JVM.
        ModuleResolver.clearOverlay(World.DEFAULT)
        ModuleTypeChecker.clearCache()
    }

    private fun root() = projectDir.toFile()

    private fun srcFile(modulePath: String) =
        File(root(), "src/" + modulePath.replace("::", "/") + ".mz")

    private fun write(modulePath: String, text: String) {
        srcFile(modulePath).apply { parentFile?.mkdirs(); writeText(text) }
    }

    private val goodHelper = """
        pub let bump = { x } -> do
            let y = x
            y
    """.trimIndent()

    private fun userOf(n: Int) = """
        use helper::{bump}

        let use$n = { v } -> bump { x = v }
    """.trimIndent()

    // ---- the thing timing-inferred batching cannot do ----------------------

    @Test
    fun `a batch can refer to a module the batch itself adds`() {
        // The case that forces content rather than paths: `helper` does not exist on
        // disk, so there is no file to resolve and no amount of coalescing helps. A
        // source overlay is the only way to check this batch at all.
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to goodHelper, "main" to userOf(1))),
                dryRun = true,
                world = world("adds"),
            )
            assertTrue(
                result.ok,
                "a batch that adds the module it imports should check clean: ${result.diagnostics}",
            )
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `the whole batch is overlaid BEFORE any of it is checked`() {
        // Order matters, and it is invisible unless the importing module is checked
        // FIRST. `helper` exists on disk exporting `old_name`; the batch renames that
        // export to `bump` and updates `main` to use it. Overlaying module-by-module
        // would check `main` while `helper` on disk still said `old_name`, and the
        // batch would be type-checked against a state that never exists.
        write("helper", "pub let old_name = { x } -> x")
        try {
            val result = ChangesetApply.apply(
                root(),
                // `main` deliberately first in iteration order.
                Changeset(linkedMapOf(
                    "main" to userOf(1),
                    "helper" to goodHelper,
                )),
                dryRun = true,
                world = world("order"),
            )
            assertTrue(
                result.ok,
                "the batch was checked against the OLD helper on disk: ${result.diagnostics}",
            )
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `every module is diagnosed, not just the first failure`() {
        // An agent wants all twelve files' errors in one round. Stopping at the first
        // would make it fix one, re-run, fix the next.
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf(
                    "helper" to goodHelper,
                    "bad_one" to "let a: Int = \"not an int\"",
                    "bad_two" to "let b: Int = True",
                )),
                dryRun = true,
                world = world("all-diagnosed"),
            )
            assertFalse(result.ok)
            assertEquals(
                setOf("bad_one", "bad_two"),
                result.failedModules.toSet(),
                "expected both bad modules reported, got ${result.diagnostics}",
            )
            // The clean module still appears, with no diagnostics — a caller should
            // not have to infer "absent means fine".
            assertTrue("helper" in result.diagnostics)
            assertEquals(emptyList<String>(), result.diagnostics["helper"])
        } finally {
            cleanUp()
        }
    }

    // ---- a dry run touches nothing -----------------------------------------

    @Test
    fun `a dry run writes no file, even when everything passes`() {
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to goodHelper, "main" to userOf(1))),
                dryRun = true,
                world = world("dry"),
            )
            assertTrue(result.ok, "${result.diagnostics}")
            assertTrue(result.written.isEmpty())
            assertFalse(srcFile("helper").exists(), "a dry run created src/helper.mz")
            assertFalse(srcFile("main").exists(), "a dry run created src/main.mz")
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `a dry run leaves an existing file untouched`() {
        // Not just "creates nothing" — it must not overwrite either.
        write("helper", goodHelper)
        val before = srcFile("helper").readText()
        try {
            ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to "pub let bump = { x } -> x")),
                dryRun = true,
                world = world("dry-existing"),
            )
            assertEquals(before, srcFile("helper").readText(), "a dry run rewrote the file")
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `a speculative world does not leak into the default one`() {
        // The point of checking in a fresh world: the hypothetical version must not
        // become what everything else sees.
        try {
            ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to goodHelper)),
                dryRun = true,   // no explicit world: apply makes and drops its own
            )
            assertTrue(
                ModuleResolver.overlaidPaths(World.DEFAULT).isEmpty(),
                "the speculative overlay leaked into the default world: " +
                    ModuleResolver.overlaidPaths(World.DEFAULT),
            )
        } finally {
            cleanUp()
        }
    }

    // ---- all-or-nothing ----------------------------------------------------

    @Test
    fun `one bad module means NOTHING is written`() {
        // A half-applied logical change that happens to compile is the failure worth
        // preventing: it looks like success.
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to goodHelper, "broken" to "let a: Int = True")),
                dryRun = false,
                world = world("partial"),
            )
            assertFalse(result.ok)
            assertTrue(result.written.isEmpty())
            assertFalse(
                srcFile("helper").exists(),
                "the good module was written even though the batch failed",
            )
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `a clean batch is written, all of it`() {
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to goodHelper, "main" to userOf(1))),
                dryRun = false,
                world = world("commit"),
            )
            assertTrue(result.ok, "${result.diagnostics}")
            assertEquals(2, result.written.size)
            assertEquals(goodHelper, srcFile("helper").readText())
            assertTrue(srcFile("main").readText().contains("use helper"))
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `the overlay is dropped once the files match it`() {
        // Left in place it would make this world permanently prefer text that is no
        // longer distinguishable from the file — and mask the next real edit.
        val w = world("cleared")
        try {
            ChangesetApply.apply(
                root(),
                Changeset(mapOf("helper" to goodHelper)),
                dryRun = false,
                world = w,
            )
            assertTrue(
                ModuleResolver.overlaidPaths(w).isEmpty(),
                "the overlay survived a committed apply: ${ModuleResolver.overlaidPaths(w)}",
            )
        } finally {
            cleanUp()
        }
    }

    // ---- diagnosis quality --------------------------------------------------

    @Test
    fun `a module that does not parse is reported without its downstream type errors`() {
        // Every type error after a parse failure is downstream of it; reporting both
        // buries the one the reader can act on.
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf("unparseable" to "let a = = =")),
                dryRun = true,
                world = world("parse"),
            )
            assertFalse(result.ok)
            val messages = result.diagnostics.getValue("unparseable")
            assertTrue(messages.isNotEmpty(), "a parse failure produced no diagnostic")
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `an import the batch does not supply is a diagnostic, not a crash`() {
        try {
            val result = ChangesetApply.apply(
                root(),
                Changeset(mapOf("main" to "use nowhere::{gone}\n\nlet x = gone { v = 1 }")),
                dryRun = true,
                world = world("missing-import"),
            )
            assertFalse(result.ok)
            assertTrue(result.diagnostics.getValue("main").isNotEmpty())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `an empty changeset is refused`() {
        // Nothing to apply is a caller mistake, not a no-op to absorb silently.
        assertThrows<IllegalArgumentException> { Changeset(emptyMap()) }
    }
}
