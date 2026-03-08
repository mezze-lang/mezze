package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import com.mezze.interpreter.v2.values.MezzeVariant

/**
 * Tests for debug handlers with nested function calls and continuations.
 * Migrated from V1 to V2 with unboxed values.
 */
class DebugHandlersTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testNestedFunctionCalls() {
        val source = """
            use std::control::continuation

            # Test what happens when calling an imported function that uses continuation
            let cont = continuation::new {}

            let inner = { x } -> do
                # Extract the suspended value from a continuation
                let extracted = cont.extract_suspended { suspended = x }
                extracted

            let outer = { x } -> do
                # Call inner, which needs to extract suspended values
                inner { x = x }

            # Now test it - create a suspended value
            let gen = {} -> do
                cont.suspend_with { value = 42 }

            let g = gen {}

            # Call outer (which calls inner) to extract the suspended value
            outer { x = g }
        """.trimIndent()

        val result = eval(source)
        println("Result: $result")
        println("Result class: ${result::class.simpleName}")
        assertTrue(result is MezzeVariant)
    }
}
