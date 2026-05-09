package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

/**
 * Tests for recursive let-bound lambdas inside do blocks.
 *
 * Pattern: `let f = {x} -> ... f {x - 1}` inside a do block — the lambda
 * body should be able to reference its own binding name.
 */
class DoBlockRecursiveLetTest {
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `simple recursive countdown`() {
        val code = """
            let outer = {n: Int} -> do
              let go = {i: Int} -> if i == 0 then 0 else go {i = i - 1}
              go {i = n}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `recursive accumulator`() {
        val code = """
            let outer = {n: Int} -> do
              let go = {acc: Int, i: Int} -> if i == n
                then acc
                else go {acc = acc + i, i = i + 1}
              go {acc = 0, i = 0}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `recursive with type annotation`() {
        val code = """
            let outer = {n: Int} -> do
              let go: {i: Int} -> Int = {i} -> if i == 0 then 0 else go {i = i - 1}
              go {i = n}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `non-recursive lambda still works`() {
        val code = """
            let outer = {n: Int} -> do
              let double = {x: Int} -> x * 2
              double {x = n}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `factorial`() {
        val code = """
            let factorial = {n: Int} -> do
              let go = {i: Int, acc: Int} -> if i == 0
                then acc
                else go {i = i - 1, acc = acc * i}
              go {i = n, acc = 1}
        """.trimIndent()
        parseAndTypeCheck(code)
    }

    @Test
    fun `still detects undefined variables`() {
        val code = """
            let outer = {n: Int} -> do
              let result = undefined_var + n
              result
        """.trimIndent()
        assertThrows<TypeCheckError> { parseAndTypeCheck(code) }
    }
}
