package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/concurrency module.
 * Tests virtual thread concurrency using Loom handler.
 */
class ConcurrencyEffectTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === Basic spawn and await Tests ===

    @Test
    fun `test spawn returns Promise`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> 42}
                Concurrency.await {promise = p}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test spawn with string result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> "hello"}
                Concurrency.await {promise = p}
        """)
        assertTrue(result is MezzeString)
        assertEquals("hello", (result as MezzeString).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(60L, (result as MezzeInt).value)
    }

    // === run Helper Tests ===

    @Test
    fun `test run helper spawns and awaits`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom, run}

            perform Loom in run {computation = {} -> 100}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(100L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(53L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value) // 3 + 12
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
        assertTrue(result is MezzeInt)
        assertEquals(50L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun `test spawn with array result`() {
        val result = eval("""
            use std::effects::concurrency::{Concurrency, Loom}

            perform Loom in do
                let p = Concurrency.spawn {computation = {} -> [1, 2, 3, 4, 5]}
                let arr = Concurrency.await {promise = p}
                __native_arraylist_length {xs = arr}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(25L, (result as MezzeInt).value) // 10 * 2 + 5
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
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(84L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeArrayList)
        val list = (result as MezzeArrayList).toList()
        assertEquals(3, list.size)
        assertEquals(1L, (list[0] as MezzeInt).value)
        assertEquals(2L, (list[1] as MezzeInt).value)
        assertEquals(3L, (list[2] as MezzeInt).value)
    }

    @Test
    fun `test all with empty computations`() {
        val result = eval("""
            use std::effects::concurrency::{all, Loom}

            perform Loom in do
                let results = all {computations = []}
                __native_arraylist_length {xs = results}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test all with single computation`() {
        val result = eval("""
            use std::effects::concurrency::{all, Loom}

            perform Loom in do
                let results = all {computations = [
                    {} -> 42
                ]}
                __native_arraylist_length {xs = results} == 1
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }
}
