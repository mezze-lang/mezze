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
    // Bug A — auto-derive for user `type` (Declared TypeApplication)
    //
    // Before the CodecGenerator TypeApplication branch expanded Declared
    // typeapps to their underlying record/variant shape, the emit fell
    // through to `tierThreeEncode`, which lowered to
    // `it.encode { config = config }` — a self-call inside the impl body
    // that infinite-recursed (or, once caught by JIT depth limits, produced
    // "Expected record for field 'encode'"). These four tests would fail
    // pre-fix and pass post-fix.
    // ========================================================================

    @Test
    fun `Bug A - JsonC encode direct on user record type`() {
        val src = """
            $jsonPreamble
            type Point = { x: Int, y: Int }
            let p: Point = { x = 3, y = 4 }
            let encoded: Json = p.encode { config = json_default }
            to_str { json = encoded }
        """.trimIndent()
        val s = eval(src) as String
        // Field order in the emitted JSON is sorted (see buildEncodeRecord).
        assertEquals("{\"x\":3,\"y\":4}", s)
    }

    @Test
    fun `Bug A - JsonC encode direct on user record with Option field`() {
        val src = """
            $jsonPreamble
            type Contact = { name: Str, email: Option Str }
            let c: Contact = { name = "A", email = Some { val = "a@b" } }
            let encoded: Json = c.encode { config = json_default }
            to_str { json = encoded }
        """.trimIndent()
        val s = eval(src) as String
        assertEquals("{\"email\":\"a@b\",\"name\":\"A\"}", s)
    }

    @Test
    fun `Bug A - JsonC encode direct on user record with Seq field`() {
        val src = """
            $jsonPreamble
            type Bag = { tags: [Str] }
            let b: Bag = { tags = ["a", "b", "c"] }
            let encoded: Json = b.encode { config = json_default }
            to_str { json = encoded }
        """.trimIndent()
        val s = eval(src) as String
        assertEquals("{\"tags\":[\"a\",\"b\",\"c\"]}", s)
    }

    @Test
    fun `Bug A - JsonC encode direct on nested user record`() {
        val src = """
            $jsonPreamble
            type Addr = { city: Str, zip: Int }
            type Person = { id: Int, addr: Addr }
            let p: Person = { id = 1, addr = { city = "NYC", zip = 10001 } }
            let encoded: Json = p.encode { config = json_default }
            to_str { json = encoded }
        """.trimIndent()
        val s = eval(src) as String
        assertEquals("{\"addr\":{\"city\":\"NYC\",\"zip\":10001},\"id\":1}", s)
    }

    @Test
    fun `Bug A - JsonC round-trip on user record type`() {
        val src = """
            $jsonPreamble
            type Point = { x: Int, y: Int }
            let original: Point = { x = 3, y = 4 }
            let encoded: Json = original.encode { config = json_default }
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let dec: Result Point DecodeError = j.decode { config = json_default }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "Point", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val r = asRecord(v)
        assertEquals(3L, r.getField("x"))
        assertEquals(4L, r.getField("y"))
    }

    // ========================================================================
    // Bug B (#86) — Tier-4 sugar (`x.to_json {}` / `x.to_json_with { config = … }`)
    //
    // Blanket-impl body dispatch: `it.encode {…}` inside `impl ToJson for 'a`
    // now marks `encode` as needing dict-passing driven off the enclosing
    // arrow's `with Encode JsonC 'a` constraint — no longer off impl-index
    // population (which is empty at stdlib-precompile time). See the codec
    // redesign doc for the three-step fix.
    // ========================================================================

    /** Small helper that extracts a JInt payload. */
    private fun jIntVal(x: Any): Long {
        val v = asVariant(x)
        assertEquals("JInt", v.constructorName)
        return asRecord(v.payloadValue!!).getField("val") as Long
    }

    /** Small helper that extracts a JString payload. */
    private fun jStringVal(x: Any): String {
        val v = asVariant(x)
        assertEquals("JString", v.constructorName)
        return asRecord(v.payloadValue!!).getField("val") as String
    }

    /** Small helper that extracts a JBool payload. */
    private fun jBoolVal(x: Any): Boolean {
        val v = asVariant(x)
        assertEquals("JBool", v.constructorName)
        return asRecord(v.payloadValue!!).getField("val") as Boolean
    }

    /** Small helper that extracts a JFloat payload. */
    private fun jFloatVal(x: Any): Double {
        val v = asVariant(x)
        assertEquals("JFloat", v.constructorName)
        return asRecord(v.payloadValue!!).getField("val") as Double
    }

    @Test
    fun `Bug B - json sugar on Int primitive`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default }
            let n: Int = 42
            n.to_json {}
        """.trimIndent()
        assertEquals(42L, jIntVal(eval(src)))
    }

    @Test
    fun `Bug B - json sugar on Str primitive`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default }
            let s: Str = "hello"
            s.to_json {}
        """.trimIndent()
        assertEquals("hello", jStringVal(eval(src)))
    }

    @Test
    fun `Bug B - json sugar on Bool primitive`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default }
            let b: Bool = True
            b.to_json {}
        """.trimIndent()
        assertEquals(true, jBoolVal(eval(src)))
    }

    @Test
    fun `Bug B - json sugar on Float primitive`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default }
            let f: Float = 1.5
            f.to_json {}
        """.trimIndent()
        assertEquals(1.5, jFloatVal(eval(src)), 1e-9)
    }

    @Test
    fun `Bug B - json sugar on user record`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default }
            type Point = { x: Int, y: Int }
            let p: Point = { x = 3, y = 4 }
            p.to_json {}
        """.trimIndent()
        // Verify by round-tripping through to_str.
        val src2 = """
            use std::codec::json::{ Json, ToJson, json_default, to_str }
            type Point = { x: Int, y: Int }
            let p: Point = { x = 3, y = 4 }
            to_str { json = p.to_json {} }
        """.trimIndent()
        // Field order is stable within Encode Record impls.
        val s = eval(src2) as String
        assertEquals("{\"x\":3,\"y\":4}", s)
        // Also make sure the direct call returns a JObject.
        val v = asVariant(eval(src))
        assertEquals("JObject", v.constructorName)
    }

    @Test
    fun `Bug B - json sugar on nested user record`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default, to_str }
            type Address = { city: Str, zip: Int }
            type Person = { id: Int, addr: Address }
            let p: Person = { id = 1, addr = { city = "NYC", zip = 10001 } }
            to_str { json = p.to_json {} }
        """.trimIndent()
        assertEquals(
            "{\"addr\":{\"city\":\"NYC\",\"zip\":10001},\"id\":1}",
            eval(src) as String
        )
    }

    @Test
    fun `Bug B - json sugar composes with to_str`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default, to_str }
            let n: Int = 42
            to_str { json = n.to_json {} }
        """.trimIndent()
        assertEquals("42", eval(src) as String)
    }

    @Test
    fun `Bug B - json sugar composes with round-trip`() {
        // Encode via .json{}, serialize, parse, decode — must round-trip.
        val src = """
            use std::codec::json::{ Json, ToJson, json_default, to_str, parse_str }
            use std::codec::core::{ Decode, DecodeError }
            type Point = { x: Int, y: Int }
            let original: Point = { x = 3, y = 4 }
            let encoded: Json = original.to_json {}
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let dec: Result Point DecodeError = j.decode { config = json_default }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "Point", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val r = asRecord(v)
        assertEquals(3L, r.getField("x"))
        assertEquals(4L, r.getField("y"))
    }

    // ========================================================================
    // Bug C (#86 sub-gap 1) — `.to_json_with { config = <expr> }` sugar
    //
    // Root cause was in `expandTypeApplication` /
    // `expandTypeApplicationToRecord` (typechecker/solving/Unification.kt):
    // they used registry-only lookup, so a nested TypeApplication reached
    // through an inlined alias for an imported-but-not-directly-`use`d type
    // (SharedConfig inside JsonC) resolved to NotFound and unification fell
    // through to a "SharedConfig is not compatible with SharedConfig" refusal.
    // Added a ModuleResolver.lookupTypeDeclaration fallback mirroring the one
    // already used by expandTypeApplicationToVariant.
    // ========================================================================

    @Test
    fun `Bug C - JsonC binding of json_default succeeds`() {
        val src = """
            use std::codec::json::{ JsonC, json_default }
            let c: JsonC = json_default
            42
        """.trimIndent()
        assertEquals(42L, eval(src))
    }

    @Test
    fun `Bug C - json_with explicit json_default on user record`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default, to_str }
            type Point = { x: Int, y: Int }
            let p: Point = { x = 3, y = 4 }
            to_str { json = p.to_json_with { config = json_default } }
        """.trimIndent()
        assertEquals("{\"x\":3,\"y\":4}", eval(src) as String)
    }

    @Test
    fun `Bug C - json_with with pretty preset on user record`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_pretty, to_str }
            type Point = { x: Int, y: Int }
            let p: Point = { x = 3, y = 4 }
            to_str { json = p.to_json_with { config = json_pretty } }
        """.trimIndent()
        // to_str uses the pretty flag but the config passed determines the
        // synthesized encoder's shape; result is still a compact JObject
        // (pretty is currently ignored by the native to_str path). We just
        // verify the encoded value survives the round-trip.
        val s = eval(src) as String
        assertTrue(s.contains("\"x\"") && s.contains("\"y\""), "expected fields in output, got: $s")
    }

    @Test
    fun `Bug C - json_with on primitive Int`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default, to_str }
            let n: Int = 42
            to_str { json = n.to_json_with { config = json_default } }
        """.trimIndent()
        assertEquals("42", eval(src) as String)
    }

    @Test
    fun `Bug C - json_with on nested user record`() {
        val src = """
            use std::codec::json::{ Json, ToJson, json_default, to_str }
            type Addr = { city: Str, zip: Int }
            type Person = { id: Int, addr: Addr }
            let p: Person = { id = 1, addr = { city = "NYC", zip = 10001 } }
            to_str { json = p.to_json_with { config = json_default } }
        """.trimIndent()
        assertEquals(
            "{\"addr\":{\"city\":\"NYC\",\"zip\":10001},\"id\":1}",
            eval(src) as String,
        )
    }

    // ========================================================================
    // Bug D (#86 sub-gap 2) — `TypeName.from_json { it = raw }` sugar
    //
    // Two-part fix (typechecker/constraints/ExprFunctionConstraints.kt +
    // ExprMethodConstraints.kt + Constraint.kt):
    //   1. Recognize `TypeName.method { it = X, … }` where TypeName is a
    //      declared type: rewrite as `X.method { … }` and thread a pin type
    //      (TypeApplication(TypeName)) through `pinFirstAbilityTypeArg`.
    //   2. In tryGenerateMethodCall, once the ability resolves, emit an
    //      Equality binding the ability's first typeParam TyVar to the pin.
    // Plus AbilityChecker padding for operation-constraint `with Decode
    // JsonC 'a` — the with-clause is short by one slot (missing 'in), so
    // pad with the outer impl's trailing target type (`Json`).
    // ========================================================================

    @Test
    fun `Bug D - Type_from_json on user record via annotated Json binding`() {
        val src = """
            use std::codec::json::{ Json, FromJson, json_default }
            use std::codec::core::{ DecodeError }
            type Point = { x: Int, y: Int }
            let raw: Json = JObject { fields = [
              { key = "x", value = JInt { val = 3 } },
              { key = "y", value = JInt { val = 4 } },
            ] }
            let dec: Result Point DecodeError = Point.from_json { it = raw }
            dec
        """.trimIndent()
        val v = okVal(eval(src))
        val r = asRecord(v)
        assertEquals(3L, r.getField("x"))
        assertEquals(4L, r.getField("y"))
    }

    @Test
    fun `Bug D - Type_from_json through parse and re-annotate`() {
        val src = """
            use std::codec::json::{ Json, FromJson, json_default, parse_str }
            use std::codec::core::{ DecodeError }
            type Point = { x: Int, y: Int }
            match parse_str { s = "{\"x\":3,\"y\":4}" } is
              Ok { val = j } -> do
                let jt: Json = j
                let dec: Result Point DecodeError = Point.from_json { it = jt }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "Point", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val r = asRecord(v)
        assertEquals(3L, r.getField("x"))
        assertEquals(4L, r.getField("y"))
    }

    @Test
    fun `Bug D - Type_from_json round-trips through json encode+parse`() {
        val src = """
            use std::codec::json::{ Json, ToJson, FromJson, json_default, to_str, parse_str }
            use std::codec::core::{ DecodeError }
            type Point = { x: Int, y: Int }
            let original: Point = { x = 3, y = 4 }
            let encoded: Json = original.to_json {}
            let s: Str = to_str { json = encoded }
            match parse_str { s = s } is
              Ok { val = j } -> do
                let jt: Json = j
                let dec: Result Point DecodeError = Point.from_json { it = jt }
                dec
              Err { error = e } -> Err { error = { path = [], message = "parse", expected = "Point", found = None } }
        """.trimIndent()
        val v = okVal(eval(src))
        val r = asRecord(v)
        assertEquals(3L, r.getField("x"))
        assertEquals(4L, r.getField("y"))
    }

    // ========================================================================
    // Container reconstruction — decode must rebuild the DECLARED container, not
    // hand back the intermediate Seq. Before the fix, Set/List/SortedSet/SortedMap
    // decode returned a MezzeSeq that crashed the moment it was used as its type
    // ("Expected MezzeSet for field 's'"). Each test decodes then USES the result
    // as its type (size), so a mislabeled Seq would throw, not mis-count.
    // ========================================================================

    /**
     * Round-trip a collection through JSON and return its size as an Int. The decode
     * target carries NO type annotation — it is inferred from `c == original` (which
     * pins `c` to `original`'s type) and from the `$sizeExpr` usage. That the decode
     * resolves without an annotation is itself part of what these pin: a decode that
     * NEEDED an annotation would be an inference bug. `c == original` also makes this
     * a true round-trip-equality check; `$sizeExpr` additionally proves `c` is a real
     * container of its declared kind (a mislabeled Seq would throw in e.g. set::size).
     */
    private fun collectionSizeRoundTrip(originalExpr: String, sizeExpr: String): String = """
        use std::codec::json::{ ToJson, FromJson, to_str, parse_str }
        use std::collections::set
        use std::collections::sorted_set
        use std::collections::list
        use std::collections::map
        use std::collections::sorted_map
        let original = $originalExpr
        let s = to_str { json = original.to_json {} }
        match parse_str { s = s } is
          Ok { val = j } -> match j.from_json {} is
              Ok { val = c } -> if c == original then $sizeExpr else (0 - 3)
              Err { error = _ } -> (0 - 1)
          Err { error = _ } -> (0 - 2)
    """.trimIndent()

    @Test
    fun `JsonC round-trip Set dedups into a real Set`() {
        // 5 elements, 3 distinct. A mislabeled Seq would crash in set::size.
        assertEquals(3L, eval(collectionSizeRoundTrip(
            "set::from_seq { xs = [3, 1, 1, 2, 3] }", "set::size { s = c }")))
    }

    @Test
    fun `JsonC round-trip List keeps all elements`() {
        assertEquals(5L, eval(collectionSizeRoundTrip(
            "list::from_seq { xs = [3, 1, 1, 2, 3] }", "list::size { xs = c }")))
    }

    @Test
    fun `JsonC round-trip SortedSet dedups into a real SortedSet`() {
        assertEquals(3L, eval(collectionSizeRoundTrip(
            "sorted_set::from_seq { xs = [3, 1, 1, 2, 3] }", "sorted_set::size { s = c }")))
    }

    @Test
    fun `JsonC round-trip Map into a real Map`() {
        assertEquals(2L, eval(collectionSizeRoundTrip(
            "map::from_list { xs = list::from_seq { xs = [{ k = 1, v = 10 }, { k = 2, v = 20 }] } }",
            "map::size { m = c }")))
    }

    @Test
    fun `JsonC round-trip SortedMap into a real SortedMap`() {
        assertEquals(2L, eval(collectionSizeRoundTrip(
            "sorted_map::from_list { xs = list::from_seq { xs = [{ k = 2, v = 20 }, { k = 1, v = 10 }] } }",
            "sorted_map::size { m = c }")))
    }

    // ========================================================================
    // Nat — a builtin scalar spelled as a newtype. Decode routes to the
    // validating `decode_nat` (NOT a Tier-3 self-call, which stack-overflowed;
    // NOT a bare unwrap to Int, which skips the >= 0 invariant).
    // ========================================================================

    @Test
    fun `multiple from_json of different shapes in one scope each decode correctly`() {
        // Each from_json's decode target is return-type-polymorphic and inferred from
        // usage (`c == original`). With several decodes of DIFFERENT shapes in one
        // scope, the earlier fix wired every later call to the FIRST decoder (Map,
        // Seq, and the nominal record each collapsed onto whichever registered first).
        // Each round-trip contributes 1 iff it decoded into the RIGHT shape; sum is 3.
        val src = """
            use std::codec::json::{ ToJson, FromJson }
            use std::collections::map
            use std::collections::list
            type Pt = { a: Int, b: Str }
            let m = map::from_list { xs = list::from_seq { xs = [{ k = 1, v = 10 }, { k = 2, v = 20 }] } }
            let s = [7, 8, 9]
            let p: Pt = { a = 5, b = "hi" }
            let r1 = match m.to_json{}.from_json {} is
              Ok { val = c } -> if c == m then 1 else 0
              Err { error = _ } -> 0
            let r2 = match s.to_json{}.from_json {} is
              Ok { val = c } -> if c == s then 1 else 0
              Err { error = _ } -> 0
            let r3 = match p.to_json{}.from_json {} is
              Ok { val = c } -> if c == p then 1 else 0
              Err { error = _ } -> 0
            r1 + r2 + r3
        """.trimIndent()
        assertEquals(3L, eval(src))
    }

    @Test
    fun `JsonC round-trip Nat positive`() {
        // `c == original` pins the decode target to Nat (no annotation); `nat::to_int`
        // reads it back. Routes through the validating decode_nat, not a Tier-3 self-call.
        val src = """
            use std::codec::json::{ ToJson, FromJson, to_str, parse_str }
            use std::nat
            let original = 5n
            let s = to_str { json = original.to_json {} }
            match parse_str { s = s } is
              Ok { val = j } -> match j.from_json {} is
                  Ok { val = n } -> if n == original then nat::to_int { n = n } else (0 - 3)
                  Err { error = _ } -> (0 - 1)
              Err { error = _ } -> (0 - 2)
        """.trimIndent()
        assertEquals(5L, eval(src))
    }

    @Test
    fun `JsonC decode Nat rejects a negative number`() {
        // Decoding a negative JSON number into Nat MUST fail (Nat >= 0) — and MUST NOT
        // stack-overflow. `nat::to_int { n = n }` pins the target to Nat with no
        // annotation. -99 is the "correctly rejected" sentinel.
        val src = """
            use std::codec::json::{ FromJson, parse_str }
            use std::nat
            match parse_str { s = "-5" } is
              Ok { val = j } -> match j.from_json {} is
                  Ok { val = n } -> nat::to_int { n = n }
                  Err { error = _ } -> (0 - 99)
              Err { error = _ } -> (0 - 98)
        """.trimIndent()
        assertEquals(-99L, eval(src))
    }

    @Test
    fun `user newtype without a codec impl declines cleanly (no self-call)`() {
        // A user newtype does NOT auto-derive a codec — it needs an explicit impl.
        // Decode synthesis must DECLINE (surfacing a type error) rather than emit a
        // Tier-3 self-call that stack-overflows. The `use_id` param pins the decode
        // target to UserId without an annotation; with no impl it must fail to compile.
        val src = """
            use std::codec::json::{ FromJson, parse_str }
            newtype UserId = Int
            let use_id = { u: UserId } -> 0
            match parse_str { s = "5" } is
              Ok { val = j } -> match j.from_json {} is
                  Ok { val = c } -> use_id { u = c }
                  Err { error = _ } -> (0 - 1)
              Err { error = _ } -> (0 - 2)
        """.trimIndent()
        assertThrows(Exception::class.java) { eval(src) }
    }
}
