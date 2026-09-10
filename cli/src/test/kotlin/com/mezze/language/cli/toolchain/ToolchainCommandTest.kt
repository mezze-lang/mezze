package com.mezze.language.cli.toolchain

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.`package`.MezzeToml
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ToolchainCommandTest {

    private fun manifest(dir: File, body: String = "") {
        File(dir, "Mezze.toml").writeText("[package]\nname = \"app\"\nversion = \"0.1.0\"\n$body")
    }

    @Test
    fun `default writes settings and reads back`(@TempDir tmp: Path) {
        val paths = ToolchainPaths(tmp.toFile())
        ToolchainCommand().execute(listOf("default", "0.2.0"), paths = paths)
        assertEquals("0.2.0", paths.defaultChannel())
        assertTrue(paths.settingsFile.exists())
    }

    @Test
    fun `installed reflects toolchains subdirs`(@TempDir tmp: Path) {
        val paths = ToolchainPaths(tmp.toFile())
        File(paths.toolchainsDir, "0.1.0").mkdirs()
        File(paths.toolchainsDir, "0.2.0").mkdirs()
        assertEquals(setOf("0.1.0", "0.2.0"), paths.installed().toSet())
    }

    @Test
    fun `pin creates a toolchain channel in Mezze_toml`(@TempDir tmp: Path) {
        val proj = tmp.toFile()
        manifest(proj, "\n[dependencies]\n")
        ToolchainCommand().execute(listOf("pin", "0.1"), currentDir = proj, paths = ToolchainPaths(File(proj, ".home")))
        val text = File(proj, "Mezze.toml").readText()
        assertTrue(text.contains("[toolchain]"))
        assertTrue(text.contains("channel = \"0.1\""))
        // and it round-trips through the manifest parser (validates the schema)
        val parsed = Toml.decodeFromString(MezzeToml.serializer(), text)
        assertEquals("0.1", parsed.toolchain?.channel)
    }

    @Test
    fun `pin replaces an existing channel, no duplicate`(@TempDir tmp: Path) {
        val proj = tmp.toFile()
        manifest(proj, "\n[toolchain]\nchannel = \"0.1\"\n")
        ToolchainCommand().execute(listOf("pin", "nightly"), currentDir = proj, paths = ToolchainPaths(File(proj, ".home")))
        val text = File(proj, "Mezze.toml").readText()
        assertEquals("nightly", Toml.decodeFromString(MezzeToml.serializer(), text).toolchain?.channel)
        assertEquals(1, Regex("(?m)^channel = ").findAll(text).count(), "exactly one channel entry")
    }
}
