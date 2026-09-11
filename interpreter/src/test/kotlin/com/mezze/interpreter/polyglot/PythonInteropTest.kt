package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for std::polyglot::python.
 *
 * Every test performs the `GraalPy` handler for the `Python` effect
 * — `Python.eval` / `Python.import` drive the interpreter through
 * the same `impl PyForeign` surface (member / array / execute / attr /
 * item / iter / len) the JVM tests use for `JvmForeign`, proving the
 * interop layer is language-agnostic while keeping per-language type
 * safety at compile time.
 *
 * Numpy-specific coverage lives in [NumpyInteropTest]. Pure-Python
 * (math, str methods, list, dict) round-trips exercise the interop
 * layer without any Python-package resolution.
 */
class PythonInteropTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertOk(result: Any?): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result?.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}, payload=${v.payload}")
        return v
    }

    private fun assertErr(result: Any?): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result?.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isErr, "Expected Err variant, got tag=${v.tag}, payload=${v.payload}")
        return v
    }

    // ==================== Smoke ====================

    @Test
    fun `python eval 1 plus 1 coerced to int equals 2`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "1 + 1" }
              match r is
                Ok { val = f } -> f.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    // ==================== Strings ====================

    @Test
    fun `python string upper via eval`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "'hello'.upper()" }
              match r is
                Ok { val = f } -> f.as_str{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("HELLO", v.getPayloadFieldByName("val"))
    }

    // ==================== Modules ====================

    @Test
    fun `import math and read pi via attr`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let mod_r = Python.import { module = "math" }
              match mod_r is
                Ok { val = m } ->
                  match m.attr { name = "pi" } is
                    Ok { val = p } -> p.as_float{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val pi = v.getPayloadFieldByName("val") as Double
        assertTrue(pi > 3.14 && pi < 3.15, "expected ~pi, got $pi")
    }

    @Test
    fun `import math and call sqrt of 16 via method`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_int }
            perform GraalPy in do
              let mod_r = Python.import { module = "math" }
              match mod_r is
                Ok { val = m } ->
                  match m.method { name = "sqrt", args = [from_int { val = 16 }] } is
                    Ok { val = r } -> r.as_float{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(4.0, v.getPayloadFieldByName("val"))
    }

    // ==================== Collections ====================

    @Test
    fun `python list array_size and array_at`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "[10, 20, 30]" }
              match r is
                Ok { val = lst } ->
                  match lst.array_at { index = 1 } is
                    Ok { val = e } -> e.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(20L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python list set_item writes then reads back`() {
        // `lst[1] = 99` then read `lst[1]` — round-trip on a mutable
        // Python list through the writeArrayElement InteropLibrary
        // message.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_int }
            perform GraalPy in do
              let a_r = Python.eval { source = "[10, 20, 30]" }
              match a_r is
                Ok { val = lst } ->
                  match lst.set_item { i = 1, val = from_int { val = 99 } } is
                    Ok { val = _ } ->
                      match lst.array_at { index = 1 } is
                        Ok { val = e } -> e.as_int{}
                        Err { error } -> Err { error = error }
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(99L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python list set_item out of bounds yields Err`() {
        // Python raises IndexError on out-of-range list assignment;
        // GraalPy surfaces that as a PolyglotException / InteropException
        // which translateException converts to an InteropError variant.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_int }
            perform GraalPy in do
              let a_r = Python.eval { source = "[1, 2, 3]" }
              match a_r is
                Ok { val = lst } ->
                  lst.set_item { i = 99, val = from_int { val = 42 } }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `python list len via inherent method`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "[1, 2, 3]" }
              match r is
                Ok { val = lst } -> lst.len{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python object attribute attr returns value`() {
        // Python dicts are exposed as hash-entry objects (not member
        // objects) through InteropLibrary — `attr` targets object
        // ATTRIBUTES.  Use a class with attributes to test.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "type('X', (), {'a': 1, 'b': 2})" }
              match r is
                Ok { val = cls } ->
                  match cls.attr { name = "a" } is
                    Ok { val = v } -> v.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(1L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python dict values accessed via method get`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_str }
            perform GraalPy in do
              let r = Python.eval { source = "{'a': 1, 'b': 2}.get('a')" }
              match r is
                Ok { val = v } -> v.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(1L, v.getPayloadFieldByName("val"))
    }

    // ==================== Functions ====================

    @Test
    fun `python lambda call with args`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_int }
            perform GraalPy in do
              let r = Python.eval { source = "lambda x, y: x + y" }
              match r is
                Ok { val = f } ->
                  match f.call { args = [from_int { val = 3 }, from_int { val = 4 }] } is
                    Ok { val = s } -> s.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python lambda square via call`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_int }
            perform GraalPy in do
              let f_r = Python.eval { source = "(lambda x: x * x)" }
              match f_r is
                Ok { val = f } ->
                  match f.call { args = [from_int { val = 7 }] } is
                    Ok { val = s } -> s.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(49L, v.getPayloadFieldByName("val"))
    }

    // ==================== Errors ====================

    @Test
    fun `method on non-existent method yields Err`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "'hello'" }
              match r is
                Ok { val = s } -> s.method { name = "wrong_method", args = [] }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `python syntax error yields Err`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in Python.eval { source = "this is not python <<>>" }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `import of non-existent module yields Err`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in Python.import { module = "definitely_not_a_real_module_xyz123" }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `import rejects unsafe module name`() {
        // Guard against ' quote breakout in the import expression.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in Python.import { module = "os'; import sys #" }
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== Type predicates ====================

    @Test
    fun `python int as_int works`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "42" }
              match r is
                Ok { val = f } -> f.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python string as_str works`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "'x'" }
              match r is
                Ok { val = f } -> f.as_str{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("x", v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python None Ok wraps foreign`() {
        // Python's None is NOT interop-null by default (GraalPy exposes
        // it as a first-class TruffleObject). What we CAN test: eval("None")
        // succeeds, returning some Foreign — the exact type coercion is
        // Python-implementation-defined.
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in Python.eval { source = "None" }
            """.trimIndent()
        )
        assertOk(result)
    }

    // ==================== Cross-language ====================

    @Test
    fun `python max function called with mezze integers`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy, from_int }
            perform GraalPy in do
              let r = Python.eval { source = "max" }
              match r is
                Ok { val = f } ->
                  match f.call {
                    args = [
                      from_int { val = 3 },
                      from_int { val = 7 },
                      from_int { val = 2 },
                      from_int { val = 5 },
                    ],
                  } is
                    Ok { val = m } -> m.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python list len via builtins`() {
        val result = eval(
            """
            use std::polyglot::python::{ Python, GraalPy }
            perform GraalPy in do
              let r = Python.eval { source = "len([1, 2, 3, 4, 5])" }
              match r is
                Ok { val = f } -> f.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(5L, v.getPayloadFieldByName("val"))
    }
}
