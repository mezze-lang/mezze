package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Comprehensive tests for NonEmptyList operations
 * Tests the non-empty list that is guaranteed to have at least one element
 */
@org.junit.jupiter.api.Disabled("NonEmptyList native implementations (__native_nel_*) don't exist yet in interpreter")
class NonEmptyListTest {

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
    @DisplayName("NonEmptyList singleton")
    fun testSingleton() {
        val source = """
            use std::collections::nonemptylist::{singleton, head}

            let xs = singleton { x = 42 }
            let h = head { xs = xs }

            let main = {} -> h
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList from head and tail")
    fun testFromHeadTail() {
        val source = """
            use std::collections::list::{empty}
            use std::collections::nonemptylist::{from_head_tail, head, length}

            let xs = from_head_tail { head = 1, tail = empty {} }
            let h = head { xs = xs }
            let len = length { xs = xs }

            let main = {} -> if h == 1 && len == 1 then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList cons")
    fun testCons() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, head, length}

            let xs = singleton { x = 2 }
            let ys = cons { x = 1, xs = xs }
            let h = head { xs = ys }
            let len = length { xs = ys }

            let main = {} -> if h == 1 && len == 2 then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList append")
    fun testAppend() {
        val source = """
            use std::collections::nonemptylist::{singleton, append, length}

            let xs = singleton { x = 1 }
            let ys = singleton { x = 2 }
            let zs = append { xs = xs, ys = ys }
            let len = length { xs = zs }

            let main = {} -> len
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(2L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList map")
    fun testMap() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, map, head}

            let xs = cons { x = 1, xs = singleton { x = 2 } }
            let ys = map { xs = xs, f = { x } -> x * 2 }
            let h = head { xs = ys }

            let main = {} -> h
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(2L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList reduce")
    fun testReduce() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, reduce}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }
            let sum = reduce { xs = xs, f = { acc, x } -> acc + x }

            let main = {} -> sum
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(6L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList last")
    fun testLast() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, last}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }
            let l = last { xs = xs }

            let main = {} -> l
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(3L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList reverse")
    fun testReverse() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, reverse, head}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }
            let ys = reverse { xs = xs }
            let h = head { xs = ys }

            let main = {} -> h
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(3L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList to_list conversion")
    fun testToList() {
        val source = """
            use std::collections::nonemptylist::{singleton, to_list}
            use std::collections::list::{length}

            let nel = singleton { x = 42 }
            let xs = to_list { xs = nel }
            let len = length { xs = xs }

            let main = {} -> len
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList equality - equal lists")
    fun testNonEmptyListEqualityTrue() {
        val source = """
            use std::collections::nonemptylist::{singleton}

            let xs = singleton { x = 42 }
            let ys = singleton { x = 42 }
            let result = xs == ys

            let main = {} -> if result then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("NonEmptyList equality - different lists")
    fun testNonEmptyListEqualityFalse() {
        val source = """
            use std::collections::nonemptylist::{singleton}

            let xs = singleton { x = 42 }
            let ys = singleton { x = 99 }
            let result = xs == ys

            let main = {} -> if result then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        interpreter.execute(typedProgram, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))
        val mainFn = interpreter.getLastExecutionContext()["main"] as? SimpleFunkFunction
            ?: throw AssertionError("main function not found")
        val result = interpreter.callFunctionWithRecord(mainFn, FunkRecord(emptyMap()), interpreter.getLastExecutionContext().toMutableMap())

        assertTrue(result is FunkInt)
        assertEquals(0L, (result as FunkInt).value)
    }
}
