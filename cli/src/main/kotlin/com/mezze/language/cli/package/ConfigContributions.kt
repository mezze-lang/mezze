package com.mezze.language.cli.`package`

/**
 * Applies package config CONTRIBUTIONS (`[[contributes]]`) to a consumer's
 * Mezze.toml. A package ships defaults it wants a consumer to have — a linter's
 * rules, an LSP extension's registration, a polyglot language's enable block —
 * and on install mez MERGES them into the consumer's manifest.
 *
 * Three invariants make this safe to let arbitrary packages do:
 *
 *  1. mez writes, the package never does. A package supplies declarative data
 *     ([ContributesEntry]); this code renders and appends the TOML. A package
 *     can't run code against your file.
 *
 *  2. NAMESPACED — a package may only contribute to a section it OWNS: the
 *     section's leaf segment must equal the contributing package's name (or its
 *     module spelling). So `foo` may write `[lint.foo]` / `[polyglot.foo]` /
 *     `[lsp.foo]`, but never `[dependencies]`, `[[app]]`, or another package's
 *     `[lint.bar]`. Enforced here; a violating entry is dropped.
 *
 *  3. MERGE, never override. A key already present anywhere in the consumer's
 *     file is left untouched — your edits always win. Only genuinely-absent
 *     sections/keys are added, so re-running is idempotent and a package update
 *     that adds a new default key adds only that key.
 *
 * This is deliberately text-append based (not a TOML round-trip) so it never
 * reorders or reformats the user's existing manifest — it only appends the
 * blocks that are missing.
 */
object ConfigContributions {

    data class Applied(val sectionsAdded: List<String>)

    /**
     * Merge [entries] contributed by package [ownerName] into [tomlText]. Returns
     * the new toml text + which sections were added (empty list ⇒ unchanged).
     */
    fun apply(
        tomlText: String,
        ownerName: String,
        entries: List<ContributesEntry>,
        /** Language this package provides, if it declares `[polyglot.provider]` —
         *  lets it own `polyglot.<lang>` even when lang != package name. */
        providerLanguage: String? = null,
    ): Pair<String, Applied> {
        val ownerModule = packageNameToModule(ownerName)
        val existingSections = existingSectionHeaders(tomlText)
        val added = mutableListOf<String>()
        val toAppend = StringBuilder()

        for (e in entries) {
            val section = e.section?.trim().orEmpty()
            if (section.isEmpty()) continue
            if (!ownsSection(section, ownerName, ownerModule, providerLanguage)) continue  // namespace guard
            if (existingSections.contains(section)) continue                 // merge: don't touch an existing section
            // Render the block + any subsections.
            toAppend.append('\n').append("# added by $ownerName (mez config contribution)\n")
            toAppend.append("[$section]\n")
            for ((k, v) in e.values) toAppend.append(renderKv(k, v))
            for ((sub, kv) in e.subsections) {
                if (existingSections.contains("$section.$sub")) continue
                toAppend.append("\n[$section.$sub]\n")
                for ((k, v) in kv) toAppend.append(renderKv(k, v))
            }
            added += section
        }

        if (added.isEmpty()) return tomlText to Applied(emptyList())
        val sep = if (tomlText.isEmpty() || tomlText.endsWith("\n")) "" else "\n"
        return (tomlText + sep + toAppend.toString()) to Applied(added)
    }

    /**
     * A package owns a section iff the section's LEAF segment equals the owner's
     * name or module spelling — `foo` owns `lint.foo`, `polyglot.foo`, etc. A
     * top-level section with no dot is owned only if it IS the owner name (rare;
     * disallowed for the reserved manifest sections).
     */
    private val RESERVED = setOf(
        "package", "app", "dependencies", "dev-dependencies", "path-dependencies",
        "build", "jars", "maven", "maven-repo", "lib", "contributes",
    )
    fun ownsSection(
        section: String, ownerName: String, ownerModule: String,
        providerLanguage: String? = null,
    ): Boolean {
        val top = section.substringBefore('.')
        if (top in RESERVED) return false
        val leaf = section.substringAfterLast('.')
        // Owned if the leaf is the package name/module, OR the section is
        // `polyglot.<lang>` and this package provides <lang>.
        if (leaf == ownerName || leaf == ownerModule) return true
        if (providerLanguage != null && section == "polyglot.$providerLanguage") return true
        return false
    }

    /** All `[section]` and `[[section]]` headers already present in the toml. */
    private fun existingSectionHeaders(toml: String): Set<String> =
        toml.lineSequence().mapNotNull { line ->
            val t = line.trim()
            when {
                t.startsWith("[[") && t.endsWith("]]") -> t.removeSurrounding("[[", "]]").trim()
                t.startsWith("[") && t.endsWith("]") -> t.removeSurrounding("[", "]").trim()
                else -> null
            }
        }.toSet()

    private fun renderKv(key: String, value: String): String {
        // Quote unless it's a bare bool/number the user meant literally. Config
        // values are strings here, so quote them TOML-style.
        val v = if (value == "true" || value == "false" || value.toDoubleOrNull() != null) value
                else "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        return "$key = $v\n"
    }
}
