package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * `mez add` / `mez remove` edit Mezze.toml in place: path/url/dev sections, upsert
 * (replace an existing entry), section-scoped removal that never touches
 * `[package] name`, and comment/formatting preservation.
 */
class AddRemoveCommandTest {

    private val base = """
        [package]
        name = "app"
        version = "0.1.0"

        # keep my comment
        [dependencies]

        [dev-dependencies]
    """.trimIndent() + "\n"

    private fun project(tmp: Path): File =
        tmp.toFile().also { File(it, "Mezze.toml").writeText(base) }

    private fun toml(dir: File) = File(dir, "Mezze.toml").readText()

    @Test
    fun `add path dep creates the section and entry, keeps comments`(@TempDir tmp: Path) {
        val dir = project(tmp)
        AddCommand().executeAdd(listOf("utils", "--path", "../utils"), dir)
        val t = toml(dir)
        assertTrue(t.contains("[path-dependencies]"), "creates [path-dependencies]")
        assertTrue(t.contains("utils = \"../utils\""), "adds the entry")
        assertTrue(t.contains("# keep my comment"), "preserves existing comments")
    }

    @Test
    fun `add url dep derives the name into dependencies`(@TempDir tmp: Path) {
        val dir = project(tmp)
        val url = "https://ex/json-1.2.3.tar.gz#sha256:" + "a".repeat(64)
        AddCommand().executeAdd(listOf(url), dir)
        assertTrue(toml(dir).contains("json = \"$url\""), "json added to [dependencies]")
    }

    @Test
    fun `add --dev lands under dev-dependencies`(@TempDir tmp: Path) {
        val dir = project(tmp)
        val url = "https://ex/t.tar.gz#sha256:" + "b".repeat(64)
        AddCommand().executeAdd(listOf("test_fw", "--url", url, "--dev"), dir)
        val t = toml(dir)
        val devIdx = t.indexOf("[dev-dependencies]")
        val entryIdx = t.indexOf("test_fw = ")
        assertTrue(devIdx in 0 until entryIdx, "test_fw should sit under [dev-dependencies]")
    }

    @Test
    fun `add replaces an existing entry (single entry remains)`(@TempDir tmp: Path) {
        val dir = project(tmp)
        AddCommand().executeAdd(listOf("utils", "--path", "../a"), dir)
        AddCommand().executeAdd(listOf("utils", "--path", "../b"), dir)
        val t = toml(dir)
        assertTrue(t.contains("utils = \"../b\""))
        assertFalse(t.contains("utils = \"../a\""))
        assertEquals(1, Regex("(?m)^utils = ").findAll(t).count(), "exactly one utils entry")
    }

    @Test
    fun `remove drops the dependency`(@TempDir tmp: Path) {
        val dir = project(tmp)
        AddCommand().executeAdd(listOf("utils", "--path", "../utils"), dir)
        AddCommand().executeRemove(listOf("utils"), dir)
        assertFalse(toml(dir).contains("utils = "), "utils should be gone")
    }

    @Test
    fun `remove never touches the package name line`(@TempDir tmp: Path) {
        val dir = project(tmp)
        // 'app' is the [package] name, not a dependency — must be left alone.
        AddCommand().executeRemove(listOf("app"), dir)
        assertTrue(toml(dir).contains("name = \"app\""), "[package] name must be untouched")
    }
}
