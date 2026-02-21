package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/inspect module.
 * Tests the Inspect ability for converting values to strings.
 * Note: Inspect is an ability (not an effect), so it doesn't require a handler.
 */
class InspectAbilityTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === Integer Inspection ===

    @Test
    fun `test inspect integer`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = 42 }
        """)
        assertTrue(result is MezzeString)
        assertEquals("42", (result as MezzeString).value)
    }

    @Test
    fun `test inspect negative integer`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = -123 }
        """)
        assertTrue(result is MezzeString)
        assertEquals("-123", (result as MezzeString).value)
    }

    // === Float Inspection ===

    @Test
    fun `test inspect float`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = 3.14 }
        """)
        assertTrue(result is MezzeString)
        assertTrue((result as MezzeString).value.startsWith("3.14"))
    }

    // === String Inspection ===

    @Test
    fun `test inspect string`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = "hello" }
        """)
        assertTrue(result is MezzeString)
        assertTrue((result as MezzeString).value.contains("hello"))
    }

    // === Boolean Inspection ===

    @Test
    fun `test inspect true`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = True }
        """)
        assertTrue(result is MezzeString)
        assertTrue((result as MezzeString).value.contains("True") || (result as MezzeString).value.contains("true"))
    }

    @Test
    fun `test inspect false`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = False }
        """)
        assertTrue(result is MezzeString)
        assertTrue((result as MezzeString).value.contains("False") || (result as MezzeString).value.contains("false"))
    }

    // === Record Inspection ===

    @Test
    fun `test inspect record`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = { x = 10, y = 20 } }
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("x") || str.contains("10"))
    }

    // === Array Inspection ===

    @Test
    fun `test inspect array`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = [1, 2, 3] }
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("1") || str.contains("["))
    }

    // === Variant Inspection ===

    @Test
    fun `test inspect Some variant`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = Some { val = 42 } }
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("Some") || str.contains("42"))
    }

    @Test
    fun `test inspect None variant`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = None }
        """)
        assertTrue(result is MezzeString)
        assertTrue((result as MezzeString).value.contains("None"))
    }

    // === Unit Inspection ===

    @Test
    fun `test inspect unit`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = {} }
        """)
        assertTrue(result is MezzeString)
    }

    // === Nat Inspection ===

    @Test
    fun `test inspect nat`() {
        val result = eval("""
            use std::effects::inspect::{Inspect, Universal}

            Universal.to_str { val = 100n }
        """)
        assertTrue(result is MezzeString)
        assertTrue((result as MezzeString).value.contains("100"))
    }
}
