package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Comprehensive tests for NonEmptyList operations
 * Tests the non-empty list that is guaranteed to have at least one element
 */
class NonEmptyListTest {

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
    @DisplayName("NonEmptyList singleton")
    fun testSingleton() {
        val source = """
            use std::collections::nonemptylist::{singleton, head}

            let xs = singleton { x = 42 }

            head { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
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

            if h == 1 && len == 1 then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
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

            if h == 1 && len == 2 then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList append")
    fun testAppend() {
        val source = """
            use std::collections::nonemptylist::{singleton, append, length}

            let xs = singleton { x = 1 }
            let ys = singleton { x = 2 }
            let zs = append { xs = xs, ys = ys }

            length { xs = zs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList map")
    fun testMap() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, map, head}

            let xs = cons { x = 1, xs = singleton { x = 2 } }
            let ys = map { xs = xs, f = {x} -> x * 2 }

            head { xs = ys }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList reduce")
    fun testReduce() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, reduce}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }

            reduce { xs = xs, f = { acc, x } -> acc + x }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList last")
    fun testLast() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, last}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }

            last { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList reverse")
    fun testReverse() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, reverse, head}

            let xs = cons { x = 1, xs = cons { x = 2, xs = singleton { x = 3 } } }
            let ys = reverse { xs = xs }

            head { xs = ys }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList to_list conversion")
    fun testToList() {
        val source = """
            use std::collections::nonemptylist::{singleton, to_list}
            use std::collections::list::{length}

            let nel = singleton { x = 42 }
            let xs = to_list { xs = nel }

            length { xs = xs }
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList any predicate")
    fun testAny() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, any}

            let xs = cons { x = 1, xs = cons { x = 5, xs = singleton { x = 3 } } }

            # Check if any element is greater than 4
            if any { xs = xs, p = {x} -> x > 4 } then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    @DisplayName("NonEmptyList all predicate")
    fun testAll() {
        val source = """
            use std::collections::nonemptylist::{singleton, cons, all}

            let xs = cons { x = 2, xs = cons { x = 4, xs = singleton { x = 6 } } }

            # Check if all elements are even
            if all { xs = xs, p = {x} -> x % 2 == 0 } then 1 else 0
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString())))

        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }
}
