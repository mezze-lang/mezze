package com.mezze.interpreter

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
                seq::size { xs = result }

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
                seq::size { xs = sorted }

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
                    for_range { start = 1, end = 6, step = 1, body = {i} ->
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
                    for_range { start = 5, end = 5, step = 1, body = {i} ->
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
                    for_range { start = 1, end = 101, step = 1, body = {i} ->
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
                    for_range { start = 1, end = 6, step = 1, body = {i} ->
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
                    seq::size { xs = ks }

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
                    seq::size { xs = vs }

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
                    seq::size { xs = es }

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
                    seq::size { xs = elems }

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
                    seq::size { xs = s }

            main {}
        """.trimIndent()
        assertEquals(3L, eval(code))
    }

    // ==========================================================================
    // MutBool Operations
    // ==========================================================================

    @Test
    fun `test MutBool read and write`() {
        val code = """
            use std::effects::mutation::{LocalMut, bool_to_mut, read_bool, write_bool}

            let main = {} ->
                perform LocalMut in do
                    let b = bool_to_mut { b = True }
                    let v1 = read_bool { b = b }
                    write_bool { b = b, v = False }
                    let v2 = read_bool { b = b }
                    if v1 then (if v2 then 0 else 1) else 0

            main {}
        """.trimIndent()
        // v1=true, v2=false -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutBool toggle`() {
        val code = """
            use std::effects::mutation::{LocalMut, bool_to_mut, read_bool, toggle_bool}

            let main = {} ->
                perform LocalMut in do
                    let b = bool_to_mut { b = False }
                    toggle_bool { b = b }
                    let v1 = read_bool { b = b }
                    toggle_bool { b = b }
                    let v2 = read_bool { b = b }
                    if v1 then (if v2 then 0 else 1) else 0

            main {}
        """.trimIndent()
        // false->toggle->true, true->toggle->false, v1=true, v2=false -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutBool set_true and set_false`() {
        val code = """
            use std::effects::mutation::{LocalMut, bool_to_mut, read_bool, set_true, set_false}

            let main = {} ->
                perform LocalMut in do
                    let b = bool_to_mut { b = False }
                    set_true { b = b }
                    let v1 = read_bool { b = b }
                    set_false { b = b }
                    let v2 = read_bool { b = b }
                    if v1 then (if v2 then 0 else 1) else 0

            main {}
        """.trimIndent()
        // v1=true, v2=false -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutBool to_immut`() {
        val code = """
            use std::effects::mutation::{LocalMut, bool_to_mut, bool_to_immut, toggle_bool}

            let main = {} ->
                perform LocalMut in do
                    let b = bool_to_mut { b = False }
                    toggle_bool { b = b }
                    let result = bool_to_immut { b = b }
                    if result then 1 else 0

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    // ==========================================================================
    // MutString Operations
    // ==========================================================================

    @Test
    fun `test MutString to_mut and to_immut`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_to_immut}
            use std::str

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "hello" }
                    let result = string_to_immut { s = s }
                    str::size { s = result }

            main {}
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test MutString read and write`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, read_str, write_str}
            use std::str

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "hello" }
                    let v1 = read_str { s = s }
                    write_str { s = s, v = "world" }
                    let v2 = read_str { s = s }
                    str::size { s = v1 } * 10 + str::size { s = v2 }

            main {}
        """.trimIndent()
        // "hello"=5, "world"=5, 5*10 + 5 = 55
        assertEquals(55L, eval(code))
    }

    @Test
    fun `test MutString len and is_empty`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_len, string_is_empty, string_clear}

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "hello" }
                    let len = string_len { s = s }
                    let empty1 = string_is_empty { s = s }
                    string_clear { s = s }
                    let empty2 = string_is_empty { s = s }
                    let result = len
                    if empty1 then 0 else (if empty2 then result else 0)

            main {}
        """.trimIndent()
        // len=5, empty1=false, empty2=true -> 5
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test MutString append and prepend`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_append, string_prepend, read_str}
            use std::str

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "world" }
                    string_prepend { s = s, prefix = "hello " }
                    string_append { s = s, suffix = "!" }
                    let result = read_str { s = s }
                    str::size { s = result }

            main {}
        """.trimIndent()
        // "hello " + "world" + "!" = "hello world!" = 12 characters
        assertEquals(12L, eval(code))
    }

    @Test
    fun `test MutString reverse`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_reverse, read_str}

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "abc" }
                    string_reverse { s = s }
                    let result = read_str { s = s }
                    result

            main {}
        """.trimIndent()
        assertEquals("cba", eval(code))
    }

    @Test
    fun `test MutString insert and delete`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_insert, string_delete, read_str}
            use std::str

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "helloworld" }
                    string_insert { s = s, i = 5, text = " " }
                    let v1 = read_str { s = s }
                    string_delete { s = s, start = 0, end = 6 }
                    let v2 = read_str { s = s }
                    str::size { s = v1 } * 10 + str::size { s = v2 }

            main {}
        """.trimIndent()
        // "hello world"=11, "world"=5, 11*10 + 5 = 115
        assertEquals(115L, eval(code))
    }

    @Test
    fun `test MutString char_at`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_char_at}
            use std::char

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "abc" }
                    let ch = string_char_at { s = s, i = 1 }
                    match ch is
                        Some { val = c } -> char::to_int { c = c }
                        None -> -1

            main {}
        """.trimIndent()
        // 'b' = 98
        assertEquals(98L, eval(code))
    }

    @Test
    fun `test MutString set_char_at`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_to_mut, string_set_char_at, read_str}
            use std::char

            let main = {} ->
                perform LocalMut in do
                    let s = string_to_mut { s = "abc" }
                    match char::from_int { code = 88 } is
                        Some { val = x } -> do
                            string_set_char_at { s = s, i = 1, c = x }
                            read_str { s = s }
                        None -> "error"

            main {}
        """.trimIndent()
        assertEquals("aXc", eval(code))
    }

    @Test
    fun `test MutString with_capacity`() {
        val code = """
            use std::effects::mutation::{LocalMut, string_with_capacity, string_append, string_len, read_str}

            let main = {} ->
                perform LocalMut in do
                    let s = string_with_capacity { capacity = 100 }
                    string_append { s = s, suffix = "hello" }
                    string_append { s = s, suffix = " world" }
                    string_len { s = s }

            main {}
        """.trimIndent()
        assertEquals(11L, eval(code))
    }

    // ==========================================================================
    // MutChar Operations
    // ==========================================================================

    @Test
    fun `test MutChar to_mut and to_immut`() {
        val code = """
            use std::effects::mutation::{LocalMut, char_to_mut, char_to_immut}
            use std::char

            let main = {} ->
                match char::from_int { code = 97 } is
                    Some { val = a } -> perform LocalMut in do
                        let c = char_to_mut { c = a }
                        let result = char_to_immut { c = c }
                        char::to_int { c = result }
                    None -> -1

            main {}
        """.trimIndent()
        // 'a' = 97
        assertEquals(97L, eval(code))
    }

    @Test
    fun `test MutChar read and write`() {
        val code = """
            use std::effects::mutation::{LocalMut, char_to_mut, read_char, write_char}
            use std::char

            let main = {} ->
                match char::from_int { code = 97 } is
                    Some { val = a } -> match char::from_int { code = 122 } is
                        Some { val = z } -> perform LocalMut in do
                            let c = char_to_mut { c = a }
                            let v1 = read_char { c = c }
                            write_char { c = c, v = z }
                            let v2 = read_char { c = c }
                            char::to_int { c = v1 } + char::to_int { c = v2 }
                        None -> -1
                    None -> -1

            main {}
        """.trimIndent()
        // 'a'=97, 'z'=122, 97+122=219
        assertEquals(219L, eval(code))
    }

    @Test
    fun `test MutChar to_upper and to_lower`() {
        val code = """
            use std::effects::mutation::{LocalMut, char_to_mut, read_char, char_to_upper, char_to_lower}
            use std::char

            let main = {} ->
                match char::from_int { code = 97 } is
                    Some { val = a } -> perform LocalMut in do
                        let c = char_to_mut { c = a }
                        char_to_upper { c = c }
                        let upper = read_char { c = c }
                        char_to_lower { c = c }
                        let lower = read_char { c = c }
                        char::to_int { c = upper } * 1000 + char::to_int { c = lower }
                    None -> -1

            main {}
        """.trimIndent()
        // 'A'=65, 'a'=97, 65*1000 + 97 = 65097
        assertEquals(65097L, eval(code))
    }

    @Test
    fun `test MutChar is_digit`() {
        val code = """
            use std::effects::mutation::{LocalMut, char_to_mut, char_is_digit, write_char}
            use std::char

            let main = {} ->
                match char::from_int { code = 53 } is
                    Some { val = five } -> match char::from_int { code = 120 } is
                        Some { val = x } -> perform LocalMut in do
                            let c = char_to_mut { c = five }
                            let d1 = char_is_digit { c = c }
                            write_char { c = c, v = x }
                            let d2 = char_is_digit { c = c }
                            if d1 then (if d2 then 0 else 1) else 0
                        None -> -1
                    None -> -1

            main {}
        """.trimIndent()
        // d1=true (digit '5'), d2=false (letter 'x') -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutChar is_letter`() {
        val code = """
            use std::effects::mutation::{LocalMut, char_to_mut, char_is_letter, write_char}
            use std::char

            let main = {} ->
                match char::from_int { code = 97 } is
                    Some { val = a } -> match char::from_int { code = 49 } is
                        Some { val = one } -> perform LocalMut in do
                            let c = char_to_mut { c = a }
                            let l1 = char_is_letter { c = c }
                            write_char { c = c, v = one }
                            let l2 = char_is_letter { c = c }
                            if l1 then (if l2 then 0 else 1) else 0
                        None -> -1
                    None -> -1

            main {}
        """.trimIndent()
        // l1=true (letter 'a'), l2=false (digit '1') -> 1
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test MutChar is_whitespace`() {
        val code = """
            use std::effects::mutation::{LocalMut, char_to_mut, char_is_whitespace, write_char}
            use std::char

            let main = {} ->
                match char::from_int { code = 32 } is
                    Some { val = space } -> match char::from_int { code = 97 } is
                        Some { val = a } -> perform LocalMut in do
                            let c = char_to_mut { c = space }
                            let w1 = char_is_whitespace { c = c }
                            write_char { c = c, v = a }
                            let w2 = char_is_whitespace { c = c }
                            if w1 then (if w2 then 0 else 1) else 0
                        None -> -1
                    None -> -1

            main {}
        """.trimIndent()
        // w1=true (space), w2=false (letter 'a') -> 1
        assertEquals(1L, eval(code))
    }

    // ==========================================================================
    // Nested perform LocalMut inside for_range body
    //
    // Caught while writing a Mut version of the Collatz benchmark for
    // perf comparison: a `for_range` whose body calls a helper that
    // itself does `perform LocalMut in do ...` silently exits with no
    // output (and exit code 0) under `mez build` / native-image. This
    // unit test pins the expected behaviour at the runtime layer so
    // we can reproduce and fix it without going through `mez build`.
    // ==========================================================================

    @Test
    fun `test nested perform LocalMut — helper called once from outer scope`() {
        // Sanity baseline: outer `perform LocalMut` calls a helper that
        // does its own `perform LocalMut`. No for_range; the helper runs
        // exactly once.
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr}

            let collatz_step = { n_init } -> perform LocalMut in do
                let n = int_to_mut { n = n_init }
                let acc = int_to_mut { n = 0 }
                incr { n = acc, delta = 1 }
                read_int { n = acc }

            let main = {} ->
                perform LocalMut in do
                    let total = int_to_mut { n = 0 }
                    let len = collatz_step { n_init = 5 }
                    incr { n = total, delta = len }
                    read_int { n = total }

            main {}
        """.trimIndent()
        assertEquals(1L, eval(code))
    }

    @Test
    fun `test nested perform LocalMut — helper called from for_range body`() {
        // The actual repro: for_range body calls a helper that does its
        // own `perform LocalMut`. Under `mez build`-produced jars this
        // silently exits at the for_range call. Test runs the same code
        // through the interpreter test harness.
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr, for_range}

            let one_step = { n_init } -> perform LocalMut in do
                let acc = int_to_mut { n = 0 }
                incr { n = acc, delta = n_init }
                read_int { n = acc }

            let main = {} ->
                perform LocalMut in do
                    let total = int_to_mut { n = 0 }
                    for_range { start = 1, end = 4, step = 1, body = {i} -> do
                        let len = one_step { n_init = i }
                        incr { n = total, delta = len }
                    }
                    read_int { n = total }

            main {}
        """.trimIndent()
        // for_range iterates i = 1, 2, 3 — total = 1 + 2 + 3 = 6.
        assertEquals(6L, eval(code))
    }

    @Test
    fun `test Mut Collatz iterative — small n`() {
        // Full miniature of the benchmark shape that exposed the bug:
        // `for_range` body calls `collatz_length_mut` which uses an
        // inner `perform LocalMut` + `while`. Tests that the whole
        // shape works end-to-end at small scale.
        val code = """
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, write_int, incr, while, for_range}

            let collatz_length_mut = { n_init } -> perform LocalMut in do
                let n = int_to_mut { n = n_init }
                let acc = int_to_mut { n = 0 }
                while {
                    cond = {} -> do
                        let cur = read_int { n = n }
                        cur > 1,
                    body = {} -> do
                        let cur = read_int { n = n }
                        let next = if cur - 2 * (cur / 2) == 0 then cur / 2 else 3 * cur + 1
                        write_int { n = n, v = next }
                        incr { n = acc, delta = 1 },
                }
                read_int { n = acc }

            let main = {} ->
                perform LocalMut in do
                    let total = int_to_mut { n = 0 }
                    for_range { start = 1, end = 11, step = 1, body = {i} -> do
                        let len = collatz_length_mut { n_init = i }
                        incr { n = total, delta = len }
                    }
                    read_int { n = total }

            main {}
        """.trimIndent()
        // Collatz lengths 1..10: 0, 1, 7, 2, 5, 8, 16, 3, 19, 6 → sum = 67
        assertEquals(67L, eval(code))
    }

    // ==========================================================================
    // Console + Mut interaction (matches what `mez build`-produced jars run)
    //
    // The benchmark that surfaced the silent-exit bug used the actual
    // CLI shape:
    //
    //   let main = {} -> perform Console in do
    //     println { msg = "..." }
    //     let result = perform LocalMut in do ...
    //     println { msg = "result = $(...)" }
    //
    // The tests above only exercise bare `LocalMut`. These exercise the
    // Console wrapper plus a nested `perform LocalMut` inside the same
    // do-block, with both println calls before AND after the nested
    // perform. The runtime test verifies all println output reaches
    // stdout in order; if the runtime is silent, the assertion fails
    // with empty captured output.
    // ==========================================================================

    @Test
    fun `test Console wrapping a nested perform LocalMut — println before and after`() {
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr}
            use std::int

            let main = {} -> perform Console in do
                println { msg = "before" }
                let result = perform LocalMut in do
                    let acc = int_to_mut { n = 0 }
                    incr { n = acc, delta = 42 }
                    read_int { n = acc }
                println { msg = "after r=$(int::to_str { n = result })" }

            main {}
        """.trimIndent()
        val out = MezzeTestHelper.runWithOutput(code).output()
        assertTrue(out.contains("before"), "expected 'before' in output, got: <$out>")
        assertTrue(out.contains("after r=42"), "expected 'after r=42' in output, got: <$out>")
    }

    @Test
    fun `test Console wrapping for_range that calls a helper using nested perform LocalMut`() {
        // Closest unit-level approximation of the benchmark shape that
        // silently exited under `mez build`. The bug repro: outer
        // Console, inner LocalMut, for_range body calling a helper that
        // does its own perform LocalMut, final println using the
        // returned value.
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, incr, for_range}
            use std::int

            let one_step = { n_init } -> perform LocalMut in do
                let acc = int_to_mut { n = 0 }
                incr { n = acc, delta = n_init }
                read_int { n = acc }

            let main = {} -> perform Console in do
                println { msg = "start" }
                let result = perform LocalMut in do
                    let total = int_to_mut { n = 0 }
                    for_range { start = 1, end = 4, step = 1, body = {i} -> do
                        let len = one_step { n_init = i }
                        incr { n = total, delta = len }
                    }
                    read_int { n = total }
                println { msg = "sum=$(int::to_str { n = result })" }

            main {}
        """.trimIndent()
        // for_range body runs for i = 1, 2, 3; total = 1 + 2 + 3 = 6.
        val out = MezzeTestHelper.runWithOutput(code).output()
        assertTrue(out.contains("start"), "expected 'start' in output, got: <$out>")
        assertTrue(out.contains("sum=6"), "expected 'sum=6' in output, got: <$out>")
    }

    @Test
    fun `test Console wrapping the full Mut Collatz iterative shape`() {
        // The exact shape that silently exited under `mez build`,
        // shrunk to a tractable size (n = 10 instead of 500000) so the
        // unit test runs in milliseconds while still exercising the
        // bug-triggering control-flow path.
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::mutation::{LocalMut, int_to_mut, read_int, write_int, incr, while, for_range}
            use std::int

            let collatz_length_mut = { n_init } -> perform LocalMut in do
                let n = int_to_mut { n = n_init }
                let acc = int_to_mut { n = 0 }
                while {
                    cond = {} -> do
                        let cur = read_int { n = n }
                        cur > 1,
                    body = {} -> do
                        let cur = read_int { n = n }
                        let next = if cur - 2 * (cur / 2) == 0 then cur / 2 else 3 * cur + 1
                        write_int { n = n, v = next }
                        incr { n = acc, delta = 1 },
                }
                read_int { n = acc }

            let main = {} -> perform Console in do
                let n = 10
                println { msg = "start n=$(int::to_str { n = n })" }
                let result = perform LocalMut in do
                    let total = int_to_mut { n = 0 }
                    for_range { start = 1, end = n + 1, step = 1, body = {i} -> do
                        let len = collatz_length_mut { n_init = i }
                        incr { n = total, delta = len }
                    }
                    read_int { n = total }
                println { msg = "Collatz sum 1..$(int::to_str { n = n }) = $(int::to_str { n = result })" }

            main {}
        """.trimIndent()
        // Collatz lengths 1..10: 0, 1, 7, 2, 5, 8, 16, 3, 19, 6 → sum = 67
        val out = MezzeTestHelper.runWithOutput(code).output()
        assertTrue(out.contains("start n=10"), "expected 'start n=10' in output, got: <$out>")
        assertTrue(out.contains("Collatz sum 1..10 = 67"),
            "expected 'Collatz sum 1..10 = 67' in output, got: <$out>")
    }

    @Test
    fun `test Console wrapping for_range under DebugLocalMut with nested perform`() {
        // Same #255 shape but with DebugLocalMut. Exercises
        // __native_debug_mutation_loop + __native_debug_mutation_mutate,
        // which call back into Mezze code (cond/body/act) from inside the
        // driver's op-dispatch. Without the enterDispatch CURRENT-clear fix,
        // nested ops in the callback suspend the outer Console fiber instead
        // of routing through chain dispatch.
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::mutation::{DebugLocalMut, int_to_mut, read_int, incr, for_range}
            use std::int

            let one_step = { n_init } -> perform DebugLocalMut in do
                let acc = int_to_mut { n = 0 }
                incr { n = acc, delta = n_init }
                read_int { n = acc }

            let main = {} -> perform Console in do
                println { msg = "start" }
                let result = perform DebugLocalMut in do
                    let total = int_to_mut { n = 0 }
                    for_range { start = 1, end = 4, step = 1, body = {i} -> do
                        let len = one_step { n_init = i }
                        incr { n = total, delta = len }
                    }
                    read_int { n = total }
                println { msg = "sum=$(int::to_str { n = result })" }

            main {}
        """.trimIndent()
        // for_range body runs for i = 1, 2, 3; total = 1 + 2 + 3 = 6.
        val out = MezzeTestHelper.runWithOutput(code).output()
        assertTrue(out.contains("start"), "expected 'start' in output, got: <$out>")
        assertTrue(out.contains("sum=6"), "expected 'sum=6' in output, got: <$out>")
    }

}
