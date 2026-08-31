package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Locks the `SizedMut` ability: the effectful sibling of `Sized` that gives the
 * mutable containers (`MutSeq`, `MutString`, `MutBytes`) uniform `.size {}` and
 * `.is_empty {}` dot-dispatch under the `<Mut>` effect.
 *
 * These are runtime assertions, not just type-check: the interesting risk is
 * dispatch, not typing. `size`/`is_empty` are op names shared with the pure
 * `Sized` ability, so the checker must pick `SizedMut` for a mutable receiver
 * (the type that implements it), NOT `Sized` by import/load order. A regression
 * would either fail to compile ("MutSeq does not implement Sized") or dispatch
 * to the wrong impl and compute a wrong value.
 */
class SizedMutAbilityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `MutSeq size dispatches to SizedMut`() {
        val result = eval(
            """
            use std::effects::mutation::{ LocalMut, seq_to_mut }
            perform LocalMut in seq_to_mut { seq = [1, 2, 3, 4] }.size {}
            """
        )
        assertEquals(4L, result)
    }

    @Test
    fun `MutSeq is_empty is true for empty and false otherwise`() {
        assertEquals(
            true,
            eval(
                """
                use std::effects::mutation::{ LocalMut, seq_to_mut }
                let xs: [Int] = []
                perform LocalMut in seq_to_mut { seq = xs }.is_empty {}
                """
            )
        )
        assertEquals(
            false,
            eval(
                """
                use std::effects::mutation::{ LocalMut, seq_to_mut }
                perform LocalMut in seq_to_mut { seq = [1] }.is_empty {}
                """
            )
        )
    }

    @Test
    fun `MutString size dispatches to SizedMut`() {
        val result = eval(
            """
            use std::effects::mutation::{ LocalMut, string_to_mut }
            perform LocalMut in string_to_mut { s = "hello" }.size {}
            """
        )
        assertEquals(5L, result)
    }

    @Test
    fun `MutString is_empty`() {
        assertEquals(
            true,
            eval(
                """
                use std::effects::mutation::{ LocalMut, string_to_mut }
                perform LocalMut in string_to_mut { s = "" }.is_empty {}
                """
            )
        )
    }

    @Test
    fun `MutBytes size dispatches to SizedMut`() {
        val result = eval(
            """
            use std::effects::mutation::{ LocalMut, bytes_to_mut }
            use std::collections::bytes::{ from_str }
            perform LocalMut in bytes_to_mut { bytes = from_str { s = "abc" } }.size {}
            """
        )
        assertEquals(3L, result)
    }

    @Test
    fun `MutBytes is_empty is false for non-empty`() {
        val result = eval(
            """
            use std::effects::mutation::{ LocalMut, bytes_to_mut }
            use std::collections::bytes::{ from_str }
            perform LocalMut in bytes_to_mut { bytes = from_str { s = "abc" } }.is_empty {}
            """
        )
        assertEquals(false, result)
    }
}
