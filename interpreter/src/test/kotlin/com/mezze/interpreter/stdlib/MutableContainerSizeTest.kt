package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `.size {}` and `.is_empty {}` on the mutable containers (`MutSeq`, `MutString`,
 * `MutBytes`), under `<Mut>`.
 *
 * They are INHERENT methods of those types. They were an ability, `SizedMut`, that
 * shared `size`/`is_empty` with the pure `Sized`, and dispatch had to tell the two
 * apart by receiver. An operation name now means one ability in a scope, so the
 * mutable containers own the methods directly (a mutable container cannot be
 * `Sized`: reading its size performs `Mut`).
 *
 * Runtime assertions, because the risk is dispatch, not typing.
 */
class MutableContainerSizeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `MutSeq size is the inherent method`() {
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
    fun `MutString size is the inherent method`() {
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
    fun `MutBytes size is the inherent method`() {
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
