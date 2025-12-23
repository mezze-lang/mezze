package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Tests for Bug #10 Extension: Spread-only records in function calls must be rejected
 *
 * Valid:
 * - `func { x = 1, y = 2 }` - explicit named arguments
 * - `func { x = k.x, y = k.y }` - field access from another record
 * - `{ ...record }` - record copy (not a function call)
 * - `{ ...record, field = newValue }` - record update (not a function call)
 *
 * Invalid:
 * - `func { ...k }` - spread-only in function call
 */
class SpreadOnlyFunctionCallTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    // ========== INVALID: Spread-only in function calls ==========

    @Test
    fun `should reject spread-only record in function call`() {
        val code = """
            let my_func = {x, y} -> x + y
            let k = { x = 5, y = 7 }
            let result = my_func { ...k }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
        assertTrue(exception.message!!.contains("my_func"))
    }

    @Test
    fun `should reject spread-only with single parameter function`() {
        val code = """
            let double = {x} -> x * 2
            let args = { x = 10 }
            let result = double { ...args }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    @Test
    fun `should reject spread-only with many parameters`() {
        val code = """
            let calc = {a, b, c, d} -> a + b + c + d
            let params = { a = 1, b = 2, c = 3, d = 4 }
            let result = calc { ...params }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    @Test
    fun `should show correct spread variable name in error message`() {
        val code = """
            let func = {x} -> x
            let myRecord = { x = 42 }
            let result = func { ...myRecord }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("myRecord"))
    }

    @Test
    fun `should suggest explicit named arguments in error message`() {
        val code = """
            let func = {x, y} -> x + y
            let k = { x = 1, y = 2 }
            let result = func { ...k }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("explicit named arguments"))
    }

    @Test
    fun `should reject spread-only in nested function call`() {
        val code = """
            let add = {x, y} -> x + y
            let double = {x} -> x * 2
            let args = { x = 5 }
            let result = add { x = double { ...args }, y = 10 }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    @Test
    fun `should reject spread-only in pipe operator function call`() {
        val code = """
            let process = {x} -> x * 2
            let args = { x = 5 }
            let result = 10 |> process { ...args }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    @Test
    fun `should reject spread-only with generic function`() {
        val code = """
            let identity = {x} -> x
            let args = { x = "hello" }
            let result = identity { ...args }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    @Test
    fun `should reject spread-only when spread expression is complex`() {
        val code = """
            let func = {x} -> x
            let getArgs = {} -> { x = 42 }
            let result = func { ...getArgs {} }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    // ========== VALID: Explicit named arguments ==========

    @Test
    fun `should allow explicit named arguments`() {
        val code = """
            let my_func = {x, y} -> x + y
            let result = my_func { x = 5, y = 7 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow field access from another record`() {
        val code = """
            let my_func = {x, y} -> x + y
            let k = { x = 5, y = 7 }
            let result = my_func { x = k.x, y = k.y }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow partial field access with literal`() {
        val code = """
            let my_func = {x, y} -> x + y
            let k = { x = 5 }
            let result = my_func { x = k.x, y = 10 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow nested function calls with explicit args`() {
        val code = """
            let add = {x, y} -> x + y
            let double = {x} -> x * 2
            let result = add { x = double { x = 5 }, y = 10 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow empty record for no-arg functions`() {
        val code = """
            let getFortyTwo = {} -> 42
            let result = getFortyTwo {}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow chained function calls with explicit args`() {
        val code = """
            let f = {x} -> {y} -> x + y
            let partial = f { x = 10 }
            let result = partial { y = 5 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow function call in if condition`() {
        val code = """
            let isPositive = {x} -> x > 0
            let result = if isPositive { x = 5 } then 1 else 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow function call with computed values`() {
        val code = """
            let add = {x, y} -> x + y
            let a = 5
            let b = 7
            let result = add { x = a * 2, y = b + 3 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== VALID: Spread for record operations (not function calls) ==========

    @Test
    fun `should allow spread-only for record copy`() {
        val code = """
            let original = { x = 1, y = 2 }
            let copy = { ...original }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow spread with field update`() {
        val code = """
            let original = { x = 1, y = 2 }
            let updated = { ...original, x = 10 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow spread with multiple field updates`() {
        val code = """
            let original = { a = 1, b = 2, c = 3 }
            let updated = { ...original, a = 10, c = 30 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow using spread result in function call with explicit args`() {
        val code = """
            let my_func = {x, y} -> x + y
            let original = { x = 1, y = 2 }
            let updated = { ...original, x = 10 }
            let result = my_func { x = updated.x, y = updated.y }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should allow nested record with spread`() {
        val code = """
            let inner = { x = 1, y = 2 }
            let outer = { inner = inner, z = 3 }
            let updatedInner = { ...inner, x = 10 }
            let updatedOuter = { ...outer, inner = updatedInner }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `should allow spread with fields even when used as function argument`() {
        // This is valid because spread has fields - it's a record update, not spread-only
        val code = """
            let takeRecord = {r} -> r.x + r.y
            let base = { x = 1, y = 2 }
            let result = takeRecord { r = { ...base, x = 10 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should reject spread-only even with matching field names`() {
        // Even if spread record has all the right fields, spread-only is not allowed
        val code = """
            let func = {x, y} -> x + y
            let perfectMatch = { x = 1, y = 2 }
            let result = func { ...perfectMatch }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }

    @Test
    fun `should allow function call with computed argument value`() {
        val code = """
            let add = {x, y} -> x + y
            let mul = {x, y} -> x * y
            let result = add { x = mul { x = 2, y = 3 }, y = 4 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should reject spread-only with higher order functions`() {
        val code = """
            let apply = {f, x} -> f { x = x }
            let double = {x} -> x * 2
            let args = { f = double, x = 5 }
            let result = apply { ...args }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message!!.contains("cannot use spread-only records"))
    }
}
