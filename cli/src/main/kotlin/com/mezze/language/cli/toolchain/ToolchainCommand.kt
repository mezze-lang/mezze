package com.mezze.language.cli.toolchain

import com.akuleshov7.ktoml.Toml
import com.mezze.language.cli.BuildInfo
import com.mezze.language.cli.`package`.MezzeToml
import java.io.File

/**
 * `mez toolchain` — inspect and pin Mezze toolchains. This is the management +
 * resolution layer; installing *multiple* versions side by side lands with the
 * `toolchains/<v>/` layout + proxy. Until then the running (built-in) toolchain is
 * always shown as present, so `list` / `which` / `pin` / `default` are already useful.
 */
class ToolchainCommand {

    fun execute(
        args: List<String>,
        currentDir: File = File(System.getProperty("user.dir")),
        paths: ToolchainPaths = ToolchainPaths.resolve(),
        envVersion: String? = System.getenv("MEZ_VERSION"),
    ) {
        when (args.firstOrNull()) {
            null, "list" -> list(paths, currentDir, envVersion)
            "which", "show" -> which(paths, currentDir, envVersion)
            "default" -> setDefault(paths, args.getOrNull(1))
            "pin" -> pin(currentDir, args.getOrNull(1))
            "dir" -> dir(paths, currentDir, envVersion)
            "-h", "--help", "help" -> usage()
            else -> { println("Unknown subcommand: toolchain ${args.first()}"); usage() }
        }
    }

    // The running toolchain (flat install) is always part of the installed set,
    // so the commands work before the multi-version layout exists.
    private fun installedVersions(paths: ToolchainPaths): List<String> =
        (paths.installed() + BuildInfo.VERSION).distinct()

    private fun resolveFor(paths: ToolchainPaths, currentDir: File, envVersion: String?) =
        ToolchainResolver.resolve(
            explicit = null,
            env = envVersion,
            projectChannel = projectChannel(currentDir),
            default = paths.defaultChannel(),
            installed = installedVersions(paths),
        )

    private fun list(paths: ToolchainPaths, currentDir: File, envVersion: String?) {
        val installed = installedVersions(paths)
        val def = paths.defaultChannel()
        val active = (resolveFor(paths, currentDir, envVersion) as? ToolchainResolver.Resolution.Ok)?.version
        println("Installed toolchains:")
        installed.sorted().forEach { v ->
            val marks = buildList {
                if (v == BuildInfo.VERSION) add("built-in")
                if (v == def) add("default")
                if (v == active) add("active here")
            }
            println("  $v${if (marks.isEmpty()) "" else "  (${marks.joinToString(", ")})"}")
        }
        if (paths.installed().isEmpty()) {
            println()
            println("Only the built-in toolchain is present. Multi-version install lands with the")
            println("toolchains/<v>/ layout; pin a project now with: mez toolchain pin <channel>")
        }
    }

    private fun which(paths: ToolchainPaths, currentDir: File, envVersion: String?) {
        when (val r = resolveFor(paths, currentDir, envVersion)) {
            is ToolchainResolver.Resolution.Ok ->
                println("${r.version}  (from ${r.request.source.name.lowercase()})")
            is ToolchainResolver.Resolution.NotInstalled -> {
                println("❌ toolchain '${r.request.channel}' (from ${r.request.source.name.lowercase()}) is not installed.")
                println("   installed: ${r.installed.joinToString(", ")}")
            }
            is ToolchainResolver.Resolution.None -> println("❌ no toolchains installed.")
        }
    }

    private fun setDefault(paths: ToolchainPaths, version: String?) {
        if (version.isNullOrBlank()) { println("Usage: mez toolchain default <version|channel>"); return }
        paths.setDefault(version)
        println("✅ global default toolchain → $version")
    }

    private fun pin(currentDir: File, channel: String?) {
        if (channel.isNullOrBlank()) { println("Usage: mez toolchain pin <channel>"); return }
        val manifest = File(currentDir, "Mezze.toml")
        if (!manifest.exists()) { println("❌ no Mezze.toml here to pin (run 'mez init' first)."); return }
        manifest.writeText(upsertToolchain(manifest.readText(), channel))
        println("✅ pinned [toolchain] channel = \"$channel\" in Mezze.toml")
        println("💡 run 'mez toolchain which' to see the resolved version")
    }

    private fun dir(paths: ToolchainPaths, currentDir: File, envVersion: String?) {
        when (val r = resolveFor(paths, currentDir, envVersion)) {
            is ToolchainResolver.Resolution.Ok -> {
                val d = paths.dirFor(r.version)
                println(if (d.isDirectory) d.absolutePath else "(built-in) ${paths.home.absolutePath}")
            }
            else -> println("(unresolved)")
        }
    }

    private fun projectChannel(currentDir: File): String? =
        findManifest(currentDir)?.let { f ->
            runCatching { Toml.decodeFromString(MezzeToml.serializer(), f.readText()).toolchain?.channel }.getOrNull()
        }

    private fun findManifest(dir: File): File? {
        var d: File? = dir.absoluteFile
        while (d != null) {
            File(d, "Mezze.toml").takeIf { it.isFile }?.let { return it }
            d = d.parentFile
        }
        return null
    }

    private fun usage() {
        println(
            """
            mez toolchain — inspect and pin Mezze toolchains

              list                 installed toolchains (default)
              which                which toolchain is active here, and why
              default <channel>    set the global default channel
              pin <channel>        pin this project (writes [toolchain] to Mezze.toml)
              dir                  the active toolchain's directory

            A channel is a track ("0.1", "nightly") or an exact version ("0.1.3").
            Precedence: MEZ_VERSION > project [toolchain] > global default > latest installed.
            """.trimIndent(),
        )
    }

    /** Add/replace `channel = "…"` under `[toolchain]` (create the section if absent). Line-based. */
    private fun upsertToolchain(text: String, channel: String): String {
        val lines = text.split("\n").toMutableList()
        val entry = "channel = \"$channel\""
        val secIdx = lines.indexOfFirst { it.trim() == "[toolchain]" }
        if (secIdx == -1) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
            lines.add("[toolchain]"); lines.add(entry)
            return lines.joinToString("\n")
        }
        var i = secIdx + 1
        while (i < lines.size) {
            if (Regex("""^\s*channel\s*=""").containsMatchIn(lines[i])) { lines[i] = entry; return lines.joinToString("\n") }
            val t = lines[i].trim()
            if (t.startsWith("[") && t.endsWith("]")) break
            i++
        }
        lines.add(i, entry)
        return lines.joinToString("\n")
    }
}
