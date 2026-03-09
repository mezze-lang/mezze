package com.mezze.interpreter.v2.codec

import com.mezze.interpreter.v2.MezzeTestHelper
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.values.MezzeRecord
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
            # Direct native call for parsing
            let result = __native_json_parse {s = "\"hello\""}
            result
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
            let result = __native_json_parse {s = "42"}
            result
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
            let result = __native_json_parse {s = "3.14"}
            result
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
            let result = __native_json_parse {s = "true"}
            result
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
            let result = __native_json_parse {s = "false"}
            result
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
            let result = __native_json_parse {s = "null"}
            result
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
            let result = __native_json_parse {s = "[1, 2, 3]"}
            result
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
            let result = __native_json_parse {s = "{\"name\": \"Alice\", \"age\": 30}"}
            result
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
            let result = __native_json_parse {s = "invalid json {{"}
            result
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
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String } |
            let json: Json = JString {val = "hello"}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        assertEquals("\"hello\"", jsonString)
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

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        assertEquals("42", jsonString)
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

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        assertEquals("3.14", jsonString)
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

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        assertEquals("true", jsonString)
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

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        assertEquals("null", jsonString)
    }

    @Test
    fun `serialize JArray`() {
        val source = """
            # Define Json type locally for testing (with Seq for items)
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String }, JArray { items: [Json] } |
            let items: [Json] = [JInt {val = 1}, JInt {val = 2}, JInt {val = 3}]
            let json: Json = JArray {items = items}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        assertEquals("[1,2,3]", jsonString)
    }

    @Test
    fun `serialize JObject`() {
        val source = """
            # Define Json type locally for testing
            type Json = | JNull, JBool { val: Bool }, JInt { val: Int }, JFloat { val: Float }, JString { val: String }, JArray { items: [Json] }, JObject { fields: [{key: String, value: Json}] } |
            let fields: [{key: String, value: Json}] = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "age", value = JInt {val = 30}}
            ]
            let json: Json = JObject {fields = fields}
            let result = __native_json_serialize {json = json, pretty = False}
            result
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is MezzeVariant)
        val okVariant = result as MezzeVariant
        assertEquals("Ok", okVariant.constructorName)

        val innerRecord = okVariant.payloadValue as MezzeRecord
        val jsonString = innerRecord.getField("val") as String
        // JSON object order may vary, check contains
        assertTrue(jsonString.contains("\"name\""))
        assertTrue(jsonString.contains("\"Alice\""))
        assertTrue(jsonString.contains("\"age\""))
        assertTrue(jsonString.contains("30"))
    }
}
