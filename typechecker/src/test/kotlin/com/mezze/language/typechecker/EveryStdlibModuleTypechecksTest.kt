package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Every stdlib module typechecks on its own, and a failing one is NAMED with
 * its own diagnostic.
 *
 * The snapshot build (`:cli:compileStdlib`) also checks every module, but it
 * reports only the first module that fails to IMPORT a dependency — "Effect
 * `Sample` is not defined" in `arbitrary.mz` — while the module that actually
 * broke (an import error is collected, not thrown) stays silent. This test
 * walks the sources directly so the first real error has a file and a line.
 */
class EveryStdlibModuleTypechecksTest {

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun stdlibRoot(): File {
        val candidates = listOf("stdlib/std", "../stdlib/std").map { File(it) }
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("stdlib/std not found from ${File(".").absolutePath}")
    }

    @Test
    fun `every stdlib module typechecks and a failure names its module`() {
        val root = stdlibRoot()
        val failures = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "mz" && !it.name.startsWith("_") }
            .sortedBy { it.path }
            .forEach { f ->
                val rel = f.relativeTo(root).path.removeSuffix(".mz")
                val modulePath = "std::" + rel.replace(File.separatorChar, ':').replace(":", "::")
                try {
                    val parsed = MezzeParser().parseParsedModule(f.readText(), f.path)
                    TypeChecker().typeCheck(parsed, Paths.get("."), enablePrelude = true, modulePath = modulePath)
                } catch (e: Throwable) {
                    failures.add("$modulePath: ${e.message?.lineSequence()?.firstOrNull()?.take(300)}")
                }
            }
        assertTrue(failures.isEmpty(), "stdlib modules that do not typecheck:\n  " + failures.joinToString("\n  "))
    }
}
