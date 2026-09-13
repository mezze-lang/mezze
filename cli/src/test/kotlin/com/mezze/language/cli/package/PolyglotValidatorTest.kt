package com.mezze.language.cli.`package`

import com.akuleshov7.ktoml.Toml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises every error path of `PolyglotValidator` — the semantic
 * validator that runs after ktoml's structural parse. Each test writes
 * a minimal Mezze.toml, parses it, runs the validator, and asserts on
 * the specific error(s) produced.
 */
class PolyglotValidatorTest {

    private fun parse(toml: String): MezzeToml =
        Toml.decodeFromString(MezzeToml.serializer(), toml)

    // ─── happy path ───────────────────────────

    @Test
    fun `well-formed toml validates ok`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            packages = ["numpy>=1.24.0"]
        """.trimIndent()
        val report = PolyglotValidator.validate(
            parse(text),
            rawText = text,
            providerLanguagesInDeps = setOf("python"),
        )
        assertTrue(report.ok, "expected no errors, got: ${report.renderAll()}")
    }

    // ─── packages shape ────────────────────────

    @Test
    fun `empty package specifier is rejected`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            packages = ["numpy", ""]
        """.trimIndent()
        val report = PolyglotValidator.validate(
            parse(text),
            rawText = text,
            providerLanguagesInDeps = setOf("python"),
        )
        assertFalse(report.ok)
        val e = report.errors.first()
        assertTrue(e.location.fieldPath.startsWith("[polyglot.python].packages["))
        assertTrue(e.message.contains("must not be blank"))
    }

    // ─── provider required fields ─────────────

    @Test
    fun `missing provider language is reported`() {
        val text = """
            [package]
            name = "mezze-python"
            version = "0.1.0"

            [polyglot.provider]
            install     = "pip install --target {dest} {packages}"
            runtime_env = "PYTHONPATH"
            runtime_dir = ".deps/python"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[polyglot.provider].language" }
        assertNotNull(err, "expected missing-language error, got: ${report.renderAll()}")
        assertTrue(err!!.message.contains("required"))
        assertTrue(err.render().startsWith("Mezze.toml:"))
    }

    @Test
    fun `install template without placeholders is reported`() {
        val text = """
            [package]
            name = "mezze-python"
            version = "0.1.0"

            [polyglot.provider]
            language    = "python"
            install     = "pip install numpy"
            runtime_env = "PYTHONPATH"
            runtime_dir = ".deps/python"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[polyglot.provider].install" }
        assertNotNull(err, "expected install-template error, got: ${report.renderAll()}")
        assertTrue(err!!.message.contains("{packages}"), "message should mention {packages}: ${err.message}")
        assertTrue(err.message.contains("{dest}"), "message should mention {dest}: ${err.message}")
    }

    @Test
    fun `install template with only one placeholder is reported`() {
        val text = """
            [package]
            name = "mezze-python"
            version = "0.1.0"

            [polyglot.provider]
            language    = "python"
            install     = "pip install --target {dest} numpy"
            runtime_env = "PYTHONPATH"
            runtime_dir = ".deps/python"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[polyglot.provider].install" }
        assertNotNull(err)
        assertTrue(err!!.message.contains("{packages}"))
        assertFalse(err.message.contains("{dest}"))
    }

    // ─── [jars] URL shape ─────────────────────

    @Test
    fun `malformed jars URL is reported`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [jars]
            python_language = "https://example.com/foo.jar"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[jars].python_language" }
        assertNotNull(err, "expected [jars] URL error, got: ${report.renderAll()}")
        assertTrue(err!!.message.contains("#sha256:<64hex>"))
    }

    @Test
    fun `short sha256 in jars URL is reported`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [jars]
            python_language = "https://example.com/foo.jar#sha256:abc"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[jars].python_language" }
        assertNotNull(err)
        assertTrue(err!!.message.contains("64 hex"))
    }

    @Test
    fun `bad URL scheme in jars URL is reported`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [jars]
            python_language = "ftp://example.com/foo.jar#sha256:${"0".repeat(64)}"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[jars].python_language" }
        assertNotNull(err)
        assertTrue(err!!.message.contains("URL scheme"))
    }

    // ─── cross-check: packages without provider on deps ───

    @Test
    fun `packages declared but no provider on deps is reported`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            packages = ["numpy"]
        """.trimIndent()
        val report = PolyglotValidator.validate(
            parse(text),
            rawText = text,
            providerLanguagesInDeps = emptySet(),
        )
        val err = report.errors.find { it.location.fieldPath == "[polyglot.python].packages" }
        assertNotNull(err, "expected cross-check error, got: ${report.renderAll()}")
        assertTrue(err!!.message.contains("no provider found for language 'python'"))
        assertTrue(err.message.contains("mezze-python"))
    }

    // ─── did-you-mean ─────────────────────────

    @Test
    fun `did-you-mean suggests closest provider key`() {
        assertEquals("install", PolyglotValidator.suggestProviderKey("instal"))
        assertEquals("install", PolyglotValidator.suggestProviderKey("Install"))
        assertEquals("runtime_env", PolyglotValidator.suggestProviderKey("runtimeenv"))
        assertEquals("language", PolyglotValidator.suggestProviderKey("langauge"))
        // Very distant → no suggestion (returns null).
        assertEquals(null, PolyglotValidator.suggestProviderKey("completely_unrelated"))
    }

    // ─── line locator ─────────────────────────

    @Test
    fun `error carries a 1-based line number when rawText is provided`() {
        val text = """
            [package]
            name = "mezze-python"
            version = "0.1.0"

            [polyglot.provider]
            language    = "python"
            install     = "no placeholders"
            runtime_env = "PYTHONPATH"
            runtime_dir = ".deps/python"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        val err = report.errors.find { it.location.fieldPath == "[polyglot.provider].install" }
        assertNotNull(err)
        assertTrue(err!!.location.line > 0, "expected a resolved line, got ${err.location.line}")
        // The `install = ` line is the 7th line of the trimmed text.
        assertEquals(7, err.location.line)
    }

    // ─── `[maven]` + `[[maven-repo]]` ─────────

    @Test
    fun `well-formed maven table validates`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [maven]
            "org.graalvm.python:python-language" = "25.0.3"

            [[maven-repo]]
            id  = "mezze-lang"
            url = "https://raw.githubusercontent.com/mezze-lang/mezze-maven/main/"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        assertTrue(report.ok, "expected no errors, got: ${report.renderAll()}")
    }

    @Test
    fun `maven key without colon is rejected`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [maven]
            "org.graalvm.python-language" = "25.0.3"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        assertFalse(report.ok)
        assertTrue(
            report.errors.any { it.message.contains("group>:<artifact") },
            "expected group:artifact hint, got: ${report.renderAll()}",
        )
    }

    @Test
    fun `maven version must not be blank`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [maven]
            "org.example:foo" = ""
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        assertFalse(report.ok)
        assertTrue(
            report.errors.any { it.message.contains("blank") },
            "expected blank-version error, got: ${report.renderAll()}",
        )
    }

    @Test
    fun `maven-repo url must end with a slash`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [[maven-repo]]
            id  = "mezze-lang"
            url = "https://raw.githubusercontent.com/mezze-lang/mezze-maven/main"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        assertFalse(report.ok)
        assertTrue(
            report.errors.any { it.message.contains("must end with '/'") },
            "expected trailing-slash error, got: ${report.renderAll()}",
        )
    }

    @Test
    fun `maven-repo duplicate ids are flagged`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [[maven-repo]]
            id  = "mezze-lang"
            url = "https://a.example.com/"

            [[maven-repo]]
            id  = "mezze-lang"
            url = "https://b.example.com/"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        assertFalse(report.ok)
        assertTrue(
            report.errors.any { it.message.contains("duplicate id") },
            "expected duplicate-id error, got: ${report.renderAll()}",
        )
    }

    // ─── accumulation ────────────────────────

    @Test
    fun `every failure is accumulated in one pass`() {
        val text = """
            [package]
            name = "demo"
            version = "0.1.0"

            [polyglot.python]
            packages = [""]

            [jars]
            python_language = "https://example.com/foo.jar"

            [polyglot.provider]
            language = "python"
        """.trimIndent()
        val report = PolyglotValidator.validate(parse(text), rawText = text)
        assertFalse(report.ok)
        // Expect at least: blank pkg, bad jar URL, missing install / runtime_env / runtime_dir,
        // cross-check no-provider-found (since deps empty).
        val paths = report.errors.map { it.location.fieldPath }.toSet()
        assertTrue(paths.any { it.startsWith("[polyglot.python].packages[") },
            "expected a packages[i] error; paths=$paths")
        assertTrue("[jars].python_language" in paths, "expected [jars] error; paths=$paths")
        assertTrue("[polyglot.provider].install" in paths, "expected missing install; paths=$paths")
        assertTrue("[polyglot.provider].runtime_env" in paths)
        assertTrue("[polyglot.provider].runtime_dir" in paths)
    }
}
