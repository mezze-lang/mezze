package com.mezze.interpreter.polyglot

import java.io.File

/**
 * Runtime-side reader for the `[polyglot.*]` sections of `Mezze.toml`.
 *
 * The rest of the manifest (package / app / dependencies) is the CLI's
 * concern; the runtime only needs to know:
 *
 *   1. Whether to enable the `python` language on the Truffle Context
 *      (`[polyglot.python]` declared)
 *   2. Where GraalPy should look for `sys.path` (`python_path`)
 *   3. Extra `python.<KEY> = <VALUE>` Context options
 *      (`[polyglot.python.options]`)
 *   4. Extra JVM classpath entries `mez dev/exec`'s parent adds when
 *      spawning the wire child (`[polyglot.jvm]` `classpath`)
 *
 * Kept out of the CLI's `MezzeToml.kt` (ktoml) because interpreter code
 * — `EmbeddedExec` (native binary) and `RuntimeServer` (wire child) —
 * must read this at runtime without pulling in ktoml.
 *
 * The parser is deliberately narrow: it ignores every table outside
 * `polyglot.*` and every value shape it doesn't understand, so it can
 * skip past the rest of the manifest without a full TOML implementation.
 */
data class ResolvedPolyglot(
    /** True iff `[polyglot.python]` is present in the manifest. */
    val enablePython: Boolean,
    /** Absolute `python.PythonPath` value (venv site-packages, etc.), or null. */
    val pythonPath: String?,
    /** Additional `python.<KEY>` options (KEY as declared in `[polyglot.python.options]`). */
    val pythonOptions: Map<String, String>,
    /** Absolute JVM classpath entries appended before spawning the wire child. */
    val jvmClasspath: List<String>,
) {
    companion object {
        val NONE = ResolvedPolyglot(false, null, emptyMap(), emptyList())
    }
}

object PolyglotManifest {

    /** Filename of the manifest we walk up looking for. */
    private const val MANIFEST_NAME = "Mezze.toml"

    /**
     * Find the nearest `Mezze.toml` at [startDir] or any ancestor. Returns
     * null when no ancestor holds one (walked all the way to filesystem root).
     */
    fun discover(startDir: File): File? {
        var cur: File? = startDir.absoluteFile.canonicalFile
        while (cur != null) {
            val candidate = File(cur, MANIFEST_NAME)
            if (candidate.isFile) return candidate
            cur = cur.parentFile
        }
        return null
    }

    /**
     * Read + parse + resolve. Returns `ResolvedPolyglot.NONE` when no
     * manifest exists in the ancestor chain (nothing to enable) OR when
     * the manifest has no `[polyglot.*]` sections.
     */
    fun load(startDir: File): ResolvedPolyglot {
        val manifest = discover(startDir) ?: return ResolvedPolyglot.NONE
        return loadFrom(manifest)
    }

    /**
     * As [load], but for a native binary that may have been shipped
     * outside its build tree. Falls back to sidecar assets next to
     * [selfExe] when the manifest is missing OR present but omits a
     * required section. Two sidecars are recognised:
     *
     *   - `<stem>-venv/lib/pythonX.Y/site-packages` for GraalPy
     *   - `<stem>-jvm/` containing JVM classpath jars
     *
     * where `<stem>` is the binary's file-name stem. Both are what
     * `NativeBuildPath.bundlePolyglotAssets` emits at build time.
     *
     * Priority: an explicit `Mezze.toml` in the ancestor chain wins.
     * That mirrors dev-mode behaviour: developers may `cd project && ./binary`
     * to iterate against a live venv without redeploying the sidecar.
     * When no manifest is discovered the sidecars take effect — the
     * shipped binary "just works" from any cwd.
     */
    fun loadForBinary(cwd: File, selfExe: File?): ResolvedPolyglot {
        val fromManifest = load(cwd)
        val sidecar = sidecarLookup(selfExe)
        return merge(fromManifest, sidecar)
    }

    /**
     * Look for `<stem>-venv/` and `<stem>-jvm/` next to the binary.
     * Returns `ResolvedPolyglot.NONE` when the binary path is unknown
     * or neither sidecar directory exists.
     */
    fun sidecarLookup(selfExe: File?): ResolvedPolyglot {
        if (selfExe == null) return ResolvedPolyglot.NONE
        val parent = selfExe.parentFile ?: return ResolvedPolyglot.NONE
        val stem = selfExe.nameWithoutExtension.takeIf { it.isNotEmpty() } ?: selfExe.name

        var enablePython = false
        var pythonPath: String? = null
        val venvDir = File(parent, "$stem-venv")
        if (venvDir.isDirectory) {
            // The venv dir's presence alone is the "python enabled"
            // marker: NativeBuildPath writes an empty `<stem>-venv/`
            // even for pure-stdlib builds that don't need a
            // `python_path`. Then we look inside for a specific
            // pythonX.Y/site-packages to feed as `python.PythonPath`.
            enablePython = true
            val libDir = File(venvDir, "lib")
            val pyLibDir = libDir.listFiles { f -> f.isDirectory && f.name.startsWith("python") }
                ?.maxByOrNull { it.name }
            val siteDir = pyLibDir?.let { File(it, "site-packages") }
            if (siteDir?.isDirectory == true) {
                pythonPath = try { siteDir.canonicalPath } catch (_: Exception) { siteDir.absolutePath }
            }
        }

        val jvmClasspath = mutableListOf<String>()
        val jvmDir = File(parent, "$stem-jvm")
        if (jvmDir.isDirectory) {
            jvmDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                ?.sortedBy { it.name }
                ?.forEach { f ->
                    jvmClasspath.add(try { f.canonicalPath } catch (_: Exception) { f.absolutePath })
                }
        }

        if (!enablePython && jvmClasspath.isEmpty()) return ResolvedPolyglot.NONE
        return ResolvedPolyglot(
            enablePython = enablePython,
            pythonPath = pythonPath,
            pythonOptions = emptyMap(),
            jvmClasspath = jvmClasspath,
        )
    }

    /**
     * Merge two resolved views: explicit [manifest] config wins on any
     * field it declares; [sidecar] fills the gaps. This gives dev-mode
     * (running from a project directory) the ability to iterate on
     * python_path without moving the sidecar, and shipped-binary mode
     * (`cp` binary + sidecar to /usr/local/bin) full autonomy.
     */
    private fun merge(manifest: ResolvedPolyglot, sidecar: ResolvedPolyglot): ResolvedPolyglot {
        // If the manifest already enabled python with a path, use its
        // values verbatim. Otherwise let the sidecar contribute.
        val enablePython = manifest.enablePython || sidecar.enablePython
        val pythonPath = manifest.pythonPath ?: sidecar.pythonPath
        val pythonOptions = if (manifest.pythonOptions.isNotEmpty()) manifest.pythonOptions
        else sidecar.pythonOptions
        // JVM classpaths concat (sidecar first — user-supplied wins on
        // duplicate class resolution via Java's usual last-writer rules;
        // both contribute).
        val jvm = if (manifest.jvmClasspath.isEmpty()) sidecar.jvmClasspath
        else (sidecar.jvmClasspath + manifest.jvmClasspath).distinct()
        return ResolvedPolyglot(enablePython, pythonPath, pythonOptions, jvm)
    }

    /** Read + parse + resolve against an explicit manifest file. */
    fun loadFrom(manifest: File): ResolvedPolyglot {
        val text = try { manifest.readText() } catch (_: Exception) { return ResolvedPolyglot.NONE }
        val sections = parse(text)
        return resolve(sections, manifest.parentFile ?: File("."))
    }

    /**
     * Parse `[polyglot.*]` sections out of [tomlText]. Public for tests;
     * production code goes through [load] / [loadFrom].
     */
    fun parse(tomlText: String): PolyglotSections {
        val parser = MiniTomlParser(tomlText)
        parser.parse()
        return PolyglotSections(
            pythonPresent = parser.pythonPresent,
            pythonPath = parser.pythonPath,
            pythonOptions = parser.pythonOptions.toMap(),
            jvmClasspath = parser.jvmClasspath.toList(),
        )
    }

    /**
     * Resolve raw section values against [manifestDir]:
     *
     *   - `python_path` and every `classpath` entry pass through if
     *     absolute; expand `~` / `~/` to the user home; otherwise
     *     resolve relative to [manifestDir]. The result is always an
     *     absolute, canonical path so the runtime can point GraalPy /
     *     the JVM launcher at it regardless of the child's cwd.
     *
     *   - Python options are opaque strings — no path resolution; they
     *     forward straight to the Truffle Context builder.
     */
    fun resolve(sections: PolyglotSections, manifestDir: File): ResolvedPolyglot {
        val base = manifestDir.absoluteFile
        val resolvedPython = sections.pythonPath?.let { resolvePath(it, base) }
        val resolvedClasspath = sections.jvmClasspath.map { resolvePath(it, base) }
        return ResolvedPolyglot(
            enablePython = sections.pythonPresent,
            pythonPath = resolvedPython,
            pythonOptions = sections.pythonOptions,
            jvmClasspath = resolvedClasspath,
        )
    }

    /** Expand `~` / `~/…` and resolve relative paths against [base]. */
    private fun resolvePath(raw: String, base: File): String {
        val expanded = when {
            raw == "~" -> System.getProperty("user.home")
            raw.startsWith("~/") -> System.getProperty("user.home") + raw.substring(1)
            raw.startsWith("\$HOME/") -> System.getProperty("user.home") + raw.substring(5)
            else -> raw
        }
        val f = File(expanded)
        val abs = if (f.isAbsolute) f else File(base, expanded)
        return try { abs.canonicalPath } catch (_: Exception) { abs.absolutePath }
    }
}

/**
 * Raw section values as they appeared in the TOML — no path resolution,
 * no `~` expansion. [PolyglotManifest.resolve] turns this into a
 * [ResolvedPolyglot] the runtime can point Truffle at.
 */
data class PolyglotSections(
    val pythonPresent: Boolean,
    val pythonPath: String?,
    val pythonOptions: Map<String, String>,
    val jvmClasspath: List<String>,
)

/**
 * Minimal TOML reader — table headers, string values, string arrays.
 * Only tracks what `[polyglot.*]` sections need; every other table
 * / dotted key / value shape is skipped without error so this stays
 * robust to whatever else lives in the manifest (package, app, deps).
 *
 * Not a compliant TOML parser: it deliberately doesn't understand
 * inline tables, dates, integers, booleans, multi-line strings, etc.
 * If future polyglot sections grow richer value shapes, extend here.
 */
private class MiniTomlParser(private val text: String) {
    var pythonPresent = false
    var pythonPath: String? = null
    val pythonOptions = LinkedHashMap<String, String>()
    val jvmClasspath = mutableListOf<String>()

    private enum class Section { OUTSIDE, PYTHON, PYTHON_OPTIONS, JVM }

    fun parse() {
        var section = Section.OUTSIDE
        var pending: String? = null  // continuation buffer for multi-line arrays

        for (rawLine in text.lines()) {
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue

            if (pending != null) {
                pending += " " + line
                if (line.contains(']')) {
                    handleAssignment(pending!!, section)
                    pending = null
                }
                continue
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                val header = line.substring(1, line.length - 1).trim()
                section = when (header) {
                    "polyglot.python" -> { pythonPresent = true; Section.PYTHON }
                    "polyglot.python.options" -> { pythonPresent = true; Section.PYTHON_OPTIONS }
                    "polyglot.jvm" -> Section.JVM
                    else -> Section.OUTSIDE
                }
                continue
            }

            if (section == Section.OUTSIDE) continue

            // Assignment. May be a single-line value or the opening of a
            // multi-line array. Detect the array-multiline case: an
            // opening `[` on the RHS with no matching `]` on this line.
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val rhs = line.substring(eq + 1).trim()
            if (rhs.startsWith("[") && !rhs.contains(']')) {
                pending = line
                continue
            }
            handleAssignment(line, section)
        }
    }

    private fun handleAssignment(line: String, section: Section) {
        val eq = line.indexOf('=')
        if (eq <= 0) return
        val key = line.substring(0, eq).trim().trim('"')
        val rhs = line.substring(eq + 1).trim()

        when (section) {
            Section.PYTHON -> {
                if (key == "python_path") {
                    pythonPath = parseString(rhs) ?: pythonPath
                }
            }
            Section.PYTHON_OPTIONS -> {
                val v = parseString(rhs) ?: return
                pythonOptions[key] = v
            }
            Section.JVM -> {
                if (key == "classpath") {
                    jvmClasspath.addAll(parseStringArray(rhs))
                }
            }
            Section.OUTSIDE -> Unit
        }
    }

    private fun stripComment(line: String): String {
        // Strip `#` comments except inside a "..." string. Simple state
        // machine — no escape handling since TOML basic strings that
        // matter here (paths, option names) don't need it.
        val sb = StringBuilder()
        var inStr = false
        for (ch in line) {
            if (ch == '"') { inStr = !inStr; sb.append(ch); continue }
            if (ch == '#' && !inStr) break
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Parse a TOML basic string `"..."`. Returns null if malformed. */
    private fun parseString(raw: String): String? {
        val s = raw.trim()
        if (s.length < 2 || !s.startsWith('"') || !s.endsWith('"')) return null
        val inner = s.substring(1, s.length - 1)
        // Handle the small set of escapes callers might reasonably
        // write in a path or option value.
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val next = inner[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    else -> { sb.append(c); sb.append(next) }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Parse `["a", "b", ...]`. Whitespace / trailing commas tolerated. */
    private fun parseStringArray(raw: String): List<String> {
        val s = raw.trim()
        if (!s.startsWith("[") || !s.endsWith("]")) return emptyList()
        val inner = s.substring(1, s.length - 1)
        val out = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            // Skip whitespace / commas.
            while (i < inner.length && (inner[i].isWhitespace() || inner[i] == ',')) i++
            if (i >= inner.length) break
            if (inner[i] != '"') { i++; continue }
            val end = inner.indexOf('"', i + 1)
            if (end < 0) break
            out.add(inner.substring(i + 1, end))
            i = end + 1
        }
        return out
    }
}
