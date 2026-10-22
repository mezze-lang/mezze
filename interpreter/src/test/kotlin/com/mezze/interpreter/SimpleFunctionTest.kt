package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for simple function definitions and calls.
 * Migrated from V1 to V2 with unboxed values.
 */
class SimpleFunctionTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test simple function definition and call`() {
        val code = """
            let add = {x, y} -> x + y
            add {x = 5, y = 3}
        """
        val result = eval(code)
        assertEquals(8L, result)
    }

    @Test
    fun `spread-only argument into an ordinary function call evaluates`() {
        // `f { ...k }` means "copy k's fields in" — the same record-merge lowering
        // as `f { ...k, x = 1 }`. Pinned because the typechecker used to forbid a
        // spread-only call record; it now allows it for ordinary (non-member) calls.
        val code = """
            let add = {x, y} -> x + y
            let k = {x = 5, y = 7}
            add { ...k }
        """
        assertEquals(12L, eval(code))
    }

    @Test
    fun `test function with single parameter`() {
        val code = """
            let square = {x} -> x * x
            square {x = 4}
        """
        assertEquals(16L, eval(code))
    }

    @Test
    fun `test function with single parameter - second call`() {
        val code = """
            let square = {x} -> x * x
            square {x = 7}
        """
        assertEquals(49L, eval(code))
    }

    @Test
    fun `test function with record parameter`() {
        val code = """
            let getFullName = {person} -> "$(person.firstName) $(person.lastName)"
            let person = {firstName = "John", lastName = "Smith"}
            getFullName {person = person}
        """
        assertEquals("John Smith", eval(code))
    }

    @Test
    fun `test function returning record`() {
        val code = """
            let createPerson = {name, age} -> {name = name, age = age, active = True}
            createPerson {name = "Alice", age = 30}
        """
        val result = eval(code) as MezzeRecord
        assertEquals("Alice", result.getField("name"))
        assertEquals(30L, result.getField("age"))
        assertEquals(true, result.getField("active"))
    }

    @Test
    fun `test function with simple conditional - max first`() {
        val code = """
            let max = {x, y} -> if x > y then x else y
            max {x = 5, y = 3}
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test function with simple conditional - max second`() {
        val code = """
            let max = {x, y} -> if x > y then x else y
            max {x = 2, y = 8}
        """
        assertEquals(8L, eval(code))
    }

    @Test
    fun `test function composition`() {
        val code = """
            let double = {x} -> x * 2
            let addOne = {x} -> x + 1
            let doubleAndAddOne = {x} -> addOne {x = double {x = x}}
            doubleAndAddOne {x = 5}
        """
        assertEquals(11L, eval(code)) // (5 * 2) + 1 = 11
    }

    @Test
    fun `test function with string operations`() {
        val code = """
            let greet = {name} -> "Hello, $(name)!"
            greet {name = "World"}
        """
        assertEquals("Hello, World!", eval(code))
    }

    @Test
    fun `test function with record manipulation`() {
        val code = """
            let updateAge: {person: {name: Str, age: Int}, newAge: Int} -> {name: Str, age: Int} = {person, newAge} -> {...person, age = newAge}
            let person = {name = "Bob", age = 25}
            updateAge {person = person, newAge = 26}
        """
        val result = eval(code) as MezzeRecord
        assertEquals("Bob", result.getField("name"))
        assertEquals(26L, result.getField("age"))
    }

    @Test
    fun `test function closure`() {
        val code = """
            let x = 10
            let addX = {y} -> x + y
            addX {y = 5}
        """
        assertEquals(15L, eval(code)) // 10 + 5
    }

    @Test
    fun `test higher-order function`() {
        val code = """
            let applyTwice = {f, x} -> f {x = f {x = x}}
            let increment = {x} -> x + 1
            applyTwice {f = increment, x = 5}
        """
        assertEquals(7L, eval(code)) // increment(increment(5)) = 7
    }

    @Test
    fun `test function with nested conditionals - positive`() {
        val code = """
            let classify = {x} -> if x > 0 then "positive" else if x < 0 then "negative" else "zero"
            classify {x = 5}
        """
        assertEquals("positive", eval(code))
    }

    @Test
    fun `test function with nested conditionals - zero`() {
        val code = """
            let classify = {x} -> if x > 0 then "positive" else if x < 0 then "negative" else "zero"
            classify {x = 0}
        """
        assertEquals("zero", eval(code))
    }

    @Test
    fun `test function with arithmetic expressions`() {
        val code = """
            let calculate = {a, b, c} -> (a + b) * c
            calculate {a = 2, b = 3, c = 4}
        """
        assertEquals(20L, eval(code)) // (2 + 3) * 4 = 20
    }

    @Test
    fun `test multiple function definitions - add`() {
        val code = """
            let add = {x, y} -> x + y
            add {x = 10, y = 5}
        """
        assertEquals(15L, eval(code))
    }

    @Test
    fun `test multiple function definitions - multiply`() {
        val code = """
            let multiply = {x, y} -> x * y
            multiply {x = 3, y = 4}
        """
        assertEquals(12L, eval(code))
    }

    @Test
    fun `test multiple function definitions - subtract`() {
        val code = """
            let subtract = {x, y} -> x - y
            subtract {x = 20, y = 8}
        """
        assertEquals(12L, eval(code))
    }
}
