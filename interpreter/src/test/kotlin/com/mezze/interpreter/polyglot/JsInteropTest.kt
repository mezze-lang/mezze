package com.mezze.interpreter.polyglot

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests for std::polyglot::js.
 *
 * Every test performs the `GraalJs` handler for the `Js` effect —
 * `Js.eval` drives the interpreter through the same `impl JsForeign`
 * surface (member / array / execute / property / index) the Python
 * tests use for `PyForeign`, proving the interop layer is language-
 * agnostic while keeping per-language type safety at compile time.
 *
 * `require` and `import` are NOT covered here: they need
 * `[polyglot.js]` `node_modules` wired up on the enclosing Context,
 * which `MezzeTestHelper` doesn't do by default. Their round-trip is
 * exercised by the polyglot_js demo instead.
 */
class JsInteropTest {

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
    fun `js eval 1 plus 1 coerced to int equals 2`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "1 + 1" }
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
    fun `js string toUpperCase via eval`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "'hello'.toUpperCase()" }
              match r is
                Ok { val = f } -> f.as_str{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals("HELLO", v.getPayloadFieldByName("val"))
    }

    // ==================== Globals: Math, JSON ====================

    @Test
    fun `js Math_PI read`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "Math.PI" }
              match r is
                Ok { val = p } -> p.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val pi = v.getPayloadFieldByName("val") as Double
        assertTrue(pi > 3.14 && pi < 3.15, "expected ~pi, got $pi")
    }

    @Test
    fun `js Math_sqrt of 16 via eval`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "Math.sqrt(16)" }
              match r is
                Ok { val = f } -> f.as_float{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(4.0, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `js Math_max via inherent method`() {
        // Bind Math as a JS object, then call `.max` through the
        // JsForeign `.method{}` inherent — proves the interop layer
        // routes JS-idiomatic vocabulary through the same natives.
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs, from_int }
            perform GraalJs in do
              let m_r = Js.eval { source = "Math" }
              match m_r is
                Ok { val = m } ->
                  match m.method { name = "max", args = [from_int { val = 3 }, from_int { val = 7 }, from_int { val = 2 }] } is
                    Ok { val = r } -> r.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    // ==================== Object literals ====================

    @Test
    fun `js object property via inherent property`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "({ a: 1, b: 2 })" }
              match r is
                Ok { val = obj } ->
                  match obj.property { name = "a" } is
                    Ok { val = v } -> v.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(1L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `js JSON parse and read property`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "JSON.parse('{\"n\": 42}').n" }
              match r is
                Ok { val = v } -> v.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    // ==================== Arrays ====================

    @Test
    fun `js array_size and array_at`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "[10, 20, 30]" }
              match r is
                Ok { val = arr } ->
                  match arr.array_at { index = 1 } is
                    Ok { val = e } -> e.as_int{}
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(20L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `js array length via inherent array_size`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "[1, 2, 3, 4, 5]" }
              match r is
                Ok { val = arr } -> arr.array_size{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(5L, v.getPayloadFieldByName("val"))
    }

    // ==================== Functions ====================

    @Test
    fun `js arrow function call with args`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs, from_int }
            perform GraalJs in do
              let r = Js.eval { source = "(x, y) => x + y" }
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
    fun `js function square via call`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs, from_int }
            perform GraalJs in do
              let f_r = Js.eval { source = "(function(x) { return x * x; })" }
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

    // ==================== Constructors ====================

    @Test
    fun `js Date new via inherent new`() {
        // Get the Date constructor and instantiate it — verifies that
        // JS-idiomatic `.new{}` routes through the same instantiate
        // native the Python `impl PyForeign` uses.
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let ctor_r = Js.eval { source = "Date" }
              match ctor_r is
                Ok { val = ctor } ->
                  match ctor.new { args = [] } is
                    Ok { val = d } ->
                      match d.method { name = "getFullYear", args = [] } is
                        Ok { val = y } -> y.as_int{}
                        Err { error } -> Err { error = error }
                    Err { error } -> Err { error = error }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        val year = v.getPayloadFieldByName("val") as Long
        assertTrue(year >= 2020 && year <= 2100, "expected recent year, got $year")
    }

    // ==================== Errors ====================

    @Test
    fun `js method on unknown method yields Err`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "'hello'" }
              match r is
                Ok { val = s } -> s.method { name = "wrong_method", args = [] }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `js syntax error yields Err`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in Js.eval { source = "this is not JavaScript <<>>" }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `js unknown property yields Err`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "({})" }
              match r is
                Ok { val = obj } -> obj.property { name = "does_not_exist" }
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        assertErr(result)
    }

    @Test
    fun `js require rejects unsafe module name`() {
        // Guard against ' quote breakout in the require expression.
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in Js.require { module = "fs'); console.log('pwned" }
            """.trimIndent()
        )
        assertErr(result)
    }

    // ==================== Type predicates ====================

    @Test
    fun `js number as_int works`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "42" }
              match r is
                Ok { val = f } -> f.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `js null is is_null`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "null" }
              match r is
                Ok { val = f } -> f.is_null{}
                Err { error } -> False
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    @Test
    fun `js boolean as_bool works`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "true && (1 === 1)" }
              match r is
                Ok { val = f } -> f.as_bool{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(true, v.getPayloadFieldByName("val"))
    }

    // ==================== Cross-language ====================

    @Test
    fun `js Math_max called with mezze integers`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs, from_int }
            perform GraalJs in do
              let r = Js.eval { source = "Math.max" }
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
    fun `js array length via builtins`() {
        val result = eval(
            """
            use std::polyglot::js::{ Js, GraalJs }
            perform GraalJs in do
              let r = Js.eval { source = "[1, 2, 3, 4, 5].length" }
              match r is
                Ok { val = f } -> f.as_int{}
                Err { error } -> Err { error = error }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(5L, v.getPayloadFieldByName("val"))
    }

    // ==================== Codec round-trip ====================

    @Test
    fun `js to_js int round trip via from_js`() {
        // Decode target is inferred from the get_or default — same
        // dispatch pattern as from_json in JsonDecodeExhaustiveTest.
        // No `perform GraalJs` needed: codec ops don't touch the Js
        // effect, so introducing a handler that never fires is a TC
        // error.
        val result = eval(
            """
            use std::codec::js::{ ToJs, FromJs }
            use std::result
            let jf = (42).to_js{}
            jf.from_js{}.get_or { def = -1 }
            """.trimIndent()
        )
        assertEquals(42L, result)
    }

    @Test
    fun `js to_js str round trip via from_js`() {
        val result = eval(
            """
            use std::codec::js::{ ToJs, FromJs }
            use std::result
            let jf = "hello".to_js{}
            jf.from_js{}.get_or { def = "?" }
            """.trimIndent()
        )
        assertEquals("hello", result)
    }
}
