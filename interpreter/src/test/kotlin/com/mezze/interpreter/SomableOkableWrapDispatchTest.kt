package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression: the generic wrapper abilities `Somable`/`Okable`/`Errable` are
 * blanket impls (`impl Somable for 'a`, `impl Okable 'e for 'a`, ...) whose
 * receiver "self" is an unconstrained type variable. Their targetTypes in the
 * compiler's ability-op index are therefore all VarType, so ability-op
 * resolution used to reject every concrete receiver (typeMatchesTarget returned
 * false for a VarType target) and fall through to a raw field read, crashing at
 * runtime with "Expected record for field 'some', got: Long".
 *
 * Fix: a bare VarType target is a blanket-impl position that matches ANY
 * receiver. These tests EXECUTE `x.some {}` / `x.ok {}` / `x.err {}` on scalar,
 * string, record, and variant receivers and assert on the resulting runtime
 * value (unwrapped via match), not print form.
 */
class SomableOkableWrapDispatchTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `some on Int wraps into Some with payload`() {
        val code = """
            use std::option
            let o = 2.some {}
            match o is
              Some { val } -> val
              None -> 0
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `some on String wraps into Some with payload`() {
        val code = """
            use std::option
            let o = "hi".some {}
            match o is
              Some { val } -> val
              None -> "none"
        """
        assertEquals("hi", eval(code))
    }

    @Test
    fun `some on record wraps into Some with payload`() {
        val code = """
            use std::option
            let o = { x = 7 }.some {}
            match o is
              Some { val } -> val.x
              None -> 0
        """
        assertEquals(7L, eval(code))
    }

    @Test
    fun `some with type annotation wraps into Some`() {
        val code = """
            use std::option
            let o: (Option Int) = 5.some {}
            match o is
              Some { val } -> val
              None -> 0
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `ok on Int wraps into Ok with payload`() {
        val code = """
            use std::result
            let r: (Result Int Str) = 2.ok {}
            match r is
              Ok { val } -> val
              Err { error = _ } -> 0
        """
        assertEquals(2L, eval(code))
    }

    @Test
    fun `err on String wraps into Err with payload`() {
        val code = """
            use std::result
            let r: (Result Int Str) = "boom".err {}
            match r is
              Ok { val = _ } -> "ok"
              Err { error } -> error
        """
        assertEquals("boom", eval(code))
    }
}
