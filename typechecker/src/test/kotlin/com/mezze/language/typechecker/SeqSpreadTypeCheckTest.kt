package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for type checking seq spread syntax.
 *
 * Valid syntax: [...arr, elem1, elem2] (spread first, then append)
 * Invalid syntax: [elem1, ...arr] (prepend not allowed - caught at parse time)
 */
class SeqSpreadTypeCheckTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    // ========== Basic Spread Tests ==========

    @Test
    fun `spread seq preserves element type`() {
        val code = """
            let arr: [Int] = [1, 2, 3]
            let copy = [...arr]
            copy
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.getVariableType("copy")
        assertTrue(resultType is MezzeType.SeqType)
        assertTrue((resultType as MezzeType.SeqType).elementType is MezzeType.IntType)
    }

    @Test
    fun `spread with append infers correct type`() {
        val code = """
            let arr = [1, 2]
            let extended = [...arr, 3, 4]
            extended
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.getVariableType("extended")
        assertTrue(resultType is MezzeType.SeqType)
        assertTrue((resultType as MezzeType.SeqType).elementType is MezzeType.IntType)
    }

    @Test
    fun `multiple spreads with same type`() {
        val code = """
            let a = [1, 2]
            let b = [3, 4]
            let combined = [...a, ...b]
            combined
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.getVariableType("combined")
        assertTrue(resultType is MezzeType.SeqType)
        assertTrue((resultType as MezzeType.SeqType).elementType is MezzeType.IntType)
    }

    @Test
    fun `spread with string seq`() {
        val code = """
            let names = ["Alice", "Bob"]
            let extended = [...names, "Charlie"]
            extended
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [Str]
        val resultType = result.getVariableType("extended")
        assertTrue(resultType is MezzeType.SeqType)
        assertTrue((resultType as MezzeType.SeqType).elementType is MezzeType.StringType)
    }

    // ========== Type Error Tests ==========

    @Test
    fun `spread non-seq should fail`() {
        val code = """
            let x = 42
            let arr = [...x]
            arr
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `spread seq with incompatible append should fail`() {
        val code = """
            let nums = [1, 2, 3]
            let mixed = [...nums, "hello"]
            mixed
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.getVariableType("result")
        assertTrue(resultType is MezzeType.SeqType)
        assertTrue((resultType as MezzeType.SeqType).elementType is MezzeType.IntType)
    }

    @Test
    fun `spread copy function`() {
        val code = """
            let copy: {arr: ['a]} -> ['a] = {arr} -> [...arr]
            let nums = copy {arr = [1, 2, 3]}
            nums
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [Int]
        val resultType = result.getVariableType("nums")
        assertTrue(resultType is MezzeType.SeqType)
        assertTrue((resultType as MezzeType.SeqType).elementType is MezzeType.IntType)
    }

    // ========== Nested Seq Tests ==========

    @Test
    fun `spread nested seqs`() {
        val code = """
            let matrix = [[1, 2], [3, 4]]
            let extended = [...matrix, [5, 6]]
            extended
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)

        // Result should be [[Int]]
        val resultType = result.getVariableType("extended")
        assertTrue(resultType is MezzeType.SeqType)
        val innerType = (resultType as MezzeType.SeqType).elementType
        assertTrue(innerType is MezzeType.SeqType)
        assertTrue((innerType as MezzeType.SeqType).elementType is MezzeType.IntType)
    }
}
