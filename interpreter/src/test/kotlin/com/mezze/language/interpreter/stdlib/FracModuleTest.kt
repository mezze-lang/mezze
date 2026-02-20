package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeFloat
import com.mezze.language.interpreter.MezzeFrac
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class FracModuleTest {

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
    fun `test frac add`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::add { l = a, r = b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        // 1/2 + 1/4 = 3/4
        assertEquals(3L, frac.numerator)
        assertEquals(4L, frac.denominator)
    }

    @Test
    fun `test frac subtract`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::subtract { l = a, r = b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        // 3/4 - 1/4 = 1/2
        assertEquals(1L, frac.numerator)
        assertEquals(2L, frac.denominator)
    }

    @Test
    fun `test frac multiply`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 2, denominator = 3 }
            let b = frac::make { numerator = 3, denominator = 4 }
            let result = frac::multiply { l = a, r = b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        // 2/3 * 3/4 = 6/12 = 1/2
        assertEquals(1L, frac.numerator)
        assertEquals(2L, frac.denominator)
    }

    @Test
    fun `test frac divide`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::divide { l = a, r = b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        // (1/2) / (1/4) = 1/2 * 4/1 = 4/2 = 2/1
        assertEquals(2L, frac.numerator)
        assertEquals(1L, frac.denominator)
    }

    @Test
    fun `test frac equals`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 2, denominator = 4 }
            let c = frac::make { numerator = 1, denominator = 3 }
            let result1 = frac::equals { l = a, r = b }
            let result2 = frac::equals { l = a, r = c }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as MezzeBool).value)
        assertEquals(false, (variables["result2"] as MezzeBool).value)
    }

    @Test
    fun `test frac less_than`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 4 }
            let b = frac::make { numerator = 1, denominator = 2 }
            let result = frac::less_than { l = a, r = b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test frac greater_than`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let result = frac::greater_than { l = a, r = b }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test frac compare`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 4 }
            let c = frac::make { numerator = 2, denominator = 4 }
            let result1 = frac::compare { l = a, r = b }
            let result2 = frac::compare { l = b, r = a }
            let result3 = frac::compare { l = a, r = c }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("GT", (variables["result1"] as MezzeVariant).constructor)
        assertEquals("LT", (variables["result2"] as MezzeVariant).constructor)
        assertEquals("EQ", (variables["result3"] as MezzeVariant).constructor)
    }

    @Test
    fun `test frac to_float`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 1, denominator = 2 }
            let result = frac::to_float { n = a }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0.5, (variables["result"] as MezzeFloat).value, 0.0001)
    }

    @Test
    fun `test frac to_string`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let result = frac::to_string { n = a }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("3/4", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test frac numerator and denominator`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let num = frac::numerator { n = a }
            let denom = frac::denominator { n = a }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3L, (variables["num"] as MezzeInt).value)
        assertEquals(4L, (variables["denom"] as MezzeInt).value)
    }

    @Test
    fun `test frac abs`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = -3, denominator = 4 }
            let result = frac::abs { n = a }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        assertEquals(3L, frac.numerator)
        assertEquals(4L, frac.denominator)
    }

    @Test
    fun `test frac negate`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let result = frac::negate { n = a }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        assertEquals(-3L, frac.numerator)
        assertEquals(4L, frac.denominator)
    }

    @Test
    fun `test frac from_int`() {
        val source = """
            use std::frac
            let result = frac::from_int { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        assertEquals(5L, frac.numerator)
        assertEquals(1L, frac.denominator)
    }

    @Test
    fun `test frac from_nat`() {
        val source = """
            use std::frac
            let result = frac::from_nat { n = 5n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        assertEquals(5L, frac.numerator)
        assertEquals(1L, frac.denominator)
    }

    @Test
    fun `test frac reduce`() {
        val source = """
            use std::frac
            let a = frac::make { numerator = 6, denominator = 8 }
            let result = frac::reduce { n = a }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val frac = variables["result"] as MezzeFrac
        // 6/8 reduces to 3/4
        assertEquals(3L, frac.numerator)
        assertEquals(4L, frac.denominator)
    }
}
