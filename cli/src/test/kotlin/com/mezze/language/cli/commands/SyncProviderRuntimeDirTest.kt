package com.mezze.language.cli.commands

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * A provider's declared `runtime_dir` exists after `mez sync`, with or without
 * packages to install into it.
 *
 * It used to appear only as a side effect of npm/pip running, which needs a
 * non-empty `packages`. So a project opting in to plain ECMAScript with a bare
 * `[polyglot.js]` — the documented way, and what `examples/polyglot_js` does —
 * never got `.deps/node`, while the runtime still pointed GraalJS at it: EVERY
 * eval failed, including `1 + 1`, with "Invalid CommonJS root folder".
 * Measured on the real example, which now runs end to end.
 *
 * The declaration is the contract; materializing it is sync's job.
 */
class SyncProviderRuntimeDirTest {

    /** A provider package declaring `[polyglot.provider]` for [language]. */
    private fun provider(root: File, name: String, language: String, runtimeDir: String): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "src").mkdirs()
        File(dir, "src/lib.mz").writeText("pub let ${language}_val = {} -> \"$language\"\n")
        File(dir, "Mezze.toml").writeText(
            """
            [package]
            name = "$name"
            version = "0.1.0"

            [lib]
            path = "src/lib.mz"

            [polyglot.provider]
            language = "$language"
            install = "true {dest} {packages}"
            runtime_env = "SOME_PATH"
            runtime_dir = "$runtimeDir"
            """.trimIndent(),
        )
        return dir
    }

    private fun app(root: File, pathDeps: Map<String, String>, extraToml: String): File {
        val dir = File(root, "app").apply { mkdirs() }
        File(dir, "src").mkdirs()
        File(dir, "src/app.mz").writeText("pub let main: {} -> {} = {} -> {}\n")
        File(dir, "Mezze.toml").writeText(
            buildString {
                appendLine("[package]")
                appendLine("name = \"app\"")
                appendLine("version = \"0.1.0\"")
                appendLine()
                appendLine("[[app]]")
                appendLine("name = \"app\"")
                appendLine("path = \"src/app.mz\"")
                appendLine("entry = \"main\"")
                appendLine()
                appendLine("[path-dependencies]")
                pathDeps.forEach { (k, v) -> appendLine("$k = \"$v\"") }
                appendLine()
                append(extraToml)
            },
        )
        return dir
    }

    @Test
    fun `runtime_dir is created with NO packages declared`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        provider(root, "mezze_js", "js", ".deps/node")
        // A bare `[polyglot.js]` — opting in to the language, installing nothing.
        val a = app(root, mapOf("mezze_js" to "../mezze_js"), "[polyglot.js]\n")

        SyncCommand().execute(emptyList(), currentDir = a)

        assertTrue(
            File(a, ".deps/node").isDirectory,
            "sync must create the provider's declared runtime_dir even with no packages",
        )
    }

    @Test
    fun `an already-present runtime_dir is left alone`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        provider(root, "mezze_js", "js", ".deps/node")
        val a = app(root, mapOf("mezze_js" to "../mezze_js"), "[polyglot.js]\n")
        val marker = File(a, ".deps/node/keep.txt").apply { parentFile.mkdirs(); writeText("x") }

        SyncCommand().execute(emptyList(), currentDir = a)

        assertTrue(marker.isFile, "sync must not clear an existing runtime_dir")
    }

    @Test
    fun `no provider on the graph creates nothing`(@TempDir tmp: Path) {
        val root = tmp.toFile()
        val a = app(root, emptyMap(), "")
        SyncCommand().execute(emptyList(), currentDir = a)
        assertTrue(!File(a, ".deps/node").exists(), "nothing declares js, so nothing is created")
    }
}
