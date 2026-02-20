package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeNat
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class NatModuleTest {

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
    fun `test nat subtract_to_int - positive result`() {
        val source = """
            use std::nat
            let result = nat::subtract_to_int { l = 10n, r = 3n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(7L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test nat subtract_to_int - negative result`() {
        val source = """
            use std::nat
            let result = nat::subtract_to_int { l = 3n, r = 10n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(-7L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test nat subtract_to_int - zero result`() {
        val source = """
            use std::nat
            let result = nat::subtract_to_int { l = 5n, r = 5n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test nat min function`() {
        val source = """
            use std::nat
            let result = nat::min { l = 5n, r = 3n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3L, (variables["result"] as MezzeNat).value)
    }

    @Test
    fun `test nat min function - equal values`() {
        val source = """
            use std::nat
            let result = nat::min { l = 5n, r = 5n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeNat).value)
    }

    @Test
    fun `test nat max function`() {
        val source = """
            use std::nat
            let result = nat::max { l = 5n, r = 3n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeNat).value)
    }

    @Test
    fun `test nat max function - equal values`() {
        val source = """
            use std::nat
            let result = nat::max { l = 5n, r = 5n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeNat).value)
    }

    @Test
    fun `test nat is_zero - true`() {
        val source = """
            use std::nat
            let result = nat::is_zero { n = 0n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test nat is_zero - false`() {
        val source = """
            use std::nat
            let result = nat::is_zero { n = 5n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test nat is_even`() {
        val source = """
            use std::nat
            let result1 = nat::is_even { n = 4n }
            let result2 = nat::is_even { n = 5n }
            let result3 = nat::is_even { n = 0n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result1"] as MezzeBool).value)
        assertEquals(false, (variables["result2"] as MezzeBool).value)
        assertEquals(true, (variables["result3"] as MezzeBool).value)
    }

    @Test
    fun `test nat is_odd`() {
        val source = """
            use std::nat
            let result1 = nat::is_odd { n = 4n }
            let result2 = nat::is_odd { n = 5n }
            let result3 = nat::is_odd { n = 0n }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result1"] as MezzeBool).value)
        assertEquals(true, (variables["result2"] as MezzeBool).value)
        assertEquals(false, (variables["result3"] as MezzeBool).value)
    }

    @Test
    fun `test nat from_int - valid positive`() {
        val source = """
            use std::nat
            let result = nat::from_int { n = 42 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(42L, (payload.fields["val"] as MezzeNat).value)
    }

    @Test
    fun `test nat from_int - negative returns None`() {
        val source = """
            use std::nat
            let result = nat::from_int { n = -5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test nat from_int - zero`() {
        val source = """
            use std::nat
            let result = nat::from_int { n = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(0L, (payload.fields["val"] as MezzeNat).value)
    }

    // === Additional tests for missing functions ===

    @Test
    fun `test nat from_float - valid positive`() {
        val source = """
            use std::nat
            let result = nat::from_float { n = 42.7 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(42L, (payload.fields["val"] as MezzeNat).value)
    }

    @Test
    fun `test nat from_float - negative returns None`() {
        val source = """
            use std::nat
            let result = nat::from_float { n = -5.5 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test nat from_float - zero`() {
        val source = """
            use std::nat
            let result = nat::from_float { n = 0.0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(0L, (payload.fields["val"] as MezzeNat).value)
    }

    @Test
    fun `test nat from_string - valid`() {
        val source = """
            use std::nat
            let result = nat::from_string { s = "42" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("Some", variant.constructor)
        val payload = variant.value as MezzeRecord
        assertEquals(42L, (payload.fields["val"] as MezzeNat).value)
    }

    @Test
    fun `test nat from_string - invalid returns None`() {
        val source = """
            use std::nat
            let result = nat::from_string { s = "not a number" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test nat from_string - negative returns None`() {
        val source = """
            use std::nat
            let result = nat::from_string { s = "-5" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        val variant = variables["result"] as MezzeVariant
        assertEquals("None", variant.constructor)
    }
}
