package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedFunctionType
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedSeqType
import com.mezze.language.parsedast.ParsedVariantType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parsing a type on its own, with no declaration around it.
 *
 * Every annotation in a Mezze program already goes through the same parser, but
 * only ever as part of a declaration — so a caller holding just a type (a
 * type-directed search, a REPL `:type`, a doc-browser query) had no way in.
 *
 * The alternative was to wrap the input in a synthetic declaration and dig the
 * annotation out of the result, which inherits the wrapper's syntax: an error in
 * the type would be reported at a line and column that do not exist in what the
 * user typed. These tests pin the two things that makes different — the whole
 * input must be a type, and errors point into the input itself.
 */
class ParseTypeAnnotationTest {

    private fun parse(src: String) = MezzeParser().parseTypeAnnotation(src)

    // ---- every shape a type can take ---------------------------------------

    @Test
    fun `parses a primitive`() {
        assertEquals("Int", (parse("Int") as ParsedNamedType).name)
    }

    @Test
    fun `parses a type variable`() {
        assertEquals("'a", (parse("'a") as ParsedNamedType).name)
    }

    @Test
    fun `parses a record`() {
        val rec = parse("{ x: Int, y: Str }") as ParsedRecordType
        assertEquals(listOf("x", "y"), rec.entries.map { it.name })
    }

    @Test
    fun `parses an open record`() {
        assertTrue((parse("{ x: Int, ... }") as ParsedRecordType).isOpen,
            "`...` is part of the type, and a search for an open record is a " +
                "different search")
    }

    @Test
    fun `parses a sequence`() {
        assertTrue(parse("[Int]") is ParsedSeqType)
    }

    @Test
    fun `parses a variant`() {
        val v = parse("|Red, Green|") as ParsedVariantType
        assertEquals(listOf("Red", "Green"), v.constructors.map { it.name })
    }

    @Test
    fun `parses a function`() {
        val fn = parse("{ x: Int } -> Str") as ParsedFunctionType
        assertTrue(fn.paramType is ParsedRecordType)
        assertEquals("Str", (fn.returnType as ParsedNamedType).name)
    }

    @Test
    fun `parses a curried function`() {
        val fn = parse("{ a: Int } -> { b: Int } -> Int") as ParsedFunctionType
        assertTrue(fn.returnType is ParsedFunctionType, "the arrow is right-associative")
    }

    @Test
    fun `parses an effect row`() {
        val fn = parse("{ msg: Str } -> {} <Log>") as ParsedFunctionType
        assertEquals(setOf("Log"), fn.effects?.effects?.toSet(),
            "an effect row is part of what a function IS")
    }

    @Test
    fun `parses an ability constraint`() {
        val fn = parse("{ x: 'a } with Ord 'a -> Bool") as ParsedFunctionType
        assertEquals(listOf("Ord"), fn.abilityConstraints.map { it.abilityName })
    }

    @Test
    fun `parses an applied type`() {
        // `Result Int Str` — a constructor with arguments, not a bare name.
        val t = parse("Result Int Str")
        assertTrue(t is com.mezze.language.parsedast.ParsedTypeApp, "got $t")
        assertEquals("Result", (t as com.mezze.language.parsedast.ParsedTypeApp).constructor)
        assertEquals(2, t.args.size)
    }

    // ---- the whole input must be a type ------------------------------------

    @Test
    fun `rejects trailing junk`() {
        // Parsing the prefix and ignoring the rest would answer a question nobody
        // asked — and silently, which is worse than failing.
        val e = assertFailsWith<ParseException> { parse("Int -> Int and then some") }
        assertTrue(e.message!!.contains("after the type"), "got: ${e.message}")
    }

    @Test
    fun `rejects the empty string`() {
        assertFailsWith<ParseException> { parse("") }
    }

    // ---- errors say something true -----------------------------------------

    @Test
    fun `input that cannot start a type says so`() {
        // The alternation's own message here is `Expected '[' but got 'let'`, which
        // names whichever branch it tried last. `[` is ONE way a type can start,
        // not the expected one, so reporting it tells the reader nothing true.
        val e = assertFailsWith<ParseException> { parse("let x = 1") }
        assertTrue(e.message!!.contains("`let` cannot start a type"), "got: ${e.message}")
    }

    @Test
    fun `the parser's own detail is kept as a hint, not discarded`() {
        // Replacing a message must not hide it: the specific expectation is still
        // the most useful line for someone debugging the grammar.
        val e = assertFailsWith<ParseException> { parse("let x = 1") }
        assertTrue(e.hints.any { it.contains("Expected") },
            "the original expectation should survive as a hint, got: ${e.hints}")
    }

    @Test
    fun `a failure INSIDE a type keeps the specific message`() {
        // The override applies only when nothing was consumed. Here the record
        // parser got somewhere and its message is the good one.
        val e = assertFailsWith<ParseException> { parse("{ x: Int ->") }
        assertTrue(e.message!!.contains("close record type"), "got: ${e.message}")
    }

    @Test
    fun `an error points into the input that was given`() {
        // The reason for not wrapping the query in a synthetic declaration: the
        // reported position must exist in what the caller typed.
        val e = assertFailsWith<ParseException> { parse("{ x: Int ->") }
        assertEquals(1, e.line)
        assertTrue(e.column in 1..11, "column ${e.column} should fall inside an 11-char input")
    }

    @Test
    fun `an unterminated string in a type is a lexer error, not a crash`() {
        assertFailsWith<ParseException> { parse("{ x: \"unterminated") }
    }

    // ---- no leakage between calls ------------------------------------------

    @Test
    fun `one parser instance handles several queries`() {
        // `currentSource`/`currentFile` are per-parse state; leaving them set would
        // attach the wrong source to the NEXT parse's diagnostics.
        val p = MezzeParser()
        assertEquals("Int", (p.parseTypeAnnotation("Int") as ParsedNamedType).name)
        assertEquals("Str", (p.parseTypeAnnotation("Str") as ParsedNamedType).name)
        assertTrue(p.parseTypeAnnotation("{ x: Int } -> Int") is ParsedFunctionType)
    }

    @Test
    fun `parsing a type does not disturb parsing a module`() {
        val p = MezzeParser()
        p.parseTypeAnnotation("{ x: Int } -> Int")
        val module = p.parseParsedModule("let add: { x: Int } -> Int = { x } -> x", "t.mz")
        assertEquals(1, module.decls.size)
    }
}
