package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.graalvm.polyglot.PolyglotException

/**
 * Tests for string interpolation functionality.
 * Migrated from V1 to V2 with unboxed values.
 */
class StringInterpolationInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test simple string interpolation`() {
        val code = """
            let name = "World"
            let greeting = "Hello $(name)!"
            greeting
        """
        assertEquals("Hello World!", eval(code))
    }

    @Test
    fun `test multiple interpolations`() {
        val code = """
            let first = "Alice"
            let last = "Smith"
            let full = "$(first) $(last)"
            full
        """
        assertEquals("Alice Smith", eval(code))
    }

    @Test
    fun `test interpolation only no surrounding text`() {
        val code = """
            let value = "test"
            let result = "$(value)"
            result
        """
        assertEquals("test", eval(code))
    }

    @Test
    fun `test interpolation with function call returning string`() {
        val code = """
            let intToString = {n: Int} -> if n == 0 then "0" else if n == 1 then "1" else if n == 42 then "42" else "other"
            let msg = "The answer is $(intToString{n = 42})"
            msg
        """
        assertEquals("The answer is 42", eval(code))
    }

    @Test
    fun `test plain string without interpolation`() {
        val code = """
            let msg = "Hello World"
            msg
        """
        assertEquals("Hello World", eval(code))
    }

    @Test
    fun `test escaped interpolation is literal`() {
        val code = """
            let msg = "Use \$(var) for interpolation"
            msg
        """
        assertEquals("Use \$(var) for interpolation", eval(code))
    }

    @Test
    fun `test multiline string with interpolation`() {
        val code = """
            let a = "first"
            let b = "second"
            let multiline = "Line 1: $(a)
Line 2: $(b)"
            multiline
        """
        assertEquals("Line 1: first\nLine 2: second", eval(code))
    }

    @Test
    fun `test non-string interpolation fails at typecheck`() {
        val code = """
            let num = 42
            let msg = "Number: $(num)"
            msg
        """
        val exception = assertThrows(PolyglotException::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("TypeCheckError") == true)
        assertTrue(exception.message?.contains("expected Str") == true)
    }

    @Test
    fun `test interpolation with record field access`() {
        val code = """
            let person = {name = "Bob", title = "Engineer"}
            let msg = "$(person.name) is an $(person.title)"
            msg
        """
        assertEquals("Bob is an Engineer", eval(code))
    }

    @Test
    fun `test nested interpolated strings`() {
        val code = """
            let inner = "nested"
            let outer = "Value: $(inner)"
            let final = "Result: $(outer)"
            final
        """
        assertEquals("Result: Value: nested", eval(code))
    }

    @Test
    fun `test empty string between interpolations`() {
        val code = """
            let a = "X"
            let b = "Y"
            let together = "$(a)$(b)"
            together
        """
        assertEquals("XY", eval(code))
    }

    @Test
    fun `test nested string with interpolation inside interpolation`() {
        val code = """
            let name = "World"
            let result = "outer: $("inner $(name)")"
            result
        """
        assertEquals("outer: inner World", eval(code))
    }

    @Test
    fun `test empty string`() {
        val code = """
            let empty = ""
            empty
        """
        assertEquals("", eval(code))
    }

    @Test
    fun `test consecutive interpolations`() {
        val code = """
            let a = "A"
            let b = "B"
            let c = "C"
            let result = "$(a)$(b)$(c)"
            result
        """
        assertEquals("ABC", eval(code))
    }

    @Test
    fun `test interpolation with if expression`() {
        val code = """
            let result = "$(if True then "yes" else "no")"
            result
        """
        assertEquals("yes", eval(code))
    }

    @Test
    fun `test interpolation with conditional expression`() {
        val code = """
            let flag = True
            let label = if flag then "enabled" else "disabled"
            let result = "Status: $(label)"
            result
        """
        assertEquals("Status: enabled", eval(code))
    }

    @Test
    fun `test escape sequences mixed with interpolation`() {
        val code = """
            let middle = "MID"
            let result = "start\n$(middle)\tend"
            result
        """
        assertEquals("start\nMID\tend", eval(code))
    }

    @Test
    fun `test boolean interpolation fails at typecheck`() {
        val code = """
            let flag = True
            let msg = "Flag: $(flag)"
            msg
        """
        val exception = assertThrows(PolyglotException::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("TypeCheckError") == true)
        assertTrue(exception.message?.contains("expected Str") == true)
    }

    @Test
    fun `test record interpolation fails at typecheck`() {
        val code = """
            let person = {name = "Bob"}
            let msg = "Person: $(person)"
            msg
        """
        val exception = assertThrows(PolyglotException::class.java) {
            eval(code)
        }
        assertTrue(exception.message?.contains("TypeCheckError") == true)
        assertTrue(exception.message?.contains("expected Str") == true)
    }

    @Test
    fun `test interpolation at string boundaries`() {
        val code = """
            let prefix = "START"
            let suffix = "END"
            let startOnly = "$(prefix) middle"
            startOnly
        """
        assertEquals("START middle", eval(code))
    }

    @Test
    fun `test interpolation at end boundary`() {
        val code = """
            let suffix = "END"
            let endOnly = "middle $(suffix)"
            endOnly
        """
        assertEquals("middle END", eval(code))
    }

    @Test
    fun `test deeply nested interpolation`() {
        val code = """
            let a = "A"
            let level1 = "1$(a)1"
            let level2 = "2$(level1)2"
            let level3 = "3$(level2)3"
            level3
        """
        assertEquals("321A123", eval(code))
    }

    @Test
    fun `test multiline string preserves newlines`() {
        val code = """
            let multi = "line1
line2
line3"
            multi
        """
        assertEquals("line1\nline2\nline3", eval(code))
    }

    @Test
    fun `test multiline with interpolation preserves formatting`() {
        val code = """
            let name = "User"
            let greeting = "Hello $(name),
Welcome to our service.
Have a great day!"
            greeting
        """
        assertEquals("Hello User,\nWelcome to our service.\nHave a great day!", eval(code))
    }

    @Test
    fun `test multiple string interpolations produce correct result`() {
        val code = """
            let a = "Hello"
            let b = "World"
            let result = "$(a) $(b)"
            result
        """
        assertEquals("Hello World", eval(code))
    }

    @Test
    fun `test lambda returning string in interpolation`() {
        val code = """
            let greet = {name: Str} -> "Hi $(name)"
            let result = "Message: $(greet{name = "Alice"})"
            result
        """
        assertEquals("Message: Hi Alice", eval(code))
    }
}
