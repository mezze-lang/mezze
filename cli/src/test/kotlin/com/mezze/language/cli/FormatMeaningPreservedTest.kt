package com.mezze.language.cli

import com.mezze.language.formatter.Formatter
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Paths

/**
 * S0.3 — the `fmt∘check == check` meta-property (formatting is meaning-preserving).
 *
 * Lives in :cli because it needs BOTH :formatter and :typechecker (the :typechecker
 * test module has no formatter dependency). For a corpus of programs, formatting the
 * source must not change what it type-checks to: the inferred top-level types of the
 * raw source and the formatted source must be identical. A formatter or parser change
 * that alters meaning (e.g. the doc-comment / comment-attachment bugs fixed this
 * session, or a mis-indent that regroups a do-block) fails here.
 */
class FormatMeaningPreservedTest {

    private lateinit var parser: MezzeParser
    private lateinit var formatter: Formatter

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        formatter = Formatter()
    }

    /** Hand-picked corpus covering the shapes that have historically broken. */
    private val corpus = listOf(
        "let x = 42\nlet y = x + 1\n",
        """
        let name = "Mezze"
        let release = 0.1
        let ready = False
        let versions = [1, 2, 3]
        """.trimIndent(),
        """
        ## doc on map
        pub let map : { xs: ['a], f: { x: 'a } -> 'b } -> ['b] = { xs, f } -> match xs is
          [] -> []
          [h, ...t] -> [f { x = h }]
        ## doc on second decl (inline-match neighbour)
        pub let first : { xs: ['a] } -> 'a = { xs } -> match xs is
          [h, ...t] -> h
        """.trimIndent(),
        """
        let compute = { cfg } -> do
          let { host, port } = cfg
          let sum = port + 1000
          sum
        """.trimIndent(),
        """
        let pipeline = { xs } -> do
          let kept = xs
          kept
        """.trimIndent(),
    )

    @Test
    fun `formatting preserves inferred types across the corpus`() {
        for ((i, src) in corpus.withIndex()) {
            val formatted = formatter.format(src)
            val rawTypes = inferOrNull(src)
            val fmtTypes = inferOrNull(formatted)
            assertEquals(rawTypes, fmtTypes,
                "fmt changed meaning (corpus #$i)\n--- raw ---\n$src\n--- formatted ---\n$formatted")
        }
    }

    @Test
    fun `formatting is idempotent across the corpus`() {
        for ((i, src) in corpus.withIndex()) {
            val once = formatter.format(src)
            val twice = formatter.format(once)
            assertEquals(once, twice, "fmt not idempotent (corpus #$i):\n$once")
        }
    }

    private fun inferOrNull(source: String): Map<String, String>? {
        return try {
            val parsed = parser.parseParsedModule(source, "fmtcheck.mz")
            val result = TypeChecker().typeCheck(parsed, Paths.get("."), enablePrelude = true)
            // Compare inferred types of the top-level bindings we can name.
            val names = Regex("""(?:pub\s+)?let\s+(\w+)""").findAll(source)
                .map { it.groupValues[1] }.toList().distinct()
            names.associateWith { n -> result.getVariableType(n)?.toString() ?: "<none>" }
        } catch (_: TypeCheckError) {
            null
        } catch (_: ParseException) {
            null
        }
    }
}
