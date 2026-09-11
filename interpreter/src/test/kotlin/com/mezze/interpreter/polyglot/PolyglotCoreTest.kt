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
 * Every JVM foreign flows through `JvmForeign` — the language-tagged
 * newtype defined in std::polyglot::jvm. Assertions use tag-based
 * checks (isOk/isErr, getPayloadFieldByName) rather than constructor
 * name lookups: variant tags are canonical across the codebase, but
 * name-registration in TagRegistry.getGlobal() is lazy and depends
 * on parse-order.
 *
 * MezzeTestHelper builds a Context with allowAllAccess(true), so JVM
 * host class lookup is enabled out of the box. There is no user-facing
 * Context lifecycle — `impl Jvm for JvmHost` has zero fields and the
 * Context is ambient.
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
    fun `JvmForeign from_int round-trips through as_int`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 42 }
            f.as_int{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_float round-trips through as_float`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_float }
            let f = from_float { val = 3.14 }
            f.as_float{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3.14, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_str round-trips through as_str`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str }
            let f = from_str { val = "hello" }
            f.as_str{}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("hello", v.getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_bool round-trips through as_bool`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_bool }
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
            use std::polyglot::jvm::{ null_val }
            let f = null_val {}
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `non-null JvmForeign is_null returns False`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 7 }
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(false, result)
    }

    // ==================== Error variants ====================

    @Test
    fun `as_int on string yields Err`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str }
            let f = from_str { val = "not-a-number" }
            f.as_int{}
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `get_member on non-record returns Err`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 5 }
            f.field { name = "does_not_exist" }
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== JVM host access ====================

    @Test
    fun `jvm class lookup for ArrayList returns Ok JvmForeign`() {
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
    fun `jvm ArrayList new returns instance`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } -> klass.new { args = [] }
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
                match klass.new { args = [] } is
                  Ok { val = list } -> list.method { name = "size", args = [] }
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
            use std::polyglot::jvm::{ from_int }
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } ->
                    match list.method { name = "add", args = [from_int { val = 100 }] } is
                      Ok { val = _ } ->
                        list.method { name = "size", args = [] }
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
            use std::polyglot::jvm::{ from_int }
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } ->
                    match list.method { name = "add", args = [from_int { val = 42 }] } is
                      Ok { val = _ } -> list.array_at { index = 0 }
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
    fun `jvm ArrayList array_size returns Ok 0 for empty list`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
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
                match klass.new { args = [] } is
                  Ok { val = list } -> list.method { name = "no_such_method_xyz", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `jvm StringBuilder length via method`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str }
            match jvm::class { name = "java.lang.StringBuilder" } is
              Ok { val = klass } ->
                match klass.new { args = [from_str { val = "abc" }] } is
                  Ok { val = sb } -> sb.method { name = "length", args = [] }
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val lenForeign = v.getPayloadFieldByName("val") as MezzeForeign
        val lib = com.oracle.truffle.api.interop.InteropLibrary.getUncached(lenForeign.delegate)
        assertEquals(3L, lib.asLong(lenForeign.delegate))
    }

    // ==================== has_member ====================

    @Test
    fun `has_member true for known JVM instance method`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.has_member { name = "size" }
                  Err { error = _ } -> False
              Err { error = _ } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    // ==================== Jvm effect handler ====================

    @Test
    fun `Jvm effect handler routes class lookup`() {
        // The Jvm effect + JvmHost handler routes through the same
        // native the free `jvm::class` fn uses — the handler carries
        // no config, host-access is Context-level.
        val result = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost }
            perform JvmHost in Jvm.class { name = "java.util.ArrayList" }
            """.trimIndent()
        )
        assertOk(result)
    }

    // ==================== type_name ====================

    @Test
    fun `type_name returns a non-empty string`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 5 }
            f.type_name{}
            """.trimIndent()
        )
        assertTrue(result is String, "Expected String, got $result")
        assertTrue((result as String).isNotEmpty())
    }

    // ==================== JVM class_of / instanceof ====================

    @Test
    fun `class_of on ArrayList instance returns a Class Foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            match jvm::class { name = "java.util.ArrayList" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = list } -> list.class_of{}
                  Err { error } -> Err { error = error }
              Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val cls = v.getPayloadFieldByName("val")
        assertTrue(cls is MezzeForeign)
    }

    // NOTE: `.instanceof{}` inherent method is defined but calling it
    // requires a Java `Class` Foreign, not a host-symbol Foreign. The
    // host-symbol returned by `jvm::class` proxies static access to
    // the type; getting the runtime `Class` value requires an extra
    // step (e.g. reading `.class` off a live instance) that the
    // current InteropLibrary surface doesn't expose uniformly across
    // GraalVM versions. Left as a documented affordance for callers
    // that already have a Class Foreign to pass in.

    // ==================== JVM end-to-end: HashMap put/get ====================

    @Test
    fun `jvm HashMap put and get with Mezze string works end-to-end`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str, from_int }
            match jvm::class { name = "java.util.HashMap" } is
              Ok { val = klass } ->
                match klass.new { args = [] } is
                  Ok { val = map } ->
                    match map.method {
                      name = "put",
                      args = [from_str { val = "k" }, from_int { val = 99 }]
                    } is
                      Ok { val = _ } ->
                        map.method { name = "get", args = [from_str { val = "k" }] }
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
