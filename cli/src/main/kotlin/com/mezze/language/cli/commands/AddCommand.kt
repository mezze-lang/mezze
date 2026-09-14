package com.mezze.language.cli.commands

import com.mezze.language.cli.`package`.*
import java.io.File

/**
 * `mez add` / `mez remove` — edit Mezze.toml's dependency sections **in place**,
 * line-based, so the user's comments and formatting are preserved (we never
 * re-serialize the manifest).
 *
 *   mez add <name> --path ../x                 # [path-dependencies]
 *   mez add <name> --url <url>#sha256:<hash>   # [dependencies]
 *   mez add <url>#sha256:<hash>                # [dependencies], name derived from url
 *   mez add ... --dev                          # [dev-dependencies] (registry deps)
 *   mez remove <name>                          # from whichever dependency section
 */
class AddCommand {

    private companion object {
        const val DEPS = "[dependencies]"
        const val DEV = "[dev-dependencies]"
        const val PATH = "[path-dependencies]"
        val DEP_SECTIONS = setOf(DEPS, DEV, PATH)
    }

    fun executeAdd(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val toml = File(currentDir, "Mezze.toml")
        if (!toml.exists()) return noManifest(currentDir)

        val p = parseArgs(args)
        val isDev = "--dev" in p.flags

        val (name, value, section) = when {
            p.flags.containsKey("--path") -> {
                val path = p.flags["--path"] ?: return usage("add <name> --path <dir>")
                val n = p.positionals.firstOrNull() ?: return usage("add <name> --path <dir>")
                if (!checkName(n)) return
                Triple(n, path, PATH)
            }
            p.flags.containsKey("--url") -> {
                val url = p.flags["--url"] ?: return usage("add <name> --url <url>#sha256:<hash>")
                val n = p.positionals.firstOrNull() ?: return usage("add <name> --url <url>#sha256:<hash>")
                if (!checkName(n) || !checkUrl(url)) return
                Triple(n, url, if (isDev) DEV else DEPS)
            }
            p.positionals.firstOrNull()?.startsWith("github:") == true -> {
                // `mez add github:<owner>/<repo>@<ref>` — a GitHub dependency.
                // Name derives from the repo, normalized to its module spelling
                // (`-`→`_`) so `use <name>::…` resolves. An explicit positional
                // name before the spec overrides (rare).
                val spec = p.positionals.first { it.startsWith("github:") }
                val explicitName = p.positionals.firstOrNull { !it.startsWith("github:") }
                val parsed = try {
                    com.mezze.language.cli.`package`.parseGithubDep(spec)
                } catch (e: IllegalArgumentException) { println(e.message); return }
                val n = explicitName ?: com.mezze.language.cli.`package`.packageNameToModule(parsed.second)
                if (!checkName(n)) return
                Triple(n, spec, if (isDev) DEV else DEPS)
            }
            else -> {
                val url = p.positionals.firstOrNull()
                    ?: return usage("add <name> --path <dir>   |   add github:<owner>/<repo>@<ref>   |   add <url>#sha256:<hash>")
                if (!checkUrl(url)) return
                val n = try { extractPackageNameFromUrl(url) } catch (e: IllegalArgumentException) { println(e.message); return }
                Triple(n, url, if (isDev) DEV else DEPS)
            }
        }

        toml.writeText(upsert(toml.readText(), section, name, value))
        println("✅ ${section.trim('[', ']')}: $name = \"$value\"")
        println()
        println("💡 Run 'mez sync' to materialize it.")
    }

    fun executeRemove(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val toml = File(currentDir, "Mezze.toml")
        if (!toml.exists()) return noManifest(currentDir)

        val name = parseArgs(args).positionals.firstOrNull() ?: return usage("remove <name>")
        val (updated, removed) = removeFromDepSections(toml.readText(), name)
        if (!removed) {
            println("❌ '$name' is not a dependency.")
            return
        }
        toml.writeText(updated)
        println("✅ Removed $name")
        println()
        println("💡 Run 'mez sync' to update.")
    }

    // --- arg parsing -------------------------------------------------------

    private data class Parsed(val positionals: List<String>, val flags: Map<String, String?>)

    /** `--path`/`--url` consume the next token as their value; `--dev` is a bare flag. */
    private fun parseArgs(args: List<String>): Parsed {
        val positionals = mutableListOf<String>()
        val flags = mutableMapOf<String, String?>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--path" || a == "--url" -> { flags[a] = args.getOrNull(i + 1); i++ }
                a.startsWith("--") -> flags[a] = null
                else -> positionals.add(a)
            }
            i++
        }
        return Parsed(positionals, flags)
    }

    // --- manifest editing (line-based, formatting-preserving) --------------

    /** Add or replace `name = "value"` inside [section], creating the section if absent. */
    private fun upsert(text: String, section: String, name: String, value: String): String {
        val lines = text.split("\n").toMutableList()
        val entry = "$name = \"$value\""
        val keyRe = Regex("^\\s*${Regex.escape(name)}\\s*=")
        val secIdx = lines.indexOfFirst { it.trim() == section }

        if (secIdx == -1) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
            lines.add(section)
            lines.add(entry)
            return lines.joinToString("\n")
        }

        var i = secIdx + 1
        while (i < lines.size) {
            if (keyRe.containsMatchIn(lines[i])) {          // key already present -> replace
                lines[i] = entry
                return lines.joinToString("\n")
            }
            val t = lines[i].trim()
            if (t.startsWith("[") && t.endsWith("]")) break // hit next section
            i++
        }
        lines.add(i, entry)
        return lines.joinToString("\n")
    }

    /** Remove `name = …` but ONLY inside dependency sections (never touches [package] etc). */
    private fun removeFromDepSections(text: String, name: String): Pair<String, Boolean> {
        val keyRe = Regex("^\\s*${Regex.escape(name)}\\s*=")
        val out = mutableListOf<String>()
        var current = ""
        var removed = false
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                current = t
                out.add(line)
            } else if (current in DEP_SECTIONS && keyRe.containsMatchIn(line)) {
                removed = true // drop this line
            } else {
                out.add(line)
            }
        }
        return out.joinToString("\n") to removed
    }

    // --- small helpers -----------------------------------------------------

    private fun checkName(n: String): Boolean =
        try { validatePackageName(n); true } catch (e: IllegalArgumentException) { println(e.message); false }

    private fun checkUrl(u: String): Boolean =
        try { parseDependencyUrl(u); true } catch (e: IllegalArgumentException) { println(e.message); false }

    private fun noManifest(dir: File) {
        println("❌ No Mezze.toml in ${dir.absolutePath}")
        println("💡 Run 'mez init' to create a package first.")
    }

    private fun usage(form: String) {
        println("Usage: mez $form")
    }
}
