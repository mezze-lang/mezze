package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for seq operations including pattern matching and map.
 * Migrated from V1 to V2 with unboxed values.
 */
class SimpleSeqTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test simple array match`() {
        val code = """
            let test = match [1, 2, 3] is
                [] -> 0
                [x] -> x
                [x, y] -> x + y
                [x, y, z] -> x + y + z
                [x, y, z, ...rest] -> x + y + z

            test
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    @Test
    fun `test recursive map using stdlib`() {
        val code = """
            use std::collections::seq

            let double = {x} -> x * 2
            let result = seq::map {f = double, xs = [1, 2, 3]}
            seq::length {xs = result}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test recursive map values`() {
        val code = """
            use std::collections::seq

            let double = {x} -> x * 2
            let result = seq::map {f = double, xs = [1, 2, 3]}
            match seq::get {xs = result, i = 0} is
                Some { val } -> val
                None -> -1
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `test recursive map second value`() {
        val code = """
            use std::collections::seq

            let double = {x} -> x * 2
            let result = seq::map {f = double, xs = [1, 2, 3]}
            match seq::get {xs = result, i = 1} is
                Some { val } -> val
                None -> -1
        """
        assertEquals(4L, eval(code))
    }

    @Test
    fun `test recursive map third value`() {
        val code = """
            use std::collections::seq

            let double = {x} -> x * 2
            let result = seq::map {f = double, xs = [1, 2, 3]}
            match seq::get {xs = result, i = 2} is
                Some { val } -> val
                None -> -1
        """
        assertEquals(6L, eval(code))
    }
}
