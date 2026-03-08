package com.mezze.language.formatter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DebugTest {
    private val formatter = Formatter()

    @Test
    fun `format should preserve operator syntax`() {
        val input = "let f = {x, y} -> x + y"
        val output = formatter.format(input)
        assertEquals("let f = {x, y} -> x + y\n", output)
    }

    @Test
    fun `format variant type`() {
        val input = "type Option 'a = | Some { value: 'a }, None |"
        val output = formatter.format(input)
        assertEquals("type Option 'a = Some { value: 'a } | None\n", output)
    }
}
