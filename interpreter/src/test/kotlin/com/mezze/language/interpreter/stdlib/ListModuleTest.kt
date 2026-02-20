package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/list module.
 *
 * Note: These tests complement ListInterpreterTest.kt which tests
 * pattern matching and variant behavior. Here we focus on the
 * module function signatures and behavior.
 */
class ListModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // === Basic Operations ===

    @Test
    fun `test list empty and is_empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::empty {}
            list::is_empty { xs = xs }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test list cons creates non-empty list`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons { head = 42, tail = list::empty {} }
            list::is_empty { xs = xs }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test list length`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 6 }
            list::length { xs = xs }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test list head - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons { head = 42, tail = list::empty {} }
            match list::head { xs = xs } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test list head - empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::empty {}
            match list::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `test list tail - non-empty`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            match list::tail { xs = xs } is
                Some { val } -> list::length { xs = val }
                None -> 999
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test list uncons`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons { head = 1, tail = list::cons { head = 2, tail = list::empty {} } }
            match list::uncons { xs = xs } is
                Some { val } -> val.head
                None -> -1
        """)
        assertEquals(MezzeInt(1), result)
    }

    // === List Construction ===

    @Test
    fun `test list singleton`() {
        val result = eval("""
            use std::collections::list
            let xs = list::singleton { x = 42 }
            list::length { xs = xs }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test list append`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 3 }
            let ys = list::range { start = 3, end = 5 }
            let zs = list::append { xs = xs, ys = ys }
            list::length { xs = zs }
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test list range`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::length { xs = xs }
        """)
        assertEquals(MezzeInt(4), result)
    }

    // === Higher-Order Functions ===

    @Test
    fun `test list map`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::map { xs = xs, f = { x } -> x * 2 }
            match list::head { xs = ys } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test list filter`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            let evens = list::filter { xs = xs, p = { x } -> x % 2 == 0 }
            list::length { xs = evens }
        """)
        assertEquals(MezzeInt(4), result)  // 2, 4, 6, 8
    }

    @Test
    fun `test list fold_left`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_left { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """)
        assertEquals(MezzeInt(6), result)  // 1 + 2 + 3
    }

    @Test
    fun `test list fold_right`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            list::fold_right { xs = xs, init = 0, f = { x, acc } -> acc * 10 + x }
        """)
        assertEquals(MezzeInt(321), result)
    }

    // === List Predicates ===

    @Test
    fun `test list any - true`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::any { xs = xs, p = { x } -> x > 5 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test list any - false`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::any { xs = xs, p = { x } -> x > 10 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test list all - true`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::all { xs = xs, p = { x } -> x < 10 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test list all - false`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::all { xs = xs, p = { x } -> x < 5 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test list contains - true`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 10 }
            list::contains { xs = xs, x = 5 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test list contains - false`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            list::contains { xs = xs, x = 10 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // === List Transformations ===

    @Test
    fun `test list reverse`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 4 }
            let ys = list::reverse { xs = xs }
            match list::head { xs = ys } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(3), result)
    }

    // === Conversion ===

    @Test
    fun `test list to_array and from_array roundtrip`() {
        val result = eval("""
            use std::collections::list
            let xs = list::range { start = 1, end = 5 }
            let arr = list::to_array { xs = xs }
            let ys = list::from_array { xs = arr }
            list::length { xs = ys }
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test list of_array`() {
        val result = eval("""
            use std::collections::list
            let arr = [1, 2, 3, 4, 5]
            let xs = list::of_array { elements = arr }
            list::length { xs = xs }
        """)
        assertEquals(MezzeInt(5), result)
    }
}
