package com.mezze.language.interpreter.codec

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
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

        val result = MezzeTestHelper.run(source)

        // Should be Ok variant containing JString
        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JString", jsonValue.constructor)

        val stringRecord = jsonValue.value as MezzeRecord
        val stringValue = stringRecord.fields["val"] as MezzeString
        assertEquals("hello", stringValue.value)
    }

    @Test
    fun `parse JSON integer`() {
        val source = """
            let result = __native_json_parse {s = "42"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JInt", jsonValue.constructor)

        val intRecord = jsonValue.value as MezzeRecord
        val intValue = intRecord.fields["val"] as MezzeInt
        assertEquals(42L, intValue.value)
    }

    @Test
    fun `parse JSON float`() {
        val source = """
            let result = __native_json_parse {s = "3.14"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JFloat", jsonValue.constructor)

        val floatRecord = jsonValue.value as MezzeRecord
        val floatValue = floatRecord.fields["val"] as MezzeFloat
        assertEquals(3.14, floatValue.value, 0.001)
    }

    @Test
    fun `parse JSON boolean true`() {
        val source = """
            let result = __native_json_parse {s = "true"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JBool", jsonValue.constructor)

        val boolRecord = jsonValue.value as MezzeRecord
        val boolValue = boolRecord.fields["val"] as MezzeBool
        assertTrue(boolValue.value)
    }

    @Test
    fun `parse JSON boolean false`() {
        val source = """
            let result = __native_json_parse {s = "false"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JBool", jsonValue.constructor)

        val boolRecord = jsonValue.value as MezzeRecord
        val boolValue = boolRecord.fields["val"] as MezzeBool
        assertFalse(boolValue.value)
    }

    @Test
    fun `parse JSON null`() {
        val source = """
            let result = __native_json_parse {s = "null"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JNull", jsonValue.constructor)
        assertNull(jsonValue.value)
    }

    @Test
    fun `parse JSON array`() {
        val source = """
            let result = __native_json_parse {s = "[1, 2, 3]"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JArray", jsonValue.constructor)

        val arrayRecord = jsonValue.value as MezzeRecord
        val elementsList = arrayRecord.fields["items"] as MezzeArrayList
        assertEquals(3, elementsList.toList().size)
    }

    @Test
    fun `parse JSON object`() {
        val source = """
            let result = __native_json_parse {s = "{\"name\": \"Alice\", \"age\": 30}"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonValue = innerRecord.fields["val"] as MezzeVariant
        assertEquals("JObject", jsonValue.constructor)

        val objRecord = jsonValue.value as MezzeRecord
        val fieldsList = objRecord.fields["fields"] as MezzeArrayList
        assertEquals(2, fieldsList.toList().size)
    }

    @Test
    fun `parse invalid JSON returns error`() {
        val source = """
            let result = __native_json_parse {s = "invalid json {{"}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val errVariant = result as MezzeVariant
        assertEquals("Err", errVariant.constructor)

        val errRecord = errVariant.value as MezzeRecord
        val error = errRecord.fields["error"] as MezzeRecord
        val message = error.fields["message"] as MezzeString
        assertTrue(message.value.contains("parse error"), "Error message should mention parse error")
    }

    // ========================================================================
    // Native JSON Serialize Tests
    // ========================================================================

    @Test
    fun `serialize JString`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String } |
            let json: Json = JString {val = "hello"}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        assertEquals("\"hello\"", jsonString.value)
    }

    @Test
    fun `serialize JInt`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String } |
            let json: Json = JInt {val = 42}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        assertEquals("42", jsonString.value)
    }

    @Test
    fun `serialize JFloat`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String } |
            let json: Json = JFloat {val = 3.14}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        assertEquals("3.14", jsonString.value)
    }

    @Test
    fun `serialize JBool true`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String } |
            let json: Json = JBool {val = True}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        assertEquals("true", jsonString.value)
    }

    @Test
    fun `serialize JNull`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String } |
            let json: Json = JNull
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        assertEquals("null", jsonString.value)
    }

    @Test
    fun `serialize JArray`() {
        val source = """
            # Define Json type locally for testing (with Array for items)
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String }, JArray { items: Array Json } |
            let items: Array Json = [JInt {val = 1}, JInt {val = 2}, JInt {val = 3}]
            let json: Json = JArray {items = items}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        assertEquals("[1,2,3]", jsonString.value)
    }

    @Test
    fun `serialize JObject`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String }, JArray { items: Array Json }, JObject { fields: Array {key: String, value: Json} } |
            let fields: Array {key: String, value: Json} = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "age", value = JInt {val = 30}}
            ]
            let json: Json = JObject {fields = fields}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = MezzeTestHelper.run(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructor)

        val innerRecord = okVariant.value as MezzeRecord
        val jsonString = innerRecord.fields["val"] as MezzeString
        // JSON object order may vary, check contains
        assertTrue(jsonString.value.contains("\"name\""))
        assertTrue(jsonString.value.contains("\"Alice\""))
        assertTrue(jsonString.value.contains("\"age\""))
        assertTrue(jsonString.value.contains("30"))
    }
}
