package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertFails

/**
 * Tests for reserved name validation.
 *
 * Reserved names include:
 * - Names starting with '__native' (reserved for Mezze internals)
 * - 'scope' (reserved for scoped effects)
 */
class ReservedNameTest {

    private val parser = MezzeParser()

    @Test
    fun `__native prefix not allowed in let binding`() {
        val source = """
            let __native_foo = 42
        """.trimIndent()

        val error = assertFails { parser.parseParsedModule(source) }
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

        val error = assertFails { parser.parseParsedModule(source) }
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

        val error = assertFails { parser.parseParsedModule(source) }
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

        val error = assertFails { parser.parseParsedModule(source) }
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
        parser.parseParsedModule(source)
    }

    @Test
    fun `regular names still work`() {
        val source = """
            let my_function = {x, y} -> x
        """.trimIndent()

        // Should not throw
        parser.parseParsedModule(source)
    }

    @Test
    fun `names with native (no double underscore prefix) are allowed`() {
        val source = """
            let native_function = {native_param} -> native_param
        """.trimIndent()

        // Should not throw
        parser.parseParsedModule(source)
    }

    // ========================================
    // 'scope' reserved word tests
    // ========================================

    @Test
    fun `scope not allowed in let binding`() {
        val source = """
            let scope = 42
        """.trimIndent()

        val error = assertFails { parser.parseParsedModule(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("'scope'") && msg.contains("reserved"),
            "Error message should mention 'scope' is reserved: $msg"
        )
    }

    @Test
    fun `scope not allowed in lambda parameter`() {
        val source = """
            let foo = {scope} -> scope
        """.trimIndent()

        val error = assertFails { parser.parseParsedModule(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("'scope'") && msg.contains("reserved"),
            "Error message should mention 'scope' is reserved: $msg"
        )
    }

    @Test
    fun `scope not allowed in typed lambda parameter`() {
        val source = """
            let foo = {scope: Int} -> scope
        """.trimIndent()

        val error = assertFails { parser.parseParsedModule(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("'scope'") && msg.contains("reserved"),
            "Error message should mention 'scope' is reserved: $msg"
        )
    }

    @Test
    fun `scope not allowed in match pattern`() {
        val source = """
            let foo = {x} ->
                match x is
                    scope -> scope
        """.trimIndent()

        val error = assertFails { parser.parseParsedModule(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("'scope'") && msg.contains("reserved"),
            "Error message should mention 'scope' is reserved: $msg"
        )
    }

    @Test
    fun `scope not allowed in record pattern field`() {
        val source = """
            let foo = {rec} ->
                match rec is
                    {scope} -> 42
        """.trimIndent()

        val error = assertFails { parser.parseParsedModule(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("'scope'") && msg.contains("reserved"),
            "Error message should mention 'scope' is reserved: $msg"
        )
    }

    @Test
    fun `scope not allowed in let destructuring`() {
        // Test that scope can't be bound in let destructuring
        val source = "let {scope} = myRecord"

        val error = assertFails { parser.parseParsedModule(source) }
        val msg = error.message ?: ""
        assertTrue(
            msg.contains("'scope'") && msg.contains("reserved"),
            "Error message should mention 'scope' is reserved: $msg"
        )
    }

    @Test
    fun `scope allowed as record field name`() {
        // scope can be used as a field name in records
        // It's only reserved as a variable binding name
        val source = """
            let rec = {scope = 42}
        """.trimIndent()

        // Should not throw - scope as field name is allowed
        parser.parseParsedModule(source)
    }

    @Test
    fun `scope allowed as member access`() {
        // scope can be used as a member access
        val source = """
            let foo = {x} -> x.scope
        """.trimIndent()

        // Should not throw - member access is allowed
        parser.parseParsedModule(source)
    }

    @Test
    fun `scoped and scope_data are allowed`() {
        // Names containing 'scope' but not exactly 'scope' are fine
        val source = """
            let scoped = 42
            let scope_data = {scoped_val} -> scoped_val
        """.trimIndent()

        // Should not throw
        parser.parseParsedModule(source)
    }
}
