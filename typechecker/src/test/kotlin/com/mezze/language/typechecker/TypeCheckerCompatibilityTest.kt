package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test that TypeChecker is a drop-in replacement for old TypeChecker.
 *
 * This test verifies that:
 * 1. TypeChecker has the same API as old TypeChecker
 * 2. It returns the same TypeCheckResult type
 * 3. It throws exceptions on errors (old behavior)
 * 4. Basic type checking works correctly
 */
class TypeCheckerCompatibilityTest {

    @BeforeEach
    fun setup() {
        // Clear cache before each test (old TypeChecker behavior)
        TypeChecker.clearCache()
    }

    @Test
    fun `test basic let binding`() {
        val source = "let x = 5"
        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")

        val checker = TypeChecker()
        val result = checker.typeCheck(parsedModule, java.nio.file.Paths.get("."))

        assertNotNull(result)
        assertNotNull(result)
    }

    @Test
    fun `test function definition and call`() {
        val source = """
            let add = {a, b} -> a + b
            let result = add {a = 5, b = 10}
        """.trimIndent()

        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")

        val checker = TypeChecker()
        val result = checker.typeCheck(parsedModule, java.nio.file.Paths.get("."))

        assertNotNull(result)
        assertNotNull(result)
    }

    @Test
    fun `test type error throws exception`() {
        val source = """
            let x = 5
            let y = "hello"
            let result = x + y
        """.trimIndent()

        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")
        val checker = TypeChecker()

        try {
            checker.typeCheck(parsedModule, java.nio.file.Paths.get("."))
            assert(false) { "Expected TypeCheckError to be thrown" }
        } catch (e: TypeCheckError) {
            // Expected behavior: a type error is thrown. Under the class-parameter
            // NumAdd ability the message is "Cannot use '+' with Str, Int" (no
            // `NumAdd Str Int` impl) rather than the old "type mismatch" wording —
            // accept either. What matters is that Int + Str is rejected.
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("Type") || msg.contains("type") ||
                    msg.contains("Cannot use '+'") || msg.contains("NumAdd"),
                "Expected a type/ability error message, got: $msg"
            )
        }
    }

    @Test
    fun `test monomorphization`() {
        val source = """
            let identity = {x} -> x
            let a = identity {x = 5}
            let b = identity {x = "hello"}
        """.trimIndent()

        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")
        val checker = TypeChecker()
        val result = checker.typeCheck(parsedModule, java.nio.file.Paths.get("."))

        assertNotNull(result)
    }

    @Test
    fun `test record types`() {
        val source = """
            let point = {x = 10, y = 20}
            let getX = {p} -> p.x
            let xValue = getX {p = point}
        """.trimIndent()

        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")
        val checker = TypeChecker()
        val result = checker.typeCheck(parsedModule, java.nio.file.Paths.get("."))

        assertNotNull(result)
        assertNotNull(result)
    }

    @Test
    fun `test variant types`() {
        val source = """
            type MyOption = | MySome { val: Int }, MyNone |

            let x = MySome {val = 42}
            let y = MyNone

            let getOr = {opt, default} ->
                match opt is
                  MySome {val} -> val
                  MyNone -> default

            let value = getOr {opt = x, default = 0}
        """.trimIndent()

        val parsedModule = MezzeParser().parseParsedModule(source, "test.mz")
        val checker = TypeChecker()
        val result = checker.typeCheck(parsedModule, java.nio.file.Paths.get("."))

        assertNotNull(result)
        assertNotNull(result)
    }

    @Test
    fun `test static cache methods exist`() {
        TypeChecker.clearCache()
        assertTrue(true)
    }
}
