package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * #84 linter — dead / redundant code (W0301 discarded value, W0302 constant
 * condition, W0303 redundant boolean). Purely syntactic; on by default.
 */
class DeadCodeLintTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun warningsFor(code: String): List<TypeCheckWarning> =
        typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get(".")).warnings

    private fun has(code: WarningCode, src: String): Boolean =
        warningsFor(src).any { it.code == code }

    // ── W0301 discarded value ────────────────────────────────────────────

    @Test
    fun `bare arithmetic statement is a discarded value`() {
        assertTrue(has(WarningCode.W0301_DISCARDED_VALUE, """
            let f = {} -> do
              1 + 2
              42
        """.trimIndent()), "Warnings: ${warningsFor("let f = {} -> do\n  1 + 2\n  42")}")
    }

    @Test
    fun `bare identifier statement is a discarded value`() {
        assertTrue(has(WarningCode.W0301_DISCARDED_VALUE, """
            let f = {x: Int} -> do
              x
              x + 1
        """.trimIndent()))
    }

    @Test
    fun `a function call statement is NOT flagged (may have effects)`() {
        assertFalse(has(WarningCode.W0301_DISCARDED_VALUE, """
            let g = {} -> 0
            let f = {} -> do
              g {}
              42
        """.trimIndent()))
    }

    @Test
    fun `the do-block result is never a discarded value`() {
        assertFalse(has(WarningCode.W0301_DISCARDED_VALUE, """
            let f = {} -> do
              let x = 5
              x + 1
        """.trimIndent()))
    }

    // ── W0302 constant condition ─────────────────────────────────────────

    @Test
    fun `if with literal True condition warns`() {
        assertTrue(has(WarningCode.W0302_CONSTANT_CONDITION, """
            let f = {} -> if True then 1 else 2
        """.trimIndent()))
    }

    @Test
    fun `if with a real condition does not warn`() {
        assertFalse(has(WarningCode.W0302_CONSTANT_CONDITION, """
            let f = {b: Bool} -> if b then 1 else 2
        """.trimIndent()))
    }

    // ── W0303 redundant boolean ──────────────────────────────────────────

    @Test
    fun `double negation warns`() {
        assertTrue(has(WarningCode.W0303_REDUNDANT_BOOLEAN, """
            let f = {b: Bool} -> !(!b)
        """.trimIndent()))
    }

    @Test
    fun `if b then True else False warns`() {
        assertTrue(has(WarningCode.W0303_REDUNDANT_BOOLEAN, """
            let f = {b: Bool} -> if b then True else False
        """.trimIndent()))
    }

    @Test
    fun `comparing to a boolean literal warns`() {
        assertTrue(has(WarningCode.W0303_REDUNDANT_BOOLEAN, """
            let f = {b: Bool} -> b == True
        """.trimIndent()))
    }

    @Test
    fun `a single negation is fine`() {
        assertFalse(has(WarningCode.W0303_REDUNDANT_BOOLEAN, """
            let f = {b: Bool} -> !b
        """.trimIndent()))
    }

    @Test
    fun `a normal comparison is fine`() {
        assertFalse(has(WarningCode.W0303_REDUNDANT_BOOLEAN, """
            let f = {x: Int} -> x == 5
        """.trimIndent()))
    }
}
