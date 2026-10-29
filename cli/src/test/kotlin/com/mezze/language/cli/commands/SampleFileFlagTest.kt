package com.mezze.language.cli.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * `mez sample --file=<path>` looks in the named module, not the project's app
 * module. The editor's sample lens appears over functions in ANY open file and
 * passes that file here, so a function in a non-app module is sampleable. Without
 * the flag, resolution falls back to the app module (the bare-CLI scope).
 */
class SampleFileFlagTest {

    private fun project(dir: Path) {
        dir.resolve("Mezze.toml").toFile().writeText(
            """
            [package]
            name = "proj"
            version = "0.1.0"

            [[app]]
            name = "proj"
            path = "src/app.mz"
            entry = "main"
            """.trimIndent()
        )
        val src = dir.resolve("src").toFile().apply { mkdirs() }
        src.resolve("app.mz").writeText("let main = { } -> 0\n")
        src.resolve("lib.mz").writeText("pub let twice = { n: Int } -> n * 2\n")
    }

    @Test
    fun `--file picks the named module`(@TempDir dir: Path) {
        project(dir)
        val chosen = SampleCommand().resolveModuleFile(
            listOf("--json", "--file=src/lib.mz", "--count=2"), dir.toFile(),
        )
        assertEquals("lib.mz", chosen?.name, "sampling a lens in lib.mz must look in lib.mz, not the app module")
    }

    @Test
    fun `an absolute --file is honored verbatim`(@TempDir dir: Path) {
        project(dir)
        val abs = dir.resolve("src").resolve("lib.mz").toFile().absolutePath
        val chosen = SampleCommand().resolveModuleFile(listOf("--file=$abs"), dir.toFile())
        assertEquals("lib.mz", chosen?.name)
    }

    @Test
    fun `no --file falls back to the app module`(@TempDir dir: Path) {
        project(dir)
        val chosen = SampleCommand().resolveModuleFile(listOf("--count=3"), dir.toFile())
        assertEquals("app.mz", chosen?.name, "bare `mez sample <fn>` still means the app module")
    }

    @Test
    fun `a --file that does not exist resolves to nothing`(@TempDir dir: Path) {
        project(dir)
        val chosen = SampleCommand().resolveModuleFile(listOf("--file=src/missing.mz"), dir.toFile())
        assertNull(chosen, "a missing file is reported, not silently sampled from the app module")
    }
}
