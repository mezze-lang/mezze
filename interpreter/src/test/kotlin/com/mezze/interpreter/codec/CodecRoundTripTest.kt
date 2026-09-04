package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Round-trip and error-surface tests for the codec (task #86, phase T).
 *
 * Covers primitives x formats and record / variant / nested shapes.
 *
 * Two shapes are exercised:
 *   - Encode -> to_str -> parse_str -> Decode  (full round-trip)
 *   - Encode -> to_str                          (encode-side check only,
 *                                                 for shapes whose Tier-3
 *                                                 decode currently hits
 *                                                 a codec-generator gap)
 *
 * Decode-side error paths are pinned separately with hand-built `Json`
 * values so the decoder gets exercised without relying on Tier-3
 * synthesis for `[T]` / `Option T` (see the note at the bottom of the
 * file for the current gaps).
 *
 * See `docs/codec/redesign-2026-09.md`.
 */
class CodecRoundTripTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ------------------------------------------------------------------------
    // Small helpers to normalise runtime values.
    // ------------------------------------------------------------------------

    private fun asVariant(x: Any): MezzeVariant {
        assertTrue(x is MezzeVariant, "expected MezzeVariant, got ${x.javaClass}")
        return x as MezzeVariant
    }

    private fun asRecord(x: Any): MezzeRecord {
        assertTrue(x is MezzeRecord, "expected MezzeRecord, got ${x.javaClass}")
        return x as MezzeRecord
    }

    /** Assert the Result is Ok and return its `val` payload. */
    private fun okVal(result: Any): Any {
        val v = asVariant(result)
        assertEquals("Ok", v.constructorName, "expected Ok, got ${v.constructorName}")
        return asRecord(v.payloadValue!!).getField("val") as Any
    }

    // ========================================================================
    // Primitives x JsonC — full round-trip via encode + to_str + parse_str + decode
    // ========================================================================

    /** Common preamble for JsonC round-trip tests. */
    private val jsonPreamble = """
        use std::codec::json::{ Json, json_default, to_str, parse_str }
        use std::codec::core::{ Encode, Decode, DecodeError }
    """.trimIndent()

    /** Emit source that round-trips a JsonC-encoded value through parse_str. */
    private fun jsonRoundTrip(sourceExpr: String, typeAnnot: String): String = """
        $jsonPreamble
        let original: $typeAnnot = $sourceExpr
        let encoded: Json = original.encode { config = json_default }
        let s: Str = to_str { json = encoded }
        match parse_str { s = s } is
          Ok { val = j } -> do
            let dec: Result $typeAnnot DecodeError = j.decode { config = json_default }
            dec
          Err { error = e } -> Err { error = { path = [], message = "parse", expected = "$typeAnnot", found = None } }
    """.trimIndent()

    @Test
    fun `JsonC round-trip Int zero`() {
        assertEquals(0L, okVal(eval(jsonRoundTrip("0", "Int"))))
    }

    @Test
    fun `JsonC round-trip Int one`() {
        assertEquals(1L, okVal(eval(jsonRoundTrip("1", "Int"))))
    }

    @Test
    fun `JsonC round-trip Int negative`() {
        assertEquals(-1L, okVal(eval(jsonRoundTrip("(0 - 1)", "Int"))))
    }

    @Test
    fun `JsonC round-trip Int large safe long`() {
        assertEquals(9007199254740992L, okVal(eval(jsonRoundTrip("9007199254740992", "Int"))))
    }

    @Test
    fun `JsonC round-trip Float 1_5`() {
        assertEquals(1.5, okVal(eval(jsonRoundTrip("1.5", "Float"))) as Double, 1e-9)
    }

    @Test
    fun `JsonC round-trip Float negative`() {
        assertEquals(-3.14, okVal(eval(jsonRoundTrip("(0.0 - 3.14)", "Float"))) as Double, 1e-9)
    }

    @Test
    fun `JsonC round-trip Str empty`() {
        assertEquals("", okVal(eval(jsonRoundTrip("\"\"", "Str"))))
    }

    @Test
    fun `JsonC round-trip Str ascii`() {
        assertEquals("hello", okVal(eval(jsonRoundTrip("\"hello\"", "Str"))))
    }

    @Test
    fun `JsonC round-trip Str unicode`() {
        assertEquals("héllo 🎉", okVal(eval(jsonRoundTrip("\"héllo 🎉\"", "Str"))))
    }

    @Test
    fun `JsonC round-trip Bool true`() {
        assertEquals(true, okVal(eval(jsonRoundTrip("True", "Bool"))))
    }

    @Test
    fun `JsonC round-trip Bool false`() {
        assertEquals(false, okVal(eval(jsonRoundTrip("False", "Bool"))))
    }

    // ========================================================================
    // Anonymous record / anonymous variant (no payload) — full round-trip
    // ========================================================================

    @Test
    fun `JsonC round-trip record two fields`() {
        val v = okVal(eval(jsonRoundTrip(
            "{ name = \"Alice\", age = 30 }",
            "{ name: Str, age: Int }"
        )))
        val r = asRecord(v)
        assertEquals("Alice", r.getField("name"))
        assertEquals(30L, r.getField("age"))
    }

    @Test
    fun `JsonC round-trip record with optional field`() {
        val v = okVal(eval(jsonRoundTrip(
            "{ name = \"A\", email = Some { val = \"a@b\" } }",
            "{ name: Str, email: Option Str }"
        )))
        val r = asRecord(v)
        assertEquals("A", r.getField("name"))
        val emailVariant = asVariant(r.getField("email") as Any)
        assertEquals("Some", emailVariant.constructorName)
    }

    @Test
    fun `JsonC round-trip nested record`() {
        val v = okVal(eval(jsonRoundTrip(
            "{ id = 1, addr = { city = \"NYC\", zip = 10001 } }",
            "{ id: Int, addr: { city: Str, zip: Int } }"
        )))
        val r = asRecord(v)
        assertEquals(1L, r.getField("id"))
        val inner = asRecord(r.getField("addr") as Any)
        assertEquals("NYC", inner.getField("city"))
        assertEquals(10001L, inner.getField("zip"))
    }

    @Test
    fun `JsonC round-trip anonymous variant no payload`() {
        val v = okVal(eval(jsonRoundTrip(
            "Red",
            "| Red, Green, Blue |"
        )))
        val variant = asVariant(v)
        assertEquals("Red", variant.constructorName)
    }

    // ========================================================================
    // Encode-only tests — the encode side works cleanly through the Tier-2
    // per-shape impls that JsonC ships. Full decode round-trip for lists,
    // options, variants-with-payloads and Nat currently hits a
    // CodecGenerator gap (see note at the bottom of the file); the encode
    // side is fully exercised end-to-end here via `to_str`.
    // ========================================================================

    private fun encodeAndSerialize(sourceExpr: String, typeAnnot: String): String = eval("""
        $jsonPreamble
        let original: $typeAnnot = $sourceExpr
        let encoded: Json = original.encode { config = json_default }
        to_str { json = encoded }
    """.trimIndent()) as String

    @Test
    fun `JsonC encode Option None serializes to null`() {
        assertEquals("null", encodeAndSerialize("None", "Option Int"))
    }

    @Test
    fun `JsonC encode Option Some Int serializes to number`() {
        assertEquals("42", encodeAndSerialize("Some { val = 42 }", "Option Int"))
    }

    @Test
    fun `JsonC encode empty list serializes to bracket pair`() {
        assertEquals("[]", encodeAndSerialize("[]", "[Int]"))
    }

    @Test
    fun `JsonC encode list of Int serializes to JSON array`() {
        assertEquals("[1,2,3]", encodeAndSerialize("[1, 2, 3]", "[Int]"))
    }

    @Test
    fun `JsonC encode list of Str serializes to quoted JSON array`() {
        assertEquals("[\"a\",\"b\"]", encodeAndSerialize("[\"a\", \"b\"]", "[Str]"))
    }

    // Nat / variant-with-payload encode currently blocks on codec-generator
    // gaps described at the end of this file; the encode-side check for
    // those shapes is left to `JsonCodecInterpreterTest.serialize*` which
    // exercises the underlying native serializer via hand-built Json
    // values.

    // ========================================================================
    // UrlC — primitive full round-trips (Str output)
    // ========================================================================

    private val urlPreamble = """
        use std::codec::url::{ UrlC, url_default }
        use std::codec::core::{ Encode, Decode, DecodeError }
    """.trimIndent()

    @Test
    fun `UrlC round-trip Int`() {
        val src = """
            $urlPreamble
            let original: Int = 42
            let s: Str = original.encode { config = url_default }
            let dec: Result Int DecodeError = s.decode { config = url_default }
            dec
        """.trimIndent()
        assertEquals(42L, okVal(eval(src)))
    }

    @Test
    fun `UrlC round-trip negative Int`() {
        val src = """
            $urlPreamble
            let original: Int = 0 - 7
            let s: Str = original.encode { config = url_default }
            let dec: Result Int DecodeError = s.decode { config = url_default }
            dec
        """.trimIndent()
        assertEquals(-7L, okVal(eval(src)))
    }

    @Test
    fun `UrlC round-trip Str`() {
        val src = """
            $urlPreamble
            let original: Str = "hello"
            let s: Str = original.encode { config = url_default }
            let dec: Result Str DecodeError = s.decode { config = url_default }
            dec
        """.trimIndent()
        assertEquals("hello", okVal(eval(src)))
    }

    @Test
    fun `UrlC round-trip Bool true`() {
        val src = """
            $urlPreamble
            let original: Bool = True
            let s: Str = original.encode { config = url_default }
            let dec: Result Bool DecodeError = s.decode { config = url_default }
            dec
        """.trimIndent()
        assertEquals(true, okVal(eval(src)))
    }

    @Test
    fun `UrlC round-trip Bool false`() {
        val src = """
            $urlPreamble
            let original: Bool = False
            let s: Str = original.encode { config = url_default }
            let dec: Result Bool DecodeError = s.decode { config = url_default }
            dec
        """.trimIndent()
        assertEquals(false, okVal(eval(src)))
    }

    // ========================================================================
    // Error-path tests — DecodeError with path + reason
    //
    // Uses hand-built `Json` values so we don't need Tier-3 auto-derive for
    // the input side; only the decode side is stressed.
    // ========================================================================

    @Test
    fun `Decode reports path on missing field`() {
        val src = """
            $jsonPreamble
            let raw: Json = JObject { fields = [{ key = "name", value = JString { val = "Alice" } }] }
            let dec: Result { name: Str, age: Int } DecodeError = raw.decode { config = json_default }
            dec
        """.trimIndent()
        val err = asVariant(eval(src))
        assertEquals("Err", err.constructorName)
        val error = (err.payloadValue as MezzeRecord).getField("error") as MezzeRecord
        val message = error.getField("message") as String
        assertTrue(
            message.contains("age", ignoreCase = true) || message.contains("Missing", ignoreCase = true),
            "expected error to mention the missing field, got: $message",
        )
    }

    @Test
    fun `Decode reports mismatch when field has wrong type`() {
        val src = """
            $jsonPreamble
            let raw: Json = JObject { fields = [
              { key = "name", value = JString { val = "A" } },
              { key = "age",  value = JString { val = "not-an-int" } },
            ] }
            let dec: Result { name: Str, age: Int } DecodeError = raw.decode { config = json_default }
            dec
        """.trimIndent()
        val err = asVariant(eval(src))
        assertEquals("Err", err.constructorName)
        val error = (err.payloadValue as MezzeRecord).getField("error") as MezzeRecord
        val expected = error.getField("expected") as String
        assertTrue(
            expected.contains("Int") || expected.contains("integer"),
            "expected 'expected' to mention Int, got: $expected",
        )
    }

    @Test
    fun `Decode reports mismatch when scalar wrong type at top level`() {
        val src = """
            $jsonPreamble
            let raw: Json = JString { val = "not-an-int" }
            let dec: Result Int DecodeError = raw.decode { config = json_default }
            dec
        """.trimIndent()
        val err = asVariant(eval(src))
        assertEquals("Err", err.constructorName)
        val error = (err.payloadValue as MezzeRecord).getField("error") as MezzeRecord
        val found = error.getField("found") as MezzeVariant
        assertEquals("Some", found.constructorName)
    }

    @Test
    fun `parse_str error surfaces as ParseError with reason`() {
        val src = """
            use std::codec::json::{ parse_str }
            parse_str { s = "{invalid" }
        """.trimIndent()
        val err = asVariant(eval(src))
        assertEquals("Err", err.constructorName)
        val error = (err.payloadValue as MezzeRecord).getField("error") as MezzeRecord
        val reason = error.getField("reason") as String
        assertTrue(reason.isNotEmpty(), "reason should be non-empty")
    }

    @Test
    fun `parse_str carries input_ref Option field on error`() {
        val src = """
            use std::codec::json::{ parse_str }
            parse_str { s = "not-json" }
        """.trimIndent()
        val err = asVariant(eval(src))
        assertEquals("Err", err.constructorName)
        val error = (err.payloadValue as MezzeRecord).getField("error") as MezzeRecord
        val inputRef = error.getField("input_ref")
        assertTrue(inputRef is MezzeVariant, "input_ref must be an Option (MezzeVariant)")
    }

    @Test
    fun `parse_str error carries path field (empty at top-level parse)`() {
        val src = """
            use std::codec::json::{ parse_str }
            parse_str { s = "not-json" }
        """.trimIndent()
        val err = asVariant(eval(src))
        val error = (err.payloadValue as MezzeRecord).getField("error") as MezzeRecord
        assertNotNull(error.getField("path"), "ParseError.path must exist")
    }

    // ========================================================================
    // Structural mismatch — the design's DP-10 property (compile-time error
    // at the field path) is enforced in CodecAbilityShapeTest via
    // ExpectedTypeCheckError. At runtime these expressions should never be
    // reachable — the typechecker rejects them before emit.
    // ========================================================================

    // ========================================================================
    // GAP 2 round-trips — Seq, Option, variant-with-payload
    //
    // The T-phase notes documented four decode-side gaps:
    //   1. `[T]` decode emitted `__native_seq_try_map` with no handler.
    //   2. `Option T` decode had an under-constrained let-annotation.
    //   3. Variant-with-payload emit bound the payload to a variable
    //      pattern named `__payload` which the runtime treated as a real
    //      record field.
    //   4. `Nat` (newtype over Int) infinite-recursed via Tier 3.
    //
    // Fixes shipped for #1, #2, and #3 close their round-trips here. #4
    // remains open — Nat/newtype auto-derive across module boundaries
    // requires a wrapper-unwrap that the compiler can't emit while the
    // newtype's constructor is module-private.
    // ========================================================================

    @Test
    fun `JsonC round-trip list of Int`() {
        val src = """
            $jsonPreamble
            let original: [Int] = [1, 2, 3]
            let encoded: Json = original.encode { config = json_default }
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let dec: Result [Int] DecodeError = j.decode { config = json_default }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "[Int]", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val seq = v as com.mezze.datastructures.seq.MezzeSeq<*>
        assertEquals(3, seq.size())
        assertEquals(1L, seq.get(0))
        assertEquals(2L, seq.get(1))
        assertEquals(3L, seq.get(2))
    }

    @Test
    fun `JsonC round-trip Option Some Int`() {
        val src = """
            $jsonPreamble
            let original: Option Int = Some { val = 42 }
            let encoded: Json = original.encode { config = json_default }
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let dec: Result (Option Int) DecodeError = j.decode { config = json_default }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "Option Int", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val opt = asVariant(v)
        assertEquals("Some", opt.constructorName)
        assertEquals(42L, asRecord(opt.payloadValue!!).getField("val"))
    }

    @Test
    fun `JsonC round-trip Option None`() {
        val src = """
            $jsonPreamble
            let original: Option Int = None
            let encoded: Json = original.encode { config = json_default }
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let dec: Result (Option Int) DecodeError = j.decode { config = json_default }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "Option Int", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val opt = asVariant(v)
        assertEquals("None", opt.constructorName)
    }

    @Test
    fun `JsonC round-trip anonymous variant with payload`() {
        val src = """
            $jsonPreamble
            let original: | Left { val: Int }, Right { val: Str } | = Left { val = 42 }
            let encoded: Json = original.encode { config = json_default }
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let dec: Result (| Left { val: Int }, Right { val: Str } |) DecodeError = j.decode { config = json_default }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "variant", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val variant = asVariant(v)
        assertEquals("Left", variant.constructorName)
        assertEquals(42L, asRecord(variant.payloadValue!!).getField("val"))
    }

    // ========================================================================
    // KNOWN GAPS — open work items after this commit
    //
    //  * `Nat` (newtype over Int) does not auto-derive: the walker recurses
    //    through Tier 3, and newtype opacity prevents unwrapping at emit
    //    time. Workaround: hand-write `impl Encode JsonC for Nat` at the
    //    call site. Same for user newtypes over primitives.
    //
    //  * Tier-4 sugar (`x.json {}` / `Type.from_json {…}`) — the ability
    //    dispatch for `.encode {…}` inside a blanket impl body's method-
    //    level `with Encode 'c 'a` constraint doesn't monomorphize; emit
    //    lowers `it.encode {…}` to a field access and the runtime fails
    //    with "Expected record for field 'encode', got: …". Direct
    //    `x.encode { config = json_default }` still works. This is a
    //    fundamental gap in how blanket-impl method bodies with method-
    //    level constraints get lowered — not a codec-generator issue.
    // ========================================================================
}
