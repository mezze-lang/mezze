package com.mezze.language.cli.`package`

import com.mezze.interpreter.polyglot.PolyglotManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Contract test for the TWO hand-synced `[polyglot.*]` manifest parsers.
 *
 * The CLI (`PolyglotResolver`, ktoml-backed) and the interpreter
 * (`PolyglotManifest`, hand-rolled `MiniTomlParser`) each parse the same
 * `Mezze.toml` sections INDEPENDENTLY — the CLI deliberately does not link
 * against the interpreter at run time (it is spawned as a child process), so
 * the two resolved shapes (`PolyglotResolver.Resolved` and
 * `ResolvedPolyglot`) cannot be unified without breaking that decoupling.
 *
 * That decoupling is exactly what makes them a drift hazard: a key renamed or
 * a section added on one side silently disagrees with the other, and nothing
 * catches it until a user's project resolves one way at build time and another
 * at run time. This test is the guard: parse one fixture through BOTH and
 * assert every overlapping field agrees.
 *
 * Fields that are legitimately one-sided are NOT compared:
 *   - CLI-only:        `manifestDir`     (a resolution concern)
 *   - interpreter-only: `enableJs`, `jsNodeModules`, `jsOptions`,
 *                        `enableWasm`, `wasmModules`, `wasmOptions`
 *                        (Truffle Context concerns the CLI never needs)
 * If you add a SHARED field to one resolver, add it here too.
 */
class PolyglotConfigContractTest {

    private fun writeManifest(dir: File): File {
        // A deliberately rich fixture: every shared field is non-default, so a
        // parser that drops or mis-keys one produces a visible disagreement.
        // Relative paths resolve against the manifest dir on both sides, so
        // they canonicalise identically regardless of where the temp dir is.
        val toml = """
            [package]
            name = "contract_fixture"
            version = "0.0.1"

            [polyglot.python]
            python_path = "./venv/site-packages"
            packages = ["numpy>=1.26", "pandas"]

            [polyglot.python.options]
            PosixModuleBackend = "java"
            SomeOtherKey = "value"

            [polyglot.jvm]
            classpath = ["./libs/a.jar", "./libs/b.jar"]
            packages = ["https://ex.test/j.jar#sha256:aaa"]

            [polyglot.js]
            packages = ["lodash@4.17.21", "left-pad"]

            [polyglot.wasm]
            packages = ["https://ex.test/m.wasm#sha256:bbb"]

            [polyglot.provider]
            language = "python"
            install = "pip install {packages} --target {dest}"
            runtime_env = "PYTHONPATH"
            runtime_dir = ".deps/py"

            [jars]
            foo = "https://ex.test/foo.jar#sha256:ccc"
            bar = "https://ex.test/bar.jar#sha256:ddd"
        """.trimIndent()
        val f = File(dir, "Mezze.toml")
        f.writeText(toml)
        return f
    }

    @Test
    fun `both parsers agree on every shared polyglot field`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        writeManifest(dir)

        val cli = PolyglotResolver.resolve(dir)
        val rt = PolyglotManifest.load(dir)

        // Non-vacuity: if the manifest failed to discover/parse, BOTH sides
        // would fall back to NONE and every assertion below would pass
        // falsely. Pin a few values so a silent both-sides-empty regression
        // (e.g. `discover()` breaking) still fails here.
        assertEquals(true, cli.enablePython, "fixture should enable python (non-vacuity)")
        assertEquals(listOf("numpy>=1.26", "pandas"), cli.pythonPackages, "fixture pythonPackages (non-vacuity)")
        assertEquals(2, cli.jars.size, "fixture jars (non-vacuity)")

        assertEquals(cli.enablePython, rt.enablePython, "enablePython")
        assertEquals(cli.pythonPath, rt.pythonPath, "pythonPath (resolved absolute)")
        assertEquals(cli.pythonOptions, rt.pythonOptions, "pythonOptions")
        assertEquals(cli.pythonPackages, rt.pythonPackages, "pythonPackages")
        assertEquals(cli.jvmClasspath, rt.jvmClasspath, "jvmClasspath (resolved absolute)")
        assertEquals(cli.jvmPackages, rt.jvmPackages, "jvmPackages")
        assertEquals(cli.jsPackages, rt.jsPackages, "jsPackages")
        assertEquals(cli.wasmPackages, rt.wasmPackages, "wasmPackages")
        assertEquals(cli.jars, rt.jars, "jars")

        // Provider block — all four fields, compared individually so a
        // mismatch names the offending one.
        val cp = cli.provider
        val rp = rt.provider
        assertEquals(cp != null, rp != null, "provider presence")
        if (cp != null && rp != null) {
            assertEquals(cp.language, rp.language, "provider.language")
            assertEquals(cp.install, rp.install, "provider.install")
            assertEquals(cp.runtimeEnv, rp.runtimeEnv, "provider.runtimeEnv")
            assertEquals(cp.runtimeDir, rp.runtimeDir, "provider.runtimeDir")
        }
    }

    @Test
    fun `both parsers agree that a bare manifest has no polyglot config`(@TempDir tmp: Path) {
        val dir = tmp.toFile()
        File(dir, "Mezze.toml").writeText(
            """
            [package]
            name = "bare"
            version = "0.0.1"
            """.trimIndent()
        )

        val cli = PolyglotResolver.resolve(dir)
        val rt = PolyglotManifest.load(dir)

        assertEquals(cli.enablePython, rt.enablePython, "enablePython (bare)")
        assertEquals(cli.pythonPath, rt.pythonPath, "pythonPath (bare)")
        assertEquals(cli.pythonPackages, rt.pythonPackages, "pythonPackages (bare)")
        assertEquals(cli.jsPackages, rt.jsPackages, "jsPackages (bare)")
        assertEquals(cli.wasmPackages, rt.wasmPackages, "wasmPackages (bare)")
        assertEquals(cli.jvmClasspath, rt.jvmClasspath, "jvmClasspath (bare)")
        assertEquals(cli.jars, rt.jars, "jars (bare)")
        assertEquals(cli.provider == null, rt.provider == null, "provider absent (bare)")
    }
}
