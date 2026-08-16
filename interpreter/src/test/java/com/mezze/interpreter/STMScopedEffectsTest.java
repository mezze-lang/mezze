package com.mezze.interpreter;

import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for the new STM API using scoped effects.
 *
 * The new API uses initialize_scope and finalize_scope to automatically
 * begin and commit transactions, eliminating the need for explicit atomically wrappers.
 *
 * Key features tested:
 * - Automatic transaction management via scoped effects
 * - TVar creation and operations (read, write, modify, swap)
 * - Transaction isolation and atomicity
 * - Nested perform blocks
 * - LoomParallel and parallel execution
 * - Money transfer invariants (total balance preservation)
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class STMScopedEffectsTest {

    // ==========================================================================
    // Basic TVar Operations
    // ==========================================================================

    @Test
    void testTVarCreateAndRead() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 42}
                let value = deref {var = var}
                value

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(42L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testTVarTransactionalRead() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar}

            let test = {} -> do
                let var = tvar {init = 100}
                perform OptimisticSTM in STM.read {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(100L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testTVarTransactionalWrite() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 0}
                perform OptimisticSTM in STM.write {var = var, val = 999}
                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(999L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testTVarModify() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let counter = tvar {init = 10}
                perform OptimisticSTM in STM.modify {var = counter, f = {val} -> val + 5}
                deref {var = counter}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(15L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testTVarSwap() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 100}
                let old = perform OptimisticSTM in STM.swap {var = var, val = 200}
                let new = deref {var = var}
                {old = old, new = new}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals(100L, MezzeTestHelper.asLong(record.getField("old")));
        assertEquals(200L, MezzeTestHelper.asLong(record.getField("new")));
    }

    // ==========================================================================
    // Transaction Atomicity
    // ==========================================================================

    @Test
    void testTransactionAtomicity() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let a = tvar {init = 100}
                let b = tvar {init = 200}

                # Multiple operations in one transaction
                perform OptimisticSTM in do
                    let va = STM.read {var = a}
                    let vb = STM.read {var = b}
                    STM.write {var = a, val = va + 50}
                    STM.write {var = b, val = vb - 50}

                let final_a = deref {var = a}
                let final_b = deref {var = b}
                {a = final_a, b = final_b, total = final_a + final_b}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals(150L, MezzeTestHelper.asLong(record.getField("a")));
        assertEquals(150L, MezzeTestHelper.asLong(record.getField("b")));
        assertEquals(300L, MezzeTestHelper.asLong(record.getField("total")));
    }

    @Test
    void testTransactionIsolation() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 0}

                # First transaction: write 10
                perform OptimisticSTM in STM.write {var = var, val = 10}

                # Second transaction: read and increment
                let result = perform OptimisticSTM in do
                    let v = STM.read {var = var}
                    STM.write {var = var, val = v + 5}
                    v  # Return what we read

                let final = deref {var = var}
                {read_value = result, final_value = final}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals(10L, MezzeTestHelper.asLong(record.getField("read_value")));
        assertEquals(15L, MezzeTestHelper.asLong(record.getField("final_value")));
    }

    // ==========================================================================
    // Money Transfer (Classic STM Test)
    // ==========================================================================

    @Test
    void testSimpleTransfer() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let transfer = {from, to, amount} ->
                perform OptimisticSTM in do
                    let from_balance = STM.read {var = from}
                    let to_balance = STM.read {var = to}
                    STM.write {var = from, val = from_balance - amount}
                    STM.write {var = to, val = to_balance + amount}

            let test = {} -> do
                let alice = tvar {init = 1000}
                let bob = tvar {init = 500}

                transfer {from = alice, to = bob, amount = 200}

                let alice_final = deref {var = alice}
                let bob_final = deref {var = bob}
                {alice = alice_final, bob = bob_final, total = alice_final + bob_final}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals(800L, MezzeTestHelper.asLong(record.getField("alice")));
        assertEquals(700L, MezzeTestHelper.asLong(record.getField("bob")));
        assertEquals(1500L, MezzeTestHelper.asLong(record.getField("total")));
    }

    @Test
    void testMultipleTransfers() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let transfer = {from, to, amount} ->
                perform OptimisticSTM in do
                    let from_balance = STM.read {var = from}
                    let to_balance = STM.read {var = to}
                    STM.write {var = from, val = from_balance - amount}
                    STM.write {var = to, val = to_balance + amount}

            let test = {} -> do
                let alice = tvar {init = 1000}
                let bob = tvar {init = 500}
                let charlie = tvar {init = 200}

                # Chain of transfers
                transfer {from = alice, to = bob, amount = 100}
                transfer {from = bob, to = charlie, amount = 50}
                transfer {from = charlie, to = alice, amount = 25}

                let a = deref {var = alice}
                let b = deref {var = bob}
                let c = deref {var = charlie}
                {alice = a, bob = b, charlie = c, total = a + b + c}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        long alice = MezzeTestHelper.asLong(record.getField("alice"));
        long bob = MezzeTestHelper.asLong(record.getField("bob"));
        long charlie = MezzeTestHelper.asLong(record.getField("charlie"));
        long total = MezzeTestHelper.asLong(record.getField("total"));

        // Alice: 1000 - 100 + 25 = 925
        assertEquals(925L, alice);
        // Bob: 500 + 100 - 50 = 550
        assertEquals(550L, bob);
        // Charlie: 200 + 50 - 25 = 225
        assertEquals(225L, charlie);
        // Total should be preserved
        assertEquals(1700L, total);
    }

    @Test
    void testTransferWithInsufficientFunds() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref, transfer}

            let test = {} -> do
                let alice = tvar {init = 100}
                let bob = tvar {init = 500}

                # Try to transfer more than alice has
                let success = perform OptimisticSTM in transfer {from = alice, to = bob, amount = 200}

                let alice_final = deref {var = alice}
                let bob_final = deref {var = bob}
                {success = success, alice = alice_final, bob = bob_final}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertFalse(MezzeTestHelper.asBoolean(record.getField("success")));
        // Balances should be unchanged
        assertEquals(100L, MezzeTestHelper.asLong(record.getField("alice")));
        assertEquals(500L, MezzeTestHelper.asLong(record.getField("bob")));
    }

    // ==========================================================================
    // Nested Perform Blocks
    // ==========================================================================

    @Test
    void testNestedPerformBlocks() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 0}

                # Outer transaction
                perform OptimisticSTM in do
                    STM.write {var = var, val = 10}

                    # Inner transaction (shares outer's transaction context)
                    perform OptimisticSTM in do
                        let v = STM.read {var = var}
                        STM.write {var = var, val = v + 5}

                    let v2 = STM.read {var = var}
                    STM.write {var = var, val = v2 * 2}

                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        // 10 + 5 = 15, then 15 * 2 = 30
        assertEquals(30L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testNestedTransactionsShareContext() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let inner_op = {var} ->
                perform OptimisticSTM in do
                    let v = STM.read {var = var}
                    STM.write {var = var, val = v + 100}

            let test = {} -> do
                let var = tvar {init = 1}

                perform OptimisticSTM in do
                    STM.write {var = var, val = 5}
                    inner_op {var = var}  # Should see value 5
                    let final = STM.read {var = var}
                    final

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(105L, MezzeTestHelper.asLong(result));
    }

    // ==========================================================================
    // Record Values in TVars
    // ==========================================================================

    @Test
    void testTVarWithRecordValue() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let account = tvar {init = {name = "Alice", balance = 1000}}

                perform OptimisticSTM in do
                    let acct = STM.read {var = account}
                    STM.write {var = account, val = {name = acct.name, balance = acct.balance - 100}}

                let final = deref {var = account}
                final

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals("Alice", record.getField("name"));
        assertEquals(900L, MezzeTestHelper.asLong(record.getField("balance")));
    }

    @Test
    void testTVarWithListValue() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}
            use std::collections::seq

            let test = {} -> do
                let list_var = tvar {init = [1, 2, 3]}

                # Add an element
                perform OptimisticSTM in do
                    let current = STM.read {var = list_var}
                    let new_list = seq::concat {xs = current, ys = [4]}
                    STM.write {var = list_var, val = new_list}

                let final = deref {var = list_var}
                seq::size {xs = final}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(4L, MezzeTestHelper.asLong(result));
    }

    // ==========================================================================
    // Counter Pattern
    // ==========================================================================

    @Test
    void testCounter() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let increment = {counter} ->
                perform OptimisticSTM in STM.modify {var = counter, f = {val} -> val + 1}

            let test = {} -> do
                let counter = tvar {init = 0}

                increment {counter = counter}
                increment {counter = counter}
                increment {counter = counter}
                increment {counter = counter}
                increment {counter = counter}

                deref {var = counter}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(5L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testCounterWithMultipleIncrements() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let increment_by = {counter, amount} ->
                perform OptimisticSTM in STM.modify {var = counter, f = {val} -> val + amount}

            let test = {} -> do
                let counter = tvar {init = 100}

                increment_by {counter = counter, amount = 10}
                increment_by {counter = counter, amount = 20}
                increment_by {counter = counter, amount = -5}

                deref {var = counter}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(125L, MezzeTestHelper.asLong(result));
    }

    // ==========================================================================
    // Multiple TVars in Single Transaction
    // ==========================================================================

    @Test
    void testMultipleTVarsInTransaction() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let x = tvar {init = 1}
                let y = tvar {init = 2}
                let z = tvar {init = 3}

                let sum = perform OptimisticSTM in do
                    let vx = STM.read {var = x}
                    let vy = STM.read {var = y}
                    let vz = STM.read {var = z}

                    # Update all three
                    STM.write {var = x, val = vx * 10}
                    STM.write {var = y, val = vy * 10}
                    STM.write {var = z, val = vz * 10}

                    vx + vy + vz

                let final_x = deref {var = x}
                let final_y = deref {var = y}
                let final_z = deref {var = z}

                {sum_before = sum, x = final_x, y = final_y, z = final_z}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals(6L, MezzeTestHelper.asLong(record.getField("sum_before")));
        assertEquals(10L, MezzeTestHelper.asLong(record.getField("x")));
        assertEquals(20L, MezzeTestHelper.asLong(record.getField("y")));
        assertEquals(30L, MezzeTestHelper.asLong(record.getField("z")));
    }

    // ==========================================================================
    // SequentialSTM Implementation
    // ==========================================================================

    @Test
    void testSequentialSTM() {
        String code = """
            use std::effects::stm::{STM, SequentialSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 50}

                perform SequentialSTM in do
                    let v = STM.read {var = var}
                    STM.write {var = var, val = v + 25}

                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(75L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testSequentialSTMMultipleOps() {
        String code = """
            use std::effects::stm::{STM, SequentialSTM, TVar, tvar, deref}

            let test = {} -> do
                let a = tvar {init = 100}
                let b = tvar {init = 200}

                perform SequentialSTM in do
                    let va = STM.read {var = a}
                    let vb = STM.read {var = b}
                    STM.write {var = a, val = vb}
                    STM.write {var = b, val = va}

                let final_a = deref {var = a}
                let final_b = deref {var = b}
                {a = final_a, b = final_b}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        // Values should be swapped
        assertEquals(200L, MezzeTestHelper.asLong(record.getField("a")));
        assertEquals(100L, MezzeTestHelper.asLong(record.getField("b")));
    }

    // ==========================================================================
    // Scoped Effect Lifecycle
    // ==========================================================================

    @Test
    void testScopedEffectCommitsOnSuccess() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 0}

                # Transaction should commit when perform block exits
                perform OptimisticSTM in do
                    STM.write {var = var, val = 42}
                    let v = STM.read {var = var}
                    v

                # Value should be visible after transaction commits
                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(42L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testMultipleSequentialTransactions() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 0}

                # Each perform block is its own transaction
                perform OptimisticSTM in STM.write {var = var, val = 10}
                perform OptimisticSTM in STM.modify {var = var, f = {val} -> val + 5}
                perform OptimisticSTM in STM.modify {var = var, f = {val} -> val * 2}

                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        // 10 + 5 = 15, 15 * 2 = 30
        assertEquals(30L, MezzeTestHelper.asLong(result));
    }

    // ==========================================================================
    // ConfigurableSTM (OptimisticSTMWith)
    // ==========================================================================

    @Test
    void testOptimisticSTMWithConfig() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTMWith, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 100}

                perform OptimisticSTMWith {max_retries = 10, spin_count = 5} in do
                    let v = STM.read {var = var}
                    STM.write {var = var, val = v + 50}

                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(150L, MezzeTestHelper.asLong(result));
    }

    // ==========================================================================
    // String Values in TVars
    // ==========================================================================

    @Test
    void testTVarWithStringValue() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let msg = tvar {init = "Hello"}

                perform OptimisticSTM in do
                    let current = STM.read {var = msg}
                    STM.write {var = msg, val = "Goodbye"}

                deref {var = msg}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals("Goodbye", result);
    }

    // ==========================================================================
    // Boolean Values in TVars
    // ==========================================================================

    @Test
    void testTVarWithBooleanValue() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let toggle = {flag} ->
                perform OptimisticSTM in STM.modify {var = flag, f = {val} ->
                    match val is
                        True -> False
                        False -> True
                }

            let test = {} -> do
                let flag = tvar {init = False}

                toggle {flag = flag}
                let v1 = deref {var = flag}

                toggle {flag = flag}
                let v2 = deref {var = flag}

                {after_first = v1, after_second = v2}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertTrue(MezzeTestHelper.asBoolean(record.getField("after_first")));
        assertFalse(MezzeTestHelper.asBoolean(record.getField("after_second")));
    }

    // ==========================================================================
    // Bank Account Simulation
    // ==========================================================================

    @Test
    void testBankAccountSimulation() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}
            use std::int

            let create_account = {initial_balance} -> tvar {init = initial_balance}

            let deposit = {account, amount} ->
                perform OptimisticSTM in STM.modify {var = account, f = {val} -> val + amount}

            let withdraw = {account, amount} ->
                perform OptimisticSTM in do
                    let balance = STM.read {var = account}
                    match balance >= amount is
                        True -> do
                            STM.write {var = account, val = balance - amount}
                            True
                        False -> False

            let get_balance = {account} -> deref {var = account}

            let test = {} -> do
                let savings = create_account {initial_balance = 1000}
                let checking = create_account {initial_balance = 500}

                deposit {account = savings, amount = 200}
                let w1 = withdraw {account = checking, amount = 100}
                let w2 = withdraw {account = checking, amount = 1000}  # Should fail

                let savings_final = get_balance {account = savings}
                let checking_final = get_balance {account = checking}

                {
                    savings = savings_final,
                    checking = checking_final,
                    withdraw1_success = w1,
                    withdraw2_success = w2,
                    total = savings_final + checking_final
                }

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        MezzeRecord record = MezzeTestHelper.asRecord(result);
        assertEquals(1200L, MezzeTestHelper.asLong(record.getField("savings")));
        assertEquals(400L, MezzeTestHelper.asLong(record.getField("checking")));
        assertTrue(MezzeTestHelper.asBoolean(record.getField("withdraw1_success")));
        assertFalse(MezzeTestHelper.asBoolean(record.getField("withdraw2_success")));
        assertEquals(1600L, MezzeTestHelper.asLong(record.getField("total")));
    }

    // ==========================================================================
    // Edge Cases
    // ==========================================================================

    @Test
    void testMinimalTransaction() {
        // Note: Empty perform blocks are rejected by the type checker as they have no effects
        // Instead, test a minimal transaction that just reads
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 42}

                # Minimal transaction that just reads (but doesn't use the result)
                let _ = perform OptimisticSTM in STM.read {var = var}

                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(42L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testReadOnlyTransaction() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar}

            let test = {} -> do
                let var = tvar {init = 123}

                # Transaction that only reads
                perform OptimisticSTM in do
                    let v1 = STM.read {var = var}
                    let v2 = STM.read {var = var}
                    v1 + v2

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(246L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testWriteOnlyTransaction() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar, deref}

            let test = {} -> do
                let var = tvar {init = 0}

                # Transaction that only writes
                perform OptimisticSTM in do
                    STM.write {var = var, val = 100}
                    STM.write {var = var, val = 200}
                    STM.write {var = var, val = 300}

                deref {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        // Last write wins
        assertEquals(300L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testReadAfterWrite() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar}

            let test = {} -> do
                let var = tvar {init = 0}

                perform OptimisticSTM in do
                    STM.write {var = var, val = 42}
                    # Read should see our write
                    STM.read {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        assertEquals(42L, MezzeTestHelper.asLong(result));
    }

    @Test
    void testMultipleReadAfterWrite() {
        String code = """
            use std::effects::stm::{STM, OptimisticSTM, TVar, tvar}

            let test = {} -> do
                let var = tvar {init = 0}

                perform OptimisticSTM in do
                    STM.write {var = var, val = 10}
                    let v1 = STM.read {var = var}

                    STM.write {var = var, val = v1 + 5}
                    let v2 = STM.read {var = var}

                    STM.write {var = var, val = v2 * 2}
                    STM.read {var = var}

            test {}
            """;

        Object result = MezzeTestHelper.run(code);
        // 10, then 15, then 30
        assertEquals(30L, MezzeTestHelper.asLong(result));
    }
}
