package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Comprehensive tests for List operations
 * Tests the immutable linked list backed by native implementations
 */
class ListTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    // ============================================================================
    // Basic List Operations
    // ============================================================================

    @Test
    @DisplayName("Empty list creation")
    fun testEmptyList() {
        val source = """
            use std::collections::list::{empty, is_empty}

            let xs = empty {}
            let check = is_empty { xs = xs }

            let main = {} -> if check then 1 else 0
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
    @DisplayName("List cons operation")
    fun testListCons() {
        val source = """
            use std::collections::list::{empty, cons, head}

            let xs = empty {}
            let ys = cons { head = 42, tail = xs }
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
        assertEquals(42L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("List head and tail")
    fun testHeadTail() {
        val source = """
            use std::collections::list::{empty, cons, head, tail, is_empty}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let h = head { xs = xs }
            let t = tail { xs = xs }
            let h2 = head { xs = t }
            let t2 = tail { xs = t }
            let empty_check = is_empty { xs = t2 }

            let main = {} -> if h == 1 && h2 == 2 && empty_check then 1 else 0
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
    @DisplayName("List length")
    fun testListLength() {
        val source = """
            use std::collections::list::{empty, cons, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
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
        assertEquals(3L, (result as FunkInt).value)
    }

    // ============================================================================
    // List Construction
    // ============================================================================

    @Test
    @DisplayName("Singleton list")
    fun testSingleton() {
        val source = """
            use std::collections::list::{singleton, length}

            let xs = singleton { x = 42 }
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
    @DisplayName("List append")
    fun testAppend() {
        val source = """
            use std::collections::list::{empty, cons, append, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let ys = cons { head = 3, tail = cons { head = 4, tail = empty {} } }
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
        assertEquals(4L, (result as FunkInt).value)
    }

    // ============================================================================
    // Higher-Order Functions
    // ============================================================================

    @Test
    @DisplayName("List map")
    fun testMap() {
        val source = """
            use std::collections::list::{empty, cons, map, head, tail}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let ys = map { xs = xs, f = { x } -> x * 2 }
            let h1 = head { xs = ys }
            let t1 = tail { xs = ys }
            let h2 = head { xs = t1 }

            let main = {} -> if h1 == 2 && h2 == 4 then 1 else 0
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
    @DisplayName("List filter")
    fun testFilter() {
        val source = """
            use std::collections::list::{empty, cons, filter, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = cons { head = 4, tail = empty {} } } } }
            let ys = filter { xs = xs, p = { x } -> x > 2 }
            let len = length { xs = ys }

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
    @DisplayName("List fold_left")
    fun testFoldLeft() {
        val source = """
            use std::collections::list::{empty, cons, fold_left}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let sum = fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }

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

    // ============================================================================
    // List Predicates
    // ============================================================================

    @Test
    @DisplayName("List any")
    fun testAny() {
        val source = """
            use std::collections::list::{empty, cons, any}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let has_even = any { xs = xs, p = { x } -> x % 2 == 0 }

            let main = {} -> if has_even then 1 else 0
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
    @DisplayName("List all")
    fun testAll() {
        val source = """
            use std::collections::list::{empty, cons, all}

            let xs = cons { head = 2, tail = cons { head = 4, tail = cons { head = 6, tail = empty {} } } }
            let all_even = all { xs = xs, p = { x } -> x % 2 == 0 }

            let main = {} -> if all_even then 1 else 0
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

    // ============================================================================
    // List Transformations
    // ============================================================================

    @Test
    @DisplayName("List reverse")
    fun testReverse() {
        val source = """
            use std::collections::list::{empty, cons, reverse, head}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
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

    // ============================================================================
    // Conversion Functions
    // ============================================================================

    @Test
    @DisplayName("List to array conversion")
    fun testToArray() {
        val source = """
            use std::collections::list::{empty, cons, to_array}
            use std::collections::array::{length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }
            let arr = to_array { xs = xs }
            let len = length { xs = arr }

            let main = {} -> len
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
    @DisplayName("Array to list conversion")
    fun testFromArray() {
        val source = """
            use std::collections::array::{of}
            use std::collections::list::{from_array, length}

            let arr = of { x = 1, xs = of { x = 2, xs = of { x = 3, xs = [] } } }
            let xs = from_array { xs = arr }
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
        assertEquals(3L, (result as FunkInt).value)
    }

    // ============================================================================
    // List Utilities
    // ============================================================================

    @Test
    @DisplayName("List range")
    fun testRange() {
        val source = """
            use std::collections::list::{range, length}

            let xs = range { start = 0, end = 10 }
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
        assertEquals(10L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("List repeat")
    fun testRepeat() {
        val source = """
            use std::collections::list::{repeat, length}

            let xs = repeat { x = 42, n = 5 }
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
        assertEquals(5L, (result as FunkInt).value)
    }

    // ============================================================================
    // List Equality (Automatic Derivation)
    // ============================================================================

    @Test
    @DisplayName("List equality - equal lists")
    fun testListEqualityTrue() {
        val source = """
            use std::collections::list::{empty, cons}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let ys = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
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
    @DisplayName("List equality - different lists")
    fun testListEqualityFalse() {
        val source = """
            use std::collections::list::{empty, cons}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }
            let ys = cons { head = 1, tail = cons { head = 3, tail = empty {} } }
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
