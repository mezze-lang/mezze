package com.mezze.interpreter.polyglot

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CommonJS support is enabled only when the root directory EXISTS.
 *
 * The callers used to pass GraalJS the path the provider DECLARES
 * (`runtime_dir = ".deps/node"`), whether or not anything had created it —
 * and `mez sync` only creates it when `[polyglot.js] packages` is non-empty
 * and npm runs. So a project opting in to plain ECMAScript with a bare
 * `[polyglot.js]`, the documented way and what `examples/polyglot_js` does,
 * had EVERY eval fail, including `1 + 1`:
 *
 *     Error: Invalid CommonJS root folder: <root>/.deps/node
 *
 * Verified end to end against real GraalJS: without `.deps/node`, `1 + 1`
 * now evaluates to 2; with `.deps/node/node_modules/tiny` present,
 * `require('tiny').twice(21)` returns 42.
 */
class CommonJsRootTest {

    @TempDir lateinit var tempDir: Path

    /** A project whose `.deps/mezze/<dep>` declares the js provider. */
    private fun projectWithJsProvider(runtimeDir: String): File {
        val root = tempDir.toFile()
        val dep = File(root, ".deps/mezze/mezze_js-0.2.1")
        dep.mkdirs()
        Files.writeString(
            File(dep, "Mezze.toml").toPath(),
            """
            [package]
            name = "mezze-js"
            version = "0.2.1"

            [polyglot.provider]
            language = "js"
            install = "npm install --prefix {dest} {packages}"
            runtime_env = "NODE_PATH"
            runtime_dir = "$runtimeDir"
            """.trimIndent(),
        )
        return root
    }

    @Test
    fun `a declared runtime_dir that was never created disables CommonJS`() {
        val root = projectWithJsProvider(".deps/node")
        assertNull(
            PolyglotManifest.commonJsRoot(root, null),
            "nothing created .deps/node, so require must stay off rather than " +
                "break every eval",
        )
    }

    @Test
    fun `a runtime_dir that exists is used`() {
        val root = projectWithJsProvider(".deps/node")
        File(root, ".deps/node").mkdirs()
        assertEquals(
            File(root, ".deps/node").absolutePath,
            PolyglotManifest.commonJsRoot(root, null),
        )
    }

    /**
     * An explicit `node_modules` is checked the same way: if the user names a
     * directory that is not there, `require` cannot work either way, and
     * breaking one call beats breaking the whole context.
     */
    @Test
    fun `an explicit node_modules must exist too`() {
        val root = projectWithJsProvider(".deps/node")
        val absent = File(root, "nope").absolutePath
        assertNull(PolyglotManifest.commonJsRoot(root, absent))

        val present = File(root, "yes").also { it.mkdirs() }.absolutePath
        assertEquals(present, PolyglotManifest.commonJsRoot(root, present))
    }

    @Test
    fun `an explicit node_modules wins over the provider's runtime_dir`() {
        val root = projectWithJsProvider(".deps/node")
        File(root, ".deps/node").mkdirs()
        val explicit = File(root, "custom").also { it.mkdirs() }.absolutePath
        assertEquals(explicit, PolyglotManifest.commonJsRoot(root, explicit))
    }

    @Test
    fun `no js provider at all means no CommonJS`() {
        assertNull(PolyglotManifest.commonJsRoot(tempDir.toFile(), null))
    }
}
