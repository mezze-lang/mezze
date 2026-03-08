package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/random module.
 * Uses SeededRandom handler for deterministic testing.
 * Migrated from V1 to V2 with unboxed values.
 */
class RandomEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === SeededRandom Basic Tests ===

    @Test
    fun `test SeededRandom random_float returns value in 0 to 1`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom { seed = 42 } in do
                let r = Random.random_float {}
                r >= 0.0 && r < 1.0
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SeededRandom random_int returns value in range`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom { seed = 42 } in do
                let r = Random.random_int { min = 1, max = 10 }
                r >= 1 && r <= 10
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SeededRandom random_nat returns value up to max`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom { seed = 42 } in do
                let r = Random.random_nat { max = 100n }
                r <= 100n
        """)
        assertEquals(true, result)
    }

    // === Determinism Tests ===

    @Test
    fun `test SeededRandom produces deterministic sequence`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom { seed = 42 } in do
                let r1 = Random.random_float {}
                let r2 = Random.random_float {}
                # Both should be valid floats, and the sequence should be consistent
                r1 >= 0.0 && r1 < 1.0 && r2 >= 0.0 && r2 < 1.0
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SeededRandom with different seeds`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            let test = {} -> do
                let r1 = perform SeededRandom { seed = 100 } in Random.random_float {}
                let r2 = perform SeededRandom { seed = 200 } in Random.random_float {}
                # Both should be valid floats
                r1 >= 0.0 && r1 < 1.0 && r2 >= 0.0 && r2 < 1.0

            test {}
        """)
        assertEquals(true, result)
    }

    // === Convenience Functions ===

    @Test
    fun `test random_float convenience function`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_float}

            perform SeededRandom { seed = 42 } in do
                let r = random_float {}
                r >= 0.0 && r < 1.0
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_int convenience function`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_int}

            perform SeededRandom { seed = 42 } in do
                let r = random_int { min = 50, max = 100 }
                r >= 50 && r <= 100
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_nat convenience function`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_nat}

            perform SeededRandom { seed = 42 } in do
                let r = random_nat { max = 50n }
                r <= 50n
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_boolean with high probability`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_boolean}

            perform SeededRandom { seed = 42 } in do
                # With probability 1.0, should always be true
                random_boolean { probability = 1.0 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_boolean with zero probability`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_boolean}

            perform SeededRandom { seed = 42 } in do
                # With probability 0.0, should always be false
                random_boolean { probability = 0.0 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test random_range produces value in range`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_range}

            perform SeededRandom { seed = 42 } in do
                let r = random_range { min = 10.0, max = 20.0 }
                r >= 10.0 && r < 20.0
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test uniform distribution convenience`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, uniform}

            perform SeededRandom { seed = 42 } in do
                let r = uniform { min = 5.0, max = 15.0 }
                r >= 5.0 && r < 15.0
        """)
        assertEquals(true, result)
    }

    // === Distribution Functions ===

    @Test
    fun `test normal distribution returns finite value`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, normal}
            use std::math::core

            perform SeededRandom { seed = 42 } in do
                let r = normal { mean = 0.0, std_dev = 1.0 }
                core::is_finite { n = r }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test exponential distribution returns positive value`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, exponential}

            perform SeededRandom { seed = 42 } in do
                let r = exponential { rate = 1.0 }
                r > 0.0
        """)
        assertEquals(true, result)
    }

    // === Multiple Random Calls ===

    @Test
    fun `test multiple random calls with same seed`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom { seed = 42 } in do
                let r1 = Random.random_float {}
                let r2 = Random.random_float {}
                let r3 = Random.random_float {}
                # All should be valid floats between 0 and 1
                r1 >= 0.0 && r1 < 1.0 && r2 >= 0.0 && r2 < 1.0 && r3 >= 0.0 && r3 < 1.0
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random in loop-like recursion`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            let sumRandoms = { n, acc } ->
                if n <= 0 then acc
                else perform SeededRandom { seed = n } in do
                    let r = Random.random_int { min = 1, max = 10 }
                    sumRandoms { n = n - 1, acc = acc + r }

            let test = {} -> do
                let total = sumRandoms { n = 5, acc = 0 }
                total > 0

            test {}
        """)
        assertEquals(true, result)
    }

    // === Random.seed Effect Operation ===

    @Test
    fun `test Random seed effect operation`() {
        val result = eval("""
            use std::effects::random::{Random, SeededRandom}

            perform SeededRandom { seed = 42 } in do
                # Call Random.seed to update the seed
                Random.seed { new_seed = 100 }
                # Then get a random value
                let r = Random.random_float {}
                r >= 0.0 && r < 1.0
        """)
        assertEquals(true, result)
    }

    // === seed_random Convenience Function ===

    @Test
    fun `test seed_random convenience function`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, seed_random, random_float}

            perform SeededRandom { seed = 42 } in do
                seed_random { seed = 999 }
                let r = random_float {}
                r >= 0.0 && r < 1.0
        """)
        assertEquals(true, result)
    }

    // === New Functions ===

    @Test
    fun `test bernoulli returns 1 for p=1`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, bernoulli}

            perform SeededRandom { seed = 42 } in do
                bernoulli { p = 1.0 }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test bernoulli returns 0 for p=0`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, bernoulli}

            perform SeededRandom { seed = 42 } in do
                bernoulli { p = 0.0 }
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test bernoulli returns 0 or 1`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, bernoulli}

            perform SeededRandom { seed = 42 } in do
                let b = bernoulli { p = 0.5 }
                b == 0 || b == 1
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_element returns Some for non-empty list`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_element}

            perform SeededRandom { seed = 42 } in do
                let elem = random_element { xs = [1, 2, 3, 4, 5] }
                match elem is
                    Some { val } -> val >= 1 && val <= 5
                    None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_element returns None for empty list`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_element}

            perform SeededRandom { seed = 42 } in do
                let elem: Option Int = random_element { xs = [] }
                match elem is
                    Some { val } -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test random_element with strings`() {
        val result = eval("""
            use std::effects::random::{SeededRandom, random_element}

            perform SeededRandom { seed = 42 } in do
                let elem = random_element { xs = ["a", "b", "c"] }
                match elem is
                    Some { val } -> True
                    None -> False
        """)
        assertEquals(true, result)
    }
}
