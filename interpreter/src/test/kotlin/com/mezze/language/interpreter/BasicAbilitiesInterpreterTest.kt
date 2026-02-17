package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class BasicAbilitiesInterpreterTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
    }

    @Test
    fun testBasicIntegerLiteral() {
        val source = "42"

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun testBasicStringLiteral() {
        val source = "\"hello\""

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeString)
        assertEquals("hello", (result as MezzeString).value)
    }

    @Test
    fun testBasicLetBinding() {
        val source = """
            let x = 42
            x
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun testNativeFunctionCall() {
        val source = """
            use std::int

            int::to_string {n = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", "/Users/shriyans/Projects/mezze"))
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is MezzeString)
        assertEquals("42", (result as MezzeString).value)
    }

    @Test
    fun testSimpleArithmetic() {
        val source = """
            5 + 3
        """.trimIndent()

        val program = parser.parse(source)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", "/Users/shriyans/Projects/mezze"))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is MezzeInt)
        assertEquals(8L, (result as MezzeInt).value)
    }

    @Test
    fun testSimpleFunction() {
        val source = """
            let double = {x: Int} -> x + x
            double {x = 5}
        """.trimIndent()

        val program = parser.parse(source)
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", "/Users/shriyans/Projects/mezze"))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is MezzeInt)
        assertEquals(10L, (result as MezzeInt).value)
    }

    @Test
    fun testBasicRecord() {
        val source = """
            let person = {name = "Alice", age = 30}
            person.name
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeString)
        assertEquals("Alice", (result as MezzeString).value)
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
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", "/Users/shriyans/Projects/mezze"))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)

        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun testSimpleIfExpression() {
        val source = """
            if True then "yes" else "no"
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeString)
        assertEquals("yes", (result as MezzeString).value)
    }

    @Test
    fun testStringInterpolation() {
        val source = """
            let hello = "Hello"
            let world = "World"
            "$(hello) $(world)"
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is MezzeString)
        assertEquals("Hello World", (result as MezzeString).value)
    }
}
