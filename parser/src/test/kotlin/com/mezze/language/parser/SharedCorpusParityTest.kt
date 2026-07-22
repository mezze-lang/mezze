package com.mezze.language.parser

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import java.io.File

/**
 * The Kotlin half of the parser ↔ tree-sitter sync harness.
 *
 * Every snippet in the `fixtures/parser-corpus` `.mz` files must parse under
 * the real [MezzeParser]. `tooling/tree-sitter-mezze/scripts/parity.sh` runs the SAME
 * snippets through the tree-sitter grammar. When both are green, the grammar
 * and the parser agree on this corpus — that's the sync guarantee.
 *
 * This test is the *source of truth*: a snippet only belongs in the corpus if
 * this test parses it. So if you add a snippet the grammar can't handle yet,
 * this test still passes (proving the snippet is valid Mezze) and the parity
 * script fails (pointing at the grammar gap).
 *
 * Reads whole files and splits them in-memory (one file open per fixture, not
 * one per snippet) so the suite stays fast.
 */
class SharedCorpusParityTest {

    private val parser = MezzeParser()

    @TestFactory
    fun `every corpus snippet parses`(): List<DynamicTest> {
        val dir = corpusDir()
        val files = dir.listFiles { f -> f.extension == "mz" }?.sortedBy { it.name }
            ?: error("no .mz fixtures in $dir")
        assertTrue(files.isNotEmpty(), "expected fixture files in $dir")

        return files.flatMap { file ->
            splitSnippets(file.readText()).map { (label, snippet) ->
                DynamicTest.dynamicTest("${file.name}: $label") {
                    try {
                        val module = parser.parseParsedModule(snippet, file.name)
                        assertTrue(module.decls.isNotEmpty(),
                            "snippet parsed to an empty module:\n$snippet")
                    } catch (e: Exception) {
                        fail<Unit>("parser rejected a corpus snippet '$label':\n$snippet\n\n${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Split a fixture file into (label, snippet) blocks on lines that start with
     * `# ===`. The text after `# ===` on the marker line is the label. Leading
     * `#`-comment header lines before the first marker are ignored.
     */
    private fun splitSnippets(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var label: String? = null
        val buf = StringBuilder()
        fun flush() {
            val body = buf.toString().trim()
            if (label != null && body.isNotEmpty()) out.add(label!! to body)
            buf.setLength(0)
        }
        for (line in text.lines()) {
            val m = Regex("^# ===\\s?(.*)$").find(line)
            if (m != null) {
                flush()
                label = m.groupValues[1].ifBlank { "block ${out.size + 1}" }
            } else if (label != null) {
                buf.appendLine(line)
            }
        }
        flush()
        return out
    }

    /** Walk up from the test working dir to the repo root's fixtures/parser-corpus. */
    private fun corpusDir(): File {
        var d: File? = File(".").absoluteFile
        while (d != null) {
            val candidate = File(d, "fixtures/parser-corpus")
            if (candidate.isDirectory) return candidate
            d = d.parentFile
        }
        error("could not locate fixtures/parser-corpus above ${File(".").absolutePath}")
    }
}
