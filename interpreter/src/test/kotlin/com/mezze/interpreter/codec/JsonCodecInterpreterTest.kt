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
        val message = error.getField("message") as String
        assertTrue(message.contains("parse error"), "Error message should mention parse error")
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
}
