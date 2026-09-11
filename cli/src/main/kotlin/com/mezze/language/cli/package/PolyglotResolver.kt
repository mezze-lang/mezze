package com.mezze.language.cli.`package`

import com.akuleshov7.ktoml.Toml
import java.io.File

/**
 * CLI-side view of `[polyglot.*]` sections for `WireRunner` (JVM
 * classpath) and `BuildCommand`/`NativeBuildPath` (native-image build
 * inputs). The interpreter has its own reader
 * (`com.mezze.interpreter.polyglot.PolyglotManifest`) that
 * `EmbeddedExec` and `RuntimeServer` use at runtime — but the
 * BUILD-TIME native-image plumbing has to see the same config from the
 * CLI side, where :interpreter isn't on the compile classpath.
 *
 * :cli intentionally does not link against :interpreter (see
 * `cli/build.gradle.kts`), so this small helper lives here rather than
 * being imported across the boundary.
 */
object PolyglotResolver {

    /**
     * The resolved shape of `[polyglot.*]` — same fields as the
     * interpreter's `ResolvedPolyglot`, kept in sync but not shared
     * (see class-level rationale above).
     */
    data class Resolved(
        /** True iff `[polyglot.python]` is present in the manifest. */
        val enablePython: Boolean,
        /** Absolute `python.PythonPath` value, or null. */
        val pythonPath: String?,
        /** Additional `python.<KEY>` options. */
        val pythonOptions: Map<String, String>,
        /** Absolute JVM classpath entries from `[polyglot.jvm].classpath`. */
        val jvmClasspath: List<String>,
        /** Absolute path to the manifest directory (for resolving venv location). */
        val manifestDir: File?,
    ) {
        companion object {
            val NONE = Resolved(false, null, emptyMap(), emptyList(), null)
        }
    }

    /**
     * Walk up from [startDir] looking for `Mezze.toml`, parse every
     * `[polyglot.*]` section, and return the resolved view. Every path
     * is absolute + canonical.
     *
     * Returns [Resolved.NONE] when no manifest exists or when parsing
     * fails — the caller decides whether that's OK; malformed manifests
     * should never crash the build path, the runtime will surface the
     * real error.
     */
    fun resolve(startDir: File): Resolved {
        val manifest = discover(startDir) ?: return Resolved.NONE
        val manifestDir = manifest.parentFile ?: File(".")
        val toml = try {
            Toml.decodeFromString(MezzeToml.serializer(), manifest.readText())
        } catch (_: Exception) {
            return Resolved.NONE
        }
        val py = toml.polyglot?.python
        val enablePython = py != null
        val resolvedPy = py?.pythonPath?.let { resolvePath(it, manifestDir) }
        val jvm = toml.polyglot?.jvm?.classpath.orEmpty()
        return Resolved(
            enablePython = enablePython,
            pythonPath = resolvedPy,
            pythonOptions = py?.options.orEmpty(),
            jvmClasspath = jvm.map { resolvePath(it, manifestDir) },
            manifestDir = manifestDir.absoluteFile,
        )
    }

    /**
     * Legacy accessor kept for `WireRunner` — returns just the JVM
     * classpath entries. New callers should use [resolve].
     */
    fun resolveJvmClasspath(startDir: File): List<String> =
        resolve(startDir).jvmClasspath

    /**
     * Nearest `Mezze.toml` at [startDir] or any ancestor. Kept in sync
     * with the interpreter-side walker so both agree on which manifest
     * a given cwd belongs to.
     */
    fun discover(startDir: File): File? {
        var cur: File? = try { startDir.absoluteFile.canonicalFile } catch (_: Exception) { startDir.absoluteFile }
        while (cur != null) {
            val candidate = File(cur, "Mezze.toml")
            if (candidate.isFile) return candidate
            cur = cur.parentFile
        }
        return null
    }

    /** Same rules as `PolyglotManifest.resolvePath` — kept in sync. */
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
