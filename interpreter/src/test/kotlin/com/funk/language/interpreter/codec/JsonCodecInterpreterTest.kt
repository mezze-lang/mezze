package com.funk.language.interpreter.codec

import com.funk.language.interpreter.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.ModuleResolver
import com.funk.language.typechecker.TypeChecker
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
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    // ========================================================================
    // Native JSON Parse Tests
    // ========================================================================

    @Test
    fun `parse simple JSON string`() {
        val source = """
            # Direct native call for parsing
            let result = __native_json_parse {s = "\"hello\""}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        // Should be Ok variant containing JString
        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JString", jsonValue.constructor)

        val stringRecord = jsonValue.value as FunkRecord
        val stringValue = stringRecord.fields["value"] as FunkString
        assertEquals("hello", stringValue.value)
    }

    @Test
    fun `parse JSON integer`() {
        val source = """
            let result = __native_json_parse {s = "42"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JInt", jsonValue.constructor)

        val intRecord = jsonValue.value as FunkRecord
        val intValue = intRecord.fields["value"] as FunkInt
        assertEquals(42L, intValue.value)
    }

    @Test
    fun `parse JSON float`() {
        val source = """
            let result = __native_json_parse {s = "3.14"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JFloat", jsonValue.constructor)

        val floatRecord = jsonValue.value as FunkRecord
        val floatValue = floatRecord.fields["value"] as FunkFloat
        assertEquals(3.14, floatValue.value, 0.001)
    }

    @Test
    fun `parse JSON boolean true`() {
        val source = """
            let result = __native_json_parse {s = "true"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JBool", jsonValue.constructor)

        val boolRecord = jsonValue.value as FunkRecord
        val boolValue = boolRecord.fields["value"] as FunkBool
        assertTrue(boolValue.value)
    }

    @Test
    fun `parse JSON boolean false`() {
        val source = """
            let result = __native_json_parse {s = "false"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JBool", jsonValue.constructor)

        val boolRecord = jsonValue.value as FunkRecord
        val boolValue = boolRecord.fields["value"] as FunkBool
        assertFalse(boolValue.value)
    }

    @Test
    fun `parse JSON null`() {
        val source = """
            let result = __native_json_parse {s = "null"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JNull", jsonValue.constructor)
        assertNull(jsonValue.value)
    }

    @Test
    fun `parse JSON array`() {
        val source = """
            let result = __native_json_parse {s = "[1, 2, 3]"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JArray", jsonValue.constructor)

        val arrayRecord = jsonValue.value as FunkRecord
        val elementsList = arrayRecord.fields["elements"] as FunkList
        assertEquals(3, elementsList.toKotlinList().size)
    }

    @Test
    fun `parse JSON object`() {
        val source = """
            let result = __native_json_parse {s = "{\"name\": \"Alice\", \"age\": 30}"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonValue = innerRecord.fields["val"] as FunkVariant
        assertEquals("JObject", jsonValue.constructor)

        val objRecord = jsonValue.value as FunkRecord
        val fieldsList = objRecord.fields["fields"] as FunkList
        assertEquals(2, fieldsList.toKotlinList().size)
    }

    @Test
    fun `parse invalid JSON returns error`() {
        val source = """
            let result = __native_json_parse {s = "invalid json {{"}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val errVariant = result as FunkVariant
        assertEquals("Err", errVariant.constructor)

        val errRecord = errVariant.value as FunkRecord
        val error = errRecord.fields["error"] as FunkRecord
        val message = error.fields["message"] as FunkString
        assertTrue(message.value.contains("parse error"), "Error message should mention parse error")
    }

    // ========================================================================
    // Native JSON Serialize Tests
    // ========================================================================

    @Test
    fun `serialize JString`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String } |
            let json: Json = JString {value = "hello"}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        assertEquals("\"hello\"", jsonString.value)
    }

    @Test
    fun `serialize JInt`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String } |
            let json: Json = JInt {value = 42}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        assertEquals("42", jsonString.value)
    }

    @Test
    fun `serialize JFloat`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String } |
            let json: Json = JFloat {value = 3.14}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        assertEquals("3.14", jsonString.value)
    }

    @Test
    fun `serialize JBool true`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String } |
            let json: Json = JBool {value = True}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        assertEquals("true", jsonString.value)
    }

    @Test
    fun `serialize JNull`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String } |
            let json: Json = JNull
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        assertEquals("null", jsonString.value)
    }

    @Test
    fun `serialize JArray`() {
        val source = """
            use std::collections::list
            # Define Json type locally for testing (with Array for elements since [...] syntax creates Array)
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String }, JArray { elements: List Json } |
            let elements: List Json = list::from_array {xs = [JInt {value = 1}, JInt {value = 2}, JInt {value = 3}]}
            let json: Json = JArray {elements = elements}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        assertEquals("[1,2,3]", jsonString.value)
    }

    @Test
    fun `serialize JObject`() {
        val source = """
            use std::collections::list
            # Define Json type locally for testing
            type Json = | JNull, JBool { value: Bool }, JInt { value: Int }, JFloat { value: Float }, JString { value: String }, JArray { elements: List Json }, JObject { fields: List {key: String, value: Json} } |
            let fields: List {key: String, value: Json} = list::from_array {xs = [
                {key = "name", value = JString {value = "Alice"}},
                {key = "age", value = JInt {value = 30}}
            ]}
            let json: Json = JObject {fields = fields}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkVariant)
        val okVariant = result as FunkVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as FunkRecord
        val jsonString = innerRecord.fields["val"] as FunkString
        // JSON object order may vary, check contains
        assertTrue(jsonString.value.contains("\"name\""))
        assertTrue(jsonString.value.contains("\"Alice\""))
        assertTrue(jsonString.value.contains("\"age\""))
        assertTrue(jsonString.value.contains("30"))
    }
}
