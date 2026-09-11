package com.mezze.language.cli.`package`

import com.akuleshov7.ktoml.Toml
import java.io.File

/**
 * CLI-side view of `[polyglot.jvm]` for `WireRunner`. The interpreter
 * has its own reader (`com.mezze.interpreter.polyglot.PolyglotManifest`)
 * for `[polyglot.python]` — but the JVM classpath must be applied
 * BEFORE spawning the wire child, which is the CLI's job. Same file, two
 * consumers, different concerns.
 *
 * :cli intentionally does not link against :interpreter (see
 * `cli/build.gradle.kts`), so this small helper lives here rather than
 * being imported across the boundary.
 */
object PolyglotResolver {

    /**
     * Walk up from [startDir] looking for `Mezze.toml`, parse its
     * `[polyglot.jvm].classpath`, resolve each entry relative to the
     * manifest's directory, and return absolute paths.
     *
     * Returns empty list when no manifest exists, when the section is
     * absent, or when parsing fails — the wire child spawn should never
     * fail because polyglot config is malformed; the surface error will
     * come from the actual `java::type(...)` lookup at runtime.
     */
    fun resolveJvmClasspath(startDir: File): List<String> {
        val manifest = discover(startDir) ?: return emptyList()
        val manifestDir = manifest.parentFile ?: File(".")
        val toml = try {
            Toml.decodeFromString(MezzeToml.serializer(), manifest.readText())
        } catch (_: Exception) {
            return emptyList()
        }
        val entries = toml.polyglot?.jvm?.classpath.orEmpty()
        return entries.map { resolvePath(it, manifestDir) }
    }

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
