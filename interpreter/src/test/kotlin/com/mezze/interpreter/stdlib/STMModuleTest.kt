package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/stm module.
 * Tests Software Transactional Memory using SequentialSTM handler for testing.
 * Migrated from V1 to V2 with unboxed values.
 */
class STMModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === TVar Creation Tests ===

    @Test
    fun `test tvar creation with int`() {
        val result = eval("""
            use std::effects::stm::{tvar, deref}

            let v = tvar {init = 42}
            deref {var = v}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test tvar creation with string`() {
        val result = eval("""
            use std::effects::stm::{tvar, deref}

            let v = tvar {init = "hello"}
            deref {var = v}
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `test tvar creation with record`() {
        val result = eval("""
            use std::effects::stm::{tvar, deref}

            let v = tvar {init = {name = "Alice", age = 30}}
            let r = deref {var = v}
            r.name
        """)
        assertEquals("Alice", result)
    }

    // === STM read Tests ===

    @Test
    fun `test STM read inside transaction`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar}

            let v = tvar {init = 100}
            perform SequentialSTM in do
                STM.read {var = v}
        """)
        assertEquals(100L, result)
    }

    // === STM write Tests ===

    @Test
    fun `test STM write updates value`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref}

            let v = tvar {init = 0}
            perform SequentialSTM in do
                STM.write {var = v, val = 42}
            deref {var = v}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test STM write visible in same transaction`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar}

            let v = tvar {init = 0}
            perform SequentialSTM in do
                STM.write {var = v, val = 42}
                STM.read {var = v}
        """)
        assertEquals(42L, result)
    }

    // === STM modify Tests ===

    @Test
    fun `test STM modify updates value with function`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref}

            let v = tvar {init = 10}
            perform SequentialSTM in do
                STM.modify {var = v, f = {val} -> val * 2}
            deref {var = v}
        """)
        assertEquals(20L, result)
    }

    @Test
    fun `test STM modify with increment`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref}

            let counter = tvar {init = 0}
            perform SequentialSTM in do
                STM.modify {var = counter, f = {val} -> val + 1}
                STM.modify {var = counter, f = {val} -> val + 1}
                STM.modify {var = counter, f = {val} -> val + 1}
            deref {var = counter}
        """)
        assertEquals(3L, result)
    }

    // === STM swap Tests ===

    @Test
    fun `test STM swap returns old value`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar}

            let v = tvar {init = 100}
            perform SequentialSTM in do
                STM.swap {var = v, val = 200}
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test STM swap updates to new value`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref}

            let v = tvar {init = 100}
            perform SequentialSTM in do
                let _ = STM.swap {var = v, val = 200}
                {}
            deref {var = v}
        """)
        assertEquals(200L, result)
    }

    // === STM check Tests ===

    @Test
    fun `test STM check with true continues`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar}

            let v = tvar {init = 10}
            perform SequentialSTM in do
                STM.check {cond = True}
                STM.read {var = v}
        """)
        assertEquals(10L, result)
    }

    // === transfer Helper Tests ===

    @Test
    fun `test transfer success with sufficient funds`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref, transfer}

            perform SequentialSTM in do
                let alice = tvar {init = 1000}
                let bob = tvar {init = 500}
                transfer {from = alice, to = bob, amount = 300}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test transfer updates balances correctly`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref, transfer}

            let alice = tvar {init = 1000}
            let bob = tvar {init = 500}
            let _ = perform SequentialSTM in transfer {from = alice, to = bob, amount = 300}
            deref {var = alice}
        """)
        assertEquals(700L, result)
    }

    @Test
    fun `test transfer to recipient`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref, transfer}

            let alice = tvar {init = 1000}
            let bob = tvar {init = 500}
            let _ = perform SequentialSTM in transfer {from = alice, to = bob, amount = 300}
            deref {var = bob}
        """)
        assertEquals(800L, result)
    }

    @Test
    fun `test transfer fails with insufficient funds`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, transfer}

            let alice = tvar {init = 100}
            let bob = tvar {init = 500}
            perform SequentialSTM in transfer {from = alice, to = bob, amount = 200}
        """)
        assertEquals(false, result)
    }

    // === atomically Helper Tests ===

    @Test
    fun `test atomically runs with OptimisticSTM`() {
        val result = eval("""
            use std::effects::stm::{atomically, STM, tvar}

            let v = tvar {init = 5}
            atomically {f = {} -> STM.read {var = v}}
        """)
        assertEquals(5L, result)
    }

    // === TMVar Tests ===

    @Test
    fun `test tmvar creation with initial value`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar, read_tmvar}

            let mv = tmvar {init = 42}
            perform SequentialSTM in read_tmvar {var = mv}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test tmvar_empty is empty`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar_empty, is_empty_tmvar}

            let mv = tmvar_empty {}
            perform SequentialSTM in is_empty_tmvar {var = mv}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test put_tmvar and take_tmvar roundtrip`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar_empty, put_tmvar, take_tmvar}

            let mv = tmvar_empty {}
            perform SequentialSTM in do
                put_tmvar {var = mv, val = 100}
                take_tmvar {var = mv}
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test take_tmvar empties the TMVar`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar, take_tmvar, is_empty_tmvar}

            let mv = tmvar {init = 42}
            perform SequentialSTM in do
                let _ = take_tmvar {var = mv}
                is_empty_tmvar {var = mv}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_take_tmvar returns Some when full`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar, try_take_tmvar}

            let mv = tmvar {init = 42}
            perform SequentialSTM in do
                match try_take_tmvar {var = mv} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test try_take_tmvar returns None when empty`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar_empty, try_take_tmvar}

            let mv = tmvar_empty {}
            perform SequentialSTM in do
                match try_take_tmvar {var = mv} is
                    Some {val} -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_put_tmvar returns true when empty`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar_empty, try_put_tmvar}

            let mv = tmvar_empty {}
            perform SequentialSTM in try_put_tmvar {var = mv, val = 42}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_put_tmvar returns false when full`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar, try_put_tmvar}

            let mv = tmvar {init = 42}
            perform SequentialSTM in try_put_tmvar {var = mv, val = 100}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test swap_tmvar`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tmvar, swap_tmvar}

            let mv = tmvar {init = 100}
            perform SequentialSTM in swap_tmvar {var = mv, val = 200}
        """)
        assertEquals(100L, result)
    }

    // === TQueue Tests ===

    @Test
    fun `test tqueue creation and write`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue, write_tqueue, is_empty_tqueue}

            let q = tqueue {}
            perform SequentialSTM in do
                write_tqueue {q = q, val = 1}
                is_empty_tqueue {q = q}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test tqueue write and read`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue, write_tqueue, read_tqueue}

            let q = tqueue {}
            perform SequentialSTM in do
                write_tqueue {q = q, val = 42}
                read_tqueue {q = q}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test tqueue FIFO order`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue, write_tqueue, read_tqueue}

            let q = tqueue {}
            perform SequentialSTM in do
                write_tqueue {q = q, val = 1}
                write_tqueue {q = q, val = 2}
                write_tqueue {q = q, val = 3}
                let first = read_tqueue {q = q}
                first
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test tqueue try_read on empty`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue, try_read_tqueue}

            let q = tqueue {}
            perform SequentialSTM in do
                match try_read_tqueue {q = q} is
                    Some {val} -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test tqueue peek_tqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue, write_tqueue, peek_tqueue, is_empty_tqueue}

            let q = tqueue {}
            perform SequentialSTM in do
                write_tqueue {q = q, val = 42}
                let peeked = peek_tqueue {q = q}
                let stillNotEmpty = is_empty_tqueue {q = q}
                peeked
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test tqueue size_tqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue, write_tqueue, size_tqueue}

            let q = tqueue {}
            perform SequentialSTM in do
                write_tqueue {q = q, val = 1}
                write_tqueue {q = q, val = 2}
                write_tqueue {q = q, val = 3}
                size_tqueue {q = q}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test tqueue_from_list`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tqueue_from_list, read_tqueue}
            use std::collections::list

            let items = list::from_seq {xs = [10, 20, 30]}
            let q = tqueue_from_list {items = items}
            perform SequentialSTM in read_tqueue {q = q}
        """)
        assertEquals(10L, result)
    }

    // === TBQueue Tests ===

    @Test
    fun `test tbqueue creation`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, capacity_tbqueue}

            let q = tbqueue {capacity = 10}
            perform SequentialSTM in capacity_tbqueue {q = q}
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test tbqueue write and read`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, write_tbqueue, read_tbqueue}

            let q = tbqueue {capacity = 10}
            perform SequentialSTM in do
                write_tbqueue {q = q, val = 42}
                read_tbqueue {q = q}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test tbqueue size_tbqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, write_tbqueue, size_tbqueue}

            let q = tbqueue {capacity = 10}
            perform SequentialSTM in do
                write_tbqueue {q = q, val = 1}
                write_tbqueue {q = q, val = 2}
                size_tbqueue {q = q}
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test tbqueue try_write when full returns false`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, write_tbqueue, try_write_tbqueue}

            let q = tbqueue {capacity = 2}
            perform SequentialSTM in do
                write_tbqueue {q = q, val = 1}
                write_tbqueue {q = q, val = 2}
                try_write_tbqueue {q = q, val = 3}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test tbqueue is_full_tbqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, write_tbqueue, is_full_tbqueue}

            let q = tbqueue {capacity = 2}
            perform SequentialSTM in do
                write_tbqueue {q = q, val = 1}
                write_tbqueue {q = q, val = 2}
                is_full_tbqueue {q = q}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test tbqueue is_empty_tbqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, is_empty_tbqueue}

            let q = tbqueue {capacity = 10}
            perform SequentialSTM in is_empty_tbqueue {q = q}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test tbqueue peek_tbqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, write_tbqueue, peek_tbqueue}

            let q = tbqueue {capacity = 10}
            perform SequentialSTM in do
                write_tbqueue {q = q, val = 42}
                peek_tbqueue {q = q}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test tbqueue try_read_tbqueue`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tbqueue, write_tbqueue, try_read_tbqueue}

            let q = tbqueue {capacity = 10}
            perform SequentialSTM in do
                write_tbqueue {q = q, val = 42}
                match try_read_tbqueue {q = q} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(42L, result)
    }

    // === write_all Tests ===

    @Test
    fun `test write_all updates multiple TVars`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref, write_all}

            let a = tvar {init = 0}
            let b = tvar {init = 0}
            let c = tvar {init = 0}
            perform SequentialSTM in do
                write_all {pairs = [
                    {var = a, val = 10},
                    {var = b, val = 20},
                    {var = c, val = 30}
                ]}
            deref {var = a} + deref {var = b} + deref {var = c}
        """)
        assertEquals(60L, result)
    }

    @Test
    fun `test write_all with empty list`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref, write_all}

            let a = tvar {init = 42}
            perform SequentialSTM in do
                write_all {pairs = []}
            deref {var = a}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test write_all with single element`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, deref, write_all}

            let a = tvar {init = 0}
            perform SequentialSTM in do
                write_all {pairs = [{var = a, val = 99}]}
            deref {var = a}
        """)
        assertEquals(99L, result)
    }

    // === read_all Tests ===

    @Test
    fun `test read_all reads multiple TVars`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, read_all}
            use std::collections::seq

            let a = tvar {init = 1}
            let b = tvar {init = 2}
            let c = tvar {init = 3}
            perform SequentialSTM in do
                let values = read_all {vars = [a, b, c]}
                seq::size {xs = values}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test read_all returns correct values`() {
        val result = eval("""
            use std::effects::stm::{STM, SequentialSTM, tvar, read_all}
            use std::collections::seq

            let a = tvar {init = 10}
            let b = tvar {init = 20}
            perform SequentialSTM in do
                let values = read_all {vars = [a, b]}
                match seq::get {xs = values, i = 0} is
                    Some {val} -> val
                    None -> 0
        """)
        assertEquals(10L, result)
    }
}
