package com.mezze.language.parser

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail

/**
 * Exhaustive quality gate for PARSER error messages. Every malformed snippet a
 * user is likely to type must reject with an error that is: located (line/col),
 * non-blank, non-generic, and — the ratchet — eventually carries a stable
 * [ErrorCode] and an actionable hint.
 *
 * How the ratchet works (drives UNCATEGORIZED → 0 without a churny allowlist):
 *
 *   - A [Bad] entry with `expectCode == null` is one we KNOW is still
 *     `UNCATEGORIZED`. The test asserts it IS still uncategorized — so the day
 *     someone assigns it a code, THIS test fails and points them at setting
 *     `expectCode` here. Migration is a one-line edit in this file.
 *   - A [Bad] entry with `expectCode != null` is migrated: the test asserts the
 *     code matches AND a hint is present. It can never silently regress to
 *     uncategorized.
 *
 * Either way EVERY entry must clear the universal bar (located, non-blank,
 * non-generic). New parser features add a `Bad` line here; the gate keeps the
 * whole surface honest. Complements the per-code tests (MatchSyntaxHintTest,
 * P0121CascadeDiagTest, …) with breadth.
 */
class ParserErrorQualityTest {

    private val parser = MezzeParser()

    /** A malformed snippet + what we expect of its error. */
    private data class Bad(
        val src: String,
        /** Pin once migrated; null == "still UNCATEGORIZED, tracked here". */
        val expectCode: ErrorCode? = null,
        /** A substring the raw message must contain (behavior lock). */
        val mustMention: String? = null,
    )

    // Raw fallback phrasings that must NEVER reach a user — a combinator internal
    // leaking out means we forgot to give the site a real message.
    private val GENERIC_PHRASES = listOf(
        "No alternatives matched",
        "no alternatives",
    )

    private val corpus = listOf(
        // ── bindings ────────────────────────────────────────────────────
        Bad("let x = ", mustMention = "expression"),
        Bad("let x = {", mustMention = "identifier"),
        Bad("let 3 = x", expectCode = ErrorCode.P0128_INVALID_LET_BINDING_TARGET, mustMention = "binds a name"),
        // ── lambdas ─────────────────────────────────────────────────────
        Bad("let f = { -> a", mustMention = "identifier"),
        // ── types / records ─────────────────────────────────────────────
        Bad("type Foo =", mustMention = "{"),
        Bad("type = Int", mustMention = "identifier"),
        Bad("let x = { a = }", mustMention = "expression"),
        Bad("let x = { a: }", expectCode = ErrorCode.P0129_RECORD_FIELD_USES_EQUALS, mustMention = "="),
        // ── match / control ─────────────────────────────────────────────
        Bad("let x = match y", mustMention = "is"),
        Bad("let x = do", expectCode = ErrorCode.P0114_DO_BLOCK_EMPTY, mustMention = "empty"),
        // A do-block statement indented differently from the first — the margin-then-
        // indent shape that used to parse silently (over-indented members were swallowed).
        Bad("let f = {} -> do\nx\n  y", expectCode = ErrorCode.P0135_DO_BLOCK_INCONSISTENT_INDENT, mustMention = "column"),
        Bad("let x = if true", mustMention = "then"),
        // ── imports ─────────────────────────────────────────────────────
        Bad("use ", mustMention = "identifier"),
        Bad("use std::", mustMention = "identifier"),
        // ── impl / ability / effect ─────────────────────────────────────
        Bad("impl Show for", mustMention = "identifier"),
        Bad("ability A", mustMention = "for"),
        Bad("effect E", mustMention = "where"),
        // ── operators / pipes ───────────────────────────────────────────
        Bad("let x = 1 ++ 2", mustMention = "++"),
        Bad("let x = |> foo", mustMention = "expression"),
        Bad("let p = a |>", expectCode = ErrorCode.P0130_PIPE_MISSING_RHS, mustMention = "pipe"),
        Bad("x |> @.method {}", expectCode = ErrorCode.P0124_PIPE_PLACEHOLDER_AS_RECEIVER, mustMention = "@"),
        // ── misc expression starts ──────────────────────────────────────
        Bad("let x = .foo", mustMention = "expression"),
        Bad("let x = (1 +", mustMention = "expression"),
        Bad("let x = [1, 2", mustMention = "]"),
        Bad("let x = Foo.(", mustMention = "newtype"),
        // ── strings ─────────────────────────────────────────────────────
        Bad("let x = \"unterminated", expectCode = ErrorCode.P0001_UNTERMINATED_STRING, mustMention = "Unterminated"),
    )

    private fun errorFor(src: String): ParseException =
        try {
            parser.parseParsedModule(src, "quality.mz")
            fail<Nothing>("Expected a parse error for malformed snippet: <<$src>> — it was ACCEPTED")
        } catch (e: ParseException) {
            e
        }

    @Test
    fun `every malformed snippet rejects with a located, non-blank, non-generic message`() {
        val failures = mutableListOf<String>()
        for (bad in corpus) {
            val e = errorFor(bad.src)
            val msg = e.errorMessage
            if (msg.isBlank()) failures += "BLANK message for <<${bad.src}>>"
            if (e.line < 1 || e.column < 1) failures += "bad location L${e.line}:${e.column} for <<${bad.src}>>"
            GENERIC_PHRASES.firstOrNull { msg.contains(it, ignoreCase = true) }?.let {
                failures += "GENERIC fallback ('$it') leaked for <<${bad.src}>>: $msg"
            }
            bad.mustMention?.let {
                if (!msg.contains(it)) failures += "message for <<${bad.src}>> must mention '$it' but was: $msg"
            }
        }
        assertTrue(failures.isEmpty(), "Parser error-quality violations:\n  " + failures.joinToString("\n  "))
    }

    @Test
    fun `a do block closed by a dedented line hints at the indentation`() {
        // The 1-space `y` dedents below the block (col 2 < 3), closing it on the `let`.
        // Most often a mis-indent, so the P0112 error adds a hint pointing at that line.
        val e = errorFor("let f = {} -> do\n  let x = 1\n y")
        assertEquals(ErrorCode.P0112_DO_BLOCK_LAST_STATEMENT_IS_LET, e.code)
        assertTrue(
            e.hints.any { it.contains("indented") },
            "a dedent-closed do block should hint at the indentation, got: ${e.hints}",
        )
    }

    @Test
    fun `a do block that ends at EOF on a let does not blame indentation`() {
        // No following line — the block ended at end of input, so the user really did
        // forget the result expression; the indentation hint would be misleading here.
        val e = errorFor("let f = {} -> do\n  let x = 1")
        assertEquals(ErrorCode.P0112_DO_BLOCK_LAST_STATEMENT_IS_LET, e.code)
        assertTrue(
            e.hints.none { it.contains("indented") },
            "an EOF-ended do block should not blame indentation, got: ${e.hints}",
        )
    }

    @Test
    fun `migrated entries carry their code and a hint, unmigrated ones stay tracked (ratchet)`() {
        val regressions = mutableListOf<String>()
        for (bad in corpus) {
            val e = errorFor(bad.src)
            if (bad.expectCode != null) {
                if (e.code != bad.expectCode)
                    regressions += "<<${bad.src}>> expected ${bad.expectCode} but was ${e.code}"
                if (e.hints.isEmpty())
                    regressions += "<<${bad.src}>> (${bad.expectCode}) must ship a hint"
            } else {
                // Still-uncategorized: when this flips, someone migrated it —
                // set expectCode on this entry (that's the whole migration step).
                if (e.code != ErrorCode.UNCATEGORIZED)
                    regressions += "<<${bad.src}>> is now ${e.code} — set expectCode on its corpus entry"
            }
        }
        assertTrue(regressions.isEmpty(), "Ratchet violations:\n  " + regressions.joinToString("\n  "))
    }
}
