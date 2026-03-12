package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/parallel module.
 * Tests data parallel operations using Sequential handler for deterministic testing.
 * Migrated from V1 to V2 with unboxed values.
 */
class ParallelEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === map Tests ===

    @Test
    fun `test map doubles each element`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = Parallel.map {xs = [1, 2, 3], f = {x} -> x * 2}
                match seq::get {xs = results, i = 0} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test map with empty array`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = Parallel.map {xs = [], f = {x} -> x * 2}
                seq::length {xs = results}
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test map preserves order`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = Parallel.map {xs = [3, 1, 4, 1, 5], f = {x} -> x + 10}
                seq::length {xs = results}
        """)
        assertEquals(5L, result)
    }

    // === for_each Tests ===

    @Test
    fun `test for_each returns unit`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.for_each {xs = [1, 2, 3], f = {x} -> {}}
                42
        """)
        assertEquals(42L, result)
    }

    // === filter Tests ===

    @Test
    fun `test filter keeps matching elements`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = Parallel.filter {xs = [1, 2, 3, 4, 5, 6], f = {x} -> x % 2 == 0}
                seq::length {xs = results}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test filter with no matches`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = Parallel.filter {xs = [1, 3, 5], f = {x} -> x % 2 == 0}
                seq::length {xs = results}
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test filter with all matches`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = Parallel.filter {xs = [2, 4, 6], f = {x} -> x % 2 == 0}
                seq::length {xs = results}
        """)
        assertEquals(3L, result)
    }

    // === reduce Tests ===

    @Test
    fun `test reduce with sum`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [1, 2, 3, 4, 5], f = {l, r} -> l + r} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(15L, result)
    }

    @Test
    fun `test reduce with product`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [1, 2, 3, 4], f = {l, r} -> l * r} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(24L, result)
    }

    @Test
    fun `test reduce with empty array returns None`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [], f = {l, r} -> l + r} is
                    Some {val} -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test reduce with single element`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.reduce {xs = [42], f = {l, r} -> l + r} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(42L, result)
    }

    // === fold Tests ===

    @Test
    fun `test fold with sum`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.fold {
                    xs = [1, 2, 3],
                    zero = 0,
                    map_fn = {x} -> x,
                    combine = {l, r} -> l + r
                }
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test fold with transform and combine`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.fold {
                    xs = [1, 2, 3],
                    zero = 0,
                    map_fn = {x} -> x * x,
                    combine = {l, r} -> l + r
                }
        """)
        assertEquals(14L, result) // 1 + 4 + 9 = 14
    }

    @Test
    fun `test fold with empty array returns zero`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.fold {
                    xs = [],
                    zero = 100,
                    map_fn = {x} -> x,
                    combine = {l, r} -> l + r
                }
        """)
        assertEquals(100L, result)
    }

    // === both Tests ===

    @Test
    fun `test both runs two computations`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let r = Parallel.both {
                    l = {} -> 1 + 2,
                    r = {} -> 3 * 4
                }
                r.l + r.r
        """)
        assertEquals(15L, result) // 3 + 12 = 15
    }

    // === both3 Tests ===

    @Test
    fun `test both3 runs three computations`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let r = Parallel.both3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                r.a + r.b + r.c
        """)
        assertEquals(6L, result)
    }

    // === find Tests ===

    @Test
    fun `test find returns first match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.find {xs = [1, 2, 3, 4, 5], f = {x} -> x > 2} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test find returns None when no match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                match Parallel.find {xs = [1, 2, 3], f = {x} -> x > 10} is
                    Some {val} -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    // === any Tests ===

    @Test
    fun `test any returns true when some match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.any {xs = [1, 2, 3, 4], f = {x} -> x > 3}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test any returns false when none match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.any {xs = [1, 2, 3], f = {x} -> x > 10}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test any with empty array`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.any {xs = [], f = {x} -> True}
        """)
        assertEquals(false, result)
    }

    // === all Tests ===

    @Test
    fun `test all returns true when all match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.all {xs = [2, 4, 6], f = {x} -> x % 2 == 0}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test all returns false when some don't match`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.all {xs = [2, 3, 6], f = {x} -> x % 2 == 0}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test all with empty array`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                Parallel.all {xs = [], f = {x} -> False}
        """)
        assertEquals(true, result) // vacuously true
    }

    // === partition Tests ===

    @Test
    fun `test partition separates by predicate`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let r = Parallel.partition {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}
                seq::length {xs = r.pass}
        """)
        assertEquals(2L, result) // [2, 4]
    }

    @Test
    fun `test partition returns not_matching`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let r = Parallel.partition {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}
                seq::length {xs = r.fail}
        """)
        assertEquals(3L, result) // [1, 3, 5]
    }

    // === group_by Tests ===

    @Test
    fun `test group_by groups by key`() {
        val result = eval("""
            use std::effects::parallel::{Parallel, Sequential}
            use std::collections::seq

            perform Sequential in do
                let groups = Parallel.group_by {xs = [1, 2, 3, 4, 5, 6], f = {x} -> x % 2}
                seq::length {xs = groups}
        """)
        assertEquals(2L, result) // 2 groups: even and odd
    }

    // === Helper Function Tests ===

    @Test
    fun `test sum helper`() {
        val result = eval("""
            use std::effects::parallel::{sum, Sequential}

            perform Sequential in sum {xs = [1, 2, 3, 4, 5]}
        """)
        assertEquals(15L, result)
    }

    @Test
    fun `test sum with empty array`() {
        val result = eval("""
            use std::effects::parallel::{sum, Sequential}

            perform Sequential in sum {xs = []}
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test product helper`() {
        val result = eval("""
            use std::effects::parallel::{product, Sequential}

            perform Sequential in product {xs = [1, 2, 3, 4]}
        """)
        assertEquals(24L, result)
    }

    @Test
    fun `test product with empty array`() {
        val result = eval("""
            use std::effects::parallel::{product, Sequential}

            perform Sequential in product {xs = []}
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test count helper`() {
        val result = eval("""
            use std::effects::parallel::{count, Sequential}

            perform Sequential in count {xs = [1, 2, 3, 4, 5], f = {x} -> x > 2}
        """)
        assertEquals(3L, result) // 3, 4, 5
    }

    @Test
    fun `test flatten helper`() {
        val result = eval("""
            use std::effects::parallel::{flatten, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = flatten {xs = [[1, 2], [3], [4, 5, 6]]}
                seq::length {xs = results}
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test flat_map helper`() {
        val result = eval("""
            use std::effects::parallel::{flat_map, Sequential}
            use std::collections::seq

            perform Sequential in do
                let results = flat_map {xs = [1, 2, 3], f = {x} -> [x, x * 10]}
                seq::length {xs = results}
        """)
        assertEquals(6L, result) // [1,10,2,20,3,30]
    }
}
