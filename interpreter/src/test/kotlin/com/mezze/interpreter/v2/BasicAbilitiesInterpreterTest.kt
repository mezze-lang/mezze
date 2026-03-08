package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class BasicAbilitiesInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testBasicIntegerLiteral() {
        val result = eval("42")
        assertEquals(42L, result)
    }

    @Test
    fun testBasicStringLiteral() {
        val result = eval("\"hello\"")
        assertEquals("hello", result)
    }

    @Test
    fun testBasicLetBinding() {
        val source = """
            let x = 42
            x
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    fun testNativeFunctionCall() {
        val source = """
            use std::int

            int::to_string {n = 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testSimpleArithmetic() {
        val result = eval("5 + 3")
        assertEquals(8L, result)
    }

    @Test
    fun testSimpleFunction() {
        val source = """
            let double = {x: Int} -> x + x
            double {x = 5}
        """.trimIndent()

        val result = eval(source)
        assertEquals(10L, result)
    }

    @Test
    fun testBasicRecord() {
        val source = """
            let person = {name = "Alice", age = 30}
            person.name
        """.trimIndent()

        val result = eval(source)
        assertEquals("Alice", result)
    }

    @Test
    fun testSimpleDoExpression() {
        val source = """
            do
                let x = 10
                let y = 20
                x + y
        """.trimIndent()

        val result = eval(source)
        assertEquals(30L, result)
    }

    @Test
    fun testSimpleIfExpression() {
        val result = eval("if True then \"yes\" else \"no\"")
        assertEquals("yes", result)
    }

    @Test
    fun testStringInterpolation() {
        val source = """
            let hello = "Hello"
            let world = "World"
            "$(hello) $(world)"
        """.trimIndent()

        val result = eval(source)
        assertEquals("Hello World", result)
    }
}
