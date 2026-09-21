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
    /** User-declared pip-style specifiers (`packages = [...]`) for the mezze-python provider. */
    val pythonPackages: List<String> = emptyList(),
    /** Absolute JVM classpath entries appended before spawning the wire child. */
    val jvmClasspath: List<String>,
    /** URL#sha256 JAR entries under `[polyglot.jvm].packages` — verified/fetched by `mez sync`. */
    val jvmPackages: List<String> = emptyList(),
    /** True iff `[polyglot.js]` is present in the manifest. */
    val enableJs: Boolean = false,
    /** Absolute path to `node_modules/` for CommonJS require, or null. */
    val jsNodeModules: String? = null,
    /** Additional `js.<KEY>` options (KEY as declared in `[polyglot.js.options]`). */
    val jsOptions: Map<String, String> = emptyMap(),
    /** User-declared npm-style specifiers (`packages = [...]`) for the mezze-js provider. */
    val jsPackages: List<String> = emptyList(),
    /** True iff `[polyglot.wasm]` is present in the manifest. */
    val enableWasm: Boolean = false,
    /** Absolute paths to `.wasm` modules the manifest asked us to preload. */
    val wasmModules: List<String> = emptyList(),
    /** Additional `wasm.<KEY>` options (KEY as declared in `[polyglot.wasm.options]`). */
    val wasmOptions: Map<String, String> = emptyMap(),
    /** URL#sha256 `.wasm` entries under `[polyglot.wasm].packages`. */
    val wasmPackages: List<String> = emptyList(),
    /**
     * `[polyglot.provider]` — populated ONLY in provider-package manifests
     * (`mezze-python`, `mezze-js`, `mezze-wasm`, `mezze-jvm`). Runtime
     * consumers ignore this; `mez sync` reads it to invoke the install
     * template and pass the runtime env-var + dir back to the child.
     */
    val provider: ResolvedProvider? = null,
    /**
     * Universal `[jars]` map — `name → "url#sha256:hex64"`. Works both in
     * user projects (extra JVM classpath) and in provider packages (the
     * graal-language jars to fetch).
     */
    val jars: Map<String, String> = emptyMap(),
) {
    companion object {
        val NONE = ResolvedPolyglot(
            enablePython = false,
            pythonPath = null,
            pythonOptions = emptyMap(),
            pythonPackages = emptyList(),
            jvmClasspath = emptyList(),
            jvmPackages = emptyList(),
            enableJs = false,
            jsNodeModules = null,
            jsOptions = emptyMap(),
            jsPackages = emptyList(),
            enableWasm = false,
            wasmModules = emptyList(),
            wasmOptions = emptyMap(),
            wasmPackages = emptyList(),
            provider = null,
            jars = emptyMap(),
        )
    }
}

/** Provider-package `[polyglot.provider]` view. See [ResolvedPolyglot.provider]. */
data class ResolvedProvider(
    /** GraalVM language id (`"python"`, `"js"`, `"wasm"`, `"java"`). */
    val language: String,
    /** Shell install template — must contain `{packages}` and `{dest}`. */
    val install: String,
    /** Env-var the runtime sets to the resolved install dir. */
    val runtimeEnv: String,
    /** Relative dir where installed packages land (under project root). */
    val runtimeDir: String,
)

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
     * Walk every subdirectory of `<projectRoot>/.deps/mezze/` looking for
     * a package whose `[polyglot.provider]` claims [language], and return
     * that provider's `runtime_dir` resolved to an absolute path under
     * [projectRoot].
     *
     * Used by the runtime to auto-derive language-specific config
     * (e.g. `js.commonjs-require-cwd`) from the provider that installed
     * the packages — so a user project that opts in via
     * `[polyglot.<lang>].packages = [...]` doesn't also have to
     * hand-write a path pointing at wherever the provider decided to
     * put them.
     */
    /**
     * The directory GraalJS should resolve `require(…)` from, or null when
     * CommonJS support must stay OFF.
     *
     * Null when the directory DOES NOT EXIST — a backstop, not the mechanism.
     * `mez sync` creates every provider's declared `runtime_dir`, so in a
     * synced project it is there; this covers the project that was never
     * synced, or whose `.deps` was removed by hand.
     *
     * The guarantee used to be neither: sync created `.deps/node` only as a
     * side effect of npm running, which needs a non-empty `packages`, and the
     * callers enabled `js.commonjs-require-cwd` on the declared path
     * regardless. So a project opting in to plain ECMAScript with a bare
     * `[polyglot.js]` had EVERY eval fail, including `1 + 1`:
     *
     *     Error: Invalid CommonJS root folder: <root>/.deps/node
     *
     * An explicit `node_modules` is checked the same way. If the user names a
     * directory that is not there, `require` cannot work either way; the
     * choice is whether that breaks one call or the whole context, and one
     * call is strictly better.
     */
    fun commonJsRoot(projectRoot: File, explicitNodeModules: String?): String? {
        val candidate = explicitNodeModules?.takeIf { it.isNotBlank() }
            ?: findProviderRuntimeDir(projectRoot, "js")
            ?: return null
        return candidate.takeIf { File(it).isDirectory }
    }

    fun findProviderRuntimeDir(projectRoot: File, language: String): String? {
        val depsDir = File(projectRoot, ".deps/mezze")
        if (!depsDir.isDirectory) return null
        val entries = depsDir.listFiles() ?: return null
        for (entry in entries) {
            val depManifest = File(entry, MANIFEST_NAME)
            if (!depManifest.isFile) continue
            val text = try { depManifest.readText() } catch (_: Exception) { continue }
            val sections = try { parse(text) } catch (_: Exception) { continue }
            val prov = sections.provider ?: continue
            if (prov.language != language) continue
            val dir = prov.runtimeDir ?: continue
            return resolvePath(dir, projectRoot.absoluteFile)
        }
        return null
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
            pythonPackages = emptyList(),
            jvmClasspath = jvmClasspath,
            jvmPackages = emptyList(),
            enableJs = false,
            jsNodeModules = null,
            jsOptions = emptyMap(),
            jsPackages = emptyList(),
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
        // JS mirrors Python: manifest wins on every declared field.
        val enableJs = manifest.enableJs || sidecar.enableJs
        val jsNodeModules = manifest.jsNodeModules ?: sidecar.jsNodeModules
        val jsOptions = if (manifest.jsOptions.isNotEmpty()) manifest.jsOptions
        else sidecar.jsOptions
        // Wasm mirrors JS: manifest wins on every declared field.
        val enableWasm = manifest.enableWasm || sidecar.enableWasm
        val wasmModules = if (manifest.wasmModules.isEmpty()) sidecar.wasmModules
        else (sidecar.wasmModules + manifest.wasmModules).distinct()
        val wasmOptions = if (manifest.wasmOptions.isNotEmpty()) manifest.wasmOptions
        else sidecar.wasmOptions
        return ResolvedPolyglot(
            enablePython = enablePython,
            pythonPath = pythonPath,
            pythonOptions = pythonOptions,
            pythonPackages = if (manifest.pythonPackages.isNotEmpty()) manifest.pythonPackages else sidecar.pythonPackages,
            jvmClasspath = jvm,
            jvmPackages = if (manifest.jvmPackages.isNotEmpty()) manifest.jvmPackages else sidecar.jvmPackages,
            enableJs = enableJs,
            jsNodeModules = jsNodeModules,
            jsOptions = jsOptions,
            jsPackages = if (manifest.jsPackages.isNotEmpty()) manifest.jsPackages else sidecar.jsPackages,
            enableWasm = enableWasm,
            wasmModules = wasmModules,
            wasmOptions = wasmOptions,
            wasmPackages = if (manifest.wasmPackages.isNotEmpty()) manifest.wasmPackages else sidecar.wasmPackages,
            provider = manifest.provider ?: sidecar.provider,
            jars = if (manifest.jars.isNotEmpty()) manifest.jars else sidecar.jars,
        )
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
        val provider = if (parser.providerLanguage != null || parser.providerInstall != null ||
                parser.providerRuntimeEnv != null || parser.providerRuntimeDir != null) {
            RawProviderSection(
                language = parser.providerLanguage,
                install = parser.providerInstall,
                runtimeEnv = parser.providerRuntimeEnv,
                runtimeDir = parser.providerRuntimeDir,
            )
        } else null
        return PolyglotSections(
            pythonPresent = parser.pythonPresent,
            pythonPath = parser.pythonPath,
            pythonOptions = parser.pythonOptions.toMap(),
            pythonPackages = parser.pythonPackages.toList(),
            jvmClasspath = parser.jvmClasspath.toList(),
            jvmPackages = parser.jvmPackages.toList(),
            jsPresent = parser.jsPresent,
            jsNodeModules = parser.jsNodeModules,
            jsOptions = parser.jsOptions.toMap(),
            jsPackages = parser.jsPackages.toList(),
            wasmPresent = parser.wasmPresent,
            wasmModules = parser.wasmModules.toList(),
            wasmOptions = parser.wasmOptions.toMap(),
            wasmPackages = parser.wasmPackages.toList(),
            provider = provider,
            jars = parser.jars.toMap(),
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
        val resolvedJsNodeModules = sections.jsNodeModules?.let { resolvePath(it, base) }
        val resolvedWasmModules = sections.wasmModules.map { resolvePath(it, base) }
        // Provider is treated as fully-declared only when every required
        // field is present; the CLI-side validator rejects partial ones
        // with a source-location error so we never see a half-populated
        // section here during a real sync.
        val provider = sections.provider?.let { raw ->
            val lang = raw.language ?: return@let null
            val inst = raw.install ?: return@let null
            val env = raw.runtimeEnv ?: return@let null
            val dir = raw.runtimeDir ?: return@let null
            ResolvedProvider(language = lang, install = inst, runtimeEnv = env, runtimeDir = dir)
        }
        return ResolvedPolyglot(
            enablePython = sections.pythonPresent,
            pythonPath = resolvedPython,
            pythonOptions = sections.pythonOptions,
            pythonPackages = sections.pythonPackages,
            jvmClasspath = resolvedClasspath,
            jvmPackages = sections.jvmPackages,
            enableJs = sections.jsPresent,
            jsNodeModules = resolvedJsNodeModules,
            jsOptions = sections.jsOptions,
            jsPackages = sections.jsPackages,
            enableWasm = sections.wasmPresent,
            wasmModules = resolvedWasmModules,
            wasmOptions = sections.wasmOptions,
            wasmPackages = sections.wasmPackages,
            provider = provider,
            jars = sections.jars,
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
    val pythonPackages: List<String> = emptyList(),
    val jvmClasspath: List<String>,
    val jvmPackages: List<String> = emptyList(),
    val jsPresent: Boolean = false,
    val jsNodeModules: String? = null,
    val jsOptions: Map<String, String> = emptyMap(),
    val jsPackages: List<String> = emptyList(),
    val wasmPresent: Boolean = false,
    val wasmModules: List<String> = emptyList(),
    val wasmOptions: Map<String, String> = emptyMap(),
    val wasmPackages: List<String> = emptyList(),
    val provider: RawProviderSection? = null,
    val jars: Map<String, String> = emptyMap(),
)

/**
 * `[polyglot.provider]` values as they appeared in the TOML — before
 * validation. Every field is nullable; the CLI validator (Commit 2)
 * rejects manifests where any required field is missing.
 */
data class RawProviderSection(
    val language: String? = null,
    val install: String? = null,
    val runtimeEnv: String? = null,
    val runtimeDir: String? = null,
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
    val pythonPackages = mutableListOf<String>()
    val jvmClasspath = mutableListOf<String>()
    val jvmPackages = mutableListOf<String>()
    var jsPresent = false
    var jsNodeModules: String? = null
    val jsOptions = LinkedHashMap<String, String>()
    val jsPackages = mutableListOf<String>()
    var wasmPresent = false
    val wasmModules = mutableListOf<String>()
    val wasmOptions = LinkedHashMap<String, String>()
    val wasmPackages = mutableListOf<String>()
    var providerLanguage: String? = null
    var providerInstall: String? = null
    var providerRuntimeEnv: String? = null
    var providerRuntimeDir: String? = null
    val jars = LinkedHashMap<String, String>()

    private enum class Section {
        OUTSIDE, PYTHON, PYTHON_OPTIONS, JVM, JS, JS_OPTIONS, WASM, WASM_OPTIONS, PROVIDER, JARS,
    }

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
                    "polyglot.js" -> { jsPresent = true; Section.JS }
                    "polyglot.js.options" -> { jsPresent = true; Section.JS_OPTIONS }
                    "polyglot.wasm" -> { wasmPresent = true; Section.WASM }
                    "polyglot.wasm.options" -> { wasmPresent = true; Section.WASM_OPTIONS }
                    "polyglot.provider" -> Section.PROVIDER
                    "jars" -> Section.JARS
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
                when (key) {
                    "python_path" -> pythonPath = parseString(rhs) ?: pythonPath
                    "packages" -> pythonPackages.addAll(parseStringArray(rhs))
                }
            }
            Section.PYTHON_OPTIONS -> {
                val v = parseString(rhs) ?: return
                pythonOptions[key] = v
            }
            Section.JVM -> {
                when (key) {
                    "classpath" -> jvmClasspath.addAll(parseStringArray(rhs))
                    "packages" -> jvmPackages.addAll(parseStringArray(rhs))
                }
            }
            Section.JS -> {
                when (key) {
                    "node_modules" -> jsNodeModules = parseString(rhs) ?: jsNodeModules
                    "packages" -> jsPackages.addAll(parseStringArray(rhs))
                }
            }
            Section.JS_OPTIONS -> {
                val v = parseString(rhs) ?: return
                jsOptions[key] = v
            }
            Section.WASM -> {
                when (key) {
                    "modules" -> wasmModules.addAll(parseStringArray(rhs))
                    "packages" -> wasmPackages.addAll(parseStringArray(rhs))
                }
            }
            Section.WASM_OPTIONS -> {
                val v = parseString(rhs) ?: return
                wasmOptions[key] = v
            }
            Section.PROVIDER -> {
                val v = parseString(rhs) ?: return
                when (key) {
                    "language" -> providerLanguage = v
                    "install" -> providerInstall = v
                    "runtime_env" -> providerRuntimeEnv = v
                    "runtime_dir" -> providerRuntimeDir = v
                }
            }
            Section.JARS -> {
                val v = parseString(rhs) ?: return
                jars[key] = v
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
