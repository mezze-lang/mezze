package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for the JSON codec native functions.
 *
 * Tests the native JSON parse/serialize functions that convert between
 * JSON strings and the Json variant type.
 */
class JsonCodecInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Native JSON Parse Tests
    // ========================================================================

    @Test
    fun `parse simple JSON string`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "\"hello\"" }
        """.trimIndent()

        val result = eval(source)

        // Should be Ok variant containing JString
        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JString", jsonValue.constructorName)

        val stringRecord = jsonValue.payloadValue as MezzeRecord
        val stringValue = stringRecord.getField("val") as String
        assertEquals("hello", stringValue)
    }

    @Test
    fun `parse JSON integer`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "42" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JInt", jsonValue.constructorName)

        val intRecord = jsonValue.payloadValue as MezzeRecord
        val intValue = intRecord.getField("val") as Long
        assertEquals(42L, intValue)
    }

    @Test
    fun `parse JSON float`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "3.14" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JFloat", jsonValue.constructorName)

        val floatRecord = jsonValue.payloadValue as MezzeRecord
        val floatValue = floatRecord.getField("val") as Double
        assertEquals(3.14, floatValue, 0.001)
    }

    @Test
    fun `parse JSON boolean true`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "true" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JBool", jsonValue.constructorName)

        val boolRecord = jsonValue.payloadValue as MezzeRecord
        val boolValue = boolRecord.getField("val") as Boolean
        assertTrue(boolValue)
    }

    @Test
    fun `parse JSON boolean false`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "false" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JBool", jsonValue.constructorName)

        val boolRecord = jsonValue.payloadValue as MezzeRecord
        val boolValue = boolRecord.getField("val") as Boolean
        assertFalse(boolValue)
    }

    @Test
    fun `parse JSON null`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "null" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JNull", jsonValue.constructorName)
        assertNull(jsonValue.payloadValue)
    }

    @Test
    fun `parse JSON array`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "[1, 2, 3]" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JArray", jsonValue.constructorName)

        val arrayRecord = jsonValue.payloadValue as MezzeRecord
        val elementsList = arrayRecord.getField("items") as Array<*>
        assertEquals(3, elementsList.size)
    }

    @Test
    fun `parse JSON object`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "{\"name\": \"Alice\", \"age\": 30}" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonValue = innerRecord.getField("val") as MezzeVariant
        assertEquals("JObject", jsonValue.constructorName)

        val objRecord = jsonValue.payloadValue as MezzeRecord
        val fieldsList = objRecord.getField("fields") as Array<*>
        assertEquals(2, fieldsList.size)
    }

    @Test
    fun `parse invalid JSON returns error`() {
        val source = """
            use std::codec::json
            json::parse_str { s = "invalid json {{" }
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val errVariant = result as MezzeVariant
        assertEquals("Err", errVariant.constructorName)

        val errRecord = errVariant.payloadValue as MezzeRecord
        val error = errRecord.getField("error") as MezzeRecord
        // ParseError shape (Phase 1 codec redesign, task #86):
        // { path: [Str], reason: Str, input_ref: Option Str }
        val reason = error.getField("reason") as String
        assertTrue(reason.contains("parse error"), "Error reason should mention parse error")
    }

    // ========================================================================
    // Native JSON Serialize Tests
    // ========================================================================

    @Test
    fun `serialize JString`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let j: Json = JString {val = "hello"}
            to_str { json = j }
        """.trimIndent()

        val result = eval(source)
        assertEquals("\"hello\"", result)
    }

    @Test
    fun `serialize JInt`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let j: Json = JInt {val = 42}
            to_str { json = j }
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun `serialize JFloat`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let j: Json = JFloat {val = 3.14}
            to_str { json = j }
        """.trimIndent()

        val result = eval(source)
        assertEquals("3.14", result)
    }

    @Test
    fun `serialize JBool true`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let j: Json = JBool {val = True}
            to_str { json = j }
        """.trimIndent()

        val result = eval(source)
        assertEquals("true", result)
    }

    @Test
    fun `serialize JNull`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let j: Json = JNull
            to_str { json = j }
        """.trimIndent()

        val result = eval(source)
        assertEquals("null", result)
    }

    @Test
    fun `serialize JArray`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let items: [Json] = [JInt {val = 1}, JInt {val = 2}, JInt {val = 3}]
            let j: Json = JArray {items = items}
            to_str { json = j }
        """.trimIndent()

        val result = eval(source)
        assertEquals("[1,2,3]", result)
    }

    @Test
    fun `serialize JObject`() {
        val source = """
            use std::codec::json::{ Json, to_str }
            let fields: [{key: Str, value: Json}] = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "age", value = JInt {val = 30}}
            ]
            let j: Json = JObject {fields = fields}
            to_str { json = j }
        """.trimIndent()

        val result = eval(source) as String
        // JSON object order may vary, check contains
        assertTrue(result.contains("\"name\""))
        assertTrue(result.contains("\"Alice\""))
        assertTrue(result.contains("\"age\""))
        assertTrue(result.contains("30"))
    }

    // ========================================================================
    // Primitive parse edge cases
    // ========================================================================

    /** Parse an Ok-wrapped Json variant out of the natives' Result. */
    private fun okJson(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "expected MezzeVariant, got ${'$'}{result?.javaClass}")
        val variant = result as MezzeVariant
        assertEquals("Ok", variant.constructorName, "expected Ok, got: ${'$'}{variant.constructorName}")
        val inner = variant.payloadValue as MezzeRecord
        return inner.getField("val") as MezzeVariant
    }

    /** Assert the parsed Json variant is a JInt with the given value. */
    private fun assertJInt(json: MezzeVariant, expected: Long) {
        assertEquals("JInt", json.constructorName)
        val payload = json.payloadValue as MezzeRecord
        assertEquals(expected, payload.getField("val") as Long)
    }

    /** Assert the parsed Json variant is a JFloat with the given value. */
    private fun assertJFloat(json: MezzeVariant, expected: Double, delta: Double = 1e-9) {
        assertEquals("JFloat", json.constructorName)
        val payload = json.payloadValue as MezzeRecord
        assertEquals(expected, payload.getField("val") as Double, delta)
    }

    /** Assert the parsed Json variant is a JString with the given value. */
    private fun assertJString(json: MezzeVariant, expected: String) {
        assertEquals("JString", json.constructorName)
        val payload = json.payloadValue as MezzeRecord
        assertEquals(expected, payload.getField("val") as String)
    }

    @Test
    fun `parse zero int`() {
        assertJInt(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "0" }
        """.trimIndent())), 0L)
    }

    @Test
    fun `parse negative int`() {
        assertJInt(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "-1" }
        """.trimIndent())), -1L)
    }

    @Test
    fun `parse large safe long`() {
        // 2^53 = 9007199254740992 is the largest whole-value double.
        // dsl-json's number heuristic collapses to JInt when representable.
        assertJInt(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "9007199254740992" }
        """.trimIndent())), 9007199254740992L)
    }

    @Test
    fun `parse very negative long`() {
        assertJInt(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "-9007199254740992" }
        """.trimIndent())), -9007199254740992L)
    }

    @Test
    fun `parse fractional float`() {
        // 0.5 has a non-integer decimal so must come back as JFloat —
        // dsl-json's number heuristic collapses whole-valued doubles
        // (e.g. "0.0", "1.0") to JInt (see DslJsonCodec.java:220-239),
        // so pick a value that provably cannot round-trip through Long.
        assertJFloat(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "0.5" }
        """.trimIndent())), 0.5)
    }

    @Test
    fun `parse negative float`() {
        assertJFloat(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "-3.14" }
        """.trimIndent())), -3.14, 1e-9)
    }

    @Test
    fun `parse float with exponent`() {
        // 1e300 far exceeds Long range so cannot collapse to JInt.
        assertJFloat(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "1e300" }
        """.trimIndent())), 1e300, 1e290)
    }

    @Test
    fun `parse empty string`() {
        assertJString(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "\"\"" }
        """.trimIndent())), "")
    }

    @Test
    fun `parse unicode string`() {
        // é U+00E9, 🎉 U+1F389 — round through UTF-8.
        assertJString(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "\"h\u00e9llo \ud83c\udf89\"" }
        """.trimIndent())), "héllo 🎉")
    }

    @Test
    fun `parse string with escapes`() {
        // Newline, tab, quote, backslash — all valid JSON escapes.
        assertJString(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "\"a\\nb\\tc\\\"d\\\\e\"" }
        """.trimIndent())), "a\nb\tc\"d\\e")
    }

    @Test
    fun `parse unicode escape sequence`() {
        // \u00e9 in JSON literal — parser must decode to é.
        assertJString(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "\"\\u00e9\"" }
        """.trimIndent())), "é")
    }

    // ========================================================================
    // Array parse edge cases
    // ========================================================================

    @Test
    fun `parse empty array`() {
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "[]" }
        """.trimIndent()))
        assertEquals("JArray", json.constructorName)
        val payload = json.payloadValue as MezzeRecord
        val items = payload.getField("items") as Array<*>
        assertEquals(0, items.size)
    }

    @Test
    fun `parse singleton array`() {
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "[42]" }
        """.trimIndent()))
        assertEquals("JArray", json.constructorName)
        val payload = json.payloadValue as MezzeRecord
        val items = payload.getField("items") as Array<*>
        assertEquals(1, items.size)
        assertJInt(items[0] as MezzeVariant, 42L)
    }

    @Test
    fun `parse array of mixed primitive types`() {
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "[1, \"two\", true, null, 3.14]" }
        """.trimIndent()))
        val items = (json.payloadValue as MezzeRecord).getField("items") as Array<*>
        assertEquals(5, items.size)
        assertJInt(items[0] as MezzeVariant, 1L)
        assertJString(items[1] as MezzeVariant, "two")
        assertEquals("JBool", (items[2] as MezzeVariant).constructorName)
        assertEquals("JNull", (items[3] as MezzeVariant).constructorName)
        assertJFloat(items[4] as MezzeVariant, 3.14, 1e-9)
    }

    @Test
    fun `parse nested array`() {
        // [[1, 2], [3, 4]]
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "[[1, 2], [3, 4]]" }
        """.trimIndent()))
        val outer = (json.payloadValue as MezzeRecord).getField("items") as Array<*>
        assertEquals(2, outer.size)
        val inner0 = (outer[0] as MezzeVariant).payloadValue as MezzeRecord
        val inner0Items = inner0.getField("items") as Array<*>
        assertEquals(2, inner0Items.size)
        assertJInt(inner0Items[0] as MezzeVariant, 1L)
        assertJInt(inner0Items[1] as MezzeVariant, 2L)
    }

    // ========================================================================
    // Object parse edge cases
    // ========================================================================

    @Test
    fun `parse empty object`() {
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "{}" }
        """.trimIndent()))
        assertEquals("JObject", json.constructorName)
        val fields = (json.payloadValue as MezzeRecord).getField("fields") as Array<*>
        assertEquals(0, fields.size)
    }

    @Test
    fun `parse single-field object`() {
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "{\"k\": 1}" }
        """.trimIndent()))
        val fields = (json.payloadValue as MezzeRecord).getField("fields") as Array<*>
        assertEquals(1, fields.size)
        val entry = fields[0] as MezzeRecord
        assertEquals("k", entry.getField("key") as String)
        assertJInt(entry.getField("value") as MezzeVariant, 1L)
    }

    @Test
    fun `parse deeply nested object`() {
        // 5-deep: {a: {b: {c: {d: {e: 1}}}}}
        val json = okJson(eval("""
            use std::codec::json
            json::parse_str { s = "{\"a\": {\"b\": {\"c\": {\"d\": {\"e\": 1}}}}}" }
        """.trimIndent()))
        var cur = json
        for (letter in listOf("a", "b", "c", "d", "e")) {
            assertEquals("JObject", cur.constructorName)
            val entries = (cur.payloadValue as MezzeRecord).getField("fields") as Array<*>
            assertEquals(1, entries.size)
            val entry = entries[0] as MezzeRecord
            assertEquals(letter, entry.getField("key") as String)
            val next = entry.getField("value")
            if (letter == "e") {
                assertJInt(next as MezzeVariant, 1L)
            } else {
                cur = next as MezzeVariant
            }
        }
    }

    // ========================================================================
    // Round-trip: parse(render(x)) == Ok(x)
    // ========================================================================

    @Test
    fun `roundtrip int survives parse of render`() {
        // Build JInt, serialize, parse — value must survive.
        val json = okJson(eval("""
            use std::codec::json::{ Json, to_str }
            use std::codec::json
            let j: Json = JInt {val = 12345}
            let s = to_str { json = j }
            json::parse_str { s = s }
        """.trimIndent()))
        assertJInt(json, 12345L)
    }

    @Test
    fun `roundtrip string with unicode survives`() {
        // The native serializer does UTF-8 encoding; parse must reverse it.
        // Use the stdlib `to_str` wrapper (which itself calls the native)
        // because bare `__native_*` names are not addressable from user
        // Mezze code — the parser restricts that prefix to stdlib.
        val json = okJson(eval("""
            use std::codec::json::{ Json, to_str }
            use std::codec::json
            let j: Json = JString {val = "café ☕"}
            let s = to_str { json = j }
            json::parse_str { s = s }
        """.trimIndent()))
        assertJString(json, "café ☕")
    }

    @Test
    fun `roundtrip nested object survives`() {
        // Serialize via the stdlib wrapper (native under the hood), parse back.
        val json = okJson(eval("""
            use std::codec::json::{ to_str }
            use std::codec::json
            let fields = [
              { key = "a", value = JInt { val = 1 } },
              { key = "b", value = JArray { items = [JInt { val = 2 }, JInt { val = 3 }] } }
            ]
            let j = JObject { fields = fields }
            let s = to_str { json = j }
            json::parse_str { s = s }
        """.trimIndent()))
        assertEquals("JObject", json.constructorName)
        val entries = (json.payloadValue as MezzeRecord).getField("fields") as Array<*>
        assertEquals(2, entries.size)
        val e0 = entries[0] as MezzeRecord
        val e1 = entries[1] as MezzeRecord
        assertEquals("a", e0.getField("key") as String)
        assertJInt(e0.getField("value") as MezzeVariant, 1L)
        assertEquals("b", e1.getField("key") as String)
        val arr = (e1.getField("value") as MezzeVariant).payloadValue as MezzeRecord
        val items = arr.getField("items") as Array<*>
        assertEquals(2, items.size)
        assertJInt(items[0] as MezzeVariant, 2L)
        assertJInt(items[1] as MezzeVariant, 3L)
    }

    // ========================================================================
    // Parse failure cases (ParseError shape)
    // ========================================================================

    /** Assert the result is an Err carrying a Phase-1 ParseError record. */
    private fun assertParseErr(result: Any, reasonMatches: (String) -> Boolean = { true }) {
        assertTrue(result is MezzeVariant, "expected MezzeVariant, got ${'$'}{result?.javaClass}")
        val variant = result as MezzeVariant
        assertEquals("Err", variant.constructorName)
        val inner = variant.payloadValue as MezzeRecord
        val error = inner.getField("error") as MezzeRecord
        // Phase-1 ParseError: { path: [Str], reason: Str, input_ref: Option Str }
        assertNotNull(error.getField("path"))
        val reason = error.getField("reason") as String
        assertTrue(reasonMatches(reason), "reason did not match: '${'$'}reason'")
        // input_ref field must exist; may be None or Some Str.
        val inputRef = error.getField("input_ref")
        assertTrue(inputRef is MezzeVariant, "input_ref must be Option (MezzeVariant)")
    }

    @Test
    fun `parse unterminated string`() {
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "\"abc" }
        """.trimIndent())) { it.contains("parse error", ignoreCase = true) }
    }

    @Test
    fun `parse missing colon in object`() {
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "{\"a\" 1}" }
        """.trimIndent()))
    }

    @Test
    fun `parse trailing comma in array`() {
        // Strict JSON: [1, 2, 3,] is invalid.
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "[1, 2, 3,]" }
        """.trimIndent()))
    }

    @Test
    fun `parse non-JSON garbage`() {
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "not json at all !!" }
        """.trimIndent()))
    }

    @Test
    fun `parse empty input`() {
        // Empty string is not a valid JSON document.
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "" }
        """.trimIndent()))
    }

    @Test
    fun `parse whitespace-only input`() {
        // parse_str trims input in the native — after trim, empty.
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "   \n\t  " }
        """.trimIndent()))
    }

    @Test
    fun `parse unbalanced object braces`() {
        assertParseErr(eval("""
            use std::codec::json
            json::parse_str { s = "{\"a\":1" }
        """.trimIndent()))
    }

    // ========================================================================
    // Edge cases: long strings and deep nesting
    // ========================================================================

    @Test
    fun `parse control character escape`() {
        // JSON forbids raw control chars in strings; \u0007 escape is legal.
        assertJString(okJson(eval("""
            use std::codec::json
            json::parse_str { s = "\"\\u0007bell\"" }
        """.trimIndent())), "\u0007bell")
    }

    @Test
    fun `parse very long string`() {
        // 10K-char body — exercises the parser's buffer growth path
        // without hitting Mezze's Truffle stack cap. `str::repeat` is a
        // recursive stdlib helper (see stdlib/std/str.mz) that would
        // blow the interpreter stack for n=10000, so we inline the
        // literal into the Mezze source string from Kotlin instead.
        val body = "x".repeat(10_000)
        val source = "use std::codec::json\n" +
                "json::parse_str { s = \"\\\"" + body + "\\\"\" }"
        val json = okJson(eval(source))
        assertJString(json, body)
    }

    @Test
    fun `parse deeply nested array succeeds or errors cleanly`() {
        // 100-level nesting: [[[...1...]]]. Either the parser accepts it and
        // returns Ok, or it rejects with a well-formed ParseError. What we
        // must NOT get is a Java stack trace or a mangled record.
        val depth = 100
        val src = "[".repeat(depth) + "1" + "]".repeat(depth)
        val result = eval("""
            use std::codec::json
            json::parse_str { s = "${'$'}src" }
        """.trimIndent())
        assertTrue(result is MezzeVariant, "must be a MezzeVariant (Ok or Err)")
        val v = result as MezzeVariant
        assertTrue(v.constructorName == "Ok" || v.constructorName == "Err",
            "must be Ok or Err, got ${'$'}{v.constructorName}")
        if (v.constructorName == "Err") {
            // Error path — must be a well-formed ParseError.
            val error = (v.payloadValue as MezzeRecord).getField("error") as MezzeRecord
            assertNotNull(error.getField("reason"))
        }
    }

    // ========================================================================
    // Serialize edge cases (round-trip on the render side)
    // ========================================================================

    @Test
    fun `serialize empty JArray`() {
        val result = eval("""
            use std::codec::json::{ Json, to_str }
            let j: Json = JArray { items = [] }
            to_str { json = j }
        """.trimIndent())
        assertEquals("[]", result)
    }

    @Test
    fun `serialize empty JObject`() {
        val result = eval("""
            use std::codec::json::{ Json, to_str }
            let j: Json = JObject { fields = [] }
            to_str { json = j }
        """.trimIndent())
        assertEquals("{}", result)
    }

    @Test
    fun `serialize JBool false`() {
        val result = eval("""
            use std::codec::json::{ Json, to_str }
            let j: Json = JBool { val = False }
            to_str { json = j }
        """.trimIndent())
        assertEquals("false", result)
    }
}
