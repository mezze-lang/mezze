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

    @Test
    fun `test array remove`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            match array::remove { xs = xs, i = 2 } is
                Some { val } -> array::length { xs = val }
                None -> -1
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test array remove - out of bounds`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            match array::remove { xs = xs, i = 10 } is
                Some { val } -> 1
                None -> 0
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `test array of`() {
        val result = eval("""
            use std::collections::array
            let xs = array::of { x = 1, xs = [2, 3] }
            array::length { xs = xs }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test array of - prepends element`() {
        val result = eval("""
            use std::collections::array
            let xs = array::of { x = 1, xs = [2, 3] }
            match array::head { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(1), result)
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

    // Note: array::reduce has a bug in the native implementation - it expects 'init' like fold
    // The reduce function should use the first element as the initial accumulator
    // Skipping these tests until the native implementation is fixed

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

    @Test
    fun `test array sort`() {
        val result = eval("""
            use std::collections::array
            let xs = [3, 1, 4, 1, 5, 9, 2, 6]
            let ys = array::sort { xs = xs }
            match array::head { xs = ys } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test array sort - preserves length`() {
        val result = eval("""
            use std::collections::array
            let xs = [3, 1, 4, 1, 5]
            let ys = array::sort { xs = xs }
            array::length { xs = ys }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test array sort_by`() {
        val result = eval("""
            use std::collections::array
            let xs = [3, 1, 4, 1, 5]
            let ys = array::sort_by { xs = xs, cmp = { a, b } -> b - a }
            match array::head { xs = ys } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(5), result)  // Descending order
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

    // === reduce ===

    @Test
    fun `test array reduce - sum`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            match array::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(15), result)
    }

    @Test
    fun `test array reduce - empty`() {
        val result = eval("""
            use std::collections::array
            let xs: [Int] = []
            match array::reduce { xs = xs, f = { acc, x } -> acc + x } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    // === zip ===

    @Test
    fun `test array zip`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            let ys = ["a", "b", "c"]
            let zipped = array::zip { xs = xs, ys = ys }
            array::length { xs = zipped }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test array zip - unequal lengths`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4]
            let ys = ["a", "b"]
            let zipped = array::zip { xs = xs, ys = ys }
            array::length { xs = zipped }
        """)
        assertEquals(MezzeInt(2), result)
    }

    // === unzip ===

    @Test
    fun `test array unzip`() {
        val result = eval("""
            use std::collections::array
            let pairs = [{ fst = 1, snd = "a" }, { fst = 2, snd = "b" }]
            let result = array::unzip { xs = pairs }
            array::length { xs = result.fsts }
        """)
        assertEquals(MezzeInt(2), result)
    }

    // === flatten ===

    @Test
    fun `test array flatten`() {
        val result = eval("""
            use std::collections::array
            let nested = [[1, 2], [3, 4], [5]]
            let flat = array::flatten { xss = nested }
            array::length { xs = flat }
        """)
        assertEquals(MezzeInt(5), result)
    }

    // === any ===

    @Test
    fun `test array any - true`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            array::any { xs = xs, p = { x } -> x > 3 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test array any - false`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            array::any { xs = xs, p = { x } -> x > 10 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // === all ===

    @Test
    fun `test array all - true`() {
        val result = eval("""
            use std::collections::array
            let xs = [2, 4, 6, 8]
            array::all { xs = xs, p = { x } -> x % 2 == 0 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test array all - false`() {
        val result = eval("""
            use std::collections::array
            let xs = [2, 4, 5, 8]
            array::all { xs = xs, p = { x } -> x % 2 == 0 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // === partition ===

    @Test
    fun `test array partition`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5, 6]
            let result = array::partition { xs = xs, p = { x } -> x % 2 == 0 }
            array::length { xs = result.matching }
        """)
        assertEquals(MezzeInt(3), result)  // evens: 2, 4, 6
    }

    // === find_index ===

    @Test
    fun `test array find_index - found`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            match array::find_index { xs = xs, p = { x } -> x > 3 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(3), result)  // index of 4
    }

    @Test
    fun `test array find_index - not found`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3]
            match array::find_index { xs = xs, p = { x } -> x > 10 } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    // === last ===

    @Test
    fun `test array last - non-empty`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            match array::last { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test array last - empty`() {
        val result = eval("""
            use std::collections::array
            let xs: [Int] = []
            match array::last { xs = xs } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(-1), result)
    }

    // === init ===

    @Test
    fun `test array init - non-empty`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4, 5]
            let result = array::init { xs = xs }
            array::length { xs = result }
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test array init - single element`() {
        val result = eval("""
            use std::collections::array
            let xs = [42]
            let result = array::init { xs = xs }
            array::length { xs = result }
        """)
        assertEquals(MezzeInt(0), result)
    }

    // === unique ===

    @Test
    fun `test array unique`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 2, 3, 3, 3, 4]
            let result = array::unique { xs = xs }
            array::length { xs = result }
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test array unique - already unique`() {
        val result = eval("""
            use std::collections::array
            let xs = [1, 2, 3, 4]
            let result = array::unique { xs = xs }
            array::length { xs = result }
        """)
        assertEquals(MezzeInt(4), result)
    }
}
