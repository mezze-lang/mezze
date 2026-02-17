package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
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
 */
@Execution(ExecutionMode.SAME_THREAD)
class STMEffectTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // =============================================================================
    // TVar Creation and Non-Transactional Read
    // =============================================================================

    @Test
    fun `test tvar creation`() {
        val code = """
            use std::stm::{tvar, deref}

            let counter = tvar {init = 42}
            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(42L, result.value)
    }

    @Test
    fun `test tvar with string value`() {
        val code = """
            use std::stm::{tvar, deref}

            let name = tvar {init = "Alice"}
            deref {var = name}
        """
        val result = eval(code) as MezzeString
        assertEquals("Alice", result.value)
    }

    @Test
    fun `test tvar with record value`() {
        val code = """
            use std::stm::{tvar, deref}

            let account = tvar {init = {balance = 1000, owner = "Bob"}}
            let acc = deref {var = account}
            acc.balance
        """
        val result = eval(code) as MezzeInt
        assertEquals(1000L, result.value)
    }

    // =============================================================================
    // OptimisticSTM - Basic Read/Write
    // =============================================================================

    @Test
    fun `test stm read`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 100}

            perform OptimisticSTM in do
                STM.read {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(100L, result.value)
    }

    @Test
    fun `test stm write`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.write {var = counter, val = 42}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(42L, result.value)
    }

    @Test
    fun `test read your own writes`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.write {var = counter, val = 10}
                STM.read {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(10L, result.value)
    }

    @Test
    fun `test multiple reads same tvar`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 5}

            perform OptimisticSTM in do
                let a = STM.read {var = counter}
                let b = STM.read {var = counter}
                a + b
        """
        val result = eval(code) as MezzeInt
        assertEquals(10L, result.value)
    }

    @Test
    fun `test multiple writes`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.write {var = counter, val = 1}
                STM.write {var = counter, val = 2}
                STM.write {var = counter, val = 3}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(3L, result.value)
    }

    // =============================================================================
    // OptimisticSTM - Modify
    // =============================================================================

    @Test
    fun `test stm modify`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 10}

            perform OptimisticSTM in do
                STM.modify {var = counter, f = {val} -> val + 5}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(15L, result.value)
    }

    @Test
    fun `test multiple modifies`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 1}

            perform OptimisticSTM in do
                STM.modify {var = counter, f = {val} -> val * 2}
                STM.modify {var = counter, f = {val} -> val * 3}
                STM.modify {var = counter, f = {val} -> val + 4}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        // 1 * 2 = 2, 2 * 3 = 6, 6 + 4 = 10
        assertEquals(10L, result.value)
    }

    // =============================================================================
    // OptimisticSTM - Swap
    // =============================================================================

    @Test
    fun `test stm swap`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 100}

            let getResult = {} -> do
                let old = perform OptimisticSTM in do
                    STM.swap {var = counter, val = 200}
                let new = deref {var = counter}
                {old = old, new = new}
        """
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", MezzeRecord(emptyMap()), typeCheckResult.monomorphicInstances) as MezzeRecord
        assertEquals(100L, (result.fields["old"] as MezzeInt).value)
        assertEquals(200L, (result.fields["new"] as MezzeInt).value)
    }

    // =============================================================================
    // OptimisticSTM - Ensure
    // =============================================================================

    @Test
    fun `test stm ensure reads value`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 42}

            perform OptimisticSTM in do
                STM.ensure {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(42L, result.value)
    }

    // =============================================================================
    // OptimisticSTM - Check
    // =============================================================================

    @Test
    fun `test stm check passes when true`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 10}

            perform OptimisticSTM in do
                let val = STM.read {var = counter}
                STM.check {cond = val > 0}
                val
        """
        val result = eval(code) as MezzeInt
        assertEquals(10L, result.value)
    }

    // =============================================================================
    // OptimisticSTM - Multiple TVars
    // =============================================================================

    @Test
    fun `test multiple tvars`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let a = tvar {init = 10}
            let b = tvar {init = 20}
            let c = tvar {init = 30}

            perform OptimisticSTM in do
                let va = STM.read {var = a}
                let vb = STM.read {var = b}
                let vc = STM.read {var = c}
                STM.write {var = a, val = va + vb + vc}

            deref {var = a}
        """
        val result = eval(code) as MezzeInt
        assertEquals(60L, result.value)
    }

    @Test
    fun `test transfer between tvars`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let alice = tvar {init = 1000}
            let bob = tvar {init = 500}

            perform OptimisticSTM in do
                let alice_balance = STM.read {var = alice}
                let bob_balance = STM.read {var = bob}
                STM.write {var = alice, val = alice_balance - 200}
                STM.write {var = bob, val = bob_balance + 200}

            let alice_final = deref {var = alice}
            let bob_final = deref {var = bob}
            {alice = alice_final, bob = bob_final}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(800L, (result.fields["alice"] as MezzeInt).value)
        assertEquals(700L, (result.fields["bob"] as MezzeInt).value)
    }

    // =============================================================================
    // SequentialSTM Handler
    // =============================================================================

    @Test
    fun `test sequential stm read write`() {
        val code = """
            use std::stm::{STM, SequentialSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform SequentialSTM in do
                STM.write {var = counter, val = 99}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(99L, result.value)
    }

    @Test
    fun `test sequential stm modify`() {
        val code = """
            use std::stm::{STM, SequentialSTM, tvar, deref}

            let counter = tvar {init = 5}

            perform SequentialSTM in do
                STM.modify {var = counter, f = {val} -> val * val}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(25L, result.value)
    }

    // =============================================================================
    // Helper Functions
    // =============================================================================

    @Test
    fun `test atomically helper`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref, atomically}

            let counter = tvar {init = 0}

            # atomically takes an effectful STM function
            let result = atomically {f = {} -> do
                let v = STM.read {var = counter}
                v + 42
            }
            result
        """
        val result = eval(code) as MezzeInt
        assertEquals(42L, result.value)
    }

    @Test
    fun `test transfer helper success`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref, transfer}

            let from = tvar {init = 500}
            let to = tvar {init = 100}

            let getResult = {} -> do
                let success = perform OptimisticSTM in do
                    transfer {from = from, to = to, amount = 200}
                let from_balance = deref {var = from}
                let to_balance = deref {var = to}
                {success = success, from = from_balance, to = to_balance}
        """
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", MezzeRecord(emptyMap()), typeCheckResult.monomorphicInstances) as MezzeRecord
        assertEquals(true, (result.fields["success"] as MezzeBool).value)
        assertEquals(300L, (result.fields["from"] as MezzeInt).value)
        assertEquals(300L, (result.fields["to"] as MezzeInt).value)
    }

    @Test
    fun `test transfer helper insufficient funds`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref, transfer}

            let from = tvar {init = 50}
            let to = tvar {init = 100}

            let getResult = {} -> do
                let success = perform OptimisticSTM in do
                    transfer {from = from, to = to, amount = 200}
                let from_balance = deref {var = from}
                let to_balance = deref {var = to}
                {success = success, from = from_balance, to = to_balance}
        """
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", MezzeRecord(emptyMap()), typeCheckResult.monomorphicInstances) as MezzeRecord
        assertEquals(false, (result.fields["success"] as MezzeBool).value)
        // Balances should remain unchanged
        assertEquals(50L, (result.fields["from"] as MezzeInt).value)
        assertEquals(100L, (result.fields["to"] as MezzeInt).value)
    }

    // =============================================================================
    // Bulk Operations
    // =============================================================================

    @Test
    fun `test read_all`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, read_all}

            let a = tvar {init = 1}
            let b = tvar {init = 2}
            let c = tvar {init = 3}

            perform OptimisticSTM in do
                let values = read_all {vars = [a, b, c]}
                values
        """
        val result = eval(code) as MezzeArrayList
        val values = result.toList().map { (it as MezzeInt).value }
        assertEquals(listOf(1L, 2L, 3L), values)
    }

    // =============================================================================
    // Nested Transactions (Flat Nesting)
    // =============================================================================

    @Test
    fun `test nested perform uses outer transaction`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTM in do
                STM.write {var = counter, val = 10}
                # Inner perform just continues using same transaction context
                let inner_result = perform OptimisticSTM in do
                    let v = STM.read {var = counter}
                    v + 5
                inner_result
        """
        val result = eval(code) as MezzeInt
        assertEquals(15L, result.value)
    }

    // =============================================================================
    // Edge Cases
    // =============================================================================

    @Test
    fun `test read only transaction`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar}

            let counter = tvar {init = 42}

            perform OptimisticSTM in do
                let v1 = STM.read {var = counter}
                let v2 = STM.read {var = counter}
                v1 + v2
        """
        val result = eval(code) as MezzeInt
        assertEquals(84L, result.value)
    }

    @Test
    fun `test transaction with list of tvars`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tvar, deref}

            let t1 = tvar {init = 1}
            let t2 = tvar {init = 2}
            let t3 = tvar {init = 3}

            let getResult = {} -> do
                let sum = perform OptimisticSTM in do
                    let v1 = STM.read {var = t1}
                    let v2 = STM.read {var = t2}
                    let v3 = STM.read {var = t3}

                    # Increment all
                    STM.write {var = t1, val = v1 + 1}
                    STM.write {var = t2, val = v2 + 1}
                    STM.write {var = t3, val = v3 + 1}

                    v1 + v2 + v3

                let new_sum = deref {var = t1} + deref {var = t2} + deref {var = t3}
                {old_sum = sum, new_sum = new_sum}
        """
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", MezzeRecord(emptyMap()), typeCheckResult.monomorphicInstances) as MezzeRecord
        assertEquals(6L, (result.fields["old_sum"] as MezzeInt).value)
        assertEquals(9L, (result.fields["new_sum"] as MezzeInt).value)
    }

    // =============================================================================
    // OptimisticSTMWith Handler
    // =============================================================================

    @Test
    fun `test optimistic stm with config`() {
        val code = """
            use std::stm::{STM, OptimisticSTMWith, tvar, deref}

            let counter = tvar {init = 0}

            perform OptimisticSTMWith {max_retries = 100, spin_count = 10} in do
                STM.modify {var = counter, f = {val} -> val + 1}

            deref {var = counter}
        """
        val result = eval(code) as MezzeInt
        assertEquals(1L, result.value)
    }

    // =============================================================================
    // TMVar Tests - Blocking Transactional Variable
    // =============================================================================

    @Test
    fun `test tmvar creation empty`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar_empty, is_empty_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                is_empty_tmvar {var = mvar}
        """
        val result = eval(code) as MezzeBool
        assertTrue(result.value)
    }

    @Test
    fun `test tmvar creation with value`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar, is_empty_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                is_empty_tmvar {var = mvar}
        """
        val result = eval(code) as MezzeBool
        assertFalse(result.value)
    }

    @Test
    fun `test tmvar take from full`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar, take_tmvar, is_empty_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                let value = take_tmvar {var = mvar}
                let empty = is_empty_tmvar {var = mvar}
                {value = value, empty = empty}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(42L, (result.fields["value"] as MezzeInt).value)
        assertTrue((result.fields["empty"] as MezzeBool).value)
    }

    @Test
    fun `test tmvar put to empty`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar_empty, put_tmvar, read_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                put_tmvar {var = mvar, val = 99}
                read_tmvar {var = mvar}
        """
        val result = eval(code) as MezzeInt
        assertEquals(99L, result.value)
    }

    @Test
    fun `test tmvar read without taking`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar, read_tmvar, is_empty_tmvar}

            let mvar = tmvar {init = 100}
            perform OptimisticSTM in do
                let v1 = read_tmvar {var = mvar}
                let v2 = read_tmvar {var = mvar}
                let empty = is_empty_tmvar {var = mvar}
                {v1 = v1, v2 = v2, empty = empty}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(100L, (result.fields["v1"] as MezzeInt).value)
        assertEquals(100L, (result.fields["v2"] as MezzeInt).value)
        assertFalse((result.fields["empty"] as MezzeBool).value)
    }

    @Test
    fun `test tmvar try_take from full`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar, try_take_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                try_take_tmvar {var = mvar}
        """
        val result = eval(code) as MezzeVariant
        assertEquals("Some", result.constructor)
        val inner = (result.value as MezzeRecord).fields["val"] as MezzeInt
        assertEquals(42L, inner.value)
    }

    @Test
    fun `test tmvar try_take from empty`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar_empty, try_take_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                try_take_tmvar {var = mvar}
        """
        val result = eval(code) as MezzeVariant
        assertEquals("None", result.constructor)
    }

    @Test
    fun `test tmvar try_put to empty`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar_empty, try_put_tmvar, read_tmvar}

            let mvar = tmvar_empty {}
            perform OptimisticSTM in do
                let success = try_put_tmvar {var = mvar, val = 55}
                let value = read_tmvar {var = mvar}
                {success = success, value = value}
        """
        val result = eval(code) as MezzeRecord
        assertTrue((result.fields["success"] as MezzeBool).value)
        assertEquals(55L, (result.fields["value"] as MezzeInt).value)
    }

    @Test
    fun `test tmvar try_put to full`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar, try_put_tmvar, read_tmvar}

            let mvar = tmvar {init = 42}
            perform OptimisticSTM in do
                let success = try_put_tmvar {var = mvar, val = 99}
                let value = read_tmvar {var = mvar}
                {success = success, value = value}
        """
        val result = eval(code) as MezzeRecord
        assertFalse((result.fields["success"] as MezzeBool).value)
        assertEquals(42L, (result.fields["value"] as MezzeInt).value)
    }

    @Test
    fun `test tmvar swap`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tmvar, swap_tmvar, read_tmvar}

            let mvar = tmvar {init = 10}
            perform OptimisticSTM in do
                let old = swap_tmvar {var = mvar, val = 20}
                let new = read_tmvar {var = mvar}
                {old = old, new = new}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(10L, (result.fields["old"] as MezzeInt).value)
        assertEquals(20L, (result.fields["new"] as MezzeInt).value)
    }

    // =============================================================================
    // TQueue Tests - Unbounded STM Queue
    // =============================================================================

    @Test
    fun `test tqueue creation and write read`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, write_tqueue, read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 42}
                read_tqueue {q = q}
        """
        val result = eval(code) as MezzeInt
        assertEquals(42L, result.value)
    }

    @Test
    fun `test tqueue fifo order`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, write_tqueue, read_tqueue}

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
        assertEquals(1L, (result.fields["first"] as MezzeInt).value)
        assertEquals(2L, (result.fields["second"] as MezzeInt).value)
        assertEquals(3L, (result.fields["third"] as MezzeInt).value)
    }

    @Test
    fun `test tqueue is_empty true for new queue`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, is_empty_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                is_empty_tqueue {q = q}
        """
        val result = eval(code) as MezzeBool
        assertTrue(result.value)
    }

    @Test
    fun `test tqueue is_empty false after write`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, write_tqueue, is_empty_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 1}
                is_empty_tqueue {q = q}
        """
        val result = eval(code) as MezzeBool
        assertFalse(result.value)
    }

    @Test
    fun `test tqueue try_read from empty`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, try_read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                try_read_tqueue {q = q}
        """
        val result = eval(code) as MezzeVariant
        assertEquals("None", result.constructor)
    }

    @Test
    fun `test tqueue try_read from non-empty`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, write_tqueue, try_read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 99}
                try_read_tqueue {q = q}
        """
        val result = eval(code) as MezzeVariant
        assertEquals("Some", result.constructor)
        val inner = (result.value as MezzeRecord).fields["val"] as MezzeInt
        assertEquals(99L, inner.value)
    }

    @Test
    fun `test tqueue peek`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tqueue, write_tqueue, peek_tqueue, read_tqueue}

            let q = tqueue {}
            perform OptimisticSTM in do
                write_tqueue {q = q, val = 42}
                let peeked = peek_tqueue {q = q}
                let read1 = read_tqueue {q = q}
                {peeked = peeked, read1 = read1}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(42L, (result.fields["peeked"] as MezzeInt).value)
        assertEquals(42L, (result.fields["read1"] as MezzeInt).value)
    }

    // =============================================================================
    // TBQueue Tests - Bounded STM Queue
    // =============================================================================

    @Test
    fun `test tbqueue creation and write read`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, read_tbqueue}

            let q = tbqueue {capacity = 10}
            perform OptimisticSTM in do
                write_tbqueue {q = q, val = 42}
                read_tbqueue {q = q}
        """
        val result = eval(code) as MezzeInt
        assertEquals(42L, result.value)
    }

    @Test
    fun `test tbqueue fifo order`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, read_tbqueue}

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
        assertEquals(1L, (result.fields["first"] as MezzeInt).value)
        assertEquals(2L, (result.fields["second"] as MezzeInt).value)
        assertEquals(3L, (result.fields["third"] as MezzeInt).value)
    }

    @Test
    fun `test tbqueue try_write when full`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, try_write_tbqueue, size_tbqueue}

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
        assertFalse((result.fields["success"] as MezzeBool).value)
        assertEquals(2L, (result.fields["size"] as MezzeInt).value)
    }

    @Test
    fun `test tbqueue is_full`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, is_full_tbqueue}

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
        assertFalse((result.fields["before"] as MezzeBool).value)
        assertFalse((result.fields["after1"] as MezzeBool).value)
        assertTrue((result.fields["after2"] as MezzeBool).value)
    }

    @Test
    fun `test tbqueue size_tbqueue`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tbqueue, write_tbqueue, read_tbqueue, size_tbqueue}

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
        assertEquals(0L, (result.fields["s0"] as MezzeInt).value)
        assertEquals(1L, (result.fields["s1"] as MezzeInt).value)
        assertEquals(2L, (result.fields["s2"] as MezzeInt).value)
        assertEquals(1L, (result.fields["s3"] as MezzeInt).value)
    }

    @Test
    fun `test tbqueue capacity_tbqueue`() {
        val code = """
            use std::stm::{STM, OptimisticSTM, tbqueue, capacity_tbqueue}

            let q = tbqueue {capacity = 50}
            perform OptimisticSTM in do
                capacity_tbqueue {q = q}
        """
        val result = eval(code) as MezzeInt
        assertEquals(50L, result.value)
    }
}
