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
            it.code == WarningCode.W0101_UNUSED_VARIABLE && it.message.contains("'$name'")
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
    fun `underscore-prefixed do-block let is not flagged`() {
        val code = """
            let test = {} -> do
                let _x = 5
                42
        """.trimIndent()
        assertFalse(hasUnusedWarningFor("_x", code),
            "Should not warn — '_x' is intentionally unused. Warnings: ${warningsFor(code)}")
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
        assertTrue(hasUnusedWarningFor("x", code),
            "Should warn about unused lambda parameter 'x'. Warnings: ${warningsFor(code)}")
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
    fun `underscore-prefixed lambda parameter is not flagged`() {
        val code = """
            let test = {_x: Int} -> 42
        """.trimIndent()
        assertFalse(hasUnusedWarningFor("_x", code),
            "Should not warn — '_x' is intentionally unused. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `match binding nested in a do-block still warns (propagation fix)`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let test = {opt: Option} -> do
                let r =
                    match opt is
                        Some { val } -> 42
                        None -> 0
                r
        """.trimIndent()
        assertTrue(hasUnusedWarningFor("val", code),
            "Should warn about unused nested match binding 'val'. Warnings: ${warningsFor(code)}")
    }

    @Test
    fun `unused-variable warning carries the W0101 code`() {
        val code = """
            let test = {} -> do
                let x = 5
                42
        """.trimIndent()
        val w = warningsFor(code).firstOrNull { it.message.contains("'x'") }
        assertNotNull(w, "Expected an unused-variable warning for 'x'. Warnings: ${warningsFor(code)}")
        assertTrue(w.code == WarningCode.W0101_UNUSED_VARIABLE,
            "Warning should carry W0101 code, got ${w.code}")
    }
}
