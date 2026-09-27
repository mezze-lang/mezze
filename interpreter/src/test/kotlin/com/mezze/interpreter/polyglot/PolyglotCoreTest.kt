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
 * Integration tests for std::polyglot::core + std::polyglot::jvm under the
 * ERROR-CARRYING-FOREIGN model.
 *
 * A `JvmForeign` is a "try": either a live guest handle or a carried
 * `InteropError`. Value verbs (`get`/`new`/`method`/`at`/…) return a
 * `JvmForeign` and short-circuit an errored one, so chains flow with no `Result`
 * between steps. A `Result` appears only at a CROSSING — a coercion
 * (`int`/`float`/`str`/`bool`), `is_null`, `type_name`, `len` (Int), `to_seq`
 * (seq), `has` (Bool). So:
 *   - to check a value, cross it and match the `Result`;
 *   - to check an intermediate foreign is live/errored, use assertLive/assertErrored.
 *
 * MezzeTestHelper builds a Context with allowAllAccess(true), so JVM host class
 * lookup is enabled out of the box.
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

    /** A live (non-errored) foreign — the value verbs' success shape. */
    private fun assertLive(result: Any): MezzeForeign {
        assertTrue(result is MezzeForeign, "Expected MezzeForeign, got $result (${result.javaClass})")
        val f = result as MezzeForeign
        assertFalse(f.isErrored(), "Expected a LIVE foreign, but it carries an error: ${f.carriedError}")
        return f
    }

    /** An errored foreign — a value verb that failed but hasn't been crossed yet. */
    private fun assertErrored(result: Any): MezzeForeign {
        assertTrue(result is MezzeForeign, "Expected MezzeForeign, got $result (${result.javaClass})")
        val f = result as MezzeForeign
        assertTrue(f.isErrored(), "Expected an ERRORED foreign, but it is live: ${f.delegate}")
        return f
    }

    // ==================== Primitive round-trips (constructors pure; coercions cross) ====================

    @Test
    fun `JvmForeign from_int round-trips through int`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 42 }
            f.int{}
            """.trimIndent()
        )
        assertEquals(42L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_float round-trips through float`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_float }
            let f = from_float { val = 3.14 }
            f.float{}
            """.trimIndent()
        )
        assertEquals(3.14, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_str round-trips through str`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str }
            let f = from_str { val = "hello" }
            f.str{}
            """.trimIndent()
        )
        assertEquals("hello", assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `JvmForeign from_bool round-trips through bool`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_bool }
            let f = from_bool { val = True }
            f.bool{}
            """.trimIndent()
        )
        assertEquals(true, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `null_val is_null crosses to Ok True`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ null_val }
            let f = null_val {}
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(true, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `non-null JvmForeign is_null crosses to Ok False`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            let f = from_int { val = 7 }
            f.is_null{}
            """.trimIndent()
        )
        assertEquals(false, assertOk(result).getPayloadFieldByName("val"))
    }

    // ==================== Errors: carried in the foreign, surfaced at the crossing ====================

    @Test
    fun `int on a string foreign crosses to Err`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str }
            from_str { val = "not-a-number" }.int{}
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `get of a missing member yields an errored foreign`() {
        // `.get{}` yields a JvmForeign that CARRIES the error — no Result yet.
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            from_int { val = 5 }.get { name = "does_not_exist" }
            """.trimIndent()
        )
        assertErrored(result)
    }

    @Test
    fun `an errored foreign short-circuits the next verb and surfaces at the coercion`() {
        // get(missing) -> errored foreign; .method{} passes it through untouched;
        // .int{} finally crosses it to Err. Proves short-circuit + deferred surface.
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            from_int { val = 5 }
              .get { name = "does_not_exist" }
              .method { name = "toString", args = [] }
              .int {}
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== JVM host access ====================

    @Test
    fun `jvm class lookup for ArrayList yields a live foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
            """.trimIndent()
        )
        assertLive(result)
    }

    @Test
    fun `jvm class lookup for a nonexistent class yields an errored foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "com.does.not.Exist_XYZ" }
            """.trimIndent()
        )
        assertErrored(result)
    }

    @Test
    fun `jvm ArrayList new yields a live instance foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }.new { args = [] }
            """.trimIndent()
        )
        assertLive(result)
    }

    @Test
    fun `jvm ArrayList empty size crosses to 0`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
              .new { args = [] }
              .method { name = "size", args = [] }
              .int {}
            """.trimIndent()
        )
        assertEquals(0L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm ArrayList add then size crosses to 1`() {
        // Same-object state (add then size on ONE list) → a function body, where
        // effectful lets are fine (only MODULE-top-level effectful lets are rejected).
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            let main = {} -> do
              let list = jvm::class { name = "java.util.ArrayList" }.new { args = [] }
              let _ = list.method { name = "add", args = [from_int { val = 100 }] }
              list.method { name = "size", args = [] }.int {}
            main {}
            """.trimIndent()
        )
        assertEquals(1L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm StringBuilder append chain flows with no Result between steps`() {
        // StringBuilder.append is fluent (returns the builder), so the whole thing
        // is one JvmForeign chain ending in the `.str{}` crossing.
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str }
            jvm::class { name = "java.lang.StringBuilder" }
              .new { args = [from_str { val = "a" }] }
              .method { name = "append", args = [from_str { val = "b" }] }
              .method { name = "append", args = [from_str { val = "c" }] }
              .method { name = "toString", args = [] }
              .str {}
            """.trimIndent()
        )
        assertEquals("abc", assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm ArrayList add then read back element crosses to the value`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            let main = {} -> do
              let list = jvm::class { name = "java.util.ArrayList" }.new { args = [] }
              let _ = list.method { name = "add", args = [from_int { val = 42 }] }
              list.at { index = 0 }.int {}
            main {}
            """.trimIndent()
        )
        assertEquals(42L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm ArrayList len crosses to Ok 0 for empty list`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }.new { args = [] }.len{}
            """.trimIndent()
        )
        assertEquals(0L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm invoke of an unknown member yields an errored foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
              .new { args = [] }
              .method { name = "no_such_method_xyz", args = [] }
            """.trimIndent()
        )
        assertErrored(result)
    }

    @Test
    fun `jvm StringBuilder length crosses to 3`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str }
            jvm::class { name = "java.lang.StringBuilder" }
              .new { args = [from_str { val = "abc" }] }
              .method { name = "length", args = [] }
              .int {}
            """.trimIndent()
        )
        assertEquals(3L, assertOk(result).getPayloadFieldByName("val"))
    }

    // ==================== has (crossing → Result Bool) ====================

    @Test
    fun `has for a known JVM instance method crosses to Ok True`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }.new { args = [] }.has { name = "size" }
            """.trimIndent()
        )
        assertEquals(true, assertOk(result).getPayloadFieldByName("val"))
    }

    // ==================== Jvm effect handler ====================

    @Test
    fun `Jvm effect handler routes class lookup to a live foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ Jvm, JvmHost }
            perform JvmHost in Jvm.class { name = "java.util.ArrayList" }
            """.trimIndent()
        )
        assertLive(result)
    }

    // ==================== type_name (crossing → Result Str) ====================

    @Test
    fun `type_name crosses to a non-empty string`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int }
            from_int { val = 5 }.type_name{}
            """.trimIndent()
        )
        val s = assertOk(result).getPayloadFieldByName("val")
        assertTrue(s is String && s.isNotEmpty(), "Expected a non-empty String, got $s")
    }

    @Test
    fun `class_of on an ArrayList instance yields a live Class foreign`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.ArrayList" }
              .new { args = [] }
              .method { name = "getClass", args = [] }
            """.trimIndent()
        )
        assertLive(result)
    }

    // ==================== JVM end-to-end: HashMap put/get ====================

    @Test
    fun `jvm HashMap put and get with a Mezze string works end-to-end`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_str, from_int }
            let main = {} -> do
              let map = jvm::class { name = "java.util.HashMap" }.new { args = [] }
              let _ = map.method { name = "put", args = [from_str { val = "k" }, from_int { val = 99 }] }
              map.method { name = "get", args = [from_str { val = "k" }] }.int {}
            main {}
            """.trimIndent()
        )
        assertEquals(99L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `a long UUID chain flows and crosses to a 36-char string`() {
        // randomUUID() -> toString() is a pure JvmForeign chain; .str{} crosses it.
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.util.UUID" }
              .method { name = "randomUUID", args = [] }
              .method { name = "toString", args = [] }
              .str {}
            """.trimIndent()
        )
        val s = assertOk(result).getPayloadFieldByName("val") as String
        assertEquals(36, s.length, "UUID string should be 36 chars, got '$s'")
    }

    // ==================== Guest containers ====================

    @Test
    fun `jvm_list len crosses to the element count`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int, jvm_list }
            jvm_list { elems = [from_int { val = 7 }, from_int { val = 9 }] }.len {}
            """.trimIndent()
        )
        assertEquals(2L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm_list elements come back in order`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int, jvm_list }
            jvm_list { elems = [from_int { val = 7 }, from_int { val = 9 }] }.at { index = 1 }.int {}
            """.trimIndent()
        )
        assertEquals(9L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `an empty jvm_list is a valid guest list, len crosses to 0`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ jvm_list }
            jvm_list { elems = [] }.len {}
            """.trimIndent()
        )
        assertEquals(0L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `reading past the end yields an errored foreign, not a crash`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_int, jvm_list }
            jvm_list { elems = [from_int { val = 7 }] }.at { index = 5 }
            """.trimIndent()
        )
        assertErrored(result)
    }

    @Test
    fun `a built list is accepted as a Java method argument`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ JvmHost, Jvm, from_str, jvm_list }
            let main = {} -> perform JvmHost in do
              let xs = jvm_list { elems = [from_str { val = "a" }, from_str { val = "b" }] }
              Jvm.class { name = "java.util.Arrays" }
                .method { name = "asList", args = [xs] }
                .method { name = "size", args = [] }
                .int {}
            main {}
            """.trimIndent()
        )
        assertEquals(2L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `jvm_dict type_name crosses to a string`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str, from_int, jvm_dict }
            jvm_dict {
              keys = [from_str { val = "a" }],
              vals = [from_int { val = 1 }],
            }.type_name {}
            """.trimIndent()
        )
        assertOk(result)
    }

    // ==================== size / to_seq ====================

    @Test
    fun `len unifies array length`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            let main = {} -> do
              let list = jvm::class { name = "java.util.ArrayList" }.new { args = [] }
              let _ = list.method { name = "add", args = [from_int { val = 7 }] }
              let _ = list.method { name = "add", args = [from_int { val = 8 }] }
              list.len {}
            main {}
            """.trimIndent()
        )
        assertEquals(2L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `len unifies hash length`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ from_str, from_int, jvm_dict }
            jvm_dict {
              keys = [from_str { val = "a" }, from_str { val = "b" }, from_str { val = "c" }],
              vals = [from_int { val = 1 }, from_int { val = 2 }, from_int { val = 3 }],
            }.len {}
            """.trimIndent()
        )
        assertEquals(3L, assertOk(result).getPayloadFieldByName("val"))
    }

    @Test
    fun `to_seq drains a non-array iterable through the iterator protocol`() {
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            use std::polyglot::jvm::{ from_int }
            use std::collections::seq
            let main = {} -> do
              let set = jvm::class { name = "java.util.HashSet" }.new { args = [] }
              let _ = set.method { name = "add", args = [from_int { val = 10 }] }
              let _ = set.method { name = "add", args = [from_int { val = 20 }] }
              let _ = set.method { name = "add", args = [from_int { val = 30 }] }
              match set.to_seq {} is
                Ok  { val = xs }  -> seq::size { xs = xs }
                Err { error = _ } -> -1
            main {}
            """.trimIndent()
        )
        assertEquals(3L, result)
    }

    // ==================== provider entry pattern + verb routing ====================

    @Test
    fun `language-effect entry pattern - handler buries Runtime around eval_raw, yields an errored foreign`() {
        // The shape every out-of-tree provider uses for its entry: a per-language
        // effect whose handler buries `perform Runtime in Polyglot.eval_raw`. In the
        // error-carrying model the op YIELDS A FOREIGN, so a missing guest language
        // yields an ERRORED foreign (not a crash) — the error surfaces when crossed.
        val result = eval(
            """
            use std::polyglot::core::{ Polyglot, Runtime, Foreign }
            newtype XForeign = Foreign
            effect X where { run: { source: Str } -> XForeign <X> }
            pub type XHost = {}
            impl X for XHost where {
              run = { source } ->
                perform Runtime in Polyglot.eval_raw { lang = "nonexistent_lang_xyz", source = source }
            }
            perform XHost in X.run { source = "1 + 1" }
            """.trimIndent()
        )
        assertErrored(result)
    }

    @Test
    fun `verbs route through the Polyglot effect with NO enclosing perform`() {
        // Each JvmForeign verb buries its own `perform Runtime in Polyglot.<op>`, so
        // a whole chain runs with no enclosing perform. Math.PI read via `.get{}`
        // then crossed via `.float{}` — user writes neither `Polyglot.*` nor a `lang`.
        val result = eval(
            """
            use std::polyglot::jvm as jvm
            jvm::class { name = "java.lang.Math" }
              .get { name = "PI" }
              .float {}
            """.trimIndent()
        )
        assertEquals(Math.PI, assertOk(result).getPayloadFieldByName("val"))
    }
}
