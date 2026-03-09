package com.mezze.interpreter.v2.concurrency

import com.mezze.interpreter.v2.MezzeTestHelper
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeList
import com.mezze.interpreter.v2.values.MezzeSeq
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for the STM (Software Transactional Memory) effect.
 *
 * Tests cover:
 * - TVar creation and non-transactional deref
 * - OptimisticSTM handler: read, write, modify, swap, ensure
 * - Control flow: retry, or_else, check
 * - SequentialSTM handler
 * - Helper functions: atomically, transfer
 * - Concurrent scenarios
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class STMEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // TVar Creation and Non-Transactional Read
    // =============================================================================

    @Test
    fun `test tvar creation`() {
        val code = """
            use std::effects::stm::{tvar, deref}

            let counter = tvar {init = 42}
            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test tvar with string value`() {
        val code = """
            use std::effects::stm::{tvar, deref}

            let name = tvar {init = "Alice"}
            deref {var = name}
        """
        val result = eval(code)
        assertEquals("Alice", result)
    }

    @Test
    fun `test tvar with record value`() {
        val code = """
            use std::effects::stm::{tvar, deref}

            let account = tvar {init = {balance = 1000, owner = "Bob"}}
            let acc = deref {var = account}
            acc.balance
        """
        val result = eval(code)
        assertEquals(1000L, result)
    }

    // =============================================================================
    // OptimisticSTM - Basic Read/Write
    // =============================================================================

    @Test
    fun `test stm read`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 100}

            perform OptimisticSTM in do
                STM.read {var = counter}
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test stm write`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.atomically {f = {} ->
                    STM.write {var = counter, val = 42}
                }

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test read your own writes`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.write {var = counter, val = 10}
                STM.read {var = counter}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test multiple reads same tvar`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 5}

            perform OptimisticSTM in do
                let a = STM.read {var = counter}
                let b = STM.read {var = counter}
                a + b
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test multiple writes`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.atomically {f = {} -> do
                    STM.write {var = counter, val = 1}
                    STM.write {var = counter, val = 2}
                    STM.write {var = counter, val = 3}
                }

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // OptimisticSTM - Modify
    // =============================================================================

    @Test
    fun `test stm modify`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 10}

            perform OptimisticSTM in do
                STM.modify {var = counter, f = {val} -> val + 5}

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test multiple modifies`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 1}

            perform OptimisticSTM in do
                STM.modify {var = counter, f = {val} -> val * 2}
                STM.modify {var = counter, f = {val} -> val * 3}
                STM.modify {var = counter, f = {val} -> val + 4}

            deref {var = counter}
        """
        val result = eval(code)
        // 1 * 2 = 2, 2 * 3 = 6, 6 + 4 = 10
        assertEquals(10L, result)
    }

    // =============================================================================
    // OptimisticSTM - Swap
    // =============================================================================

    @Test
    fun `test stm swap`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 100}

            perform OptimisticSTM in do
                let old = STM.swap {var = counter, val = 200}
                # Read using STM.read to see the in-transaction value
                let new = STM.read {var = counter}
                {old = old, new = new}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(100L, result.getField("old"))
        assertEquals(200L, result.getField("new"))
    }

    // =============================================================================
    // OptimisticSTM - Ensure
    // =============================================================================

    @Test
    fun `test stm ensure reads value`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 42}

            perform OptimisticSTM in do
                STM.ensure {var = counter}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // OptimisticSTM - Check
    // =============================================================================

    @Test
    fun `test stm check passes when true`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 10}

            perform OptimisticSTM in do
                let val = STM.read {var = counter}
                STM.check {cond = val > 0}
                val
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    // =============================================================================
    // OptimisticSTM - Multiple TVars
    // =============================================================================

    @Test
    fun `test multiple tvars`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let a = tvar {init = 10}
            let b = tvar {init = 20}
            let c = tvar {init = 30}

            perform OptimisticSTM in do
                STM.atomically {f = {} -> do
                    let va = STM.read {var = a}
                    let vb = STM.read {var = b}
                    let vc = STM.read {var = c}
                    STM.write {var = a, val = va + vb + vc}
                }

            deref {var = a}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test transfer between tvars`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let alice = tvar {init = 1000}
            let bob = tvar {init = 500}

            perform OptimisticSTM in do
                STM.atomically {f = {} -> do
                    let alice_balance = STM.read {var = alice}
                    let bob_balance = STM.read {var = bob}
                    STM.write {var = alice, val = alice_balance - 200}
                    STM.write {var = bob, val = bob_balance + 200}
                }

            let alice_final = deref {var = alice}
            let bob_final = deref {var = bob}
            {alice = alice_final, bob = bob_final}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(800L, result.getField("alice"))
        assertEquals(700L, result.getField("bob"))
    }

    // =============================================================================
    // SequentialSTM Handler
    // =============================================================================

    @Test
    fun `test sequential stm read write`() {
        val code = """
            use std::effects::stm::{STM, SequentialSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform SequentialSTM in do
                STM.write {var = counter, val = 99}

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    @Test
    fun `test sequential stm modify`() {
        val code = """
            use std::effects::stm::{STM, SequentialSTM, tvar, deref}

            let counter = tvar {init = 5}

            perform SequentialSTM in do
                STM.modify {var = counter, f = {val} -> val * val}

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(25L, result)
    }

    // =============================================================================
    // Helper Functions
    // =============================================================================

    @Test
    fun `test atomically helper`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref, atomically}

            let counter = tvar {init = 0}

            # atomically takes an effectful STM function
            let result = atomically {f = {} -> do
                let v = STM.read {var = counter}
                v + 42
            }
            result
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test transfer helper success`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref, transfer}

            let from = tvar {init = 500}
            let to = tvar {init = 100}

            perform OptimisticSTM in do
                let success = STM.atomically {f = {} ->
                    transfer {from = from, to = to, amount = 200}
                }
                let from_balance = deref {var = from}
                let to_balance = deref {var = to}
                {success = success, from = from_balance, to = to_balance}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(true, result.getField("success"))
        assertEquals(300L, result.getField("from"))
        assertEquals(300L, result.getField("to"))
    }

    @Test
    fun `test transfer helper insufficient funds`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref, transfer}

            let from = tvar {init = 50}
            let to = tvar {init = 100}

            perform OptimisticSTM in do
                let success = STM.atomically {f = {} ->
                    transfer {from = from, to = to, amount = 200}
                }
                let from_balance = deref {var = from}
                let to_balance = deref {var = to}
                {success = success, from = from_balance, to = to_balance}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(false, result.getField("success"))
        // Balances should remain unchanged
        assertEquals(50L, result.getField("from"))
        assertEquals(100L, result.getField("to"))
    }

    // =============================================================================
    // Bulk Operations
    // =============================================================================

    @Test
    fun `test read_all`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, read_all}

            let a = tvar {init = 1}
            let b = tvar {init = 2}
            let c = tvar {init = 3}

            perform OptimisticSTM in do
                let values = read_all {vars = [a, b, c]}
                values
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(3, elements.size)
        assertEquals(1L, elements[0])
        assertEquals(2L, elements[1])
        assertEquals(3L, elements[2])
    }

    // =============================================================================
    // Nested Transactions (Flat Nesting)
    // =============================================================================

    @Test
    fun `test nested perform uses outer transaction`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.write {var = counter, val = 10}
                # Inner perform just continues using same transaction context
                let inner_result = perform OptimisticSTM in do
                    let v = STM.read {var = counter}
                    v + 5
                inner_result
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    // =============================================================================
    // Edge Cases
    // =============================================================================

    @Test
    fun `test read only transaction`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 42}

            perform OptimisticSTM in do
                let v1 = STM.read {var = counter}
                let v2 = STM.read {var = counter}
                v1 + v2
        """
        val result = eval(code)
        assertEquals(84L, result)
    }

    @Test
    fun `test transaction with list of tvars`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let t1 = tvar {init = 1}
            let t2 = tvar {init = 2}
            let t3 = tvar {init = 3}

            perform OptimisticSTM in do
                let sum = STM.atomically {f = {} -> do
                    let v1 = STM.read {var = t1}
                    let v2 = STM.read {var = t2}
                    let v3 = STM.read {var = t3}

                    # Increment all
                    STM.write {var = t1, val = v1 + 1}
                    STM.write {var = t2, val = v2 + 1}
                    STM.write {var = t3, val = v3 + 1}

                    v1 + v2 + v3
                }

                let new_sum = deref {var = t1} + deref {var = t2} + deref {var = t3}
                {old_sum = sum, new_sum = new_sum}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(6L, result.getField("old_sum"))
        assertEquals(9L, result.getField("new_sum"))
    }

    // =============================================================================
    // OptimisticSTMWith Handler
    // =============================================================================

    @Test
    fun `test optimistic stm with config`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTMWith, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTMWith {max_retries = 100, spin_count = 10} in do
                STM.modify {var = counter, f = {val} -> val + 1}

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    // =============================================================================
    // TMVar Tests - Blocking Transactional Variable
    // =============================================================================

    @Test
    fun `test tmvar creation empty`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar_empty, is_empty_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                is_empty_tmvar {var = mvar}
        """
        val result = eval(code)
        assertTrue(result as Boolean)
    }

    @Test
    fun `test tmvar creation with value`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar, is_empty_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                is_empty_tmvar {var = mvar}
        """
        val result = eval(code)
        assertFalse(result as Boolean)
    }

    @Test
    fun `test tmvar take from full`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar, take_tmvar, is_empty_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                let value = take_tmvar {var = mvar}
                let empty = is_empty_tmvar {var = mvar}
                {value = value, empty = empty}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(42L, result.getField("value"))
        assertTrue(result.getField("empty") as Boolean)
    }

    @Test
    fun `test tmvar put to empty`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar_empty, put_tmvar, read_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                put_tmvar {var = mvar, val = 99}
                read_tmvar {var = mvar}
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    @Test
    fun `test tmvar read without taking`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar, read_tmvar, is_empty_tmvar}

            let mvar = tmvar {init = 100}
            perform OptimisticSTM in do
                let v1 = read_tmvar {var = mvar}
                let v2 = read_tmvar {var = mvar}
                let empty = is_empty_tmvar {var = mvar}
                {v1 = v1, v2 = v2, empty = empty}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(100L, result.getField("v1"))
        assertEquals(100L, result.getField("v2"))
        assertFalse(result.getField("empty") as Boolean)
    }

    @Test
    fun `test tmvar try_take from full`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar, try_take_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                match try_take_tmvar {var = mvar} is
                    Some {val = v} -> v
                    None -> -1
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test tmvar try_take from empty`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar_empty, try_take_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                match try_take_tmvar {var = mvar} is
                    Some {val = _} -> False
                    None -> True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test tmvar try_put to empty`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar_empty, try_put_tmvar, read_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                let success = try_put_tmvar {var = mvar, val = 55}
                let value = read_tmvar {var = mvar}
                {success = success, value = value}
        """
        val result = eval(code) as MezzeRecord
        assertTrue(result.getField("success") as Boolean)
        assertEquals(55L, result.getField("value"))
    }

    @Test
    fun `test tmvar try_put to full`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar, try_put_tmvar, read_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                let success = try_put_tmvar {var = mvar, val = 99}
                let value = read_tmvar {var = mvar}
                {success = success, value = value}
        """
        val result = eval(code) as MezzeRecord
        assertFalse(result.getField("success") as Boolean)
        assertEquals(42L, result.getField("value"))
    }

    @Test
    fun `test tmvar swap`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tmvar, swap_tmvar, read_tmvar}

            let mvar = tmvar {init = 10}
            perform OptimisticSTM in do
                let old = swap_tmvar {var = mvar, val = 20}
                let new = read_tmvar {var = mvar}
                {old = old, new = new}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(10L, result.getField("old"))
        assertEquals(20L, result.getField("new"))
    }

    // =============================================================================
    // TQueue Tests - Unbounded STM Queue
    // =============================================================================

    @Test
    fun `test tqueue creation and write read`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, write_tqueue, read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 42}
                read_tqueue {q = q}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test tqueue fifo order`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, write_tqueue, read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 1}
                write_tqueue {q = q, val = 2}
                write_tqueue {q = q, val = 3}
                let first = read_tqueue {q = q}
                let second = read_tqueue {q = q}
                let third = read_tqueue {q = q}
                {first = first, second = second, third = third}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(1L, result.getField("first"))
        assertEquals(2L, result.getField("second"))
        assertEquals(3L, result.getField("third"))
    }

    @Test
    fun `test tqueue is_empty true for new queue`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, is_empty_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                is_empty_tqueue {q = q}
        """
        val result = eval(code)
        assertTrue(result as Boolean)
    }

    @Test
    fun `test tqueue is_empty false after write`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, write_tqueue, is_empty_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 1}
                is_empty_tqueue {q = q}
        """
        val result = eval(code)
        assertFalse(result as Boolean)
    }

    @Test
    fun `test tqueue try_read from empty`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, try_read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                match try_read_tqueue {q = q} is
                    Some {val = _} -> False
                    None -> True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test tqueue try_read from non-empty`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, write_tqueue, try_read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 99}
                match try_read_tqueue {q = q} is
                    Some {val = v} -> v
                    None -> -1
        """
        val result = eval(code)
        assertEquals(99L, result)
    }

    @Test
    fun `test tqueue peek`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tqueue, write_tqueue, peek_tqueue, read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 42}
                let peeked = peek_tqueue {q = q}
                let read1 = read_tqueue {q = q}
                {peeked = peeked, read1 = read1}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(42L, result.getField("peeked"))
        assertEquals(42L, result.getField("read1"))
    }

    // =============================================================================
    // TBQueue Tests - Bounded STM Queue
    // =============================================================================

    @Test
    fun `test tbqueue creation and write read`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, read_tbqueue}

            let q = tbqueue {capacity = 10}
            perform OptimisticSTM in do
                write_tbqueue {q = q, val = 42}
                read_tbqueue {q = q}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test tbqueue fifo order`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, read_tbqueue}

            let q = tbqueue {capacity = 10}
            perform OptimisticSTM in do
                write_tbqueue {q = q, val = 1}
                write_tbqueue {q = q, val = 2}
                write_tbqueue {q = q, val = 3}
                let first = read_tbqueue {q = q}
                let second = read_tbqueue {q = q}
                let third = read_tbqueue {q = q}
                {first = first, second = second, third = third}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(1L, result.getField("first"))
        assertEquals(2L, result.getField("second"))
        assertEquals(3L, result.getField("third"))
    }

    @Test
    fun `test tbqueue try_write when full`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, try_write_tbqueue, size_tbqueue}

            let q = tbqueue {capacity = 2}
            perform OptimisticSTM in do
                write_tbqueue {q = q, val = 1}
                write_tbqueue {q = q, val = 2}
                # Queue is now full (size = 2, capacity = 2)
                let success = try_write_tbqueue {q = q, val = 3}
                let size = size_tbqueue {q = q}
                {success = success, size = size}
        """
        val result = eval(code) as MezzeRecord
        assertFalse(result.getField("success") as Boolean)
        assertEquals(2L, result.getField("size"))
    }

    @Test
    fun `test tbqueue is_full`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, is_full_tbqueue}

            let q = tbqueue {capacity = 2}
            perform OptimisticSTM in do
                let before = is_full_tbqueue {q = q}
                write_tbqueue {q = q, val = 1}
                let after1 = is_full_tbqueue {q = q}
                write_tbqueue {q = q, val = 2}
                let after2 = is_full_tbqueue {q = q}
                {before = before, after1 = after1, after2 = after2}
        """
        val result = eval(code) as MezzeRecord
        assertFalse(result.getField("before") as Boolean)
        assertFalse(result.getField("after1") as Boolean)
        assertTrue(result.getField("after2") as Boolean)
    }

    @Test
    fun `test tbqueue size_tbqueue`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, read_tbqueue, size_tbqueue}

            let q = tbqueue {capacity = 10}
            perform OptimisticSTM in do
                let s0 = size_tbqueue {q = q}
                write_tbqueue {q = q, val = 1}
                let s1 = size_tbqueue {q = q}
                write_tbqueue {q = q, val = 2}
                let s2 = size_tbqueue {q = q}
                let _ = read_tbqueue {q = q}
                let s3 = size_tbqueue {q = q}
                {s0 = s0, s1 = s1, s2 = s2, s3 = s3}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(0L, result.getField("s0"))
        assertEquals(1L, result.getField("s1"))
        assertEquals(2L, result.getField("s2"))
        assertEquals(1L, result.getField("s3"))
    }

    @Test
    fun `test tbqueue capacity_tbqueue`() {
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tbqueue, capacity_tbqueue}

            let q = tbqueue {capacity = 50}
            perform OptimisticSTM in do
                capacity_tbqueue {q = q}
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    // =============================================================================
    // Transaction Boundary Tests - Ensure commits happen at perform block end
    // =============================================================================

    @Test
    fun `test multiple writes in single perform block are atomic`() {
        // All writes within an atomically block should be committed together
        val code = """
            use std::effects::stm::{STM, OptimisticSTM, tvar, deref}

            let a = tvar {init = 100}
            let b = tvar {init = 200}

            perform OptimisticSTM in do
                STM.atomically {f = {} -> do
                    STM.write {var = a, val = 50}
                    STM.write {var = b, val = 150}
                }

            let va = deref {var = a}
            let vb = deref {var = b}
            va + vb
        """
        val result = eval(code)
        // Both writes should be committed: 50 + 150 = 200
        assertEquals(200L, result)
    }

    @Test
    fun `test atomically wrapper commits transaction`() {
        // atomically should properly commit the transaction
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}

            let counter = tvar {init = 0}

            let _ = atomically {f = {} -> do
                let v = STM.read {var = counter}
                STM.write {var = counter, val = v + 10}
            }

            deref {var = counter}
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test atomically with multiple operations`() {
        // atomically should handle multiple reads and writes atomically
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}

            let alice = tvar {init = 1000}
            let bob = tvar {init = 500}

            # Transfer 100 from alice to bob
            let _ = atomically {f = {} -> do
                let a = STM.read {var = alice}
                let b = STM.read {var = bob}
                STM.write {var = alice, val = a - 100}
                STM.write {var = bob, val = b + 100}
            }

            let final_alice = deref {var = alice}
            let final_bob = deref {var = bob}
            {alice = final_alice, bob = final_bob, total = final_alice + final_bob}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(900L, result.getField("alice"))
        assertEquals(600L, result.getField("bob"))
        assertEquals(1500L, result.getField("total"))
    }

    // =============================================================================
    // Async + STM Integration Tests
    // =============================================================================

    @Test
    fun `test async spawn with atomically`() {
        // Spawned tasks should be able to use atomically for STM operations
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let counter = tvar {init = 0}

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> atomically {f = {} ->
                    STM.write {var = counter, val = 42}
                }}
                let _ = Async.await {p = p}
                deref {var = counter}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test async spawn inherits Log effect handler`() {
        // Spawned tasks should have access to parent's effect handlers (like Log)
        val code = """
            use std::effects::log::{Console, println}
            use std::effects::async::{Async, LoomAsync}
            use std::effects::stm::{STM, tvar, deref, atomically}

            let result_var = tvar {init = 0}

            perform Console in do
                # Use Log effect at top level so typechecker sees it
                println {msg = "Starting Log handler test"}
                perform LoomAsync in do
                    let p = Async.spawn {f = {} -> do
                        # This uses Log effect - should work because handlers are propagated
                        println {msg = "Hello from spawned task"}
                        # Use atomically to update the tvar
                        atomically {f = {} -> STM.write {var = result_var, val = 123}}
                    }
                    let _ = Async.await {p = p}
                    deref {var = result_var}
        """
        val result = eval(code)
        assertEquals(123L, result)
    }

    @Test
    fun `test concurrent STM writes with atomically`() {
        // Concurrent tasks using atomically should both succeed
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let counter = tvar {init = 0}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 10}
                }}
                let p2 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let v = STM.read {var = counter}
                    STM.write {var = counter, val = v + 5}
                }}
                let _ = Async.await {p = p1}
                let _ = Async.await {p = p2}
                deref {var = counter}
        """
        val result = eval(code)
        // Both increments should succeed (with retry if conflict): 0 + 10 + 5 = 15
        assertEquals(15L, result)
    }

    @Test
    fun `test bank transfer demo - concurrent transfers preserve total`() {
        // This is the key demo - concurrent transfers should preserve total balance
        val code = """
            use std::effects::stm::{STM, tvar, deref, atomically}
            use std::effects::async::{Async, LoomAsync}

            let alice = tvar {init = 1000}
            let bob = tvar {init = 500}
            let charlie = tvar {init = 200}

            perform LoomAsync in do
                # Transfer 100 from alice to bob
                let t1 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let a = STM.read {var = alice}
                    let b = STM.read {var = bob}
                    STM.write {var = alice, val = a - 100}
                    STM.write {var = bob, val = b + 100}
                }}

                # Transfer 50 from bob to charlie
                let t2 = Async.spawn {f = {} -> atomically {f = {} -> do
                    let b = STM.read {var = bob}
                    let c = STM.read {var = charlie}
                    STM.write {var = bob, val = b - 50}
                    STM.write {var = charlie, val = c + 50}
                }}

                let _ = Async.await {p = t1}
                let _ = Async.await {p = t2}

                let final_alice = deref {var = alice}
                let final_bob = deref {var = bob}
                let final_charlie = deref {var = charlie}
                final_alice + final_bob + final_charlie
        """
        val result = eval(code)
        // Total should always be preserved: 1000 + 500 + 200 = 1700
        assertEquals(1700L, result)
    }
}
