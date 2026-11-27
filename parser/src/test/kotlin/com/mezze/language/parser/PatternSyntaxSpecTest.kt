package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedConstructorPattern
import com.mezze.language.parsedast.ParsedLambda
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedMatch
import com.mezze.language.parsedast.ParsedPattern
import com.mezze.language.parsedast.ParsedRecordPattern
import com.mezze.language.parsedast.ParsedSeqPattern
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 * The pattern spelling rules (decided 2026-11-26/27), one test per form:
 *
 *   `_`    exactly one POSITIONAL value: a list element, a `$(_)` string hole, a whole arm
 *   `...`  "the rest": the remaining record fields (always last) or list elements
 *          (once, anywhere; named only when last, `[first, ...rest]`)
 *   a variant's payload is always a tagged record: `Some { val }`, `Some { ... }`
 *
 * Every rejected form is a parse error that names the spelling to use instead.
 */
class PatternSyntaxSpecTest {

    private val parser = MezzeParser()

    /** The first arm's pattern of `match x is <pat> -> 1 / _ -> 0`. */
    private fun armPattern(pat: String): ParsedPattern {
        val src = "let f = { x } -> match x is\n  $pat -> 1\n  _ -> 0"
        val decl = parser.parseParsedModule(src).decls.first() as ParsedLetDecl
        val match = (decl.value as ParsedLambda).body as ParsedMatch
        return match.cases.first().patterns.first()
    }

    private fun rejected(src: String, expectInMessage: String) {
        val err = runCatching { parser.parseParsedModule(src) }.exceptionOrNull()
            ?: fail("expected a parse error for:\n$src")
        assertTrue(err.message.orEmpty().contains(expectInMessage),
            "the error must say `$expectInMessage`; got: ${err.message}")
    }

    private fun rejectedArm(pat: String, expectInMessage: String) =
        rejected("let f = { x } -> match x is\n  $pat -> 1\n  _ -> 0", expectInMessage)

    // ── accepted ────────────────────────────────────────────────────────────

    @Test fun `exact-length list`() {
        val p = armPattern("[first, _]") as ParsedSeqPattern
        assertEquals(2, p.elements.size); assertNull(p.restPattern); assertEquals(0, p.suffixCount)
    }

    @Test fun `an unnamed rest at the end binds nothing`() {
        val p = armPattern("[first, ...]") as ParsedSeqPattern
        assertEquals(ParsedSeqPattern.IGNORED_REST, p.restPattern); assertEquals(0, p.suffixCount)
    }

    @Test fun `a named rest at the end binds the tail`() {
        val p = armPattern("[first, ...rest]") as ParsedSeqPattern
        assertEquals("rest", p.restPattern); assertEquals(1, p.elements.size)
    }

    @Test fun `an unnamed rest in the middle - elements after it are a suffix`() {
        val p = armPattern("[first, ..., last]") as ParsedSeqPattern
        assertEquals(ParsedSeqPattern.IGNORED_REST, p.restPattern)
        assertEquals(2, p.elements.size); assertEquals(1, p.suffixCount)
    }

    @Test fun `an unnamed rest first`() {
        val p = armPattern("[..., a, b]") as ParsedSeqPattern
        assertEquals(2, p.suffixCount); assertEquals(2, p.elements.size)
    }

    @Test fun `a positional wildcard next to a rest`() {
        val p = armPattern("[_, b, ...]") as ParsedSeqPattern
        assertEquals(2, p.elements.size); assertEquals(0, p.suffixCount)
    }

    @Test fun `the empty list and any list`() {
        assertNull((armPattern("[]") as ParsedSeqPattern).restPattern)
        assertEquals(0, (armPattern("[...]") as ParsedSeqPattern).elements.size)
    }

    @Test fun `a payload ignored with dots`() {
        val p = armPattern("Err { ... }") as ParsedConstructorPattern
        val payload = p.payload as ParsedRecordPattern
        assertTrue(payload.isOpen); assertTrue(payload.entries.isEmpty())
    }

    @Test fun `a payload field bound by pun and by name`() {
        assertNotNull(armPattern("Some { val }"))
        assertNotNull(armPattern("Some { val = x }"))
        assertTrue(((armPattern("Ok { val, ... }") as ParsedConstructorPattern).payload as ParsedRecordPattern).isOpen)
    }

    @Test fun `a string hole wildcard`() {
        assertNotNull(armPattern("\"api/$(_)\""))
    }

    @Test fun `let takes some record fields and ignores the rest`() {
        assertNotNull(parser.parseParsedModule("let f = { r } -> do\n  let { a, ... } = r\n  a"))
    }

    // ── rejected, each naming what to write instead ─────────────────────────

    @Test fun `field = _ - leave the field out and end with dots`() =
        rejectedArm("Err { error = _ }", "leave it out and end the pattern with `...`")

    @Test fun `dots-underscore - write dots alone`() =
        rejectedArm("[first, ..._]", "Write `...` on its own")

    @Test fun `Some x shorthand - a payload is a record`() =
        rejectedArm("Some y", "is not a pattern")

    @Test fun `a record cannot bind its remaining fields`() =
        rejected("let f = { r } -> do\n  let { a, ...rest } = r\n  a", "cannot bind its remaining fields")

    @Test fun `dots come last in a record pattern`() =
        rejected("let f = { r } -> do\n  let { ..., a } = r\n  a", "always comes last")

    @Test fun `a named rest must be last`() =
        rejectedArm("[first, ...middle, last]", "must be the last element")

    @Test fun `only one rest in a list`() =
        rejectedArm("[..., x, ...]", "only one `...`")

    @Test fun `let underscore binds nothing`() =
        rejected("let f = {} -> do\n  let _ = 1\n  2", "binds nothing")

    @Test fun `the field = _ error is not swallowed inside an impl operation`() =
        rejected(
            "effect E where { op: { o: Option Int } -> Int <E> }\n" +
                "impl E for H where {\n  op = { o } -> match o is\n    Some { val = _ } -> 1\n    None -> 0,\n}",
            "leave it out and end the pattern with `...`",
        )
}
