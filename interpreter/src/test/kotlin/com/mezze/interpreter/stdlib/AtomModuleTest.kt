package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/concurrent/atom module.
 * Tests Clojure-style atomic references for thread-safe state management.
 * Migrated from V1 to V2 with unboxed values.
 */
class AtomModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test atom creates atomic reference`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref}

            let counter = atom {val = 42}
            deref {atom = counter}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test atom with record value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref}

            let state = atom {val = {count = 0, name = "test"}}
            let current = deref {atom = state}
            current.count
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test reset changes value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, reset}

            let counter = atom {val = 0}
            let _ = reset {atom = counter, val = 100}
            deref {atom = counter}
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test reset returns new value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, reset}

            let counter = atom {val = 0}
            reset {atom = counter, val = 42}
        """)
        assertEquals(42L, result)
    }

    // === Swap Operations ===

    @Test
    fun `test swap applies function`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap}

            let counter = atom {val = 10}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            deref {atom = counter}
        """)
        assertEquals(11L, result)
    }

    @Test
    fun `test swap returns new value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, swap}

            let counter = atom {val = 5}
            swap {atom = counter, f = {current} -> current * 2}
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test multiple swaps`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap}

            let counter = atom {val = 0}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            deref {atom = counter}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test swap with record`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap}

            let state = atom {val = {count = 0}}
            let _ = swap {atom = state, f = {current} -> {count = current.count + 5}}
            let final = deref {atom = state}
            final.count
        """)
        assertEquals(5L, result)
    }

    // === swap_with ===

    @Test
    fun `test swap_with passes argument`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap_with}

            let counter = atom {val = 10}
            let _ = swap_with {atom = counter, f = {current, arg} -> current + arg, arg = 5}
            deref {atom = counter}
        """)
        assertEquals(15L, result)
    }

    @Test
    fun `test swap_with with string argument`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap_with}

            let state = atom {val = "Hello"}
            let _ = swap_with {atom = state, f = {current, arg} -> current, arg = " World"}
            deref {atom = state}
        """)
        assertEquals("Hello", result)
    }

    // === compare_and_set ===

    @Test
    fun `test compare_and_set succeeds when value matches`() {
        val result = eval("""
            use std::effects::async::atom::{atom, compare_and_set}

            let counter = atom {val = 0}
            compare_and_set {atom = counter, expected = 0, new = 1}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test compare_and_set fails when value differs`() {
        val result = eval("""
            use std::effects::async::atom::{atom, compare_and_set}

            let counter = atom {val = 5}
            compare_and_set {atom = counter, expected = 0, new = 1}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test compare_and_set updates value on success`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, compare_and_set}

            let counter = atom {val = 0}
            let _ = compare_and_set {atom = counter, expected = 0, new = 42}
            deref {atom = counter}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test compare_and_set does not update on failure`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, compare_and_set}

            let counter = atom {val = 5}
            let _ = compare_and_set {atom = counter, expected = 0, new = 42}
            deref {atom = counter}
        """)
        assertEquals(5L, result)
    }

    // === get_and_swap ===

    @Test
    fun `test get_and_swap returns old and new values`() {
        val result = eval("""
            use std::effects::async::atom::{atom, get_and_swap}

            let counter = atom {val = 10}
            let result = get_and_swap {atom = counter, f = {current} -> current + 5}
            result.old
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test get_and_swap new value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, get_and_swap}

            let counter = atom {val = 10}
            let result = get_and_swap {atom = counter, f = {current} -> current + 5}
            result.new
        """)
        assertEquals(15L, result)
    }

    // === Validators ===

    @Test
    fun `test atom_validated creates validated atom`() {
        val result = eval("""
            use std::effects::async::atom::{atom_validated, deref}

            let positive = atom_validated {val = 5, validator = {val} -> val >= 0}
            deref {atom = positive}
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test set_validator adds validator`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap, set_validator}

            let counter = atom {val = 10}
            let _ = set_validator {atom = counter, validator = {val} -> val >= 0}
            let _ = swap {atom = counter, f = {current} -> current + 5}
            deref {atom = counter}
        """)
        assertEquals(15L, result)
    }

    // === Edge Cases ===

    @Test
    fun `test atom with boolean value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, swap}

            let flag = atom {val = False}
            let _ = swap {atom = flag, f = {current} -> True}
            deref {atom = flag}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test atom with list value`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref}
            use std::collections::seq

            let items = atom {val = [1, 2, 3]}
            let current = deref {atom = items}
            seq::size {xs = current}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test atom identity check`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, reset}

            let a = atom {val = 1}
            let _ = reset {atom = a, val = 2}
            let _ = reset {atom = a, val = 3}
            deref {atom = a}
        """)
        assertEquals(3L, result)
    }

    // === Watches ===

    @Test
    fun `test add_watch and remove_watch`() {
        val result = eval("""
            use std::effects::async::atom::{atom, deref, reset, add_watch, remove_watch}

            let counter = atom {val = 0}
            let _ = add_watch {atom = counter, key = "test", f = {key, old, new} -> {}}
            let _ = reset {atom = counter, val = 10}
            let _ = remove_watch {atom = counter, key = "test"}
            deref {atom = counter}
        """)
        assertEquals(10L, result)
    }
}
