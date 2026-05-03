package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/concurrency module.
 * Tests virtual thread concurrency using Loom handler.
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class ConcurrencyEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic spawn and await Tests ===

    @Test
    fun `test spawn returns Promise`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> 42}
                Concurrency.await {promise = p}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test spawn with string result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> "hello"}
                Concurrency.await {promise = p}
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `test spawn multiple computations`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p1 = Concurrency.spawn {computation = {} -> 10}
                let p2 = Concurrency.spawn {computation = {} -> 20}
                let p3 = Concurrency.spawn {computation = {} -> 30}
                let r1 = Concurrency.await {promise = p1}
                let r2 = Concurrency.await {promise = p2}
                let r3 = Concurrency.await {promise = p3}
                r1 + r2 + r3
        """)
        assertEquals(60L, result)
    }

    // === run Helper Tests ===

    @Test
    fun `test run helper spawns and awaits`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom, run}

            perform Loom in run {computation = {} -> 100}
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test run with complex computation`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom, run}

            perform Loom in run {computation = {} -> do
                let x = 5
                let y = 10
                x * y + 3
            }
        """)
        assertEquals(53L, result)
    }

    // === Concurrent Operations Tests ===

    @Test
    fun `test concurrent arithmetic`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let add = Concurrency.spawn {computation = {} -> 1 + 2}
                let mul = Concurrency.spawn {computation = {} -> 3 * 4}
                let sum = Concurrency.await {promise = add}
                let prod = Concurrency.await {promise = mul}
                sum + prod
        """)
        assertEquals(15L, result) // 3 + 12
    }

    @Test
    fun `test spawned computation can access outer scope`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            let multiplier = 10
            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> 5 * multiplier}
                Concurrency.await {promise = p}
        """)
        assertEquals(50L, result)
    }

    // === Record and Complex Type Tests ===

    @Test
    fun `test spawn with record result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> {name = "Alice", age = 30}}
                let r = Concurrency.await {promise = p}
                r.age
        """)
        assertEquals(30L, result)
    }

    @Test
    fun `test spawn with array result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}
            use std::collections::seq

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> [1, 2, 3, 4, 5]}
                let arr = Concurrency.await {promise = p}
                seq::size {xs = arr}
        """)
        assertEquals(5L, result)
    }

    // === Sequential Promise Chain Tests ===

    @Test
    fun `test sequential promise chain`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p1 = Concurrency.spawn {computation = {} -> 10}
                let r1 = Concurrency.await {promise = p1}
                let p2 = Concurrency.spawn {computation = {} -> r1 * 2}
                let r2 = Concurrency.await {promise = p2}
                let p3 = Concurrency.spawn {computation = {} -> r2 + 5}
                Concurrency.await {promise = p3}
        """)
        assertEquals(25L, result) // 10 * 2 + 5
    }

    // === Nested Concurrent Operations ===

    // NOTE: Nested spawn with effects inside the computation is NOT supported.
    // The spawn operation expects a PURE computation: {computation: {} -> 'a}
    // If you need effects inside a spawned computation, use a nested perform:
    //
    //   perform Loom in do
    //     let outer = Concurrency.spawn {computation = {} ->
    //       perform Loom in do
    //         let inner = Concurrency.spawn {computation = {} -> 42}
    //         Concurrency.await {promise = inner}
    //     }
    //     Concurrency.await {promise = outer}
    //
    // For now, we test with a nested perform to make it work correctly.
    @Test
    fun `test nested spawn with nested perform`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let outer = Concurrency.spawn {computation = {} ->
                    perform Loom in do
                        let inner = Concurrency.spawn {computation = {} -> 42}
                        Concurrency.await {promise = inner}
                }
                Concurrency.await {promise = outer}
        """)
        assertEquals(42L, result)
    }

    // === Unit Result Tests ===

    @Test
    fun `test spawn with unit result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> {}}
                let _ = Concurrency.await {promise = p}
                42
        """)
        assertEquals(42L, result)
    }

    // === Boolean Result Tests ===

    @Test
    fun `test spawn with boolean result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> 5 > 3}
                Concurrency.await {promise = p}
        """)
        assertEquals(true, result)
    }

    // === Variant Result Tests ===

    @Test
    fun `test spawn with Option result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> Some {val = 42}}
                let opt = Concurrency.await {promise = p}
                match opt is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(42L, result)
    }

    // === Multiple Spawns Same Reference ===

    @Test
    fun `test await same promise multiple times`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> 42}
                let r1 = Concurrency.await {promise = p}
                let r2 = Concurrency.await {promise = p}
                r1 + r2
        """)
        assertEquals(84L, result)
    }

    // === All Helper Tests ===

    @Test
    fun `test all runs multiple computations concurrently`() {
        val result = eval("""
            use std::effects::concurrency::{all, Loom}

            perform Loom in do
                let results = all {computations = [
                    {} -> 1,
                    {} -> 2,
                    {} -> 3
                ]}
                results
        """)
        assertTrue(result is Array<*>)
        @Suppress("UNCHECKED_CAST")
        val array = result as Array<*>
        assertEquals(3, array.size)
        assertEquals(1L, array[0])
        assertEquals(2L, array[1])
        assertEquals(3L, array[2])
    }

    @Test
    fun `test all with empty computations`() {
        val result = eval("""
            use std::effects::concurrency::{all, Loom}
            use std::collections::seq

            perform Loom in do
                let results = all {computations = []}
                seq::size {xs = results}
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test all with single computation`() {
        val result = eval("""
            use std::effects::concurrency::{all, Loom}
            use std::collections::seq

            perform Loom in do
                let results = all {computations = [
                    {} -> 42
                ]}
                seq::size {xs = results} == 1
        """)
        assertEquals(true, result)
    }
}
