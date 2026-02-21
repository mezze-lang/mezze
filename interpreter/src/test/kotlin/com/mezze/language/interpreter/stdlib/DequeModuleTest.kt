package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for std/collections/deque module.
 */
class DequeModuleTest {

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

    // ===== Construction =====

    @Test
    fun `test deque empty`() {
        val result = eval("""
            use std::collections::deque
            deque::is_empty { d = deque::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test deque singleton`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::singleton { x = 42 }
            deque::length { d = d }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test deque from_list`() {
        val result = eval("""
            use std::collections::deque
            let xs = Cons { head = 1, tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } } }
            let d = deque::from_list { xs = xs }
            deque::length { d = d }
        """)
        assertEquals(MezzeInt(3), result)
    }

    // ===== Add Elements =====

    @Test
    fun `test deque push_front`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::singleton { x = 2 }
            let d2 = deque::push_front { d = d1, x = 1 }
            deque::length { d = d2 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque push_front preserves order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::singleton { x = 2 }
            let d2 = deque::push_front { d = d1, x = 1 }
            match deque::peek_front { d = d2 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test deque push_back`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::singleton { x = 1 }
            let d2 = deque::push_back { d = d1, x = 2 }
            deque::length { d = d2 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque push_back preserves order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::singleton { x = 1 }
            let d2 = deque::push_back { d = d1, x = 2 }
            match deque::peek_back { d = d2 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ===== Remove Elements =====

    @Test
    fun `test deque pop_front - non-empty`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_front { d = deque::push_front { d = deque::empty {}, x = 2 }, x = 1 }
            match deque::pop_front { d = d } is
                Some { val } -> val.head
                None -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test deque pop_front - empty`() {
        val result = eval("""
            use std::collections::deque
            match deque::pop_front { d = deque::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test deque pop_front returns tail`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_front { d = d } is
                Some { val } -> deque::length { d = val.tail }
                None -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test deque pop_back - non-empty`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_back { d = d } is
                Some { val } -> val.last
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque pop_back - empty`() {
        val result = eval("""
            use std::collections::deque
            match deque::pop_back { d = deque::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test deque pop_back returns init`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop_back { d = d } is
                Some { val } -> deque::length { d = val.init }
                None -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    // ===== Peek =====

    @Test
    fun `test deque peek_front - non-empty`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_front { d = deque::empty {}, x = 42 }
            match deque::peek_front { d = d } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test deque peek_front - empty`() {
        val result = eval("""
            use std::collections::deque
            match deque::peek_front { d = deque::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test deque peek_back - non-empty`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::empty {}, x = 42 }
            match deque::peek_back { d = d } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test deque peek_back - empty`() {
        val result = eval("""
            use std::collections::deque
            match deque::peek_back { d = deque::empty {} } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ===== Queries =====

    @Test
    fun `test deque is_empty - true`() {
        val result = eval("""
            use std::collections::deque
            deque::is_empty { d = deque::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test deque is_empty - false`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::singleton { x = 1 }
            deque::is_empty { d = d }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test deque length`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = d1, x = 2 }
            let d3 = deque::push_back { d = d2, x = 3 }
            deque::length { d = d3 }
        """)
        assertEquals(MezzeInt(3), result)
    }

    // ===== Conversions =====

    @Test
    fun `test deque to_list`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let xs = deque::to_list { d = d }
            match xs is
                Cons { head, tail } -> head
                Nil -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test deque to_array`() {
        val result = eval("""
            use std::collections::deque
            use std::collections::array
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let arr = deque::to_array { d = d }
            array::length { xs = arr }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque from_list to_list roundtrip`() {
        val result = eval("""
            use std::collections::deque
            let xs = Cons { head = 1, tail = Cons { head = 2, tail = Cons { head = 3, tail = Nil } } }
            let d = deque::from_list { xs = xs }
            let ys = deque::to_list { d = d }
            match ys is
                Cons { head, tail } -> head
                Nil -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    // ===== Higher-Order Functions =====

    @Test
    fun `test deque map`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let d2 = deque::map { d = d1, f = {x} -> x * 10 }
            match deque::peek_front { d = d2 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `test deque filter`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            let d2 = deque::filter { d = d1, p = {x} -> x > 1 }
            deque::length { d = d2 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque fold`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }, x = 3 }
            deque::fold { d = d, init = 0, f = {acc, x} -> acc + x }
        """)
        assertEquals(MezzeInt(6), result)
    }

    // ===== Transformation =====

    @Test
    fun `test deque reverse`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let d2 = deque::reverse { d = d1 }
            match deque::peek_front { d = d2 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque concat`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = deque::empty {}, x = 2 }
            let d3 = deque::concat { d1 = d1, d2 = d2 }
            deque::length { d = d3 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque concat preserves order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = deque::empty {}, x = 2 }
            let d3 = deque::concat { d1 = d1, d2 = d2 }
            match deque::peek_back { d = d3 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ===== Queue Operations (FIFO) =====

    @Test
    fun `test deque enqueue`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::empty {}
            let d2 = deque::enqueue { d = d1, x = 1 }
            let d3 = deque::enqueue { d = d2, x = 2 }
            deque::length { d = d3 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque dequeue`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::enqueue { d = deque::enqueue { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::dequeue { d = d1 } is
                Some { val } -> val.head
                None -> 0
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test deque FIFO order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::enqueue { d = deque::empty {}, x = 1 }
            let d2 = deque::enqueue { d = d1, x = 2 }
            let d3 = deque::enqueue { d = d2, x = 3 }
            match deque::dequeue { d = d3 } is
                Some { val } -> do
                    let remaining = val.tail
                    match deque::dequeue { d = remaining } is
                        Some { val } -> val.head
                        None -> 0
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ===== Stack Operations (LIFO) =====

    @Test
    fun `test deque push`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::empty {}
            let d2 = deque::push { d = d1, x = 1 }
            let d3 = deque::push { d = d2, x = 2 }
            deque::length { d = d3 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque pop`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push { d = deque::push { d = deque::empty {}, x = 1 }, x = 2 }
            match deque::pop { d = d1 } is
                Some { val } -> val.head
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test deque LIFO order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push { d = deque::empty {}, x = 1 }
            let d2 = deque::push { d = d1, x = 2 }
            let d3 = deque::push { d = d2, x = 3 }
            match deque::pop { d = d3 } is
                Some { val } -> do
                    let remaining = val.tail
                    match deque::pop { d = remaining } is
                        Some { val } -> val.head
                        None -> 0
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    // ===== Equality =====

    @Test
    fun `test deque equals - true`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let d2 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            deque::equals { d1 = d1, d2 = d2 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test deque equals - false different elements`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = deque::empty {}, x = 2 }
            deque::equals { d1 = d1, d2 = d2 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test deque equals - false different lengths`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let d2 = deque::push_back { d = deque::empty {}, x = 1 }
            deque::equals { d1 = d1, d2 = d2 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test deque equals - empty deques`() {
        val result = eval("""
            use std::collections::deque
            deque::equals { d1 = deque::empty {}, d2 = deque::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ===== String Elements =====

    @Test
    fun `test deque with string elements`() {
        val result = eval("""
            use std::collections::deque
            let d = deque::push_back { d = deque::push_back { d = deque::empty {}, x = "hello" }, x = "world" }
            match deque::peek_front { d = d } is
                Some { val } -> val
                None -> "empty"
        """)
        assertEquals(MezzeString("hello"), result)
    }
}
