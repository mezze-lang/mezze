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

    // ==========================================================================
    // MutSeq Dynamic Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutSeq push and pop`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_with_capacity, push, pop, len}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_with_capacity { capacity = 10 }
                    push { seq = seq, v = 1 }
                    push { seq = seq, v = 2 }
                    push { seq = seq, v = 3 }
                    let popped = pop { seq = seq }
                    let length = len { seq = seq }
                    match popped is
                        Some { val } -> val * 10 + length
                        None -> -1

            main {}
        """.trimIndent()
        // popped=3, length=2, 3*10 + 2 = 32
        assertEquals(32L, eval(code))
    }

    @Test
    fun `test MutSeq pop empty returns None`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_with_capacity, pop}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_with_capacity { capacity = 10 }
                    let popped = pop { seq = seq }
                    match popped is
                        Some { val } -> val
                        None -> -1

            main {}
        """.trimIndent()
        assertEquals(-1L, eval(code))
    }

    @Test
    fun `test MutSeq get and set`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, get, set}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_to_mut { seq = [10, 20, 30] }
                    let _ = set { seq = seq, i = 1, v = 99 }
                    let val = get { seq = seq, i = 1 }
                    match val is
                        Some { val } -> val
                        None -> -1

            main {}
        """.trimIndent()
        assertEquals(99L, eval(code))
    }

    @Test
    fun `test MutSeq get out of bounds returns None`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, get}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_to_mut { seq = [10, 20, 30] }
                    let val = get { seq = seq, i = 100 }
                    match val is
                        Some { val } -> val
                        None -> -1

            main {}
        """.trimIndent()
        assertEquals(-1L, eval(code))
    }

    @Test
    fun `test MutSeq clear`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, clear, len}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_to_mut { seq = [1, 2, 3, 4, 5] }
                    clear { seq = seq }
                    len { seq = seq }

            main {}
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `test MutSeq insert`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, insert, get, len}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_to_mut { seq = [1, 3, 4] }
                    let _ = insert { seq = seq, i = 1, v = 2 }
                    let length = len { seq = seq }
                    let val = get { seq = seq, i = 1 }
                    match val is
                        Some { val } -> val * 10 + length
                        None -> -1

            main {}
        """.trimIndent()
        // val=2, length=4, 2*10 + 4 = 24
        assertEquals(24L, eval(code))
    }

    @Test
    fun `test MutSeq remove`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, remove, len}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_to_mut { seq = [1, 2, 3, 4] }
                    let removed = remove { seq = seq, i = 1 }
                    let length = len { seq = seq }
                    match removed is
                        Some { val } -> val * 10 + length
                        None -> -1

            main {}
        """.trimIndent()
        // removed=2, length=3, 2*10 + 3 = 23
        assertEquals(23L, eval(code))
    }

    @Test
    fun `test MutSeq swap`() {
        val code = """
            use std::effects::mutation::{LocalMut, seq_to_mut, swap, get}

            let main = {} ->
                perform LocalMut in do
                    let seq = seq_to_mut { seq = [1, 2, 3] }
                    let _ = swap { seq = seq, i = 0, j = 2 }
                    let first = get { seq = seq, i = 0 }
                    let last = get { seq = seq, i = 2 }
                    match first is
                        Some { val = f } -> match last is
                            Some { val = l } -> f * 10 + l
                            None -> -1
                        None -> -1

            main {}
        """.trimIndent()
        // first=3, last=1, 3*10 + 1 = 31
        assertEquals(31L, eval(code))
    }

    // ==========================================================================
    // MutInt Extended Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutInt multiply`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, mul_int}

            let main = {} ->
                perform LocalMut in do
                    let n = int_to_mut { n = 5 }
                    mul_int { n = n, factor = 3 }
                    read_int { n = n }

            main {}
        """.trimIndent()
        assertEquals(15L, eval(code))
    }

    @Test
    fun `test MutInt divide`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, div_int}

            let main = {} ->
                perform LocalMut in do
                    let n = int_to_mut { n = 20 }
                    div_int { n = n, divisor = 4 }
                    read_int { n = n }

            main {}
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test MutInt mod`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, mod_int}

            let main = {} ->
                perform LocalMut in do
                    let n = int_to_mut { n = 17 }
                    mod_int { n = n, divisor = 5 }
                    read_int { n = n }

            main {}
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test MutInt compare_and_swap success`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, compare_and_swap}

            let main = {} ->
                perform LocalMut in do
                    let n = int_to_mut { n = 10 }
                    let success = compare_and_swap { n = n, expected = 10, new_val = 20 }
                    let val = read_int { n = n }
                    if success then val else -1

            main {}
        """.trimIndent()
        assertEquals(20L, eval(code))
    }

    @Test
    fun `test MutInt compare_and_swap failure`() {
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, compare_and_swap}

            let main = {} ->
                perform LocalMut in do
                    let n = int_to_mut { n = 10 }
                    let success = compare_and_swap { n = n, expected = 5, new_val = 20 }
                    let val = read_int { n = n }
                    if success then -1 else val

            main {}
        """.trimIndent()
        // CAS failed, value should still be 10
        assertEquals(10L, eval(code))
    }

    // ==========================================================================
    // MutFloat Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutFloat read and write`() {
        val code = """
            use std::effects::mutation::{LocalMut, float_to_mut, read_float, write_float}

            let main = {} ->
                perform LocalMut in do
                    let f = float_to_mut { n = 1.5 }
                    write_float { n = f, v = 3.5 }
                    let result = read_float { n = f }
                    if result > 3.0 then 1 else 0

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutFloat add`() {
        val code = """
            use std::effects::mutation::{LocalMut, float_to_mut, read_float, add_float}

            let main = {} ->
                perform LocalMut in do
                    let f = float_to_mut { n = 1.5 }
                    add_float { n = f, delta = 2.5 }
                    let result = read_float { n = f }
                    if result > 3.9 then 1 else 0

            main {}
        """.trimIndent()
        // 1.5 + 2.5 = 4.0
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutFloat sub`() {
        val code = """
            use std::effects::mutation::{LocalMut, float_to_mut, read_float, sub_float}

            let main = {} ->
                perform LocalMut in do
                    let f = float_to_mut { n = 5.0 }
                    sub_float { n = f, delta = 2.0 }
                    let result = read_float { n = f }
                    if result > 2.9 then 1 else 0

            main {}
        """.trimIndent()
        // 5.0 - 2.0 = 3.0
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutFloat mul`() {
        val code = """
            use std::effects::mutation::{LocalMut, float_to_mut, read_float, mul_float}

            let main = {} ->
                perform LocalMut in do
                    let f = float_to_mut { n = 2.0 }
                    mul_float { n = f, factor = 3.0 }
                    let result = read_float { n = f }
                    if result > 5.9 then 1 else 0

            main {}
        """.trimIndent()
        // 2.0 * 3.0 = 6.0
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutFloat div`() {
        val code = """
            use std::effects::mutation::{LocalMut, float_to_mut, read_float, div_float}

            let main = {} ->
                perform LocalMut in do
                    let f = float_to_mut { n = 10.0 }
                    div_float { n = f, divisor = 4.0 }
                    let result = read_float { n = f }
                    if result > 2.4 then 1 else 0

            main {}
        """.trimIndent()
        // 10.0 / 4.0 = 2.5
        assertEquals(1L, eval(code))
    }

    // ==========================================================================
    // MutMap Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutMap put and get`() {
        val code = """
            use std::effects::mutation::{LocalMut, map_with_capacity, map_put, map_get}

            let main = {} ->
                perform LocalMut in do
                    let m = map_with_capacity { capacity = 10 }
                    map_put { map = m, key = "a", value = 1 }
                    map_put { map = m, key = "b", value = 2 }
                    let val = map_get { map = m, key = "b" }
                    match val is
                        Some { val } -> val
                        None -> -1

            main {}
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test MutMap contains and remove`() {
        val code = """
            use std::effects::mutation::{LocalMut, map_with_capacity, map_put, map_contains, map_remove}

            let main = {} ->
                perform LocalMut in do
                    let m = map_with_capacity { capacity = 10 }
                    map_put { map = m, key = "x", value = 42 }
                    let before = map_contains { map = m, key = "x" }
                    map_remove { map = m, key = "x" }
                    let after = map_contains { map = m, key = "x" }
                    if before then (if after then 0 else 1) else 0

            main {}
        """.trimIndent()
        // before=true, after=false -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutMap size and clear`() {
        val code = """
            use std::effects::mutation::{LocalMut, map_with_capacity, map_put, map_size, map_clear}

            let main = {} ->
                perform LocalMut in do
                    let m = map_with_capacity { capacity = 10 }
                    map_put { map = m, key = "a", value = 1 }
                    map_put { map = m, key = "b", value = 2 }
                    let before = map_size { map = m }
                    map_clear { map = m }
                    let after = map_size { map = m }
                    before * 10 + after

            main {}
        """.trimIndent()
        // before=2, after=0, 2*10 + 0 = 20
        assertEquals(20L, eval(code))
    }

    @Test
    fun `test MutMap keys`() {
        val code = """
            use std::effects::mutation::{LocalMut, map_with_capacity, map_put, map_keys}
            use std::collections::seq

            let main = {} ->
                perform LocalMut in do
                    let m = map_with_capacity { capacity = 10 }
                    map_put { map = m, key = "a", value = 1 }
                    map_put { map = m, key = "b", value = 2 }
                    let ks = map_keys { map = m }
                    seq::length { xs = ks }

            main {}
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test MutMap values`() {
        val code = """
            use std::effects::mutation::{LocalMut, map_with_capacity, map_put, map_values}
            use std::collections::seq

            let main = {} ->
                perform LocalMut in do
                    let m = map_with_capacity { capacity = 10 }
                    map_put { map = m, key = "a", value = 10 }
                    map_put { map = m, key = "b", value = 20 }
                    let vs = map_values { map = m }
                    seq::length { xs = vs }

            main {}
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test MutMap entries`() {
        val code = """
            use std::effects::mutation::{LocalMut, map_with_capacity, map_put, map_entries}
            use std::collections::seq

            let main = {} ->
                perform LocalMut in do
                    let m = map_with_capacity { capacity = 10 }
                    map_put { map = m, key = "x", value = 100 }
                    let es = map_entries { map = m }
                    seq::length { xs = es }

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    // ==========================================================================
    // MutSet Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutSet add and contains`() {
        val code = """
            use std::effects::mutation::{LocalMut, set_with_capacity, set_add, set_contains}

            let main = {} ->
                perform LocalMut in do
                    let s = set_with_capacity { capacity = 10 }
                    set_add { set = s, elem = 42 }
                    let has = set_contains { set = s, elem = 42 }
                    if has then 1 else 0

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutSet remove`() {
        val code = """
            use std::effects::mutation::{LocalMut, set_with_capacity, set_add, set_remove, set_contains}

            let main = {} ->
                perform LocalMut in do
                    let s = set_with_capacity { capacity = 10 }
                    set_add { set = s, elem = 10 }
                    set_remove { set = s, elem = 10 }
                    let has = set_contains { set = s, elem = 10 }
                    if has then 0 else 1

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutSet size and clear`() {
        val code = """
            use std::effects::mutation::{LocalMut, set_with_capacity, set_add, set_size, set_clear}

            let main = {} ->
                perform LocalMut in do
                    let s = set_with_capacity { capacity = 10 }
                    set_add { set = s, elem = 1 }
                    set_add { set = s, elem = 2 }
                    set_add { set = s, elem = 3 }
                    let before = set_size { set = s }
                    set_clear { set = s }
                    let after = set_size { set = s }
                    before * 10 + after

            main {}
        """.trimIndent()
        // before=3, after=0, 3*10 + 0 = 30
        assertEquals(30L, eval(code))
    }

    @Test
    fun `test MutSet elements`() {
        val code = """
            use std::effects::mutation::{LocalMut, set_with_capacity, set_add, set_elements}
            use std::collections::seq

            let main = {} ->
                perform LocalMut in do
                    let s = set_with_capacity { capacity = 10 }
                    set_add { set = s, elem = 10 }
                    set_add { set = s, elem = 20 }
                    set_add { set = s, elem = 30 }
                    let elems = set_elements { set = s }
                    seq::length { xs = elems }

            main {}
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test MutSet toggle`() {
        val code = """
            use std::effects::mutation::{LocalMut, set_with_capacity, set_toggle, set_contains}

            let main = {} ->
                perform LocalMut in do
                    let s = set_with_capacity { capacity = 10 }
                    let added = set_toggle { set = s, elem = 5 }
                    let removed = set_toggle { set = s, elem = 5 }
                    let has = set_contains { set = s, elem = 5 }
                    # added=true (now present), removed=false (now absent), has=false
                    if added then (if removed then 0 else (if has then 0 else 1)) else 0

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    // ==========================================================================
    // MutBytes Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutBytes get and set`() {
        val code = """
            use std::effects::mutation::{LocalMut, bytes_to_mut, bytes_get, bytes_set}
            use std::collections::bytes

            let main = {} ->
                perform LocalMut in do
                    let b = bytes_to_mut { bytes = bytes::from_seq { arr = [1, 2, 3] } }
                    let _ = bytes_set { bytes = b, i = 1, v = 99 }
                    let v = bytes_get { bytes = b, i = 1 }
                    match v is
                        Some { val } -> val
                        None -> -1

            main {}
        """.trimIndent()
        assertEquals(99L, eval(code))
    }

    @Test
    fun `test MutBytes len and fill`() {
        val code = """
            use std::effects::mutation::{LocalMut, bytes_to_mut, bytes_len, bytes_fill, bytes_get}
            use std::collections::bytes

            let main = {} ->
                perform LocalMut in do
                    let b = bytes_to_mut { bytes = bytes::from_seq { arr = [0, 0, 0, 0, 0] } }
                    bytes_fill { bytes = b, v = 42 }
                    let len = bytes_len { bytes = b }
                    let result = bytes_get { bytes = b, i = 2 }
                    match result is
                        Some { val } -> len * 100 + val
                        None -> -1

            main {}
        """.trimIndent()
        // len=5, val=42, 5*100 + 42 = 542
        assertEquals(542L, eval(code))
    }

    @Test
    fun `test MutBytes swap`() {
        val code = """
            use std::effects::mutation::{LocalMut, bytes_to_mut, bytes_swap, bytes_get}
            use std::collections::bytes

            let main = {} ->
                perform LocalMut in do
                    let b = bytes_to_mut { bytes = bytes::from_seq { arr = [1, 2, 3] } }
                    let _ = bytes_swap { bytes = b, i = 0, j = 2 }
                    let first = bytes_get { bytes = b, i = 0 }
                    let last = bytes_get { bytes = b, i = 2 }
                    match first is
                        Some { val = f } -> match last is
                            Some { val = l } -> f * 10 + l
                            None -> -1
                        None -> -1

            main {}
        """.trimIndent()
        // first=3, last=1, 3*10 + 1 = 31
        assertEquals(31L, eval(code))
    }

    @Test
    fun `test MutBytes reverse`() {
        val code = """
            use std::effects::mutation::{LocalMut, bytes_to_mut, bytes_reverse, bytes_get}
            use std::collections::bytes

            let main = {} ->
                perform LocalMut in do
                    let b = bytes_to_mut { bytes = bytes::from_seq { arr = [1, 2, 3, 4, 5] } }
                    bytes_reverse { bytes = b }
                    let first = bytes_get { bytes = b, i = 0 }
                    let last = bytes_get { bytes = b, i = 4 }
                    match first is
                        Some { val = f } -> match last is
                            Some { val = l } -> f * 10 + l
                            None -> -1
                        None -> -1

            main {}
        """.trimIndent()
        // first=5, last=1, 5*10 + 1 = 51
        assertEquals(51L, eval(code))
    }

    @Test
    fun `test MutBytes clear`() {
        val code = """
            use std::effects::mutation::{LocalMut, bytes_to_mut, bytes_clear, bytes_get}
            use std::collections::bytes

            let main = {} ->
                perform LocalMut in do
                    let b = bytes_to_mut { bytes = bytes::from_seq { arr = [10, 20, 30] } }
                    bytes_clear { bytes = b }
                    let result = bytes_get { bytes = b, i = 1 }
                    match result is
                        Some { val } -> val
                        None -> -1

            main {}
        """.trimIndent()
        // After clear, all bytes should be 0
        assertEquals(0L, eval(code))
    }

    // ==========================================================================
    // MutDeque Operations (NEW)
    // ==========================================================================

    @Test
    fun `test MutDeque push_front and push_back`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_push_front, deque_push_back, deque_size}

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    deque_push_front { deque = d, elem = 1 }
                    deque_push_back { deque = d, elem = 2 }
                    deque_push_front { deque = d, elem = 0 }
                    deque_size { deque = d }

            main {}
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    @Test
    fun `test MutDeque pop_front and pop_back`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_push_back, deque_pop_front, deque_pop_back}

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    deque_push_back { deque = d, elem = 1 }
                    deque_push_back { deque = d, elem = 2 }
                    deque_push_back { deque = d, elem = 3 }
                    let front = deque_pop_front { deque = d }
                    let back = deque_pop_back { deque = d }
                    match front is
                        Some { val = f } -> match back is
                            Some { val = b } -> f * 10 + b
                            None -> -1
                        None -> -1

            main {}
        """.trimIndent()
        // front=1, back=3, 1*10 + 3 = 13
        assertEquals(13L, eval(code))
    }

    @Test
    fun `test MutDeque peek_front and peek_back`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_push_back, deque_peek_front, deque_peek_back, deque_size}

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    deque_push_back { deque = d, elem = 10 }
                    deque_push_back { deque = d, elem = 20 }
                    let front = deque_peek_front { deque = d }
                    let back = deque_peek_back { deque = d }
                    let size = deque_size { deque = d }
                    match front is
                        Some { val = f } -> match back is
                            Some { val = b } -> f + b + size
                            None -> -1
                        None -> -1

            main {}
        """.trimIndent()
        // front=10, back=20, size=2 (peek doesn't remove), 10+20+2 = 32
        assertEquals(32L, eval(code))
    }

    @Test
    fun `test MutDeque is_empty`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_is_empty, deque_push_back}

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    let empty1 = deque_is_empty { deque = d }
                    deque_push_back { deque = d, elem = 1 }
                    let empty2 = deque_is_empty { deque = d }
                    if empty1 then (if empty2 then 0 else 1) else 0

            main {}
        """.trimIndent()
        // empty1=true, empty2=false -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutDeque clear`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_push_back, deque_clear, deque_size}

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    deque_push_back { deque = d, elem = 1 }
                    deque_push_back { deque = d, elem = 2 }
                    deque_push_back { deque = d, elem = 3 }
                    let before = deque_size { deque = d }
                    deque_clear { deque = d }
                    let after = deque_size { deque = d }
                    before * 10 + after

            main {}
        """.trimIndent()
        // before=3, after=0, 3*10 + 0 = 30
        assertEquals(30L, eval(code))
    }

    @Test
    fun `test MutDeque reverse`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_push_back, deque_reverse, deque_pop_front, deque_pop_back}

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    deque_push_back { deque = d, elem = 1 }
                    deque_push_back { deque = d, elem = 2 }
                    deque_push_back { deque = d, elem = 3 }
                    deque_reverse { deque = d }
                    let front = deque_pop_front { deque = d }
                    let back = deque_pop_back { deque = d }
                    match front is
                        Some { val = f } -> match back is
                            Some { val = b } -> f * 10 + b
                            None -> -1
                        None -> -1

            main {}
        """.trimIndent()
        // After reverse: [3, 2, 1], front=3, back=1, 3*10 + 1 = 31
        assertEquals(31L, eval(code))
    }

    @Test
    fun `test MutDeque to_seq`() {
        val code = """
            use std::effects::mutation::{LocalMut, deque_with_capacity, deque_push_back, deque_to_seq}
            use std::collections::seq

            let main = {} ->
                perform LocalMut in do
                    let d = deque_with_capacity { capacity = 10 }
                    deque_push_back { deque = d, elem = 10 }
                    deque_push_back { deque = d, elem = 20 }
                    deque_push_back { deque = d, elem = 30 }
                    let s = deque_to_seq { deque = d }
                    seq::length { xs = s }

            main {}
        """.trimIndent()
        assertEquals(3L, eval(code))
    }
}
