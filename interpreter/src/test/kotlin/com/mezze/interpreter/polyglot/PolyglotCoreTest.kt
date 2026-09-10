package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeForeign
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for std::polyglot::core + std::polyglot::jvm.
 *
 * Covers the Phase 1 surface — primitive round-trips, JVM host class
 * lookup + instantiate + method invoke, member / array / execute
 * interop, Context lifecycle, and error variants.  Every test runs
 * against a fresh Context that MezzeTestHelper builds with
 * allowAllAccess(true), so JVM host lookup is enabled.
 *
 * Assertions use tag-based checks (isOk/isErr, getPayloadFieldByName)
 * rather than constructorName lookups — variant tags are canonical
 * across the codebase, but name-registration in TagRegistry.getGlobal()
 * is lazy and depends on parse-order.
 */
class PolyglotCoreTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertOk(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}")
        return v
    }

    private fun assertErr(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isErr, "Expected Err variant, got tag=${v.tag}")
        return v
    }

    // ==================== Primitive round-trips ====================

    @Test
    fun `Foreign from_int round-trips through as_int`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 42 }
            f.as_int{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `Foreign from_float round-trips through as_float`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_float }
            let f = from_float { val = 3.14 }
            f.as_float{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3.14, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `Foreign from_str round-trips through as_string`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_str }
            let f = from_str { val = "hello" }
            f.as_string{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("hello", v.getPayloadFieldByName("val"))
    }

    @Test
    fun `Foreign from_bool round-trips through as_bool`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_bool }
            let f = from_bool { val = True }
            f.as_bool{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(true, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `null_val is_null returns True`() {
        val result = eval(
            """
            use std::polyglot::core::{ null_val }
            let f = null_val {}
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `non-null Foreign is_null returns False`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 7 }
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(false, result)
    }

    // ==================== Type predicates ====================

    @Test
    fun `is_string true for from_str`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_str }
            let f = from_str { val = "hi" }
            f.is_string{}
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `is_number true for from_int`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 5 }
            f.is_number{}
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `fits_in_int true for from_int`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 5 }
            f.fits_in_int{}
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `is_number false for from_str`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_str }
            let f = from_str { val = "not-a-number" }
            f.is_number{}
            """.trimIndent()
        )
        assertEquals(false, result)
    }

    // ==================== Error variants ====================

    @Test
    fun `as_int on string yields Err`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_str }
            let f = from_str { val = "not-a-number" }
            f.as_int{}
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `read_member on non-record returns Err`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 5 }
            f.read_member { name = "does_not_exist" }
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== JVM host access ====================

    @Test
    fun `jvm class lookup for ArrayList returns Ok Foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
            """.trimIndent()
        )
        val v = assertOk(result)
        val foreign = v.getPayloadFieldByName("val")
        assertTrue(foreign is MezzeForeign, "Expected MezzeForeign, got ${foreign?.javaClass}")
    }

    @Test
    fun `jvm class lookup for nonexistent class returns Err`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "com.does.not.Exist_XYZ" }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `jvm ArrayList instantiate returns instance`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } -> klass.instantiate { args = [] }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val instance = v.getPayloadFieldByName("val")
        assertTrue(instance is MezzeForeign)
    }

    @Test
    fun `jvm ArrayList empty size is 0`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } -> list.invoke_member { name = "size", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val sizeAsForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val delegate = sizeAsForeign.delegate
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(delegate)
        assertTrue(lib.isNumber(delegate))
        assertEquals(0L, lib.asLong(delegate))
    }

    @Test
    fun `jvm ArrayList add and size returns 1`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::core::{ from_int }
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } ->
                    match list.invoke_member { name = "add", args = [from_int { val = 100 }] } is
                      Ok { val = _ } ->
                        list.invoke_member { name = "size", args = [] }
                      Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val sizeAsForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(sizeAsForeign.delegate)
        assertEquals(1L, lib.asLong(sizeAsForeign.delegate))
    }

    @Test
    fun `jvm ArrayList add then read back element`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::core::{ from_int }
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } ->
                    match list.invoke_member { name = "add", args = [from_int { val = 42 }] } is
                      Ok { val = _ } -> list.read_array { index = 0 }
                      Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val elt = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(elt.delegate)
        assertEquals(42L, lib.asLong(elt.delegate))
    }

    @Test
    fun `jvm ArrayList has_array_elements is true`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } -> list.has_array_elements{}
                  Err { error } -> False
              Err { error } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `jvm ArrayList array_size returns Ok 0 for empty list`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } -> list.array_size{}
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(0L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm invoke unknown member returns Err`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } -> list.invoke_member { name = "no_such_method_xyz", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `jvm StringBuilder length via invoke_member`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::core::{ from_str }
            match jvm::class { name = "java.lang.StringBuilder" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [from_str { val = "abc" }] } is
                  Ok { val = sb } -> sb.invoke_member { name = "length", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val lenForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(lenForeign.delegate)
        assertEquals(3L, lib.asLong(lenForeign.delegate))
    }

    // ==================== has_members / has_member ====================

    @Test
    fun `has_members true for JVM instance`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = list } -> list.has_members{}
                  Err { error } -> False
              Err { error } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    // ==================== Context lifecycle ====================

    @Test
    fun `context create close round-trip`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context }
            let ctx = create_context { languages = [], classpath = [] }
            close_context { it = ctx }
            42
            """.trimIndent()
        )
        assertEquals(42L, result)
    }

    @Test
    fun `context eval after close returns Err`() {
        val result = eval(
            """
            use std::polyglot::core::{ create_context, close_context, context_eval }
            let ctx = create_context { languages = [], classpath = [] }
            close_context { it = ctx }
            context_eval { it = ctx, language = "python", code = "1 + 1" }
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== type_name ====================

    @Test
    fun `type_name returns a non-empty string`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 5 }
            f.type_name{}
            """.trimIndent()
        )
        assertTrue(result is String, "Expected String, got $result")
        assertTrue((result as String).isNotEmpty())
    }

    // ==================== is_executable / is_instantiable ====================

    @Test
    fun `is_executable false for int Foreign`() {
        val result = eval(
            """
            use std::polyglot::core::{ from_int }
            let f = from_int { val = 5 }
            f.is_executable{}
            """.trimIndent()
        )
        assertEquals(false, result)
    }

    @Test
    fun `is_instantiable true for jvm class`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } -> klass.is_instantiable{}
              Err { error } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    // ==================== JVM end-to-end: HashMap put/get ====================

    @Test
    fun `jvm HashMap put and get with Mezze string works end-to-end`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::core::{ from_str, from_int }
            match jvm::class { name = "java.util.HashMap" } is
              Ok { val = klass } ->
                match klass.instantiate { args = [] } is
                  Ok { val = map } ->
                    match map.invoke_member {
                      name = "put",
                      args = [from_str { val = "k" }, from_int { val = 99 }]
                    } is
                      Ok { val = _ } ->
                        map.invoke_member { name = "get", args = [from_str { val = "k" }] }
                      Err { error } -> Err { error = error }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val got = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(got.delegate)
        assertEquals(99L, lib.asLong(got.delegate))
    }
}
