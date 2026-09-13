package com.mezze.language.cli.`package`

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Data classes representing Mezze.toml manifest file structure
 *
 * Example Mezze.toml:
 * ```
 * [package]
 * name = "http_client"
 * version = "0.2.1"
 *
 * [lib]
 * path = "src/lib.mz"
 *
 * [[app]]
 * name = "http_client"
 * path = "src/app.mz"
 *
 * [dependencies]
 * json = "https://github.com/mezze-lang/json/releases/download/v1.2.3/json.tar.gz#sha256:abc123..."
 *
 * [dev-dependencies]
 * test_framework = "https://github.com/mezze-lang/test/releases/download/v0.8.0/test.tar.gz#sha256:def456..."
 * ```
 */
@Serializable
data class MezzeToml(
    val `package`: PackageMetadata,
    val lib: LibraryConfig? = null,
    val app: List<AppConfig>? = null,
    val build: BuildConfig = BuildConfig(),
    val dependencies: Map<String, String> = emptyMap(),
    @SerialName("dev-dependencies")
    val devDependencies: Map<String, String> = emptyMap(),
    /** Per-project toolchain pin — which `mez` version builds/runs this project. */
    val toolchain: ToolchainConfig? = null,
    /**
     * Local path dependencies — `name = "../relative/or/abs/path"` pointing at
     * another Mezze package on disk. `mez sync` symlinks each into
     * `.deps/mezze/<name>-<version>/`, where the module resolver already looks,
     * so `use <name>::...` resolves against live source (edits reflect
     * immediately). Registry deps (URL#sha256) stay in `[dependencies]`.
     */
    @SerialName("path-dependencies")
    val pathDependencies: Map<String, String> = emptyMap(),
    /**
     * Optional polyglot configuration. Runtime code (`EmbeddedExec`,
     * `RuntimeServer`) reads these sections independently via
     * `PolyglotManifest` — the field lives here purely so `mez check`
     * and friends can validate schema shape. Absence == no polyglot.
     *
     * User project schema — one field per language, `packages`, whose
     * entries are language-native specifiers interpreted by that
     * language's provider package (mezze-python / mezze-js / …):
     *
     *     [polyglot.python]
     *     packages = ["numpy>=1.24.0", "requests"]
     *
     *     [polyglot.js]
     *     packages = ["lodash@4.17.21"]
     *
     *     [polyglot.wasm]
     *     packages = ["https://.../foo.wasm#sha256:..."]
     *
     *     [polyglot.jvm]
     *     packages = ["https://repo1.maven.org/.../commons-lang3-3.14.0.jar#sha256:..."]
     *
     * Provider packages (mezze-python etc.) declare `[polyglot.provider]`
     * with the install command template + runtime env; user projects
     * never write this section.
     *
     * Legacy fields (`python_path`, `classpath`, `node_modules`, `modules`)
     * stay parseable so pre-migration projects and bundled examples keep
     * running while the provider ecosystem grows.
     */
    val polyglot: PolyglotSection? = null,
    /**
     * Universal `[jars]` table — a map of `name = "url#sha256:hex64"`.
     * Works both in user projects (as extra JVM classpath entries) and
     * in provider packages (declaring the graal-language jars to fetch).
     * `mez sync` verifies each sha256 against `~/.mezze/store/jars/<sha>/`.
     */
    val jars: Map<String, String> = emptyMap(),
)

/**
 * `[toolchain]` — pins the Mezze toolchain a project uses.
 *
 *     [toolchain]
 *     channel = "0.1"      # a track ("0.1", "nightly") or an exact pin ("0.1.3")
 *
 * `mez` resolves this against the toolchains installed in `~/.mezze/toolchains/`
 * (see ToolchainResolver). Overridden by `mez +<ver>` or `MEZ_VERSION`.
 */
@Serializable
data class ToolchainConfig(
    val channel: String? = null,
)

/**
 * Manifest-level polyglot block. Each language sub-section is optional.
 * `provider` is only meaningful in a provider package (mezze-python,
 * mezze-js, …); user projects leave it null.
 */
@Serializable
data class PolyglotSection(
    val python: PythonSection? = null,
    val jvm: JvmSection? = null,
    val js: JsSection? = null,
    val wasm: WasmSection? = null,
    val provider: ProviderSection? = null,
)

/**
 * `[polyglot.python]`. Presence of the section itself opts the run in
 * to the `python` Truffle language — no boolean flag needed.
 *
 * `packages` is the new user-facing field: a list of pip specifiers
 * (`"numpy>=1.24.0"`, `"requests==2.31.0"`, …) that the provider
 * package's install template turns into a real install at sync time.
 *
 * `python_path` is legacy — pre-provider projects that ship their own
 * venv still use it. Resolved relative to this manifest's directory;
 * absolute paths pass through.
 */
@Serializable
data class PythonSection(
    /** Pip-style package specifiers; the mezze-python provider installs them. */
    val packages: List<String> = emptyList(),
    @SerialName("python_path")
    val pythonPath: String? = null,
    val options: Map<String, String> = emptyMap(),
)

/**
 * `[polyglot.jvm]`. `packages` are `url#sha256:hex64` JAR URLs (the new
 * scheme); `classpath` is the legacy list of local jar paths. Both
 * appear on the wire-child JVM's `-cp` so `java::type("com.foo.Bar")`
 * resolves. Legacy paths follow the same resolution rules as
 * [PythonSection.pythonPath].
 */
@Serializable
data class JvmSection(
    /** URL#sha256 jar entries fetched into `~/.mezze/store/jars/<sha>/`. */
    val packages: List<String> = emptyList(),
    val classpath: List<String> = emptyList(),
)

/**
 * `[polyglot.js]`. Presence enables the `js` Truffle language.
 * `packages` are npm-style specifiers (`"lodash@4.17.21"`), interpreted
 * by the mezze-js provider. `node_modules` is legacy — pre-provider
 * projects that ship their own `node_modules/` still use it.
 */
@Serializable
data class JsSection(
    /** npm-style specifiers; the mezze-js provider installs them. */
    val packages: List<String> = emptyList(),
    @SerialName("node_modules")
    val nodeModules: String? = null,
    val options: Map<String, String> = emptyMap(),
)

/**
 * `[polyglot.wasm]`. Presence enables the `wasm` Truffle language.
 * `packages` are `url#sha256:hex64` entries pointing at `.wasm` modules;
 * `modules` is legacy — pre-provider projects that hand-list local
 * modules still use it.
 */
@Serializable
data class WasmSection(
    /** URL#sha256 .wasm module entries. */
    val packages: List<String> = emptyList(),
    val modules: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
)

/**
 * `[polyglot.provider]` — declared ONLY in a provider package's
 * `Mezze.toml`. Tells `mez sync` how to install the user's declared
 * `packages` and tells the runtime how to expose the install location
 * to the underlying Graal language. User projects never write this.
 *
 *     [polyglot.provider]
 *     language     = "python"                                                     # graalvm language id
 *     install      = "{prefix}/bin/graalpy -m pip install --target {dest} {packages}"
 *     runtime_env  = "PYTHONPATH"
 *     runtime_dir  = ".deps/python"
 *
 * `install` MUST contain both `{packages}` and `{dest}` placeholders;
 * `{prefix}` is optional (expands to the provider's install prefix).
 */
@Serializable
data class ProviderSection(
    /** GraalVM language id (`"python"`, `"js"`, `"wasm"`, `"java"`). */
    val language: String? = null,
    /** Shell install command template with `{packages}` + `{dest}` placeholders. */
    val install: String? = null,
    /** Env-var name the runtime sets to the resolved install dir. */
    @SerialName("runtime_env")
    val runtimeEnv: String? = null,
    /** Relative dir where installed packages land (under project root). */
    @SerialName("runtime_dir")
    val runtimeDir: String? = null,
)

/**
 * Build-output configuration. `mez build` writes JARs / launchers under
 * the [out] directory. Override with `--out=<path>` on the CLI or with
 * a `[build]` section in Mezze.toml:
 *
 *     [build]
 *     out = "dist"
 *
 * The default lives in [BuildConfig.DEFAULT_OUT] — single source of
 * truth, so callers that want to display "the default would be X" can
 * reference it instead of hard-coding the string.
 */
@Serializable
data class BuildConfig(
    val out: String = DEFAULT_OUT,
) {
    companion object {
        /** Default directory `mez build` writes to when no override is set. */
        const val DEFAULT_OUT: String = ".build"
    }
}

@Serializable
data class PackageMetadata(
    val name: String,
    val version: String
)

@Serializable
data class LibraryConfig(
    val path: String = "src/lib.mz"
)

@Serializable
data class AppConfig(
    val name: String,
    val path: String = "src/app.mz",
    /** Entry point function name. Defaults to "main". */
    val entry: String = "main"
)

/**
 * Parse dependency URL with checksum
 *
 * Example: "https://github.com/user/pkg/releases/download/v1.0.0/pkg.tar.gz#sha256:abc123..."
 *
 * Returns: Pair(url, checksum)
 */
fun parseDependencyUrl(fullUrl: String): Pair<String, String> {
    val parts = fullUrl.split("#sha256:")

    if (parts.size != 2) {
        throw IllegalArgumentException(
            "Invalid dependency URL format. Expected: <url>#sha256:<checksum>\n" +
            "Got: $fullUrl\n\n" +
            "💡 Dependency URLs must include a SHA-256 checksum for security.\n" +
            "   Example: https://github.com/user/pkg/releases/download/v1.0.0/pkg.tar.gz#sha256:abc123..."
        )
    }

    val url = parts[0]
    val checksum = parts[1]

    if (checksum.length != 64) {
        throw IllegalArgumentException(
            "Invalid SHA-256 checksum length. Expected 64 hex characters, got ${checksum.length}.\n" +
            "Checksum: $checksum\n\n" +
            "💡 SHA-256 checksums must be exactly 64 hexadecimal characters."
        )
    }

    if (!checksum.matches(Regex("[0-9a-fA-F]{64}"))) {
        throw IllegalArgumentException(
            "Invalid SHA-256 checksum format. Must contain only hexadecimal characters (0-9, a-f).\n" +
            "Checksum: $checksum"
        )
    }

    return Pair(url, checksum)
}

/**
 * Extract package name from dependency URL
 *
 * Example: "https://github.com/user/json/releases/download/v1.2.3/json.tar.gz#sha256:abc..."
 * Returns: "json"
 */
fun extractPackageNameFromUrl(fullUrl: String): String {
    val url = fullUrl.split("#")[0]
    val filename = url.substringAfterLast("/")

    // Remove .tar.gz extension
    val packageName = when {
        filename.endsWith(".tar.gz") -> filename.removeSuffix(".tar.gz")
        filename.endsWith(".tgz") -> filename.removeSuffix(".tgz")
        else -> throw IllegalArgumentException(
            "Invalid package archive format. Expected .tar.gz or .tgz file.\n" +
            "Got: $filename\n\n" +
            "💡 Mezze packages must be distributed as .tar.gz archives."
        )
    }

    // Extract version from package name (e.g., "json-1.2.3" -> "json")
    val nameWithoutVersion = packageName.replace(Regex("-\\d+\\.\\d+\\.\\d+.*$"), "")

    if (!nameWithoutVersion.matches(Regex("[a-z][a-z0-9_]*"))) {
        throw IllegalArgumentException(
            "Invalid package name: $nameWithoutVersion\n\n" +
            "💡 Package names must:\n" +
            "   - Start with a lowercase letter\n" +
            "   - Contain only lowercase letters, numbers, and underscores\n" +
            "   - Use snake_case (e.g., http_client, json_parser)"
        )
    }

    return nameWithoutVersion
}

/**
 * Validate package name format
 */
fun validatePackageName(name: String) {
    if (!name.matches(Regex("[a-z][a-z0-9_]*"))) {
        throw IllegalArgumentException(
            "Invalid package name: $name\n\n" +
            "💡 Package names must:\n" +
            "   - Start with a lowercase letter\n" +
            "   - Contain only lowercase letters, numbers, and underscores\n" +
            "   - Use snake_case (e.g., http_client, json_parser)\n\n" +
            "Examples:\n" +
            "   ✅ json_parser\n" +
            "   ✅ http_client\n" +
            "   ✅ test_framework2\n" +
            "   ❌ JsonParser (no camelCase)\n" +
            "   ❌ http-client (no hyphens)\n" +
            "   ❌ 2fast (cannot start with number)"
        )
    }
}

/**
 * Validate semantic version format
 */
fun validateVersion(version: String) {
    if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+(-.+)?"))) {
        throw IllegalArgumentException(
            "Invalid version format: $version\n\n" +
            "💡 Versions must follow semantic versioning (semver):\n" +
            "   Format: MAJOR.MINOR.PATCH[-PRERELEASE]\n\n" +
            "Examples:\n" +
            "   ✅ 1.0.0\n" +
            "   ✅ 0.2.1\n" +
            "   ✅ 1.0.0-alpha\n" +
            "   ✅ 2.1.3-beta.1\n" +
            "   ❌ 1.0 (missing patch version)\n" +
            "   ❌ v1.0.0 (no 'v' prefix)\n" +
            "   ❌ 1.0.0.0 (too many version parts)\n\n" +
            "Learn more: https://semver.org"
        )
    }
}
