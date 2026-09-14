package com.mezze.language.cli

import com.mezze.language.cli.`package`.ConfigContributions
import com.mezze.language.cli.`package`.ContributesEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Package config contributions (`[[contributes]]`): mez merges a package's
 * default config into the consumer's Mezze.toml — namespaced to the package,
 * merge-not-override, idempotent.
 */
class ConfigContributionsTest {

    private fun entry(section: String, values: Map<String, String> = emptyMap(),
                      subs: Map<String, Map<String, String>> = emptyMap()) =
        ContributesEntry(section = section, values = values, subsections = subs)

    @Test fun `adds a missing section owned by the package`() {
        val toml = "[package]\nname = \"app\"\n"
        val (out, applied) = ConfigContributions.apply(
            toml, "mezze-python",
            listOf(entry("polyglot.python", subs = mapOf("options" to mapOf("PosixModuleBackend" to "java")))),
            providerLanguage = "python",
        )
        assertEquals(listOf("polyglot.python"), applied.sectionsAdded)
        assertTrue(out.contains("[polyglot.python]"), "adds the block")
        assertTrue(out.contains("[polyglot.python.options]"), "adds the subsection")
        assertTrue(out.contains("PosixModuleBackend = \"java\""), "renders the option")
        assertTrue(out.contains("# added by mezze-python"), "annotates provenance")
    }

    @Test fun `never overwrites an existing section (merge not override)`() {
        val toml = "[package]\nname = \"app\"\n\n[polyglot.python]\n# my own config\n"
        val (out, applied) = ConfigContributions.apply(
            toml, "mezze-python", listOf(entry("polyglot.python", mapOf("Foo" to "bar"))),
            providerLanguage = "python",
        )
        assertTrue(applied.sectionsAdded.isEmpty(), "existing section left untouched")
        assertFalse(out.contains("Foo = \"bar\""), "must not inject into an existing section")
        assertTrue(out.contains("# my own config"), "keeps the user's block")
    }

    @Test fun `is idempotent — second apply adds nothing`() {
        val toml = "[package]\nname = \"app\"\n"
        val (once, _) = ConfigContributions.apply(toml, "foo", listOf(entry("lint.foo", mapOf("rules" to "all"))))
        val (twice, applied2) = ConfigContributions.apply(once, "foo", listOf(entry("lint.foo", mapOf("rules" to "all"))))
        assertTrue(applied2.sectionsAdded.isEmpty(), "re-apply adds nothing")
        assertEquals(once, twice, "idempotent")
    }

    @Test fun `namespace guard — a package cannot contribute to sections it does not own`() {
        val toml = "[package]\nname = \"app\"\n"
        // 'evil' tries to write [dependencies] and another package's [lint.other].
        val (out, applied) = ConfigContributions.apply(
            toml, "evil",
            listOf(entry("dependencies", mapOf("x" to "y")), entry("lint.other", mapOf("a" to "b"))),
        )
        assertTrue(applied.sectionsAdded.isEmpty(), "reserved + non-owned sections rejected")
        assertFalse(out.contains("x = \"y\""))
        assertFalse(out.contains("a = \"b\""))
    }

    @Test fun `a package owns a section whose leaf is its name or module spelling`() {
        assertTrue(ConfigContributions.ownsSection("lint.foo", "foo", "foo"))
        assertTrue(ConfigContributions.ownsSection("lsp.mezze_py", "mezze-py", "mezze_py")) // hyphen owner, underscore leaf
        assertFalse(ConfigContributions.ownsSection("lint.other", "foo", "foo"))
        assertFalse(ConfigContributions.ownsSection("dependencies", "dependencies", "dependencies")) // reserved
        assertFalse(ConfigContributions.ownsSection("[[app]]", "app", "app"))
        // A provider owns polyglot.<its-language> even when lang != package name.
        assertTrue(ConfigContributions.ownsSection("polyglot.python", "mezze-python", "mezze_python", "python"))
        assertFalse(ConfigContributions.ownsSection("polyglot.js", "mezze-python", "mezze_python", "python")) // not its language
    }
}
