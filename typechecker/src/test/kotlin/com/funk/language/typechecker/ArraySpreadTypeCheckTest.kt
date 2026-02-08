package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.FunkType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for type checking array spread syntax.
 *
 * Valid syntax: [...arr, elem1, elem2] (spread first, then append)
 * Invalid syntax: [elem1, ...arr] (prepend not allowed - caught at parse time)
 */
class ArraySpreadTypeCheckTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    // ========== Basic Spread Tests ==========

    @Test
    fun `spread array preserves element type`() {
        val code = """
            let arr: [Int] = [1, 2, 3]
            let copy = [...arr]
            copy
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        assertTrue((resultType as FunkType.ArrayType).elementType is FunkType.IntType)
    }

    @Test
    fun `spread with append infers correct type`() {
        val code = """
            let arr = [1, 2]
            let extended = [...arr, 3, 4]
            extended
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        assertTrue((resultType as FunkType.ArrayType).elementType is FunkType.IntType)
    }

    @Test
    fun `multiple spreads with same type`() {
        val code = """
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b]
            combined
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        assertTrue((resultType as FunkType.ArrayType).elementType is FunkType.IntType)
    }

    @Test
    fun `spread with string array`() {
        val code = """
            let names = ["Alice", "Bob"]
            let extended = [...names, "Charlie"]
            extended
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [String]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        assertTrue((resultType as FunkType.ArrayType).elementType is FunkType.StringType)
    }

    // ========== Type Error Tests ==========

    @Test
    fun `spread non-array should fail`() {
        val code = """
            let x = 42
            let arr = [...x]
            arr
        """.trimIndent()

        val program = parser.parse(code)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `spread array with incompatible append should fail`() {
        val code = """
            let nums = [1, 2, 3]
            let mixed = [...nums, "hello"]
            mixed
        """.trimIndent()

        val program = parser.parse(code)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `multiple spreads with incompatible types should fail`() {
        val code = """
            let nums = [1, 2]
            let strs = ["a", "b"]
            let mixed = [...nums, ...strs]
            mixed
        """.trimIndent()

        val program = parser.parse(code)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    // ========== Polymorphic Tests ==========

    @Test
    fun `spread in polymorphic function`() {
        val code = """
            let prepend: {arr: ['a], x: 'a} -> ['a] = {arr, x} -> [...arr, x]
            let result = prepend {arr = [1, 2], x = 3}
            result
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        assertTrue((resultType as FunkType.ArrayType).elementType is FunkType.IntType)
    }

    @Test
    fun `spread copy function`() {
        val code = """
            let copy: {arr: ['a]} -> ['a] = {arr} -> [...arr]
            let nums = copy {arr = [1, 2, 3]}
            nums
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        assertTrue((resultType as FunkType.ArrayType).elementType is FunkType.IntType)
    }

    // ========== Nested Array Tests ==========

    @Test
    fun `spread nested arrays`() {
        val code = """
            let matrix = [[1, 2], [3, 4]]
            let extended = [...matrix, [5, 6]]
            extended
        """.trimIndent()

        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result)

        // Result should be [[Int]]
        val resultType = result.program.items.last().let {
            (it as com.funk.language.ast.TopLevelExpression).expression.inferredType
        }
        assertTrue(resultType is FunkType.ArrayType)
        val innerType = (resultType as FunkType.ArrayType).elementType
        assertTrue(innerType is FunkType.ArrayType)
        assertTrue((innerType as FunkType.ArrayType).elementType is FunkType.IntType)
    }
}
