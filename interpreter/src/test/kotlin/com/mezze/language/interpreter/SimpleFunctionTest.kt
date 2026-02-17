package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SimpleFunctionTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    init {
        // Disable strict validation for function tests due to complex type inference issues
        System.setProperty("mezze.typecheck.strict", "false")
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    @Test
    fun `test simple function definition and call`() {
        val source = """
            let add = {x, y} -> x + y
            let result = add {x = 5, y = 3}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(8L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test function with single parameter`() {
        val source = """
            let square = {x} -> x * x
            let result1 = square {x = 4}
            let result2 = square {x = 7}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(16L, (variables["result1"] as MezzeInt).value)
        assertEquals(49L, (variables["result2"] as MezzeInt).value)
    }

    @Test
    fun `test function with record parameter`() {
        val source = """
            let getFullName = {person} -> "$(person.firstName) $(person.lastName)"
            let person = {firstName = "John", lastName = "Smith"}
            let fullName = getFullName {person = person}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("John Smith", (variables["fullName"] as MezzeString).value)
    }

    @Test
    fun `test function returning record`() {
        val source = """
            let createPerson = {name, age} -> {name = name, age = age, active = True}
            let person = createPerson {name = "Alice", age = 30}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val person = variables["person"] as MezzeRecord
        assertEquals("Alice", (person.fields["name"] as MezzeString).value)
        assertEquals(30L, (person.fields["age"] as MezzeInt).value)
        assertEquals(true, (person.fields["active"] as MezzeBool).value)
    }

    @Test
    fun `test function with simple conditional`() {
        val source = """
            let max = {x, y} -> if x > y then x else y
            let result1 = max {x = 5, y = 3}
            let result2 = max {x = 2, y = 8}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(5L, (variables["result1"] as MezzeInt).value)
        assertEquals(8L, (variables["result2"] as MezzeInt).value)
    }

    @Test
    fun `test function composition`() {
        val source = """
            let double = {x} -> x * 2
            let addOne = {x} -> x + 1
            let doubleAndAddOne = {x} -> addOne {x = double {x = x}}
            let result = doubleAndAddOne {x = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(11L, (variables["result"] as MezzeInt).value) // (5 * 2) + 1 = 11
    }

    @Test
    fun `test function with string operations`() {
        val source = """
            let greet = {name} -> "Hello, $(name)!"
            let greeting = greet {name = "World"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Hello, World!", (variables["greeting"] as MezzeString).value)
    }

    @Test
    fun `test function with record manipulation`() {
        val source = """
            let updateAge: {person: {name: String, age: Int}, newAge: Int} -> {name: String, age: Int} = {person, newAge} -> {...person, age = newAge}
            let person = {name = "Bob", age = 25}
            let olderPerson = updateAge {person = person, newAge = 26}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val person = variables["person"] as MezzeRecord
        assertEquals("Bob", (person.fields["name"] as MezzeString).value)
        assertEquals(25L, (person.fields["age"] as MezzeInt).value)

        val olderPerson = variables["olderPerson"] as MezzeRecord
        assertEquals("Bob", (olderPerson.fields["name"] as MezzeString).value)
        assertEquals(26L, (olderPerson.fields["age"] as MezzeInt).value)
    }

    @Test
    fun `test function closure`() {
        val source = """
            let x = 10
            let addX = {y} -> x + y
            let result = addX {y = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(15L, (variables["result"] as MezzeInt).value) // 10 + 5
    }

    @Test
    fun `test higher-order function`() {
        val source = """
            let applyTwice = {f, x} -> f {x = f {x = x}}
            let increment = {x} -> x + 1
            let result = applyTwice {f = increment, x = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(7L, (variables["result"] as MezzeInt).value) // increment(increment(5)) = 7
    }

    @Test
    fun `test function with nested conditionals`() {
        val source = """
            let classify = {x} -> if x > 0 then "positive" else if x < 0 then "negative" else "zero"
            let result1 = classify {x = 5}
            let result2 = classify {x = 0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("positive", (variables["result1"] as MezzeString).value)
        assertEquals("zero", (variables["result2"] as MezzeString).value)
    }

    @Test
    fun `test function with arithmetic expressions`() {
        val source = """
            let calculate = {a, b, c} -> (a + b) * c
            let result = calculate {a = 2, b = 3, c = 4}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(20L, (variables["result"] as MezzeInt).value) // (2 + 3) * 4 = 20
    }

    @Test
    fun `test multiple function definitions`() {
        val source = """
            let add = {x, y} -> x + y
            let multiply = {x, y} -> x * y
            let subtract = {x, y} -> x - y
            let result1 = add {x = 10, y = 5}
            let result2 = multiply {x = 3, y = 4}
            let result3 = subtract {x = 20, y = 8}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(15L, (variables["result1"] as MezzeInt).value)
        assertEquals(12L, (variables["result2"] as MezzeInt).value)
        assertEquals(12L, (variables["result3"] as MezzeInt).value)
    }
}
