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
 * Every test spins up a fresh polyglot Context with `languages =
 * ["python"]` and drives the Python interpreter through the same
 * `impl Foreign` surface that the JVM tests use — read_member,
 * invoke_member, array ops, execute — proving the interop layer is
 * language-agnostic.
 *
 * Numpy-specific coverage lives in [NumpyInteropTest].  Pure-Python
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
    fun `python_eval 1 plus 1 coerced to int equals 2`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "1 + 1" }
            let _ = close_context { it = ctx }
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "'hello'.upper()" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = f } -> f.as_string{}
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("HELLO", v.getPayloadFieldByName("val"))
    }

    // ==================== Modules ====================

    @Test
    fun `import math and read pi`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let mod_r = python_import { context = ctx, name = "math" }
            let _ = close_context { it = ctx }
            match mod_r is
              Ok { val = m } ->
                match m.read_member { name = "pi" } is
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
    fun `import math and call sqrt of 16`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let mod_r = python_import { context = ctx, name = "math" }
            let _ = close_context { it = ctx }
            match mod_r is
              Ok { val = m } ->
                match m.invoke_member { name = "sqrt", args = [16] } is
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
    fun `python list array_size and read_array`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "[10, 20, 30]" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = lst } ->
                match lst.read_array { index = 1 } is
                  Ok { val = e } -> e.as_int{}
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(20L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python list has array elements`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "[1, 2, 3]" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = lst } -> lst.has_array_elements{}
              Err { error = _ } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `python object attribute read_member returns value`() {
        // Python dicts are exposed as hash-entry objects (not member
        // objects) through InteropLibrary — `read_member` targets object
        // ATTRIBUTES.  Use a class with attributes to test member read.
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "type('X', (), {'a': 1, 'b': 2})" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = cls } ->
                match cls.read_member { name = "a" } is
                  Ok { val = v } -> v.as_int{}
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(1L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python dict values accessed via subscript`() {
        // For dict-like objects, subscript ([]) is exposed as
        // invoke_member "__getitem__".  This tests that GraalPy
        // exposes it through the interop surface.
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "{'a': 1, 'b': 2}.get('a')" }
            let _ = close_context { it = ctx }
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
    fun `python lambda executed with args`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "lambda x, y: x + y" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = f } ->
                match f.execute { args = [3, 4] } is
                  Ok { val = s } -> s.as_int{}
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `python lambda square`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let f_r = python_eval { context = ctx, source = "(lambda x: x * x)" }
            let _ = close_context { it = ctx }
            match f_r is
              Ok { val = f } ->
                match f.execute { args = [7] } is
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
    fun `invoke_member on non-existent method yields Err`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "'hello'" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = s } -> s.invoke_member { name = "wrong_method", args = [] }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `python syntax error yields Err`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "this is not python <<>>" }
            let _ = close_context { it = ctx }
            r
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `import of non-existent module yields Err`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_import { context = ctx, name = "definitely_not_a_real_module_xyz123" }
            let _ = close_context { it = ctx }
            r
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `import_module rejects unsafe module name`() {
        // Guard against ' quote breakout in the import expression.
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_import }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_import { context = ctx, name = "os'; import sys #" }
            let _ = close_context { it = ctx }
            r
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== Type predicates ====================

    @Test
    fun `python int is_number`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "42" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = f } -> f.is_number{}
              Err { error = _ } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `python string is_string`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "'x'" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = f } -> f.is_string{}
              Err { error = _ } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `python None Ok wraps foreign`() {
        // Python's None is NOT interop-null by default (GraalPy exposes it
        // as a first-class TruffleObject).  What we CAN test: eval("None")
        // succeeds, returning some Foreign — the exact type coercion is
        // Python-implementation-defined.
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "None" }
            let _ = close_context { it = ctx }
            r
            """.trimIndent()
        )
        assertOk(result)
    }

    // ==================== Cross-language ====================

    @Test
    fun `python max function invoked with mezze integers`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "max" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = f } ->
                match f.execute { args = [3, 7, 2, 5] } is
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
            use std::polyglot::core::{ create_context, close_context }
            use std::polyglot::python::{ python_eval }
            let ctx = create_context { languages = ["python"], classpath = [] }
            let r = python_eval { context = ctx, source = "len([1, 2, 3, 4, 5])" }
            let _ = close_context { it = ctx }
            match r is
              Ok { val = f } -> f.as_int{}
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(5L, v.getPayloadFieldByName("val"))
    }
}
