package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.WarningCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * #84 linter — unused-variable coverage.
 *
 * The existing unused-variable warning only fired for match-case bindings, and
 * even those were dropped when nested inside a do-block (the collector's `merge`
 * did not propagate `unusedVariables`). These tests extend the lint to:
 *  - unused do-block `let` bindings,
 *  - unused lambda parameters,
 *  - nested match bindings (propagation fix),
 * and confirm `_`-prefix suppression plus a structured [WarningCode].
 */
class UnusedVariableLintTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun warningsFor(code: String): List<TypeCheckWarning> =
        typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get(".")).warnings

    private fun hasUnusedWarningFor(name: String, code: String): Boolean =
        warningsFor(code).any {
            it.code == WarningCode.W0101_UNUSED_VARIABLE && it.message.contains("`$name`")
        }

    private fun hasUnusedParamWarningFor(name: String, code: String): Boolean =
        warningsFor(code).any {
            it.code == WarningCode.W0103_UNUSED_PARAMETER && it.message.contains("`$name`")
        }

    @Test
    fun `unused do-block let binding warns`() {
        val code = """
            let test = {} -> do
                let x = 5
                42
        """.trimIndent()
        assertTrue(hasUnusedWarningFor("x", code),
            "Should warn about unused do-block let 'x'. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `used do-block let binding does not warn`() {
        val code = """
            let test = {} -> do
                let x = 5
                x + 1
        """.trimIndent()
        assertFalse(hasUnusedWarningFor("x", code),
            "Should not warn — 'x' is used. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `an underscore-prefixed do-block let is an ordinary name and is flagged`() {
        val code = """
            let test = {} -> do
                let _x = 5
                42
        """.trimIndent()
        assertTrue(hasUnusedWarningFor("_x", code),
            "`_x` is an ordinary name: an unused value is not bound at all. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `do-block let used only by a later let still counts as used`() {
        val code = """
            let test = {} -> do
                let x = 5
                let y = x + 1
                y
        """.trimIndent()
        assertFalse(hasUnusedWarningFor("x", code),
            "Should not warn — 'x' is used by 'y'. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `unused lambda parameter warns`() {
        val code = """
            let test = {x: Int} -> 42
        """.trimIndent()
        assertTrue(hasUnusedParamWarningFor("x", code),
            "Should warn (W0103) about unused lambda parameter 'x'. Warnings: ${warningsFor(code)}")
        // A parameter's name is the field callers pass: `_x` would rename the field,
        // so the message must never suggest it (that advice produced T0211 in CI).
        assertTrue(warningsFor(code).none { it.message.contains("Prefix with '_'") },
            "a parameter warning must not suggest `_`. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `a callback parameter is not flagged - its shape is given by the callee`() {
        val code = """
            let apply2: { f: { a: Int, b: Int } -> Int } -> Int = { f } -> f { a = 1, b = 2 }
            pub let t = {} -> apply2 { f = { a, b } -> a }
        """.trimIndent()
        assertTrue(!hasUnusedParamWarningFor("b", code) && !hasUnusedWarningFor("b", code),
            "`b` is fixed by apply2's callback type and cannot be dropped or renamed. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `used lambda parameter does not warn`() {
        val code = """
            let test = {x: Int} -> x + 1
        """.trimIndent()
        assertFalse(hasUnusedWarningFor("x", code),
            "Should not warn — 'x' is used. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `an underscore-prefixed free lambda parameter is an ordinary name and is flagged`() {
        val code = """
            let test = {_x: Int} -> 42
        """.trimIndent()
        assertTrue(hasUnusedParamWarningFor("_x", code),
            "`_x` is the field `_x`, not an exemption. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `match binding nested in a do-block still warns (propagation fix)`() {
        val code = """

            let test = {opt: Option Int} -> do
                let r =
                    match opt is
                        Some { val } -> 42
                        None -> 0
                r
        """.trimIndent()
        assertTrue(warningsFor(code).any { it.code == WarningCode.W0104_UNUSED_PATTERN_BINDING && it.message.contains("`val`") },
            "Should warn (W0104) about unused nested match binding 'val'. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `unused-variable warning carries the W0101 code`() {
        val code = """
            let test = {} -> do
                let x = 5
                42
        """.trimIndent()
        val w = warningsFor(code).firstOrNull { it.message.contains("`x`") }
        assertNotNull(w, "Expected an unused-variable warning for 'x'. Warnings: ${warningsFor(code)}")
        assertTrue(w.code == WarningCode.W0101_UNUSED_VARIABLE,
            "Warning should carry W0101 code, got ${w.code}")
    }
}
