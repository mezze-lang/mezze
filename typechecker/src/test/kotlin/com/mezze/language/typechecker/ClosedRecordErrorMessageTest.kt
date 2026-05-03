package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #228: closed-record field mismatch error rendered with opaque
 * "Closed record field mismatch: {y} vs {x}. extra fields in first ..."
 * jargon. Replace with user-facing "missing 'x'; unexpected 'y'" wording.
 *
 * These tests pin the new wording so future edits don't accidentally
 * regress to the technical phrasing.
 */
class ClosedRecordErrorMessageTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun checkExpectingError(code: String): String {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return try {
            val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = false)
            // Recovery path can return Failure instead of throwing.
            when (r) {
                is TypeChecker.TypeCheckResult.Failure -> r.errors.joinToString("\n") { it.message }
                else -> fail<String>("expected typecheck to fail, got: $r")
            }
        } catch (e: TypeCheckError) {
            e.message
        }
    }

    @Test
    fun `lambda passed with wrong field name says missing and unexpected`() {
        // f wants `{ x: Int } -> Int`, caller passes `{ y } -> 99`. The
        // unifier reaches the inner record and decides {y} != {x}.
        val msg = checkExpectingError("""
            let f = { p: { x: Int } -> Int } -> p { x = 1 }
            let g = {} -> f { p = { y } -> 99 }
        """.trimIndent())
        // The new wording.
        assertTrue("missing 'x'" in msg,
            "expected message to mention missing 'x', got: $msg")
        assertTrue("unexpected 'y'" in msg,
            "expected message to mention unexpected 'y', got: $msg")
        // No leftover legacy phrasing.
        assertFalse("Closed record field mismatch" in msg,
            "old jargon should be gone, got: $msg")
        assertFalse("extra fields in first" in msg,
            "old jargon should be gone, got: $msg")
        assertFalse("extra fields in second" in msg,
            "old jargon should be gone, got: $msg")
    }

    @Test
    fun `record literal with extra field says unexpected`() {
        val msg = checkExpectingError("""
            let take = { r: { x: Int } } -> r.x
            let go = {} -> take { r = { x = 1, y = 2 } }
        """.trimIndent())
        // Either the outer record-arg is closed-record-mismatched, or the
        // inner field's record is. In either case the diagnostic must
        // surface the unexpected 'y' in user-friendly terms.
        assertTrue("unexpected 'y'" in msg || "extra" in msg.lowercase() && "y" in msg,
            "expected 'unexpected y' style wording, got: $msg")
        assertFalse("extra fields in first" in msg,
            "no 'first/second' jargon, got: $msg")
    }
}
