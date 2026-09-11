package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `depLibEntryFile` maps a dependency dir to its `[lib]` entry — the shared
 * hook that lets `use <pkg>` resolve to a package's library module in both the
 * module resolver (compile) and the LSP (go-to-definition).
 */
class DepLibEntryFileTest {

    @Test
    fun `reads the lib path from a dependency Mezze_toml`(@TempDir tmp: Path) {
        val dep = tmp.resolve("greet-0.1.0")
        Files.createDirectories(dep.resolve("src"))
        Files.writeString(dep.resolve("src/lib.mz"), "pub let x = {} -> 1\n")
        Files.writeString(
            dep.resolve("Mezze.toml"),
            "[package]\nname = \"greet\"\nversion = \"0.1.0\"\n\n[lib]\npath = \"src/lib.mz\"\n",
        )
        assertEquals(dep.resolve("src/lib.mz"), depLibEntryFile(dep))
    }

    @Test
    fun `null when there is no lib section`(@TempDir tmp: Path) {
        val dep = tmp.resolve("app-0.1.0")
        Files.createDirectories(dep)
        Files.writeString(dep.resolve("Mezze.toml"), "[package]\nname = \"app\"\nversion = \"0.1.0\"\n")
        assertNull(depLibEntryFile(dep))
    }

    @Test
    fun `null when there is no Mezze_toml`(@TempDir tmp: Path) {
        assertNull(depLibEntryFile(tmp.resolve("does-not-exist")))
    }

    @Test
    fun `a section after lib does not leak into the path scan`(@TempDir tmp: Path) {
        val dep = tmp.resolve("greet-0.1.0")
        Files.createDirectories(dep)
        // path lives under [dependencies], not [lib] — must not be picked up.
        Files.writeString(
            dep.resolve("Mezze.toml"),
            "[lib]\nname = \"greet\"\n\n[dependencies]\npath = \"nope\"\n",
        )
        assertNull(depLibEntryFile(dep))
    }
}
