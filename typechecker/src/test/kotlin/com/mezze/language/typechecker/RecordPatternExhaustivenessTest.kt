package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A record pattern must bind EVERY field of the record it matches, unless it
 * ends with `, ...`. This is the pattern-side dual of the value-side rule that a
 * closed record literal must supply every declared field (see
 * [OpenRecordValidationTest], the #190 fix): destructuring `{ a }` against a
 * `{ a, b }` value drops `b`, so it is rejected with
 * [ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN] and the fix is to write
 * `{ a, ... }`.
 *
 * The rule is by-pattern, not by-type: even when the value's record type is
 * OPEN (`{ a, b, ... }`), a subset pattern still needs an explicit `...` — the
 * openness of the type is independent of the openness of the pattern.
 */
class RecordPatternExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    /** Returns null on success, else the thrown TypeCheckError. */
    private fun err(src: String): TypeCheckError? =
        try {
            tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) { e }

    // ── match-arm record patterns (closed payload) ───────────────────────

    @Test
    fun `binding all payload fields is accepted`() {
        assertNull(err("""
            type Box = | Boxed { a: Int, b: Int } |
            let f = { x: Box } ->
              match x is
                Boxed { a = aa, b = bb } -> aa
        """))
    }

    @Test
    fun `binding a strict subset without dots is rejected`() {
        val e = err("""
            type Box = | Boxed { a: Int, b: Int } |
            let f = { x: Box } ->
              match x is
                Boxed { a = aa } -> aa
        """)
        assertNotNull(e, "a subset record pattern must be rejected")
        assertEquals(ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN, e!!.code)
        assertTrue(e.message.contains("b"), "should name the dropped field b: ${e.message}")
    }

    @Test
    fun `binding a subset WITH dots is accepted`() {
        assertNull(err("""
            type Box = | Boxed { a: Int, b: Int } |
            let f = { x: Box } ->
              match x is
                Boxed { a = aa, ... } -> aa
        """))
    }

    // ── the open-payload case (the interesting one) ──────────────────────

    @Test
    fun `a subset pattern on an OPEN payload still needs dots`() {
        // The payload record TYPE is open, but the PATTERN openness is separate:
        // `{ a }` still drops `b`, so `...` is still required.
        val e = err("""
            type Box = | Boxed { a: Int, b: Int, ... } |
            let f = { x: Box } ->
              match x is
                Boxed { a = aa } -> aa
        """)
        assertNotNull(e, "subset on an open payload must still be rejected without ...")
        assertEquals(ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN, e!!.code)
    }

    @Test
    fun `a subset pattern WITH dots on an OPEN payload is accepted`() {
        assertNull(err("""
            type Box = | Boxed { a: Int, b: Int, ... } |
            let f = { x: Box } ->
              match x is
                Boxed { a = aa, ... } -> aa
        """))
    }

    // ── plain (non-variant) record value in a match ──────────────────────

    @Test
    fun `subset pattern on a bare record value is rejected`() {
        val e = err("""
            let f = { r: { a: Int, b: Int } } ->
              match r is
                { a = aa } -> aa
        """)
        assertNotNull(e)
        assertEquals(ErrorCode.V0602_NON_EXHAUSTIVE_RECORD_PATTERN, e!!.code)
    }

    @Test
    fun `full pattern on a bare record value is accepted`() {
        assertNull(err("""
            let f = { r: { a: Int, b: Int } } ->
              match r is
                { a = aa, b = bb } -> aa
        """))
    }
}
