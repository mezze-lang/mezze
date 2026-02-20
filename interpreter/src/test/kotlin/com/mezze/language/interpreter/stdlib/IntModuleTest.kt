package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class IntModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        // Set project root for module resolution
        TestUtils.setupProjectRoot()
        // Clear module cache to ensure clean state between tests
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, com.mezze.language.interpreter.MezzeValue> {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return MezzeTestHelper.getLastExecutionContext()
    }

    @Test
    fun `test int add function`() {
        val source = """
            use std::int
            let result = int::add { l = 5, r = 3 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(8L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int subtract function`() {
        val source = """
            use std::int
            let result = int::subtract { l = 10, r = 4 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(6L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int multiply function`() {
        val source = """
            use std::int
            let result = int::multiply { l = 6, r = 7 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int divide function`() {
        val source = """
            use std::int
            let result = int::divide { l = 20, r = 4 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int modulo function`() {
        val source = """
            use std::int
            let result = int::modulo { l = 17, r = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(2L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int to_string function`() {
        val source = """
            use std::int
            let result = int::to_string { n = 42 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("42", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test int abs function`() {
        val source = """
            use std::int
            let result1 = int::abs { n = -5 }
            let result2 = int::abs { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result1"] as MezzeInt).value)
        assertEquals(5L, (variables["result2"] as MezzeInt).value)
    }

    @Test
    fun `test int max function`() {
        val source = """
            use std::int
            let result = int::max { l = 5, r = 8 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(8L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int min function`() {
        val source = """
            use std::int
            let result = int::min { l = 5, r = 8 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int is_even function`() {
        val source = """
            use std::int
            let result1 = int::is_even { n = 4 }
            let result2 = int::is_even { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as com.mezze.language.interpreter.MezzeBool).value)
        assertEquals(false, (variables["result2"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test int is_odd function`() {
        val source = """
            use std::int
            let result1 = int::is_odd { n = 4 }
            let result2 = int::is_odd { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result1"] as com.mezze.language.interpreter.MezzeBool).value)
        assertEquals(true, (variables["result2"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test int gcd function`() {
        val source = """
            use std::int
            let result = int::gcd { l = 48, r = 18 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(6L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int lcm function`() {
        val source = """
            use std::int
            let result = int::lcm { l = 12, r = 18 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(36L, (variables["result"] as MezzeInt).value)
    }

    // === Additional tests for missing functions ===

    @Test
    fun `test int from_string - valid integer`() {
        val source = """
            use std::int
            let result = match int::from_string { s = "42" } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int from_string - negative integer`() {
        val source = """
            use std::int
            let result = match int::from_string { s = "-123" } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-123L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int from_string - invalid string`() {
        val source = """
            use std::int
            let result = match int::from_string { s = "not a number" } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-999L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int from_nat`() {
        val source = """
            use std::int
            let result = int::from_nat { n = 42n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int sign - positive`() {
        val source = """
            use std::int
            let result = int::sign { n = 42 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int sign - negative`() {
        val source = """
            use std::int
            let result = int::sign { n = -42 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-1L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int sign - zero`() {
        val source = """
            use std::int
            let result = int::sign { n = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int pow - basic`() {
        val source = """
            use std::int
            let result = int::pow { base = 2, exp = 10 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1024L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int pow - zero exponent`() {
        val source = """
            use std::int
            let result = int::pow { base = 5, exp = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test int pow - exponent of one`() {
        val source = """
            use std::int
            let result = int::pow { base = 7, exp = 1 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(7L, (variables["result"] as MezzeInt).value)
    }
}
