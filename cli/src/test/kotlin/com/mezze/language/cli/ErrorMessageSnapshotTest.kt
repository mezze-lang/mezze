package com.mezze.language.cli

import com.mezze.language.typechecker.printing.ParseErrorFormatter
import com.mezze.language.cli.commands.TypeCheckRunner
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.nio.file.Paths

/**
 * Snapshot tests for compiler error output.
 *
 * Reads every pair under `src/test/resources/error-snapshots/` of the form
 * `<name>.mz` + `<name>.expected`. Each `.mz` file should contain a small
 * program that produces a known set of errors; the `.expected` file holds
 * the exact formatted output that `mez check` would print.
 *
 * Adding a new snapshot:
 * 1. Create `error-snapshots/my-case.mz` with a program that errors.
 * 2. Run the test once — it'll fail with the actual output printed.
 * 3. Save the output to `error-snapshots/my-case.expected`.
 * 4. Re-run — should pass.
 *
 * Updating a snapshot after intentional message changes:
 * Set `MEZZE_UPDATE_SNAPSHOTS=1` and rerun. The test will overwrite
 * `.expected` files with current output. Review the diff before
 * committing.
 *
 * These tests are the canary that catches accidental message drift across
 * the 407 error sites we're migrating. Any rewrite that changes a critical
 * error's wording will fail until the snapshot is intentionally updated.
 */
class ErrorMessageSnapshotTest {

    @TestFactory
    fun snapshots(): List<DynamicTest> {
        val snapshotsDir = resolveSnapshotsDir()
        if (!snapshotsDir.exists() || !snapshotsDir.isDirectory) {
            return emptyList()
        }
        val mezFiles = snapshotsDir.listFiles { f -> f.extension == "mz" }
            ?.sortedBy { it.name }
            ?: return emptyList()

        return mezFiles.map { mezFile ->
            DynamicTest.dynamicTest(mezFile.nameWithoutExtension) {
                runSnapshot(mezFile)
            }
        }
    }

    private fun runSnapshot(mezFile: File) {
        val source = mezFile.readText()
        val expectedFile = File(mezFile.parentFile, "${mezFile.nameWithoutExtension}.expected")

        val actual = compileAndFormat(source, mezFile.name)
        val update = System.getenv("MEZZE_UPDATE_SNAPSHOTS") == "1"

        if (!expectedFile.exists()) {
            if (update) {
                expectedFile.writeText(actual)
                println("[snapshot] created ${expectedFile.name}")
                return
            }
            throw AssertionError(
                "Missing snapshot for ${mezFile.name}.\n" +
                "Expected file: ${expectedFile.absolutePath}\n" +
                "Run with MEZZE_UPDATE_SNAPSHOTS=1 to create it.\n\n" +
                "Actual output:\n$actual"
            )
        }

        val expected = expectedFile.readText()
        if (actual.trimEnd() == expected.trimEnd()) return  // pass

        if (update) {
            expectedFile.writeText(actual)
            println("[snapshot] updated ${expectedFile.name}")
            return
        }

        assertEquals(
            expected.trimEnd(),
            actual.trimEnd(),
            "Snapshot mismatch for ${mezFile.name}. Set MEZZE_UPDATE_SNAPSHOTS=1 to update.",
        )
    }

    /**
     * Run parse + typecheck on [source], format the resulting errors via
     * the canonical formatter. Output mirrors what `mez check` prints.
     */
    private fun compileAndFormat(source: String, fileName: String): String {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()

        val parser = MezzeParser()
        val parseResult = parser.parseWithRecovery(source, file = fileName)

        val output = StringBuilder()

        if (parseResult.errors.isNotEmpty()) {
            output.append(ParseErrorFormatter.formatAll(parseResult.errors))
        }

        val typechecker = TypeChecker()
        val rawResult = try {
            typechecker.checkParsedModule(parseResult.module, Paths.get("."), enablePrelude = false)
        } catch (e: TypeCheckError) {
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(com.mezze.language.typechecker.printing.ErrorFormatter.format(e, source))
            return output.toString()
        } catch (e: com.mezze.language.typechecker.module.ModuleError) {
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(com.mezze.language.typechecker.printing.ErrorFormatter.formatModuleError(e))
            return output.toString()
        }

        if (rawResult is TypeChecker.TypeCheckResult.Failure) {
            if (output.isNotEmpty()) output.append("\n\n")
            output.append(TypeCheckRunner.formatErrors(rawResult, source))
        }

        return output.toString()
    }

    private fun resolveSnapshotsDir(): File {
        val candidates = listOf(
            "src/test/resources/error-snapshots",
            "cli/src/test/resources/error-snapshots",
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f
        }
        return File(candidates[0])
    }
}
