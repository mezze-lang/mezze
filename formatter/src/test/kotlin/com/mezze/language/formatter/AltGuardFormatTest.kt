package com.mezze.language.formatter

import com.mezze.language.parser.MezzeParser
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * A per-alternative guard SURVIVES formatting.
 *
 * `|` scopes a guard to its own alternative, so an arm can carry several. The
 * printer joined the patterns with `|` and then printed only the arm-level
 * guard, so `Red if a | Pink if b` came back as `Red | Pink` — still valid, and
 * meaning something else. A formatter that loses code is worse than one that
 * formats badly, and nothing else would have caught it: the parsed AST was
 * right, the typechecker was right, and only running `mez fmt` destroyed the
 * program.
 */
class AltGuardFormatTest {
    private val parser = MezzeParser()

    private fun fmt(src: String): String =
        PrettyPrinter().printModule(parser.parseParsedModule(src.trimIndent()))

    @Test
    fun `a guard on each alternative is printed on each alternative`() {
        val out = fmt(
            """
            pub let f = { c, n } -> match c is
              Red if n > 0 | Pink if n < 5 -> 1
              _ -> 0
            """
        )
        assertTrue(
            out.contains("Red if n > 0 | Pink if n < 5"),
            "both guards must survive, each beside its own pattern; got:\n$out",
        )
    }

    @Test
    fun `a guard on only one alternative stays on that alternative`() {
        val out = fmt(
            """
            pub let f = { c, n } -> match c is
              Red | Pink if n > 0 -> 1
              _ -> 0
            """
        )
        assertTrue(
            out.contains("Red | Pink if n > 0"),
            "the guard belongs to Pink and must not migrate or vanish; got:\n$out",
        )
    }

    @Test
    fun `formatting is idempotent, so the guards do not drift`() {
        val src = """
            pub let f = { c, n } -> match c is
              Red if n > 0 | Pink if n < 5 -> 1
              _ -> 0
        """
        val once = fmt(src)
        val twice = PrettyPrinter().printModule(parser.parseParsedModule(once))
        assertEquals(
            once,
            twice,
            "a second pass must not move or drop a guard. NOTE this test alone would " +
                "NOT have caught the bug: dropping the guards is itself idempotent, so " +
                "the round trip was stable on the broken output. Verified — with the " +
                "fix removed, the two tests above fail and this one passes.",
        )
    }

    @Test
    fun `an ordinary arm guard is unchanged`() {
        val out = fmt(
            """
            pub let f = { c, n } -> match c is
              Red if n > 0 -> 1
              _ -> 0
            """
        )
        assertTrue(
            out.contains("Red if n > 0"),
            "one alternative keeps its guard in `guard`, so this path is the old " +
                "one and must stay exactly as it was; got:\n$out",
        )
    }
}
