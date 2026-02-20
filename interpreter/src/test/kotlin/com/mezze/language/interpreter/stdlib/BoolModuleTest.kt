package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class BoolModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return MezzeTestHelper.getLastExecutionContext()
    }

    @Test
    fun `test bool and function - true and true`() {
        val source = """
            use std::bool
            let result = bool::and { l = True, r = True }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool and function - true and false`() {
        val source = """
            use std::bool
            let result = bool::and { l = True, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool and function - false and false`() {
        val source = """
            use std::bool
            let result = bool::and { l = False, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool or function - true or false`() {
        val source = """
            use std::bool
            let result = bool::or { l = True, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool or function - false or false`() {
        val source = """
            use std::bool
            let result = bool::or { l = False, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool not function - not true`() {
        val source = """
            use std::bool
            let result = bool::not { b = True }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool not function - not false`() {
        val source = """
            use std::bool
            let result = bool::not { b = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool xor function - true xor true`() {
        val source = """
            use std::bool
            let result = bool::xor { l = True, r = True }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool xor function - true xor false`() {
        val source = """
            use std::bool
            let result = bool::xor { l = True, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool xor function - false xor false`() {
        val source = """
            use std::bool
            let result = bool::xor { l = False, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool implies function - true implies true`() {
        val source = """
            use std::bool
            let result = bool::implies { l = True, r = True }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool implies function - true implies false`() {
        val source = """
            use std::bool
            let result = bool::implies { l = True, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool implies function - false implies anything is true`() {
        val source = """
            use std::bool
            let result1 = bool::implies { l = False, r = True }
            let result2 = bool::implies { l = False, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as MezzeBool).value)
        assertEquals(true, (variables["result2"] as MezzeBool).value)
    }

    @Test
    fun `test bool iff function - true iff true`() {
        val source = """
            use std::bool
            let result = bool::iff { l = True, r = True }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool iff function - true iff false`() {
        val source = """
            use std::bool
            let result = bool::iff { l = True, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool iff function - false iff false`() {
        val source = """
            use std::bool
            let result = bool::iff { l = False, r = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test bool to_string function`() {
        val source = """
            use std::bool
            let result1 = bool::to_string { b = True }
            let result2 = bool::to_string { b = False }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("true", (variables["result1"] as MezzeString).value)
        assertEquals("false", (variables["result2"] as MezzeString).value)
    }
}
