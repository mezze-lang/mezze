package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Locks the DX naming-consistency renames so a future refactor can't silently
 * revert them:
 *   - option/result `get_or_else` and map `get_or_default` -> `get_or` (param `def`)
 *   - list `of_seq` -> `from_seq`
 *   - mutation `len` -> `size`
 * Runtime assertions (not just type-check) — a name that resolves but dispatches
 * wrong would still fail here.
 */
class DxNameConsistencyTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `option get_or returns present value`() {
        val result = eval(
            """
            use std::option::{ get_or }
            Some { val = 7 }.get_or { def = 0 }
            """
        )
        assertEquals(7L, result)
    }

    @Test
    fun `option get_or falls back to def on None`() {
        val result = eval(
            """
            use std::option::{ get_or }
            let opt: Option Int = None
            opt.get_or { def = 42 }
            """
        )
        assertEquals(42L, result)
    }

    @Test
    fun `result get_or returns Ok value`() {
        val result = eval(
            """
            use std::result::{ get_or }
            let res: Result Int Str = Ok { val = 5 }
            res.get_or { def = 0 }
            """
        )
        assertEquals(5L, result)
    }

    @Test
    fun `map get_or with def`() {
        val result = eval(
            """
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 42 }
            map::get_or { m = m, k = "a", def = 0 }
            """
        )
        assertEquals(42L, result)
    }

    @Test
    fun `map get_or falls back to def on missing key`() {
        val result = eval(
            """
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 42 }
            map::get_or { m = m, k = "z", def = 99 }
            """
        )
        assertEquals(99L, result)
    }

    @Test
    fun `list from_seq builds a list`() {
        val result = eval(
            """
            use std::collections::list::{ from_seq, size }
            size { xs = from_seq { xs = [10, 20, 30] } }
            """
        )
        assertEquals(3L, result)
    }

    @Test
    fun `mutation size renamed from len`() {
        val result = eval(
            """
            use std::effects::mutation::{ LocalMut, seq_to_mut, size }
            perform LocalMut in size { seq = seq_to_mut { seq = [1, 2, 3, 4] } }
            """
        )
        assertEquals(4L, result)
    }
}
