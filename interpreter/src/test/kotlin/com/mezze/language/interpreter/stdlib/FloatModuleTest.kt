package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeFloat
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

class FloatModuleTest {

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
    fun `test float to_string function`() {
        val source = """
            use std::float
            let result = float::to_string { n = 3.14 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("3.14", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test float from_int function`() {
        val source = """
            use std::float
            let result = float::from_int { n = 42 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float from_nat function`() {
        val source = """
            use std::float
            let result = float::from_nat { n = 42n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float abs function - positive`() {
        val source = """
            use std::float
            let result = float::abs { n = 3.14 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3.14, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float abs function - negative`() {
        val source = """
            use std::float
            let result = float::abs { n = -3.14 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3.14, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float abs function - zero`() {
        val source = """
            use std::float
            let result = float::abs { n = 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float min function`() {
        val source = """
            use std::float
            let result = float::min { l = 3.14, r = 2.71 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(2.71, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float min function - negative numbers`() {
        val source = """
            use std::float
            let result = float::min { l = -3.14, r = -2.71 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-3.14, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float max function`() {
        val source = """
            use std::float
            let result = float::max { l = 3.14, r = 2.71 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3.14, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float max function - negative numbers`() {
        val source = """
            use std::float
            let result = float::max { l = -3.14, r = -2.71 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-2.71, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float min and max with equal values`() {
        val source = """
            use std::float
            let min_result = float::min { l = 5.0, r = 5.0 }
            let max_result = float::max { l = 5.0, r = 5.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5.0, (variables["min_result"] as MezzeFloat).value, 0.0001)
        assertEquals(5.0, (variables["max_result"] as MezzeFloat).value, 0.0001)
    }

    // === Additional tests for missing functions ===

    @Test
    fun `test float from_string - valid float`() {
        val source = """
            use std::float
            let result = match float::from_string { s = "3.14" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3.14, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float from_string - integer string`() {
        val source = """
            use std::float
            let result = match float::from_string { s = "42" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float from_string - negative float`() {
        val source = """
            use std::float
            let result = match float::from_string { s = "-2.718" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-2.718, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float from_string - invalid string`() {
        val source = """
            use std::float
            let result = match float::from_string { s = "not a number" } is
                Some { val } -> val
                None -> -999.0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-999.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    // === Float Classification Tests ===

    @Test
    fun `test float is_nan - with NaN`() {
        val source = """
            use std::float
            let result = float::is_nan { n = 0.0 / 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_nan - with normal value`() {
        val source = """
            use std::float
            let result = float::is_nan { n = 3.14 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_infinite - with positive infinity`() {
        val source = """
            use std::float
            let result = float::is_infinite { n = 1.0 / 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_infinite - with negative infinity`() {
        val source = """
            use std::float
            let result = float::is_infinite { n = -1.0 / 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_infinite - with normal value`() {
        val source = """
            use std::float
            let result = float::is_infinite { n = 3.14 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_finite - with normal value`() {
        val source = """
            use std::float
            let result = float::is_finite { n = 3.14 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_finite - with infinity`() {
        val source = """
            use std::float
            let result = float::is_finite { n = 1.0 / 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    @Test
    fun `test float is_finite - with NaN`() {
        val source = """
            use std::float
            let result = float::is_finite { n = 0.0 / 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as com.mezze.language.interpreter.MezzeBool).value)
    }

    // === Float Utilities Tests ===

    @Test
    fun `test float sign - positive`() {
        val source = """
            use std::float
            let result = float::sign { n = 5.5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float sign - negative`() {
        val source = """
            use std::float
            let result = float::sign { n = -5.5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-1.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float sign - zero`() {
        val source = """
            use std::float
            let result = float::sign { n = 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float clamp - within range`() {
        val source = """
            use std::float
            let result = float::clamp { n = 5.0, min = 0.0, max = 10.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float clamp - below min`() {
        val source = """
            use std::float
            let result = float::clamp { n = -5.0, min = 0.0, max = 10.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float clamp - above max`() {
        val source = """
            use std::float
            let result = float::clamp { n = 15.5, min = 0.0, max = 10.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(10.0, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test float to_int - positive truncate`() {
        val source = """
            use std::float
            let result = match float::to_int { n = 3.7 } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3L, (variables["result"] as com.mezze.language.interpreter.MezzeInt).value)
    }

    @Test
    fun `test float to_int - negative truncate`() {
        val source = """
            use std::float
            let result = match float::to_int { n = -3.7 } is
                Some { val } -> val
                None -> -999
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-3L, (variables["result"] as com.mezze.language.interpreter.MezzeInt).value)
    }

    @Test
    fun `test float to_int - with infinity returns None`() {
        val source = """
            use std::float
            let result = match float::to_int { n = 1.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as com.mezze.language.interpreter.MezzeInt).value)
    }

    @Test
    fun `test float to_int - with NaN returns None`() {
        val source = """
            use std::float
            let result = match float::to_int { n = 0.0 / 0.0 } is
                Some { val } -> 1
                None -> 0
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as com.mezze.language.interpreter.MezzeInt).value)
    }
}
