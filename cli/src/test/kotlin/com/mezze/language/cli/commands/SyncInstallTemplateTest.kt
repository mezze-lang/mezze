package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `mez sync` install-template execution: after fetching packages and
 * jars, sync walks the user manifest's `[polyglot.<lang>]` sections and
 * — for each with a non-empty `packages` list — invokes the matching
 * provider's install template. Prefix/dest substitution, cache-skip on
 * a `.mez-installed.json` marker, and hard error on conflicting
 * providers all live here.
 *
 * Every test uses a shell one-liner as the install template so the
 * assertion set is exact: no dependency on a real graalpy / node /
 * jvm toolchain.
 */
class SyncInstallTemplateTest {

    /** A minimal Mezze package with a `[lib]` entry and no deps. */
    private fun writeLibPkg(dir: File, name: String) {
        dir.mkdirs(); File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ${name}_val = {} -> \"$name\"\n")
        File(dir, "Mezze.toml").writeText(
            """
            [package]
            name = "$name"
            version = "1.0.0"
            [lib]
            path = "src/lib.mz"
            """.trimIndent(),
        )
    }

    /**
     * A provider package for [lang], installing via [installTemplate].
     * Every required `[polyglot.provider]` field is populated so the
     * validator accepts it.
     */
    private fun writeProviderPkg(
        dir: File,
        name: String,
        lang: String,
        installTemplate: String,
        runtimeDir: String = ".deps/$lang",
    ) {
        dir.mkdirs(); File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ok = {} -> \"$lang\"\n")
        File(dir, "Mezze.toml").writeText(
            """
            [package]
            name = "$name"
            version = "1.0.0"
            [lib]
            path = "src/lib.mz"

            [polyglot.provider]
            language    = "$lang"
            install     = "$installTemplate"
            runtime_env = "MOCK_${lang.uppercase()}_PATH"
            runtime_dir = "$runtimeDir"
            """.trimIndent(),
        )
    }

    /**
     * The user app: declares `[polyglot.<lang>].packages` and depends
     * on the provider via `path-dependencies` so the graph resolves
     * without git / network access.
     */
    private fun writeAppPkg(
        dir: File,
        lang: String,
        packages: List<String>,
        providerName: String,
        providerPath: String,
    ) {
        dir.mkdirs(); File(dir, "src").mkdirs()
        File(dir, "src/app.mz").writeText("let main = {} -> {}\n")
        File(dir, "Mezze.toml").writeText(
            """
            [package]
            name = "app"
            version = "0.1.0"

            [[app]]
            name  = "app"
            path  = "src/app.mz"

            [polyglot.$lang]
            packages = [${packages.joinToString(", ") { "\"$it\"" }}]

            [path-dependencies]
            $providerName = "$providerPath"
            """.trimIndent(),
        )
    }

    @Test
    fun `sync runs the install template with prefix, dest, packages substituted`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val logFile = File(root, "install.log")
        // The install template writes prefix + dest + packages to logFile
        // so we can assert exact substitution.
        val provider = File(root, "mezze-python").also {
            writeProviderPkg(it, name = "mezze_python", lang = "python",
                installTemplate = "printf '%s\\n%s\\n%s\\n' '{prefix}' '{dest}' '{packages}' > '${logFile.absolutePath}'")
        }
        val app = File(root, "app").also {
            writeAppPkg(it, lang = "python", packages = listOf("numpy>=1.24.0", "requests"),
                providerName = "mezze_python", providerPath = provider.absolutePath)
        }

        assertEquals(0, SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store))

        assertTrue(logFile.isFile, "install template must have run")
        val lines = logFile.readLines()
        assertEquals(3, lines.size, "template writes 3 lines: prefix, dest, packages")
        assertEquals(provider.canonicalPath, File(lines[0]).canonicalPath, "{prefix} = provider dir")
        assertEquals(File(app, ".deps/python").absolutePath, lines[1], "{dest} = <app>/<runtime_dir>")
        assertEquals("numpy>=1.24.0 requests", lines[2], "{packages} space-joined")
    }

    @Test
    fun `install marker skips a second sync with the same inputs`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val counter = File(root, "count")
        val provider = File(root, "mezze-python").also {
            writeProviderPkg(it, name = "mezze_python", lang = "python",
                installTemplate = "printf x >> '${counter.absolutePath}'")
        }
        val app = File(root, "app").also {
            writeAppPkg(it, lang = "python", packages = listOf("numpy"),
                providerName = "mezze_python", providerPath = provider.absolutePath)
        }

        assertEquals(0, SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store))
        val afterFirst = counter.readText()
        assertEquals("x", afterFirst, "first sync runs the install once")

        // Second sync with identical inputs must skip.
        assertEquals(0, SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store))
        assertEquals("x", counter.readText(), "second sync skips — marker matched")

        // Changing the packages list invalidates the cache.
        writeAppPkg(app, lang = "python", packages = listOf("numpy", "scipy"),
            providerName = "mezze_python", providerPath = provider.absolutePath)
        assertEquals(0, SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store))
        assertEquals("xx", counter.readText(), "third sync re-runs — packages changed")
    }

    @Test
    fun `install template non-zero exit fails the sync`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val provider = File(root, "mezze-python").also {
            writeProviderPkg(it, name = "mezze_python", lang = "python",
                installTemplate = "echo boom >&2; exit 3")
        }
        val app = File(root, "app").also {
            writeAppPkg(it, lang = "python", packages = listOf("numpy"),
                providerName = "mezze_python", providerPath = provider.absolutePath)
        }

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(1, code, "non-zero install exit must fail sync")
        // No marker on failure.
        assertFalse(File(app, ".deps/python/.mez-installed.json").exists())
    }

    @Test
    fun `duplicate provider claiming the same language is a hard error`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")

        val logA = File(root, "a.log")
        val provA = File(root, "prov-a").also {
            writeProviderPkg(it, name = "mezze_python_a", lang = "python",
                installTemplate = "echo A > '${logA.absolutePath}'")
        }
        val logB = File(root, "b.log")
        val provB = File(root, "prov-b").also {
            writeProviderPkg(it, name = "mezze_python_b", lang = "python",
                installTemplate = "echo B > '${logB.absolutePath}'")
        }

        val app = File(root, "app")
        writeAppPkg(app, lang = "python", packages = listOf("numpy"),
            providerName = "mezze_python_a", providerPath = provA.absolutePath)
        // Second provider added by hand.
        File(app, "Mezze.toml").appendText("mezze_python_b = \"${provB.absolutePath}\"\n")

        val code = SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store)
        assertEquals(1, code, "conflicting providers is a hard failure")
        // Neither install ran.
        assertFalse(logA.exists(), "the first provider must not run when a conflict is present")
        assertFalse(logB.exists())
    }

    @Test
    fun `only languages with a non-empty packages list are installed`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val store = File(root, "store")
        val marker = File(root, "python.log")
        val provider = File(root, "mezze-python").also {
            writeProviderPkg(it, name = "mezze_python", lang = "python",
                installTemplate = "echo ran > '${marker.absolutePath}'")
        }
        // The app depends on the python provider but does NOT declare a
        // [polyglot.python] section. The install must be skipped.
        val app = File(root, "app")
        app.mkdirs(); File(app, "src").mkdirs()
        File(app, "src/app.mz").writeText("let main = {} -> {}\n")
        File(app, "Mezze.toml").writeText(
            """
            [package]
            name = "app"
            version = "0.1.0"
            [[app]]
            name  = "app"
            path  = "src/app.mz"
            [path-dependencies]
            mezze_python = "${provider.absolutePath}"
            """.trimIndent(),
        )

        assertEquals(0, SyncCommand().execute(emptyList(), currentDir = app, storeRootOverride = store))
        assertFalse(marker.exists(), "no packages declared → no install")
    }
}
