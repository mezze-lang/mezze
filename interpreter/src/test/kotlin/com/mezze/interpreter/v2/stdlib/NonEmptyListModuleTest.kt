package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/collections/nonemptylist module.
 * Migrated from V1 to V2 with unboxed values.
 */
class NonEmptyListModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ===== Basic Operations =====

    @Test
    fun `test nel singleton`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 42 }
            nonemptylist::head { xs = xs }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test nel singleton length`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 1 }
            nonemptylist::length { xs = xs }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test nel from_head_tail`() {
        val result = eval("""
            use std::collections::nonemptylist
            let tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } }
            let xs = nonemptylist::from_head_tail { head = 1, tail = tail }
            nonemptylist::length { xs = xs }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel head`() {
        val result = eval("""
            use std::collections::nonemptylist
            let tail = Cons { head = 2, tail = Nil }
            let xs = nonemptylist::from_head_tail { head = 1, tail = tail }
            nonemptylist::head { xs = xs }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test nel tail`() {
        val result = eval("""
            use std::collections::nonemptylist
            let tail = Cons { head = 2, tail = Nil }
            let xs = nonemptylist::from_head_tail { head = 1, tail = tail }
            match nonemptylist::tail { xs = xs } is
                Cons { head, tail } -> head
                Nil -> 0
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test nel tail is empty for singleton`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 42 }
            match nonemptylist::tail { xs = xs } is
                Cons { head, tail } -> False
                Nil -> True
        """)
        assertEquals(true, result)
    }

    // ===== Construction =====

    @Test
    fun `test nel cons`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 2 }
            let ys = nonemptylist::cons { x = 1, xs = xs }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test nel cons increases length`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 2 }
            let ys = nonemptylist::cons { x = 1, xs = xs }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test nel append`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 1 }
            let ys = nonemptylist::singleton { x = 2 }
            let zs = nonemptylist::append { xs = xs, ys = ys }
            nonemptylist::length { xs = zs }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test nel append preserves order`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 1 }
            let ys = nonemptylist::singleton { x = 2 }
            let zs = nonemptylist::append { xs = xs, ys = ys }
            nonemptylist::last { xs = zs }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test nel prepend_list`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = Cons { head = 1, tail = Cons { head = 2, tail = Nil } }
            let ys = nonemptylist::prepend_list { xs = xs, y = 3 }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test nel append_list`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 1 }
            let tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } }
            let ys = nonemptylist::append_list { xs = xs, ys = tail }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(3L, result)
    }

    // ===== Access =====

    @Test
    fun `test nel get`() {
        val result = eval("""
            use std::collections::nonemptylist
            let tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } }
            let xs = nonemptylist::from_head_tail { head = 1, tail = tail }
            nonemptylist::get { xs = xs, index = 1 }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test nel get first element`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 42 }
            nonemptylist::get { xs = xs, index = 0 }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test nel last`() {
        val result = eval("""
            use std::collections::nonemptylist
            let tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } }
            let xs = nonemptylist::from_head_tail { head = 1, tail = tail }
            nonemptylist::last { xs = xs }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel last singleton`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 42 }
            nonemptylist::last { xs = xs }
        """)
        assertEquals(42L, result)
    }

    // ===== Higher-Order Functions =====

    @Test
    fun `test nel map`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } }
            let ys = nonemptylist::map { xs = xs, f = {x} -> x * 10 }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test nel map preserves length`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            let ys = nonemptylist::map { xs = xs, f = {x} -> x * 2 }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel flat_map`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } }
            let ys = nonemptylist::flat_map { xs = xs, f = {x} -> nonemptylist::cons { x = x, xs = nonemptylist::singleton { x = x * 10 } } }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(4L, result)
    }

    @Test
    fun `test nel fold_left`() {
        val result = eval("""
            use std::collections::nonemptylist
            let tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } }
            let xs = nonemptylist::from_head_tail { head = 1, tail = tail }
            nonemptylist::fold_left { xs = xs, init = 0, f = {acc, x} -> acc + x }
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test nel fold_right`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::fold_right { xs = xs, init = 0, f = {x, acc} -> x + acc }
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test nel reduce`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::reduce { xs = xs, f = {acc, x} -> acc + x }
        """)
        assertEquals(6L, result)
    }

    // ===== Predicates =====

    @Test
    fun `test nel any - true`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::any { xs = xs, p = {x} -> x > 2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test nel any - false`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::any { xs = xs, p = {x} -> x > 10 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test nel all - true`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::all { xs = xs, p = {x} -> x > 0 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test nel all - false`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::all { xs = xs, p = {x} -> x > 1 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test nel contains - true`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::contains { xs = xs, x = 2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test nel contains - false`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            nonemptylist::contains { xs = xs, x = 99 }
        """)
        assertEquals(false, result)
    }

    // ===== Transformations =====

    @Test
    fun `test nel reverse`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            let ys = nonemptylist::reverse { xs = xs }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel reverse preserves length`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            let ys = nonemptylist::reverse { xs = xs }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel zip`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } }
            let ys = nonemptylist::cons { x = 10, xs = nonemptylist::singleton { x = 20 } }
            let zs = nonemptylist::zip { xs = xs, ys = ys }
            let pair = nonemptylist::head { xs = zs }
            pair.fst + pair.snd
        """)
        assertEquals(11L, result)
    }

    @Test
    fun `test nel intersperse`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            let ys = nonemptylist::intersperse { xs = xs, sep = 0 }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test nel intersperse singleton`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::singleton { x = 42 }
            let ys = nonemptylist::intersperse { xs = xs, sep = 0 }
            nonemptylist::length { xs = ys }
        """)
        assertEquals(1L, result)
    }

    // ===== Conversions =====

    @Test
    fun `test nel to_list`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } }
            let list = nonemptylist::to_list { xs = xs }
            match list is
                Cons { head, tail } -> head
                Nil -> 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test nel from_list`() {
        val result = eval("""
            use std::collections::nonemptylist
            let list = Cons { head = 1, tail = Cons { head = 2, tail = Nil } }
            let xs = nonemptylist::from_list { xs = list }
            nonemptylist::length { xs = xs }
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test nel to_seq`() {
        val result = eval("""
            use std::collections::nonemptylist
            use std::collections::seq
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            let arr = nonemptylist::to_seq { xs = xs }
            seq::length { xs = arr }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel from_seq`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::from_seq { xs = [1, 2, 3] }
            nonemptylist::length { xs = xs }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel roundtrip to_list from_list`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::singleton { x = 3 } } }
            let list = nonemptylist::to_list { xs = xs }
            let ys = nonemptylist::from_list { xs = list }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(1L, result)
    }

    // ===== Sorting =====

    @Test
    fun `test nel sort`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 3, xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } } }
            let ys = nonemptylist::sort { xs = xs }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test nel sort preserves elements`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 3, xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } } }
            let ys = nonemptylist::sort { xs = xs }
            nonemptylist::last { xs = ys }
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test nel sort_by`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = 3, xs = nonemptylist::cons { x = 1, xs = nonemptylist::singleton { x = 2 } } }
            let ys = nonemptylist::sort_by { xs = xs, cmp = {l, r} -> r - l }
            nonemptylist::head { xs = ys }
        """)
        assertEquals(3L, result)
    }

    // ===== String Elements =====

    @Test
    fun `test nel with string elements`() {
        val result = eval("""
            use std::collections::nonemptylist
            let xs = nonemptylist::cons { x = "hello", xs = nonemptylist::singleton { x = "world" } }
            nonemptylist::head { xs = xs }
        """)
        assertEquals("hello", result)
    }
}
