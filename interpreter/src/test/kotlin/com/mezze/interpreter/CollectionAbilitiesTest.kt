package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for collection equality (Eq ability) implementations.
 */
class CollectionAbilitiesTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Array Equality Tests
    // ========================================

    @Test
    fun `array equals - same elements`() {
        val result = eval("""
            use std::collections::seq
            let a1 = seq::of { x = 1, xs = seq::of { x = 2, xs = seq::of { x = 3, xs = [] } } }
            let a2 = seq::of { x = 1, xs = seq::of { x = 2, xs = seq::of { x = 3, xs = [] } } }
            seq::equals { l = a1, r = a2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `array equals - different elements`() {
        val result = eval("""
            use std::collections::seq
            let a1 = seq::of { x = 1, xs = seq::of { x = 2, xs = seq::of { x = 3, xs = [] } } }
            let a2 = seq::of { x = 1, xs = seq::of { x = 2, xs = seq::of { x = 4, xs = [] } } }
            seq::equals { l = a1, r = a2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `array equals - different lengths`() {
        val result = eval("""
            use std::collections::seq
            let a1 = seq::of { x = 1, xs = seq::of { x = 2, xs = [] } }
            let a2 = seq::of { x = 1, xs = seq::of { x = 2, xs = seq::of { x = 3, xs = [] } } }
            seq::equals { l = a1, r = a2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `array equals - empty arrays`() {
        val result = eval("""
            use std::collections::seq
            let a1: [Int] = []
            let a2: [Int] = []
            seq::equals { l = a1, r = a2 }
        """)
        assertEquals(true, result)
    }

    // ========================================
    // Set Equality Tests
    // ========================================

    @Test
    fun `set equals - same elements`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 2 }, x = 1 }
            set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `set equals - different elements`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = set::empty {}, x = 2 }
            set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `set equals - different sizes`() {
        val result = eval("""
            use std::collections::set
            let s1 = set::insert { s = set::empty {}, x = 1 }
            let s2 = set::insert { s = set::insert { s = set::empty {}, x = 1 }, x = 2 }
            set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `set equals - empty sets`() {
        val result = eval("""
            use std::collections::set
            set::equals { s1 = set::empty {}, s2 = set::empty {} }
        """)
        assertEquals(true, result)
    }

    // ========================================
    // Map Equality Tests
    // ========================================

    @Test
    fun `map equals - same entries`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 1 }
            map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `map equals - different values`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "a", v = 2 }
            map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `map equals - different keys`() {
        val result = eval("""
            use std::collections::map
            let m1 = map::insert { m = map::empty {}, k = "a", v = 1 }
            let m2 = map::insert { m = map::empty {}, k = "b", v = 1 }
            map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `map equals - empty maps`() {
        val result = eval("""
            use std::collections::map
            map::equals { m1 = map::empty {}, m2 = map::empty {} }
        """)
        assertEquals(true, result)
    }

    // ========================================
    // SortedSet Equality Tests
    // ========================================

    @Test
    fun `sorted set equals - same elements`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 1 }, x = 2 }
            let s2 = sorted_set::add { s = sorted_set::add { s = sorted_set::empty {}, x = 2 }, x = 1 }
            sorted_set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `sorted set equals - different elements`() {
        val result = eval("""
            use std::collections::sorted_set
            let s1 = sorted_set::add { s = sorted_set::empty {}, x = 1 }
            let s2 = sorted_set::add { s = sorted_set::empty {}, x = 2 }
            sorted_set::equals { s1 = s1, s2 = s2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `sorted set equals - empty sets`() {
        val result = eval("""
            use std::collections::sorted_set
            sorted_set::equals { s1 = sorted_set::empty {}, s2 = sorted_set::empty {} }
        """)
        assertEquals(true, result)
    }

    // ========================================
    // SortedMap Equality Tests
    // ========================================

    @Test
    fun `sorted map equals - same entries`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "a" }
            let m2 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "a" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `sorted map equals - different values`() {
        val result = eval("""
            use std::collections::sorted_map
            let m1 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "a" }
            let m2 = sorted_map::insert { m = sorted_map::empty {}, k = 1, v = "b" }
            sorted_map::equals { m1 = m1, m2 = m2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `sorted map equals - empty maps`() {
        val result = eval("""
            use std::collections::sorted_map
            sorted_map::equals { m1 = sorted_map::empty {}, m2 = sorted_map::empty {} }
        """)
        assertEquals(true, result)
    }

    // ========================================
    // Deque Equality Tests
    // ========================================

    @Test
    fun `deque equals - same elements same order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let d2 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            deque::equals { d1 = d1, d2 = d2 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `deque equals - same elements different order`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 1 }, x = 2 }
            let d2 = deque::push_back { d = deque::push_back { d = deque::empty {}, x = 2 }, x = 1 }
            deque::equals { d1 = d1, d2 = d2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `deque equals - different elements`() {
        val result = eval("""
            use std::collections::deque
            let d1 = deque::push_back { d = deque::empty {}, x = 1 }
            let d2 = deque::push_back { d = deque::empty {}, x = 2 }
            deque::equals { d1 = d1, d2 = d2 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `deque equals - empty deques`() {
        val result = eval("""
            use std::collections::deque
            deque::equals { d1 = deque::empty {}, d2 = deque::empty {} }
        """)
        assertEquals(true, result)
    }

    // ========================================
    // Frac Compare Tests
    // ========================================

    @Test
    fun `frac compare - less than`() {
        val result = eval("""
            use std::frac
            let a = frac::make { numerator = 1, denominator = 3 }
            let b = frac::make { numerator = 1, denominator = 2 }
            match frac::compare { l = a, r = b } is
                LT -> 1
                EQ -> 0
                GT -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `frac compare - equal`() {
        val result = eval("""
            use std::frac
            let a = frac::make { numerator = 2, denominator = 4 }
            let b = frac::make { numerator = 1, denominator = 2 }
            match frac::compare { l = a, r = b } is
                LT -> 1
                EQ -> 0
                GT -> -1
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `frac compare - greater than`() {
        val result = eval("""
            use std::frac
            let a = frac::make { numerator = 3, denominator = 4 }
            let b = frac::make { numerator = 1, denominator = 2 }
            match frac::compare { l = a, r = b } is
                LT -> 1
                EQ -> 0
                GT -> -1
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `frac compare - negative fractions`() {
        val result = eval("""
            use std::frac
            let a = frac::make { numerator = -1, denominator = 2 }
            let b = frac::make { numerator = 1, denominator = 2 }
            match frac::compare { l = a, r = b } is
                LT -> 1
                EQ -> 0
                GT -> -1
        """)
        assertEquals(1L, result)
    }
}
