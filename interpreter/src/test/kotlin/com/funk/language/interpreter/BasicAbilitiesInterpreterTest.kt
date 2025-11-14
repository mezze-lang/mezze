package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class BasicAbilitiesInterpreterTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    @Test
    fun testBasicIntegerLiteral() {
        val source = "42"

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }

    @Test
    fun testBasicStringLiteral() {
        val source = "\"hello\""

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkString)
        assertEquals("hello", (result as FunkString).value)
    }

    @Test
    fun testBasicLetBinding() {
        val source = """
            let x = 42
            x
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }

    @Test
    fun testNativeFunctionCall() {
        val source = """
            use std::int

            int::to_string {n = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val result = interpreter.execute(typedProgram, projectRoot)

        assertTrue(result is FunkString)
        assertEquals("42", (result as FunkString).value)
    }

    @Test
    fun testSimpleArithmetic() {
        val source = """
            5 + 3
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(8L, (result as FunkInt).value)
    }

    @Test
    fun testSimpleFunction() {
        val source = """
            let double = {x: Int} -> x + x
            double {x = 5}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(10L, (result as FunkInt).value)
    }

    @Test
    fun testBasicRecord() {
        val source = """
            let person = {name = "Alice", age = 30}
            person.name
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkString)
        assertEquals("Alice", (result as FunkString).value)
    }

    @Test
    fun testSimpleDoExpression() {
        val source = """
            do
                let x = 10
                let y = 20
                x + y
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(30L, (result as FunkInt).value)
    }

    @Test
    fun testSimpleIfExpression() {
        val source = """
            if true then "yes" else "no"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkString)
        assertEquals("yes", (result as FunkString).value)
    }

    @Test
    fun testStringConcatenation() {
        val source = """
            "Hello" ++ " " ++ "World"
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkString)
        assertEquals("Hello World", (result as FunkString).value)
    }
}
