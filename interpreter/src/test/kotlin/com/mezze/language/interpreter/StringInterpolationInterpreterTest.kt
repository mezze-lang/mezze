package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class StringInterpolationInterpreterTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    @Test
    fun `test simple string interpolation`() {
        val source = """
            let name = "World"
            let greeting = "Hello $(name)!"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("World", (variables["name"] as MezzeString).value)
        assertEquals("Hello World!", (variables["greeting"] as MezzeString).value)
    }

    @Test
    fun `test multiple interpolations`() {
        val source = """
            let first = "Alice"
            let last = "Smith"
            let full = "$(first) $(last)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Alice Smith", (variables["full"] as MezzeString).value)
    }

    @Test
    fun `test interpolation only no surrounding text`() {
        val source = """
            let value = "test"
            let result = "$(value)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("test", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test interpolation with function call returning string`() {
        val source = """
            let intToString = {n: Int} -> if n == 0 then "0" else if n == 1 then "1" else if n == 42 then "42" else "other"
            let msg = "The answer is $(intToString{n = 42})"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("The answer is 42", (variables["msg"] as MezzeString).value)
    }

    @Test
    fun `test plain string without interpolation`() {
        val source = """
            let msg = "Hello World"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Hello World", (variables["msg"] as MezzeString).value)
    }

    @Test
    fun `test escaped interpolation is literal`() {
        val source = """
            let msg = "Use \$(var) for interpolation"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Use \$(var) for interpolation", (variables["msg"] as MezzeString).value)
    }

    @Test
    fun `test multiline string with interpolation`() {
        val source = """
            let a = "first"
            let b = "second"
            let multiline = "Line 1: $(a)
Line 2: $(b)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Line 1: first\nLine 2: second", (variables["multiline"] as MezzeString).value)
    }

    @Test
    fun `test non-string interpolation fails at typecheck`() {
        val source = """
            let num = 42
            let msg = "Number: $(num)"
        """.trimIndent()

        // Should throw a TypeCheckError because num is Int, not String
        assertThrows<TypeCheckError> {
            val program = parser.parse(source)
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test interpolation with record field access`() {
        val source = """
            let person = {name = "Bob", title = "Engineer"}
            let msg = "$(person.name) is an $(person.title)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Bob is an Engineer", (variables["msg"] as MezzeString).value)
    }

    @Test
    fun `test nested interpolated strings`() {
        val source = """
            let inner = "nested"
            let outer = "Value: $(inner)"
            let final = "Result: $(outer)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("nested", (variables["inner"] as MezzeString).value)
        assertEquals("Value: nested", (variables["outer"] as MezzeString).value)
        assertEquals("Result: Value: nested", (variables["final"] as MezzeString).value)
    }

    @Test
    fun `test empty string between interpolations`() {
        val source = """
            let a = "X"
            let b = "Y"
            let together = "$(a)$(b)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("XY", (variables["together"] as MezzeString).value)
    }

    @Test
    fun `test nested string with interpolation inside interpolation`() {
        val source = """
            let name = "World"
            let result = "outer: $("inner $(name)")"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("outer: inner World", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test empty string`() {
        val source = """
            let empty = ""
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("", (variables["empty"] as MezzeString).value)
    }

    @Test
    fun `test consecutive interpolations`() {
        val source = """
            let a = "A"
            let b = "B"
            let c = "C"
            let result = "$(a)$(b)$(c)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("ABC", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test interpolation with if expression`() {
        val source = """
            let result = "$(if True then "yes" else "no")"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("yes", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test interpolation with conditional expression`() {
        // Test conditional logic in interpolation context
        val source = """
            let flag = True
            let label = if flag then "enabled" else "disabled"
            let result = "Status: $(label)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Status: enabled", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test escape sequences mixed with interpolation`() {
        val source = """
            let middle = "MID"
            let result = "start\n$(middle)\tend"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("start\nMID\tend", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test boolean interpolation fails at typecheck`() {
        val source = """
            let flag = True
            let msg = "Flag: $(flag)"
        """.trimIndent()

        assertThrows<TypeCheckError> {
            val program = parser.parse(source)
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test record interpolation fails at typecheck`() {
        val source = """
            let person = {name = "Bob"}
            let msg = "Person: $(person)"
        """.trimIndent()

        assertThrows<TypeCheckError> {
            val program = parser.parse(source)
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test interpolation at string boundaries`() {
        val source = """
            let prefix = "START"
            let suffix = "END"
            let startOnly = "$(prefix) middle"
            let endOnly = "middle $(suffix)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("START middle", (variables["startOnly"] as MezzeString).value)
        assertEquals("middle END", (variables["endOnly"] as MezzeString).value)
    }

    @Test
    fun `test deeply nested interpolation`() {
        val source = """
            let a = "A"
            let level1 = "1$(a)1"
            let level2 = "2$(level1)2"
            let level3 = "3$(level2)3"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("1A1", (variables["level1"] as MezzeString).value)
        assertEquals("21A12", (variables["level2"] as MezzeString).value)
        assertEquals("321A123", (variables["level3"] as MezzeString).value)
    }

    @Test
    fun `test multiline string preserves newlines`() {
        val source = """
            let multi = "line1
line2
line3"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("line1\nline2\nline3", (variables["multi"] as MezzeString).value)
    }

    @Test
    fun `test multiline with interpolation preserves formatting`() {
        val source = """
            let name = "User"
            let greeting = "Hello $(name),
Welcome to our service.
Have a great day!"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Hello User,\nWelcome to our service.\nHave a great day!", (variables["greeting"] as MezzeString).value)
    }

    @Test
    fun `test multiple string interpolations produce correct result`() {
        val source = """
            let a = "Hello"
            let b = "World"
            let result = "$(a) $(b)"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Hello World", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test lambda returning string in interpolation`() {
        val source = """
            let greet = {name: String} -> "Hi $(name)"
            let result = "Message: $(greet{name = "Alice"})"
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Message: Hi Alice", (variables["result"] as MezzeString).value)
    }
}
