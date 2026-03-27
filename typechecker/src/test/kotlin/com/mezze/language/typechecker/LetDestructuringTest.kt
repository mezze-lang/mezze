package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class LetDestructuringTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program)
    }

    // ========== TOP-LEVEL RECORD DESTRUCTURING ==========

    @Test
    fun `test simple record destructuring at top level`() {
        val code = """
            let point = { x = 10, y = 20 }
            let { x, y } = point
            let sum = x + y
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test record destructuring with nested field access`() {
        val code = """
            let record = { a = 1, b = 2, c = 3 }
            let { a, b, c } = record
            let result = a + b + c
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test record destructuring with type annotation`() {
        val code = """
            let point: { x: Int, y: Int } = { x = 10, y = 20 }
            let { x, y } = point
            let sum = x + y
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== DO-BLOCK RECORD DESTRUCTURING ==========

    @Test
    fun `test record destructuring in do block`() {
        val code = """
            let getPoint = {} -> { x = 10, y = 20 }

            let test = {} -> do
                let point = getPoint {}
                let { x, y } = point
                x + y
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test multiple destructurings in do block`() {
        val code = """
            let p1 = { x = 1, y = 2 }
            let p2 = { x = 10, y = 20 }

            let test = {} -> do
                let { x = a, y = b } = p1
                let { x = c, y = d } = p2
                a + b + c + d
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== VARIANT DESTRUCTURING ==========
    // Note: Constructor pattern destructuring in let bindings (e.g., `let Constructor { x } = expr`)
    // requires parser support that is not yet implemented. Use match expressions for now.
    // The typechecker already supports this via extractPatternBindings().

    // ========== NESTED DESTRUCTURING ==========

    @Test
    fun `test nested record destructuring`() {
        val code = """
            let outer = { inner = { value = 42 } }
            let { inner = { value } } = outer
            let result = value
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test deeply nested destructuring`() {
        val code = """
            let deep = { a = { b = { c = 100 } } }
            let { a = { b = { c } } } = deep
            let result = c
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== DESTRUCTURING WITH RENAMED BINDINGS ==========

    @Test
    fun `test record destructuring with renamed binding`() {
        val code = """
            let point = { x = 10, y = 20 }
            let { x = myX, y = myY } = point
            let sum = myX + myY
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== TYPE INFERENCE ==========

    @Test
    fun `test destructured variables have correct types`() {
        // name should be Str, count should be Int, active should be Bool
        val code = """
            let record = { name = "test", count = 42, active = True }
            let { name, count, active } = record
            let countDouble = count + count
            let notActive = if active then False else True
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== ERROR CASES ==========

    @Test
    fun `test destructuring with duplicate bindings fails`() {
        val code = """
            let point = { x = 10, y = 20 }
            let { x, y } = point
            let { x, y } = point
        """.trimIndent()

        // Should fail because x and y are already defined
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
    }

    // ========== STRICT RECORD DESTRUCTURING ==========

    @Test
    fun `test partial destructuring without ellipsis fails`() {
        val code = """
            let record = { x = 1, y = 2, z = 3 }
            let { x, y } = record
        """.trimIndent()

        // Should fail because z is not destructured and no ... is used
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("missing fields") || exception.message!!.contains("Non-exhaustive record pattern"))
        kotlin.test.assertTrue(exception.message!!.contains("z"))
    }

    @Test
    fun `test partial destructuring with ellipsis passes`() {
        val code = """
            let record = { x = 1, y = 2, z = 3 }
            let { x, y, ... } = record
            let sum = x + y
        """.trimIndent()

        // Should pass because ... explicitly ignores extra fields
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test full destructuring without ellipsis passes`() {
        val code = """
            let record = { x = 1, y = 2 }
            let { x, y } = record
            let sum = x + y
        """.trimIndent()

        // Should pass because all fields are destructured
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test partial destructuring in do block without ellipsis fails`() {
        val code = """
            let test = {} -> do
                let record = { a = 1, b = 2, c = 3 }
                let { a } = record
                a
        """.trimIndent()

        // Should fail because b and c are not destructured
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        kotlin.test.assertTrue(exception.message!!.contains("missing fields") || exception.message!!.contains("Non-exhaustive record pattern"))
    }

    @Test
    fun `test partial destructuring in do block with ellipsis passes`() {
        val code = """
            let test = {} -> do
                let record = { a = 1, b = 2, c = 3 }
                let { a, ... } = record
                a
        """.trimIndent()

        // Should pass because ... ignores b and c
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test strict destructuring error message is helpful`() {
        val code = """
            let data = { name = "test", age = 25, city = "NYC" }
            let { name } = data
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Error message should list missing fields and suggest using ...
        kotlin.test.assertTrue(exception.message!!.contains("age"))
        kotlin.test.assertTrue(exception.message!!.contains("city"))
        kotlin.test.assertTrue(exception.message!!.contains("..."))
    }

    // ========== INTERACTION WITH OTHER FEATURES ==========

    @Test
    fun `test destructuring result used in function call`() {
        val code = """
            use std::int

            let point = { x = 10, y = 20 }
            let { x, y } = point
            let result = int::to_str { n = x + y }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test destructuring in lambda body`() {
        val code = """
            let processPoint = {point: {x: Int, y: Int}} -> do
                let { x, y } = point
                x * y

            let result = processPoint { point = { x = 3, y = 4 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test destructuring with match expression`() {
        val code = """
            type Result = | Ok { value: Int }, Err { message: Str } |

            let handleResult = {r: Result} -> match r is
                Ok { value } -> value
                Err { message } -> 0

            let ok = Ok { value = 42 }
            let test = handleResult { r = ok }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }
}
