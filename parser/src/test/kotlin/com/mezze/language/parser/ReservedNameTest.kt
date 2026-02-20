package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFails

/**
 * Tests for reserved name validation.
 *
 * Names starting with '__native' are reserved for Mezze internals
 * and should not be allowed in user code.
 */
class ReservedNameTest {

    private val parser = MezzeParser()

    @Test
    fun `__native prefix not allowed in let binding`() {
        val source = """
            let __native_foo = 42
        """.trimIndent()

        val error = assertFails { parser.parse(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("__native") && msg.contains("reserved"),
            "Error message should mention __native is reserved: $msg"
        )
    }

    @Test
    fun `__native prefix not allowed in lambda parameter`() {
        val source = """
            let foo = {__native_x} -> __native_x
        """.trimIndent()

        val error = assertFails { parser.parse(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("__native") && msg.contains("reserved"),
            "Error message should mention __native is reserved: $msg"
        )
    }

    @Test
    fun `__native prefix not allowed in match pattern`() {
        val source = """
            let foo = {x} ->
                match x is
                    __native_val -> __native_val
        """.trimIndent()

        val error = assertFails { parser.parse(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("__native") && msg.contains("reserved"),
            "Error message should mention __native is reserved: $msg"
        )
    }

    @Test
    fun `__native prefix not allowed in record pattern field`() {
        val source = """
            let foo = {rec} ->
                match rec is
                    {__native_field} -> 42
        """.trimIndent()

        val error = assertFails { parser.parse(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("__native") && msg.contains("reserved"),
            "Error message should mention __native is reserved: $msg"
        )
    }

    @Test
    fun `calling __native functions is allowed`() {
        // Users should be able to CALL __native functions (from stdlib)
        // They just can't DEFINE them
        val source = """
            let foo = {} -> __native_int_add {a = 1, b = 2}
        """.trimIndent()

        // Should not throw - calling native functions is allowed
        parser.parse(source)
    }

    @Test
    fun `regular names still work`() {
        val source = """
            let my_function = {x, y} -> x
        """.trimIndent()

        // Should not throw
        parser.parse(source)
    }

    @Test
    fun `names with native (no double underscore prefix) are allowed`() {
        val source = """
            let native_function = {native_param} -> native_param
        """.trimIndent()

        // Should not throw
        parser.parse(source)
    }
}
