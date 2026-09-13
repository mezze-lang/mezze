package com.mezze.language.cli.`package`

/**
 * Second commit — schema validation for `[polyglot.*]` and `[jars]`.
 *
 * ktoml (via kotlinx-serialization) catches structural errors — a table
 * where a value is expected, a missing required field on a data class,
 * etc. — but not the *semantic* rules we care about here:
 *
 *   - `[polyglot.<lang>].packages` MUST be a list of non-empty strings.
 *   - `[polyglot.provider].install` MUST contain the `{packages}` and
 *     `{dest}` placeholders.
 *   - `[jars]` entry values MUST look like `<url>#sha256:<64hex>`.
 *   - `[polyglot.provider]` requires every field when the section is
 *     declared at all — no half-populated blocks.
 *   - `[polyglot.<lang>].packages` declared without a matching provider
 *     under `[dependencies]` is a cross-check failure at sync time
 *     (surfaced by the caller via [ValidatorReport.crossCheck]).
 *
 * Every failure carries a field-path (e.g. `[polyglot.provider].install`)
 * and, where the raw TOML text is available, a line index. The validator
 * accumulates every failure it finds rather than short-circuiting on the
 * first one, so users see all problems in a single sync run.
 */
object PolyglotValidator {

    /** Where a failure lives, for diagnostics. */
    data class Location(
        /** Field path — the human-readable "coordinate" the message shows. */
        val fieldPath: String,
        /** 1-based line number when we could locate the field; -1 otherwise. */
        val line: Int = -1,
    ) {
        fun render(): String = if (line > 0) "$fieldPath (line $line)" else fieldPath
    }

    /** One structured error. */
    data class Error(
        val location: Location,
        val message: String,
        /** Optional "did you mean X?" hint. */
        val hint: String? = null,
    ) {
        fun render(): String =
            buildString {
                append("Mezze.toml: ")
                append(location.render())
                append(": ")
                append(message)
                if (hint != null) {
                    append(" — did you mean '").append(hint).append("'?")
                }
            }
    }

    /** Result of one validation pass. */
    data class Report(val errors: List<Error>) {
        val ok: Boolean get() = errors.isEmpty()
        fun renderAll(): String = errors.joinToString("\n") { it.render() }
    }

    /**
     * Validate a parsed [toml]. [rawText] is optional — when provided the
     * validator uses it to attach line numbers to each error via a
     * simple substring-match; when null (e.g. tests using the data-class
     * form directly), line numbers stay -1 and the messages carry only
     * the field path.
     *
     * [providerLanguagesInDeps] is the set of provider languages the caller
     * has already resolved from `[dependencies]` (e.g. mezze-python →
     * "python"). Used for the cross-check that a `[polyglot.<lang>]`
     * block declaring `packages` also has a matching provider on the
     * dep list — see [Error]s tagged `no provider found`.
     */
    fun validate(
        toml: MezzeToml,
        rawText: String? = null,
        providerLanguagesInDeps: Set<String> = emptySet(),
    ): Report {
        val errors = mutableListOf<Error>()
        val lineIndex = rawText?.let { buildLineIndex(it) }

        // ─── Per-language `packages` shape ─────────────────
        toml.polyglot?.python?.let { validatePackages("python", it.packages, errors, lineIndex) }
        toml.polyglot?.js?.let { validatePackages("js", it.packages, errors, lineIndex) }
        toml.polyglot?.wasm?.let { validatePackages("wasm", it.packages, errors, lineIndex) }
        toml.polyglot?.jvm?.let { validatePackages("jvm", it.packages, errors, lineIndex) }

        // ─── `[polyglot.provider]` completeness + install template ────
        toml.polyglot?.provider?.let { p -> validateProvider(p, errors, lineIndex) }

        // ─── `[jars]` URL shape ─────────────────────────────
        toml.jars.forEach { (name, spec) -> validateJarSpec(name, spec, errors, lineIndex) }

        // ─── Cross-check: packages declared but no provider on deps ───
        if (providerLanguagesInDeps.isNotEmpty() || dependsOnProviderCheck(toml)) {
            crossCheckProviders(toml, providerLanguagesInDeps, errors, lineIndex)
        }

        return Report(errors)
    }

    // ─── Per-language `packages` ────────────────────────────

    private fun validatePackages(
        lang: String,
        packages: List<String>,
        errors: MutableList<Error>,
        lineIndex: LineIndex?,
    ) {
        packages.forEachIndexed { i, pkg ->
            if (pkg.isBlank()) {
                errors += Error(
                    Location("[polyglot.$lang].packages[$i]", lineIndex?.locate("[polyglot.$lang]", "packages") ?: -1),
                    "package specifier must not be blank",
                )
            }
        }
    }

    // ─── `[polyglot.provider]` completeness + install template ─────

    private fun validateProvider(
        p: ProviderSection,
        errors: MutableList<Error>,
        lineIndex: LineIndex?,
    ) {
        val required = mapOf(
            "language" to p.language,
            "install" to p.install,
            "runtime_env" to p.runtimeEnv,
            "runtime_dir" to p.runtimeDir,
        )
        for ((name, value) in required) {
            if (value.isNullOrBlank()) {
                errors += Error(
                    Location("[polyglot.provider].$name", lineIndex?.locate("[polyglot.provider]", name) ?: -1),
                    "is required",
                )
            }
        }
        val install = p.install
        if (install != null && install.isNotBlank()) {
            val missing = buildList {
                if (!install.contains("{packages}")) add("{packages}")
                if (!install.contains("{dest}")) add("{dest}")
            }
            if (missing.isNotEmpty()) {
                errors += Error(
                    Location("[polyglot.provider].install", lineIndex?.locate("[polyglot.provider]", "install") ?: -1),
                    "must contain " + missing.joinToString(" and ") + " placeholder" + (if (missing.size > 1) "s" else ""),
                )
            }
        }
    }

    // ─── `[jars]` URL#sha256:<hex64> ────────────────────────

    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

    private fun validateJarSpec(
        name: String,
        spec: String,
        errors: MutableList<Error>,
        lineIndex: LineIndex?,
    ) {
        val loc = Location("[jars].$name", lineIndex?.locate("[jars]", "\"$name\"") ?: -1)
        val hash = spec.substringAfter("#sha256:", missingDelimiterValue = "")
        if (hash.isEmpty()) {
            errors += Error(loc, "URL must end with '#sha256:<64hex>' — got: $spec")
            return
        }
        if (!SHA256_HEX.matches(hash)) {
            errors += Error(loc, "sha256 must be 64 hex chars — got ${hash.length} chars")
        }
        val urlPart = spec.substringBefore("#sha256:")
        // `file:` covers both the two-slash absolute form (`file:///tmp/…`) and
        // the single-slash form JDK's `File.toURI()` emits (`file:/tmp/…`) —
        // both are valid, and either can appear in a manifest.
        if (!urlPart.startsWith("http://") && !urlPart.startsWith("https://") && !urlPart.startsWith("file:")) {
            errors += Error(loc, "URL scheme must be http/https/file — got: $urlPart")
        }
    }

    // ─── Cross-check: user declared packages but no provider on deps ─

    private val PROVIDER_LANGS = listOf("python", "js", "wasm", "jvm")

    private fun dependsOnProviderCheck(toml: MezzeToml): Boolean =
        listOfNotNull(
            toml.polyglot?.python?.packages,
            toml.polyglot?.js?.packages,
            toml.polyglot?.wasm?.packages,
            toml.polyglot?.jvm?.packages,
        ).any { it.isNotEmpty() }

    private fun crossCheckProviders(
        toml: MezzeToml,
        providerLanguagesInDeps: Set<String>,
        errors: MutableList<Error>,
        lineIndex: LineIndex?,
    ) {
        val declared = mapOf(
            "python" to (toml.polyglot?.python?.packages ?: emptyList()),
            "js" to (toml.polyglot?.js?.packages ?: emptyList()),
            "wasm" to (toml.polyglot?.wasm?.packages ?: emptyList()),
            "jvm" to (toml.polyglot?.jvm?.packages ?: emptyList()),
        )
        for ((lang, pkgs) in declared) {
            if (pkgs.isNotEmpty() && lang !in providerLanguagesInDeps) {
                errors += Error(
                    Location("[polyglot.$lang].packages", lineIndex?.locate("[polyglot.$lang]", "packages") ?: -1),
                    "declared but no provider found for language '$lang' — add mezze-$lang to [dependencies]",
                )
            }
        }
    }

    // ─── "did you mean"? for unknown provider keys ─────────

    /**
     * Called by the CLI when it detects an unknown key inside
     * `[polyglot.provider]` (kotlinx-serialization with
     * `ignoreUnknownKeys = false` throws — we surface those upstream
     * and pass the unknown key here for a friendlier hint).
     */
    fun suggestProviderKey(unknown: String): String? {
        val known = listOf("language", "install", "runtime_env", "runtime_dir")
        val lower = unknown.lowercase()
        val best = known.minByOrNull { levenshtein(lower, it) } ?: return null
        val dist = levenshtein(lower, best)
        // Suggest when the unknown is a plausible mistype of `best` — at
        // most 3 edits AWAY from `best`, or `best` is a prefix of the
        // input (e.g. `install_command` → `install`).
        return if (dist <= 3 || lower.startsWith(best) || best.startsWith(lower)) best else null
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    // ─── Line-index helper (substring match — good enough) ─

    private class LineIndex(private val lines: List<String>) {
        /**
         * Best-effort line locator: finds the first line that begins with
         * `header` (e.g. "[polyglot.provider]"), then walks forward until it
         * hits a line whose trimmed form starts with `key = ` or `"key" = `.
         * Returns 1-based line number, or -1 when either the header or the
         * key isn't found.
         */
        fun locate(header: String, key: String): Int {
            var inHeader = false
            for ((i, raw) in lines.withIndex()) {
                val line = raw.trim()
                if (line == header) { inHeader = true; continue }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inHeader = false
                    if (line == header) inHeader = true
                    continue
                }
                if (inHeader && (line.startsWith("$key ") || line.startsWith("$key=") ||
                        line.startsWith("\"$key\" ") || line.startsWith("\"$key\"="))) {
                    return i + 1
                }
            }
            return -1
        }
    }

    private fun buildLineIndex(text: String): LineIndex = LineIndex(text.lines())
}
