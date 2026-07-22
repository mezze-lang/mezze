package com.mezze.language.parser

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in recorder that captures every source string handed to
 * [MezzeParser.parseParsedModule], tagged with whether the parser accepted it,
 * so the parser test suite can be replayed to build the tree-sitter corpus.
 *
 * This is the mechanism that keeps the tree-sitter grammar in sync with the
 * parser AUTOMATICALLY: run `:parser:test` with capture on, and every snippet
 * the ~700 parser tests exercise — accepted or rejected — lands in a generated
 * corpus. `scripts/parity.sh` then asserts tree-sitter agrees (accepted must
 * parse with no ERROR; rejected must produce an ERROR). Add a language feature
 * to the parser + a test, regenerate, and the grammar's obligations update.
 *
 * OFF unless `MEZZE_CORPUS_CAPTURE` is set (to a writable file path, or `1`/`true`
 * for the default `build/corpus-capture.ndjson`). Zero cost otherwise: [enabled]
 * is a single boolean check on the hot path.
 *
 * Output is NDJSON, one record per line: `{"accepted":true,"source":"..."}`.
 * De-duplication and grouping happen in the downstream generator, not here, to
 * keep this recorder trivially cheap and thread-safe under parallel tests.
 */
object CorpusCapture {
    val enabled: Boolean = System.getenv("MEZZE_CORPUS_CAPTURE")?.isNotBlank() == true

    private val outFile: File? by lazy {
        val raw = System.getenv("MEZZE_CORPUS_CAPTURE") ?: return@lazy null
        val path = if (raw == "1" || raw.equals("true", ignoreCase = true)) {
            "build/corpus-capture.ndjson"
        } else raw
        File(path).also { it.parentFile?.mkdirs(); it.writeText("") }
    }

    // Dedup within a run so the file doesn't explode with repeated snippets;
    // keyed on (accepted, source). The map is only touched when enabled.
    private val seen = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun record(source: String, accepted: Boolean) {
        val f = outFile ?: return
        val key = (if (accepted) "1" else "0") + source
        if (!seen.add(key)) return
        f.appendText(jsonLine(source, accepted))
    }

    /** Minimal JSON string escaping — no dependency on a JSON lib in the parser. */
    private fun jsonLine(source: String, accepted: Boolean): String {
        val sb = StringBuilder()
        sb.append("{\"accepted\":").append(accepted).append(",\"source\":\"")
        for (c in source) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"}\n")
        return sb.toString()
    }
}
