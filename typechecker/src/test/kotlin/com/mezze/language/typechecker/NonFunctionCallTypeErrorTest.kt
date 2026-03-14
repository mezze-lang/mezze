package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests that the typechecker correctly rejects calling non-function types as functions.
 *
 * This catches bugs where expressions like `f {} {}` are incorrectly accepted when
 * `f {}` returns a non-function type (like unit `{}`).
 */
class NonFunctionCallTypeErrorTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /**
     * Helper to check that type checking fails with a TypeCheckError.
     */
    private fun assertTypeCheckFails(source: String, message: String) {
        val program = parser.parse(source)
        try {
            typeChecker.typeCheck(program)
            fail<Unit>("Expected TypeCheckError but type checking succeeded. $message")
        } catch (e: TypeCheckError) {
            // Expected - type check should fail
            assertTrue(true, "TypeCheckError thrown as expected: ${e.message}")
        }
    }

    /**
     * Helper to check that type checking succeeds.
     */
    private fun assertTypeCheckSucceeds(source: String, message: String) {
        val program = parser.parse(source)
        try {
            typeChecker.typeCheck(program)
            // Success - no exception thrown
        } catch (e: TypeCheckError) {
            fail<Unit>("Expected type checking to succeed but got TypeCheckError: ${e.message}. $message")
        }
    }

    // =========================================================================
    // BASIC NON-FUNCTION CALL ERRORS
    // =========================================================================

    @Test
    fun `calling unit type should fail`() {
        // {} is unit, calling it with {} should fail
        val source = """
            let x = {} {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling unit as function")
    }

    @Test
    fun `calling integer literal should fail`() {
        val source = """
            let x = 42 {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Int as function")
    }

    @Test
    fun `calling string literal should fail`() {
        val source = """
            let x = "hello" {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling String as function")
    }

    @Test
    fun `calling boolean variable should fail`() {
        // Note: `True {}` parses as variant construction, not function call
        // So we test with a variable bound to a boolean
        val source = """
            let b = True
            let x = b {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Boolean variable as function")
    }

    @Test
    fun `calling record literal should fail`() {
        val source = """
            let x = { a = 1, b = 2 } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling record as function")
    }

    @Test
    fun `calling seq literal should fail`() {
        val source = """
            let x = [1, 2, 3] {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Seq as function")
    }

    // =========================================================================
    // CHAINED CALL ERRORS (the original bug)
    // =========================================================================

    @Test
    fun `chained call where first returns unit should fail`() {
        // This is the original bug case
        // f {} returns {}, so f {} {} should fail
        val source = """
            let f = { x: Int } -> {}
            let result = f { x = 1 } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling result of function that returns unit")
    }

    @Test
    fun `chained call where first returns Int should fail`() {
        val source = """
            let f = { x: Int } -> 42
            let result = f { x = 1 } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling result of function that returns Int")
    }

    @Test
    fun `chained call where first returns String should fail`() {
        val source = """
            let f = { x: Int } -> "hello"
            let result = f { x = 1 } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling result of function that returns String")
    }

    @Test
    fun `chained call where first returns record should fail`() {
        val source = """
            let f = { x: Int } -> { a = 1, b = 2 }
            let result = f { x = 1 } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling result of function that returns record")
    }

    // =========================================================================
    // VALID CURRIED FUNCTION CALLS (should pass)
    // =========================================================================

    @Test
    fun `curried function with two calls should pass`() {
        // f returns a function, so f {} {} is valid
        val source = """
            let f = { x: Int } -> { y: Int } -> x + y
            let result = f { x = 1 } { y = 2 }
        """.trimIndent()

        assertTypeCheckSucceeds(source, "Curried function call should succeed")
    }

    @Test
    fun `curried function with three calls should pass`() {
        val source = """
            let f = { x: Int } -> { y: Int } -> { z: Int } -> x + y + z
            let result = f { x = 1 } { y = 2 } { z = 3 }
        """.trimIndent()

        assertTypeCheckSucceeds(source, "Triple curried function call should succeed")
    }

    @Test
    fun `partial application should pass`() {
        val source = """
            let f = { x: Int } -> { y: Int } -> x + y
            let g = f { x = 1 }
            let result = g { y = 2 }
        """.trimIndent()

        assertTypeCheckSucceeds(source, "Partial application should succeed")
    }

    // =========================================================================
    // DO BLOCK EDGE CASES
    // =========================================================================

    @Test
    fun `do block result called as function should fail`() {
        // do block returns {}, calling it should fail
        val source = """
            let x = (do
                let y = 1
                {}) {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling do block result as function")
    }

    @Test
    fun `function call result in do block called again should fail`() {
        // This models the original bug in impl blocks
        val source = """
            let write = { content: Str } -> {}
            let x = do
                write { content = "hello" } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling write result (unit) as function")
    }

    // =========================================================================
    // VARIABLE BINDING EDGE CASES
    // =========================================================================

    @Test
    fun `calling variable bound to unit should fail`() {
        val source = """
            let unit = {}
            let x = unit {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling unit variable as function")
    }

    @Test
    fun `calling variable bound to Int should fail`() {
        val source = """
            let num = 42
            let x = num {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Int variable as function")
    }

    @Test
    fun `calling function variable should pass`() {
        val source = """
            let f = { x: Int } -> x + 1
            let x = f { x = 1 }
        """.trimIndent()

        assertTypeCheckSucceeds(source, "Calling function variable should succeed")
    }

    // =========================================================================
    // IF EXPRESSION EDGE CASES
    // =========================================================================

    @Test
    fun `calling if expression that returns unit should fail`() {
        val source = """
            let x = (if True then {} else {}) {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling if expression result as function")
    }

    @Test
    fun `calling if expression that returns function should pass`() {
        val source = """
            let f = { x: Int } -> x
            let g = { x: Int } -> x + 1
            let x = (if True then f else g) { x = 1 }
        """.trimIndent()

        assertTypeCheckSucceeds(source, "Calling if expression that returns function should succeed")
    }

    // =========================================================================
    // MATCH EXPRESSION EDGE CASES
    // =========================================================================

    @Test
    fun `calling match expression that returns unit should fail`() {
        val source = """
            type Option 'a = | Some { value: 'a }, None |
            let opt = Some { value = 1 }
            let x = (match opt is
                Some { value } -> {}
                None -> {}) {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling match expression result as function")
    }

    // =========================================================================
    // MEMBER ACCESS EDGE CASES
    // =========================================================================

    @Test
    fun `calling non-function record field should fail`() {
        val source = """
            let r = { x = 42 }
            let y = r.x {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Int field as function")
    }

    @Test
    fun `calling function record field should pass`() {
        val source = """
            let r = { f = { x: Int } -> x + 1 }
            let y = r.f { x = 1 }
        """.trimIndent()

        assertTypeCheckSucceeds(source, "Calling function field should succeed")
    }

    // =========================================================================
    // EFFECT CALL EDGE CASES (original bug)
    // =========================================================================

    @Test
    fun `effect operation result called as function should fail`() {
        // This is the pattern from the original bug report
        // FileSystem.write_file returns {}, calling that with {} should fail
        val source = """
            use std::effects::filesystem::{ FileSystem }

            let x = do
                FileSystem.write_file { path = "/tmp/test", content = "hello" } {}
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling effect operation result as function")
    }

    @Test
    fun `effect impl with chained call should fail`() {
        // The full original bug case
        val source = """
            use std::effects::log
            use std::effects::filesystem::{ FileSystem }

            impl log::Log for FileLogger { path: Str } where {
                log = { level, msg } -> do
                    FileSystem.write_file { path, content = msg } {}
            }
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject effect impl with chained call on unit result")
    }

    // =========================================================================
    // ABILITY IMPL EDGE CASES
    // =========================================================================

    @Test
    fun `ability impl with chained call should fail`() {
        val source = """
            use std::int

            ability Show 'a where {
                show: { val: 'a } -> Str
            }

            impl Show Int where {
                show = { val } -> do
                    int::to_str { n = val } {}
            }
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject ability impl with chained call on non-function result")
    }

    // =========================================================================
    // MULTIPLE ARGUMENT ERRORS
    // =========================================================================

    @Test
    fun `calling non-function with multiple arguments should fail`() {
        val source = """
            let x = 42 { a = 1 } { b = 2 }
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Int with multiple arguments")
    }

    @Test
    fun `function returning non-function called with extra arg should fail`() {
        val source = """
            let f = { x: Int } -> x
            let x = f { x = 1 } { y = 2 }
        """.trimIndent()

        assertTypeCheckFails(source, "Should reject calling Int result with extra argument")
    }
}
