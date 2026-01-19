package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.ModuleResolver
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

    @BeforeEach
    fun setup() {
        // CRITICAL: Clear BOTH module caches to ensure clean state
        // TypeChecker has its own cache for typed modules
        // ModuleResolver has its own cache for parsed modules
        TypeChecker.clearCache()
        ModuleResolver.clearCache()

        parser = FunkParser()
        typeChecker = TypeChecker()
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

            if check then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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
            let h_opt = head { xs = ys }

            match h_opt is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("List head and tail")
    fun testHeadTail() {
        val source = """
            use std::collections::list::{empty, cons, head, tail, is_empty}

            let xs = cons { head = 1, tail = cons { head = 2, tail = empty {} } }

            let check_h = { h } -> if h == 1 then true else false
            let check_h2 = { h2 } -> if h2 == 2 then true else false
            let check_empty = { t2 } -> is_empty { xs = t2 }

            match head { xs = xs } is
                Some { val } ->
                    if check_h { h = val } then
                        match tail { xs = xs } is
                            Some { val } ->
                                match head { xs = val } is
                                    Some { val } ->
                                        if check_h2 { h2 = val } then
                                            match tail { xs = xs } is
                                                Some { val } ->
                                                    match tail { xs = val } is
                                                        Some { val } ->
                                                            if check_empty { t2 = val } then 1 else 0
                                                        None -> 0
                                                None -> 0
                                        else 0
                                    None -> 0
                            None -> 0
                    else 0
                None -> 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("List length")
    fun testListLength() {
        val source = """
            use std::collections::list::{empty, cons, length}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }

            length { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            length { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            length { xs = zs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            let check_h1 = { h1 } -> if h1 == 2 then true else false
            let check_h2 = { h2 } -> if h2 == 4 then true else false

            match head { xs = ys } is
                Some { val } ->
                    if check_h1 { h1 = val } then
                        match tail { xs = ys } is
                            Some { val } ->
                                match head { xs = val } is
                                    Some { val } ->
                                        if check_h2 { h2 = val } then 1 else 0
                                    None -> 0
                            None -> 0
                    else 0
                None -> 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            length { xs = ys }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

        assertTrue(result is FunkInt)
        assertEquals(2L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("List fold_left")
    fun testFoldLeft() {
        val source = """
            use std::collections::list::{empty, cons, fold_left}

            let xs = cons { head = 1, tail = cons { head = 2, tail = cons { head = 3, tail = empty {} } } }

            fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            if has_even then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            if all_even then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            match head { xs = ys } is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            length { xs = arr }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            length { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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

            length { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

        assertTrue(result is FunkInt)
        assertEquals(10L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("List repeat")
    fun testRepeat() {
        val source = """
            use std::collections::list::{repeat, length}

            let xs = repeat { x = 42, n = 5 }

            length { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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
            let eq_result = xs == ys

            if eq_result then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

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
            let eq_result = xs == ys

            if eq_result then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk")))

        assertTrue(result is FunkInt)
        assertEquals(0L, (result as FunkInt).value)
    }
}
