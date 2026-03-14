package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for simple performer functionality.
 * Migrated from V1 to V2 with unboxed values.
 */
class SimplePerformerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testVerySimplePerformer() {
        // This is the exact same code that works in CLI
        val source = """
            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            impl Console for Terminal where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            perform Terminal in Console.print {msg = "Hello"}
        """.trimIndent()

        val result = eval(source)

        assertTrue(MezzeTestHelper.isUnit(result))
    }
}
