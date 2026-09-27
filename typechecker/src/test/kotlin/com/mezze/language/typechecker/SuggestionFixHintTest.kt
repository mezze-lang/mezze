package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.FixHint
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A suggestion the checker computed must reach a TOOL, not only a reader.
 *
 * Several errors ran a Levenshtein search, wrote the winner into the message as
 * "Did you mean 'x'?", and attached no `FixHint`. The candidate existed and was
 * then discarded into prose: an editor could not offer the one-click rename, and a
 * `--json` consumer could only recover it by parsing English out of `message`.
 *
 * The rule these pin: **if the message names a candidate, the diagnostic carries
 * it.** Each test therefore asserts on the FIX, not on the sentence.
 *
 * Not every "did you mean" is a rename. Some suggest different SYNTAX — "did you
 * mean a method call `x.foo {}`?" — where there is no single replacement text, and
 * inventing one would be guesswork. Those deliberately carry no `Rename`, and the
 * last test states that boundary.
 */
class SuggestionFixHintTest {

    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun caught(src: String): TypeCheckError? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) { e }

    private fun renameCandidates(e: TypeCheckError?): List<String> =
        e?.fixHints?.filterIsInstance<FixHint.Rename>()?.flatMap { it.candidates }.orEmpty()

    // ---- effect operations --------------------------------------------------

    @Test
    fun `a misspelled effect operation carries a rename fix`() {
        val e = caught(
            """
            effect Counter where {
              increment : {} -> {} <Counter>
            }

            impl Counter for Basic where {
              increment : {} -> {} = {} -> {}
            }

            let go = {} -> perform Basic in do
                Counter.incremnt {}
            """
        )
        assertNotNull(e, "a misspelled operation must be an error")
        assertTrue("increment" in renameCandidates(e),
            "the message says `Did you mean 'increment'?`; the fix must say it too. " +
                "message=${e?.message} fixHints=${e?.fixHints}")
    }

    // ---- built-in ability methods -------------------------------------------

    @Test
    fun `a misspelled built-in ability method carries a rename fix`() {
        val e = caught(
            """
            let show: { x: Int } -> Str = { x } -> Inspect.to_dbgg { it = x }
            """
        )
        assertNotNull(e)
        assertTrue("to_dbg" in renameCandidates(e),
            "message=${e?.message} fixHints=${e?.fixHints}")
    }

    // ---- an undefined ability/effect receiver -------------------------------

    @Test
    fun `a misspelled ability RECEIVER carries a rename fix`() {
        val e = caught(
            """
            ability Show for 'a where { render: {it: 'a} -> Str }

            let go: { x: Int } -> Str = { x } -> Shw.render { it = x }
            """
        )
        assertNotNull(e)
        assertTrue("Show" in renameCandidates(e),
            "message=${e?.message} fixHints=${e?.fixHints}")
    }

    @Test
    fun `the receiver fix spans the RECEIVER, not the whole member access`() {
        // `Shw.render { … }` — a fix spanning the whole expression would delete the
        // method call along with the name it was meant to correct.
        val e = caught(
            """
            ability Show for 'a where { render: {it: 'a} -> Str }

            let go: { x: Int } -> Str = { x } -> Shw.render { it = x }
            """
        )
        val hint = e?.fixHints?.filterIsInstance<FixHint.Rename>()?.firstOrNull()
        assertNotNull(hint, "expected a rename fix; fixHints=${e?.fixHints}")
        // `Shw` is three characters; the span must not reach the `.render` call.
        val width = hint!!.loc.endCol - hint.loc.startCol
        assertTrue(width <= 4,
            "the fix should cover just the receiver name, got a span ${width} wide: ${hint.loc}")
    }

    // ---- record fields, already covered — kept so a regression is visible ---

    @Test
    fun `a misspelled record field carries a rename fix`() {
        val e = caught(
            """
            let f: { r: { alpha: Int } } -> Int = { r } -> r.alpa
            """
        )
        assertNotNull(e)
        assertTrue("alpha" in renameCandidates(e),
            "message=${e?.message} fixHints=${e?.fixHints}")
    }

    // ---- the boundary ------------------------------------------------------

    @Test
    fun `an error with no candidate carries no rename fix`() {
        // Nothing resembles `zqxwv`, so there is nothing to offer. An empty list is
        // the right answer; a fix pointing at an unrelated name would be worse than
        // none.
        val e = caught(
            """
            let f: { r: { alpha: Int } } -> Int = { r } -> r.zqxwv
            """
        )
        assertNotNull(e)
        assertTrue(renameCandidates(e).isEmpty(),
            "no near miss means no fix, got ${renameCandidates(e)}")
    }
}
