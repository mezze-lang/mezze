package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
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

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
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
        val result = eval(code) as FunkInt
        assertEquals(42L, result.value)
    }

    @Test
    fun `test tvar with string value`() {
        val code = """
            use std::stm::{tvar, deref}

            let name = tvar {init = "Alice"}
            deref {var = name}
        """
        val result = eval(code) as FunkString
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = FunkTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", FunkRecord(emptyMap()), typeCheckResult.monomorphicInstances) as FunkRecord
        assertEquals(100L, (result.fields["old"] as FunkInt).value)
        assertEquals(200L, (result.fields["new"] as FunkInt).value)
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkRecord
        assertEquals(800L, (result.fields["alice"] as FunkInt).value)
        assertEquals(700L, (result.fields["bob"] as FunkInt).value)
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = FunkTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", FunkRecord(emptyMap()), typeCheckResult.monomorphicInstances) as FunkRecord
        assertEquals(true, (result.fields["success"] as FunkBool).value)
        assertEquals(300L, (result.fields["from"] as FunkInt).value)
        assertEquals(300L, (result.fields["to"] as FunkInt).value)
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
        val result = FunkTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", FunkRecord(emptyMap()), typeCheckResult.monomorphicInstances) as FunkRecord
        assertEquals(false, (result.fields["success"] as FunkBool).value)
        // Balances should remain unchanged
        assertEquals(50L, (result.fields["from"] as FunkInt).value)
        assertEquals(100L, (result.fields["to"] as FunkInt).value)
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
        val result = eval(code) as FunkArrayList
        val values = result.toList().map { (it as FunkInt).value }
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
        val result = eval(code) as FunkInt
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
        val result = eval(code) as FunkInt
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
        val result = FunkTestHelper.executeAndCallFunction(typeCheckResult.program, "getResult", FunkRecord(emptyMap()), typeCheckResult.monomorphicInstances) as FunkRecord
        assertEquals(6L, (result.fields["old_sum"] as FunkInt).value)
        assertEquals(9L, (result.fields["new_sum"] as FunkInt).value)
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
        val result = eval(code) as FunkInt
        assertEquals(1L, result.value)
    }
}
