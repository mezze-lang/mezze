package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Project-root discovery walks up to the nearest Mezze.toml — the one rule that
 * makes `.mez/` land at the root and every command work from any subdirectory.
 */
class ProjectRootTest {

    private fun manifest(dir: File) = File(dir, "Mezze.toml").apply { parentFile.mkdirs(); writeText("[package]\nname=\"x\"\n") }

    @Test
    fun `finds Mezze_toml in the start directory`(@TempDir tmp: Path) {
        val root = tmp.toFile(); manifest(root)
        assertEquals(root.canonicalFile, ProjectRoot.discover(root)?.canonicalFile)
    }

    @Test
    fun `walks up to the nearest ancestor holding Mezze_toml`(@TempDir tmp: Path) {
        val root = tmp.toFile(); manifest(root)
        val deep = File(root, "src/a/b").apply { mkdirs() }
        assertEquals(root.canonicalFile, ProjectRoot.discover(deep)?.canonicalFile)
    }

    @Test
    fun `nearest wins for nested projects`(@TempDir tmp: Path) {
        val outer = tmp.toFile(); manifest(outer)
        val inner = File(outer, "sub").apply { mkdirs() }; manifest(inner)
        val deep = File(inner, "x/y").apply { mkdirs() }
        assertEquals(inner.canonicalFile, ProjectRoot.discover(deep)?.canonicalFile,
            "a subdirectory with its own Mezze.toml is its own project")
    }

    @Test
    fun `null when no Mezze_toml at or above`(@TempDir tmp: Path) {
        val deep = File(tmp.toFile(), "a/b/c").apply { mkdirs() }
        assertNull(ProjectRoot.discover(deep))
    }
}
