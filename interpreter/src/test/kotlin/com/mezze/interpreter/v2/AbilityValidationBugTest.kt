package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for ability typechecker validation bugs.
 * These test cases should fail at typecheck time — the typechecker should
 * reject invalid ability impls before any code runs.
 */
class AbilityValidationBugTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun shouldTypeError(source: String, description: String) {
        try {
            eval(source)
            fail("Expected type error for: $description — but it ran successfully")
        } catch (e: Exception) {
            // Check it's a TYPE error, not a runtime error
            val msg = e.message ?: ""
            println("[$description] Error: ${e::class.simpleName}: $msg")
            assertTrue(
                msg.contains("type") || msg.contains("Type") || msg.contains("annotation") ||
                msg.contains("mismatch") || msg.contains("TypeCheck") ||
                e::class.simpleName?.contains("TypeCheck") == true,
                "Expected TYPE error but got runtime error: ${e::class.simpleName}: $msg"
            )
        }
    }

    @Test
    fun `show = it should fail — impl op must be a lambda`() {
        shouldTypeError("""
            ability Show for 'a where {
              show: 'a -> 'a
            }
            impl Show for Int where {
              show = it
            }
            42.show {}
        """.trimIndent(), "show = it (bare identifier, not a lambda)")
    }

    @Test
    fun `correct ability with record params should work`() {
        val result = eval("""
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> "number"
            }
            42.show {}
        """.trimIndent())
        assertEquals("number", result)
    }

    @Test
    fun `ability op type annotation should use record syntax`() {
        // The operation type should be {it: 'a} -> Str, not 'a -> Str
        val result = eval("""
            ability Describe for 'a where {
              describe: {it: 'a} -> Str
            }
            impl Describe for Int where {
              describe = {it} -> "int"
            }
            42.describe {}
        """.trimIndent())
        assertEquals("int", result)
    }

    @Test
    fun `polymorphic show via dictionary passing`() {
        val result = eval("""
            ability Describe for 'a where {
              describe: {it: 'a} -> Str
            }
            impl Describe for Int where {
              describe = {it} -> "int"
            }
            let show = {x} -> x.describe {}
            show {x = 42}
        """.trimIndent())
        assertEquals("int", result)
    }
}
