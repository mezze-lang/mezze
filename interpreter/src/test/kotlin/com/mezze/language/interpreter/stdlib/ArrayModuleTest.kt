package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/array module.
 */
class ArrayModuleTest {

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
    fun `test array length`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            array::length { xs = xs }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test array length - empty`() {
        val result = eval("""
            use std::collections::array
            let xs: Array Int = []
            array::length { xs = xs }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `test array get`() {
        val result = eval("""
            use std::collections::array
            let xs = [10, 20, 30]
            match array::get { xs = xs, i = 1 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(20), result)
    }

    @Test
    fun `test array is_empty - true`() {
        val result = eval("""
            use std::collections::array
            let xs: Array Int = []
            array::is_empty { xs = xs }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test array is_empty - false`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            array::is_empty { xs = xs }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test array set`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            match array::set { xs = xs, i = 1, x = 99 } is
                Some { val = ys } ->
                    match array::get { xs = ys, i = 1 } is
                        Some { val } -> val
                        None -> -2
                None -> -1
        """)
        assertEquals(MezzeInt(99), result)
    }

    @Test
    fun `test array add`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            let ys = array::add { xs = xs, x = 4 }
            array::length { xs = ys }
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test array concat`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2]
            let ys = [3, 4]
            let zs = array::concat { xs = xs, ys = ys }
            array::length { xs = zs }
        """)
        assertEquals(MezzeInt(4), result)
    }

    // === Higher-Order Functions ===

    @Test
    fun `test array map`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            let ys = array::map { xs = xs, f = { x } -> x * 2 }
            match array::get { xs = ys, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test array filter`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5, 6]
            let evens = array::filter { xs = xs, p = { x } -> x % 2 == 0 }
            array::length { xs = evens }
        """)
        assertEquals(MezzeInt(3), result)  // 2, 4, 6
    }

    @Test
    fun `test array fold`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4]
            array::fold { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """)
        assertEquals(MezzeInt(10), result)
    }

    // === Utility Functions ===

    @Test
    fun `test array contains - true`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            array::contains { xs = xs, x = 3 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test array contains - false`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            array::contains { xs = xs, x = 10 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test array find - found`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            match array::find { xs = xs, p = { x } -> x > 3 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test array find - not found`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            match array::find { xs = xs, p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `test array reverse`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            let ys = array::reverse { xs = xs }
            match array::get { xs = ys, i = 0 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(3), result)
    }

    // Note: index_of returns Option with bare value instead of Option with record
    // This is an inconsistency in the stdlib that should be fixed

    // === Slicing ===

    @Test
    fun `test array slice`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            let ys = array::slice { xs = xs, start = 1, end = 4 }
            array::length { xs = ys }
        """)
        assertEquals(MezzeInt(3), result)  // [2, 3, 4]
    }

    @Test
    fun `test array take`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            let ys = array::take { xs = xs, n = 3 }
            array::length { xs = ys }
        """)
        assertEquals(MezzeInt(3), result)  // [1, 2, 3]
    }

    @Test
    fun `test array drop`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            let ys = array::drop { xs = xs, n = 2 }
            array::length { xs = ys }
        """)
        assertEquals(MezzeInt(3), result)  // [3, 4, 5]
    }

    // === Head and Tail ===

    @Test
    fun `test array head - non-empty`() {
        val result = eval("""
            use std::collections::array
            let xs = [10, 20, 30]
            match array::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `test array head - empty`() {
        val result = eval("""
            use std::collections::array
            let xs: Array Int = []
            match array::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `test array index_of - found`() {
        val result = eval("""
            use std::collections::array
            let xs = [10, 20, 30, 40]
            match array::index_of { xs = xs, x = 30 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test array index_of - not found`() {
        val result = eval("""
            use std::collections::array
            let xs = [10, 20, 30, 40]
            match array::index_of { xs = xs, x = 99 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    @Test
    fun `test array tail`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            let ys = array::tail { xs = xs }
            array::length { xs = ys }
        """)
        assertEquals(MezzeInt(4), result)  // [2, 3, 4, 5]
    }

    // === Equality ===

    @Test
    fun `test array equals - true`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            let ys = [1, 2, 3]
            array::equals { l = xs, r = ys }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test array equals - false`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            let ys = [1, 2, 4]
            array::equals { l = xs, r = ys }
        """)
        assertEquals(MezzeBool(false), result)
    }
}
