package com.funk.language.interpreter.stdlib

import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkInterpreter
import com.funk.language.interpreter.FunkString
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class IntModuleTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        interpreter = FunkInterpreter()
        // Set project root for module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, com.funk.language.interpreter.FunkValue> {
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        interpreter.execute(typedProgram, projectRoot)
        return interpreter.getLastExecutionContext()
    }

    @Test
    fun `test int add function`() {
        val source = """
            use std::int
            let result = int::add { l = 5, r = 3 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(8L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int subtract function`() {
        val source = """
            use std::int
            let result = int::subtract { l = 10, r = 4 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(6L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int multiply function`() {
        val source = """
            use std::int
            let result = int::multiply { l = 6, r = 7 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(42L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int divide function`() {
        val source = """
            use std::int
            let result = int::divide { l = 20, r = 4 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int modulo function`() {
        val source = """
            use std::int
            let result = int::modulo { l = 17, r = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(2L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int to_string function`() {
        val source = """
            use std::int
            let result = int::to_string { n = 42 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("42", (variables["result"] as FunkString).value)
    }

    @Test
    fun `test int abs function`() {
        val source = """
            use std::int
            let result1 = int::abs { n = -5 }
            let result2 = int::abs { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result1"] as FunkInt).value)
        assertEquals(5L, (variables["result2"] as FunkInt).value)
    }

    @Test
    fun `test int max function`() {
        val source = """
            use std::int
            let result = int::max { l = 5, r = 8 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(8L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int min function`() {
        val source = """
            use std::int
            let result = int::min { l = 5, r = 8 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int is_even function`() {
        val source = """
            use std::int
            let result1 = int::is_even { n = 4 }
            let result2 = int::is_even { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as com.funk.language.interpreter.FunkBool).value)
        assertEquals(false, (variables["result2"] as com.funk.language.interpreter.FunkBool).value)
    }

    @Test
    fun `test int is_odd function`() {
        val source = """
            use std::int
            let result1 = int::is_odd { n = 4 }
            let result2 = int::is_odd { n = 5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result1"] as com.funk.language.interpreter.FunkBool).value)
        assertEquals(true, (variables["result2"] as com.funk.language.interpreter.FunkBool).value)
    }

    @Test
    fun `test int gcd function`() {
        val source = """
            use std::int
            let result = int::gcd { l = 48, r = 18 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(6L, (variables["result"] as FunkInt).value)
    }

    @Test
    fun `test int lcm function`() {
        val source = """
            use std::int
            let result = int::lcm { l = 12, r = 18 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(36L, (variables["result"] as FunkInt).value)
    }
}
