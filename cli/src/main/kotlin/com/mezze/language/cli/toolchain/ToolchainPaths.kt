package com.mezze.language.cli.toolchain

import java.io.File

/**
 * Where installed toolchains and global settings live.
 *
 *     ~/.mezze/
 *       toolchains/<version>/     one dir per installed toolchain
 *       settings.toml             default = "<channel>"
 *
 * `MEZZE_HOME` overrides `~/.mezze` (used by tests, and by anyone relocating it).
 */
class ToolchainPaths(val home: File) {
    val toolchainsDir = File(home, "toolchains")
    val settingsFile = File(home, "settings.toml")

    /** Installed toolchain versions = subdir names under `toolchains/` (newest-first-agnostic). */
    fun installed(): List<String> =
        toolchainsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    fun dirFor(version: String): File = File(toolchainsDir, version)

    /** Global default channel from `settings.toml` (`default = "…"`), or null. */
    fun defaultChannel(): String? =
        settingsFile.takeIf { it.exists() }?.readLines()?.firstNotNullOfOrNull { line ->
            DEFAULT_RE.find(line)?.groupValues?.get(1)
        }

    /** Set the global default channel, preserving the rest of `settings.toml`. */
    fun setDefault(channel: String) {
        home.mkdirs()
        val lines = settingsFile.takeIf { it.exists() }?.readLines()?.toMutableList() ?: mutableListOf()
        val entry = "default = \"$channel\""
        val i = lines.indexOfFirst { DEFAULT_RE.containsMatchIn(it) }
        if (i >= 0) lines[i] = entry else lines.add(entry)
        settingsFile.writeText(lines.joinToString("\n").trimEnd('\n') + "\n")
    }

    companion object {
        private val DEFAULT_RE = Regex("""^\s*default\s*=\s*"([^"]+)"\s*$""")

        fun resolve(homeOverride: File? = null): ToolchainPaths =
            ToolchainPaths(
                homeOverride
                    ?: System.getenv("MEZZE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                    ?: File(System.getProperty("user.home"), ".mezze"),
            )
    }
}
