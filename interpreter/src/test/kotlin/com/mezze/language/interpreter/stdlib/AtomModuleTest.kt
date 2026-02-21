package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/concurrent/atom module.
 * Tests Clojure-style atomic references for thread-safe state management.
 */
class AtomModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === Basic Operations ===

    @Test
    fun `test atom creates atomic reference`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref}

            let counter = atom {value = 42}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test atom with record value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref}

            let state = atom {value = {count = 0, name = "test"}}
            let current = deref {atom = state}
            current.count
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test reset changes value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, reset}

            let counter = atom {value = 0}
            let _ = reset {atom = counter, value = 100}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(100L, (result as MezzeInt).value)
    }

    @Test
    fun `test reset returns new value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, reset}

            let counter = atom {value = 0}
            reset {atom = counter, value = 42}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === Swap Operations ===

    @Test
    fun `test swap applies function`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap}

            let counter = atom {value = 10}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(11L, (result as MezzeInt).value)
    }

    @Test
    fun `test swap returns new value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, swap}

            let counter = atom {value = 5}
            swap {atom = counter, f = {current} -> current * 2}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(10L, (result as MezzeInt).value)
    }

    @Test
    fun `test multiple swaps`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap}

            let counter = atom {value = 0}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            let _ = swap {atom = counter, f = {current} -> current + 1}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test swap with record`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap}

            let state = atom {value = {count = 0}}
            let _ = swap {atom = state, f = {current} -> {count = current.count + 5}}
            let final = deref {atom = state}
            final.count
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5L, (result as MezzeInt).value)
    }

    // === swap_with ===

    @Test
    fun `test swap_with passes argument`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap_with}

            let counter = atom {value = 10}
            let _ = swap_with {atom = counter, f = {current, arg} -> current + arg, arg = 5}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value)
    }

    @Test
    fun `test swap_with with string argument`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap_with}

            let state = atom {value = "Hello"}
            let _ = swap_with {atom = state, f = {current, arg} -> current, arg = " World"}
            deref {atom = state}
        """)
        assertTrue(result is MezzeString)
        assertEquals("Hello", (result as MezzeString).value)
    }

    // === compare_and_set ===

    @Test
    fun `test compare_and_set succeeds when value matches`() {
        val result = eval("""
            use std::concurrent::atom::{atom, compare_and_set}

            let counter = atom {value = 0}
            compare_and_set {atom = counter, expected = 0, new = 1}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test compare_and_set fails when value differs`() {
        val result = eval("""
            use std::concurrent::atom::{atom, compare_and_set}

            let counter = atom {value = 5}
            compare_and_set {atom = counter, expected = 0, new = 1}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test compare_and_set updates value on success`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, compare_and_set}

            let counter = atom {value = 0}
            let _ = compare_and_set {atom = counter, expected = 0, new = 42}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test compare_and_set does not update on failure`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, compare_and_set}

            let counter = atom {value = 5}
            let _ = compare_and_set {atom = counter, expected = 0, new = 42}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5L, (result as MezzeInt).value)
    }

    // === get_and_swap ===

    @Test
    fun `test get_and_swap returns old and new values`() {
        val result = eval("""
            use std::concurrent::atom::{atom, get_and_swap}

            let counter = atom {value = 10}
            let result = get_and_swap {atom = counter, f = {current} -> current + 5}
            result.old
        """)
        assertTrue(result is MezzeInt)
        assertEquals(10L, (result as MezzeInt).value)
    }

    @Test
    fun `test get_and_swap new value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, get_and_swap}

            let counter = atom {value = 10}
            let result = get_and_swap {atom = counter, f = {current} -> current + 5}
            result.new
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value)
    }

    // === Validators ===

    @Test
    fun `test atom_validated creates validated atom`() {
        val result = eval("""
            use std::concurrent::atom::{atom_validated, deref}

            let positive = atom_validated {value = 5, validator = {val} -> val >= 0}
            deref {atom = positive}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5L, (result as MezzeInt).value)
    }

    @Test
    fun `test set_validator adds validator`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap, set_validator}

            let counter = atom {value = 10}
            let _ = set_validator {atom = counter, validator = {val} -> val >= 0}
            let _ = swap {atom = counter, f = {current} -> current + 5}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(15L, (result as MezzeInt).value)
    }

    // === Edge Cases ===

    @Test
    fun `test atom with boolean value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, swap}

            let flag = atom {value = False}
            let _ = swap {atom = flag, f = {current} -> True}
            deref {atom = flag}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test atom with list value`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref}

            let items = atom {value = [1, 2, 3]}
            let current = deref {atom = items}
            __native_arraylist_length {xs = current}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test atom identity check`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, reset}

            let a = atom {value = 1}
            let _ = reset {atom = a, value = 2}
            let _ = reset {atom = a, value = 3}
            deref {atom = a}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    // === Watches ===

    @Test
    fun `test add_watch and remove_watch`() {
        val result = eval("""
            use std::concurrent::atom::{atom, deref, reset, add_watch, remove_watch}

            let counter = atom {value = 0}
            let _ = add_watch {atom = counter, key = "test", f = {key, old, new} -> {}}
            let _ = reset {atom = counter, value = 10}
            let _ = remove_watch {atom = counter, key = "test"}
            deref {atom = counter}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(10L, (result as MezzeInt).value)
    }
}
