package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Runtime tests for the Mutation effect.
 *
 * These tests verify that mutation operations execute correctly at runtime,
 * including the JIT-optimized nodes (ForRangeNode, WhileMutNode, etc.).
 */
class MutationRuntimeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==========================================================================
    // Basic Seq Mutation
    // ==========================================================================

    @Test
    fun `test seq_to_mut and seq_to_immut round trip`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut}
            use std::collections::seq

            let main = {} -> do
                let original = [1, 2, 3, 4, 5]
                let result = perform LocalMut in do
                    let m = seq_to_mut { seq = original }
                    seq_to_immut { seq = m }
                seq::length { xs = result }

            main {}
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test seq_to_mut preserves elements`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut}
            use std::collections::seq

            let main = {} -> do
                let original = [10, 20, 30]
                let result = perform LocalMut in do
                    let m = seq_to_mut { seq = original }
                    seq_to_immut { seq = m }
                match seq::get { xs = result, i = 1 } is
                    Some { val } -> val
                    None -> -1

            main {}
        """.trimIndent()
        assertEquals(20L, eval(code))
    }

    // ==========================================================================
    // MutSeq Sort
    // ==========================================================================

    @Test
    fun `test in-place sort`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut, sort}
            use std::collections::seq

            let main = {} -> do
                let unsorted = [5, 2, 8, 1, 9, 3]
                let sorted = perform LocalMut in do
                    let m = seq_to_mut { seq = unsorted }
                    sort { seq = m }
                    seq_to_immut { seq = m }

                # Check first and last element
                let first = match seq::get { xs = sorted, i = 0 } is
                    Some { val } -> val
                    None -> -1
                let last = match seq::get { xs = sorted, i = 5 } is
                    Some { val } -> val
                    None -> -1
                first * 10 + last

            main {}
        """.trimIndent()
        // first=1, last=9, 1*10 + 9 = 19
        assertEquals(19L, eval(code))
    }

    @Test
    fun `test in-place sort empty seq`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut, sort}
            use std::collections::seq

            let main = {} -> do
                let empty: [Int] = []
                let sorted = perform LocalMut in do
                    let m = seq_to_mut { seq = empty }
                    sort { seq = m }
                    seq_to_immut { seq = m }
                seq::length { xs = sorted }

            main {}
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `test in-place sort single element`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut, sort}
            use std::collections::seq

            let main = {} -> do
                let single = [42]
                let sorted = perform LocalMut in do
                    let m = seq_to_mut { seq = single }
                    sort { seq = m }
                    seq_to_immut { seq = m }
                match seq::get { xs = sorted, i = 0 } is
                    Some { val } -> val
                    None -> -1

            main {}
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    // ==========================================================================
    // MutSeq Reverse
    // ==========================================================================

    @Test
    fun `test in-place reverse`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut, reverse}
            use std::collections::seq

            let main = {} -> do
                let original = [1, 2, 3, 4, 5]
                let reversed = perform LocalMut in do
                    let m = seq_to_mut { seq = original }
                    reverse { seq = m }
                    seq_to_immut { seq = m }

                # Check first and last
                let first = match seq::get { xs = reversed, i = 0 } is
                    Some { val } -> val
                    None -> -1
                let last = match seq::get { xs = reversed, i = 4 } is
                    Some { val } -> val
                    None -> -1
                first * 10 + last

            main {}
        """.trimIndent()
        // first=5, last=1, 5*10 + 1 = 51
        assertEquals(51L, eval(code))
    }

    // ==========================================================================
    // MutInt Operations
    // ==========================================================================

    @Test
    fun `test int_to_mut and read`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int}

            let main = {} ->
                perform LocalMut in do
                    let m = int_to_mut { n = 42 }
                    read_int { n = m }

            main {}
        """.trimIndent()
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test int_to_mut write and read`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, write_int}

            let main = {} ->
                perform LocalMut in do
                    let m = int_to_mut { n = 0 }
                    write_int { n = m, v = 100 }
                    read_int { n = m }

            main {}
        """.trimIndent()
        assertEquals(100L, eval(code))
    }

    @Test
    fun `test int_to_mut incr`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr}

            let main = {} ->
                perform LocalMut in do
                    let m = int_to_mut { n = 10 }
                    incr { n = m, delta = 5 }
                    incr { n = m, delta = 3 }
                    read_int { n = m }

            main {}
        """.trimIndent()
        assertEquals(18L, eval(code))
    }

    @Test
    fun `test int_to_mut decr`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, decr}

            let main = {} ->
                perform LocalMut in do
                    let m = int_to_mut { n = 100 }
                    decr { n = m, delta = 30 }
                    decr { n = m, delta = 20 }
                    read_int { n = m }

            main {}
        """.trimIndent()
        assertEquals(50L, eval(code))
    }

    @Test
    fun `test int_to_immut`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, int_to_immut, incr}

            let main = {} ->
                perform LocalMut in do
                    let m = int_to_mut { n = 10 }
                    incr { n = m, delta = 5 }
                    int_to_immut { n = m }

            main {}
        """.trimIndent()
        assertEquals(15L, eval(code))
    }

    // ==========================================================================
    // for_range Loop
    // ==========================================================================

    @Test
    fun `test for_range basic loop`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr, for_range}

            let main = {} ->
                perform LocalMut in do
                    let sum = int_to_mut { n = 0 }
                    for_range { start = 1, end = 6, body = {i} ->
                        incr { n = sum, delta = i }
                    }
                    read_int { n = sum }

            main {}
        """.trimIndent()
        // 1 + 2 + 3 + 4 + 5 = 15
        assertEquals(15L, eval(code))
    }

    @Test
    fun `test for_range empty range`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr, for_range}

            let main = {} ->
                perform LocalMut in do
                    let sum = int_to_mut { n = 42 }
                    for_range { start = 5, end = 5, body = {i} ->
                        incr { n = sum, delta = i }
                    }
                    read_int { n = sum }

            main {}
        """.trimIndent()
        // Loop should not execute
        assertEquals(42L, eval(code))
    }

    @Test
    fun `test for_range large iteration`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr, for_range}

            let main = {} ->
                perform LocalMut in do
                    let sum = int_to_mut { n = 0 }
                    for_range { start = 1, end = 101, body = {i} ->
                        incr { n = sum, delta = i }
                    }
                    read_int { n = sum }

            main {}
        """.trimIndent()
        // Sum of 1 to 100 = 100 * 101 / 2 = 5050
        assertEquals(5050L, eval(code))
    }

    // ==========================================================================
    // Combined Operations
    // ==========================================================================

    @Test
    fun `test sort descending list`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, seq_to_immut, sort}
            use std::collections::seq

            let main = {} -> do
                let original = [5, 4, 3, 2, 1]
                let sorted = perform LocalMut in do
                    let m = seq_to_mut { seq = original }
                    sort { seq = m }
                    seq_to_immut { seq = m }

                # Check first and last elements
                let first = match seq::get { xs = sorted, i = 0 } is
                    Some { val } -> val
                    None -> -1
                let last = match seq::get { xs = sorted, i = 4 } is
                    Some { val } -> val
                    None -> -1

                first * 10 + last

            main {}
        """.trimIndent()
        assertEquals(15L, eval(code))  // first=1, last=5, 1*10 + 5 = 15
    }

    @Test
    fun `test accumulate sum with for_range`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr, for_range}

            let main = {} -> do
                # Calculate 1^2 + 2^2 + 3^2 + 4^2 + 5^2 = 55
                let sum = perform LocalMut in do
                    let acc = int_to_mut { n = 0 }
                    for_range { start = 1, end = 6, body = {i} ->
                        incr { n = acc, delta = i * i }
                    }
                    read_int { n = acc }
                sum

            main {}
        """.trimIndent()
        assertEquals(55L, eval(code))
    }
}
