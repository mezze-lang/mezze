package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/json module.
 * Tests JSON serialization, parsing, and field transformation.
 */
class JsonModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === JSON Type Construction Tests ===

    @Test
    fun `test JNull construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JNull
            match j is
                JNull -> "null"
                _ -> "other"
        """)
        assertTrue(result is MezzeString)
        assertEquals("null", (result as MezzeString).value)
    }

    @Test
    fun `test JBool construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JBool {val = True}
            match j is
                JBool {val} -> val
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test JInt construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JInt {val = 42}
            match j is
                JInt {val} -> val
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test JFloat construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JFloat {val = 3.14}
            match j is
                JFloat {val} -> val > 3.0
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test JString construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JString {val = "hello"}
            match j is
                JString {val} -> val
                _ -> ""
        """)
        assertTrue(result is MezzeString)
        assertEquals("hello", (result as MezzeString).value)
    }

    @Test
    fun `test JArray construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JArray {items = [JInt {val = 1}, JInt {val = 2}, JInt {val = 3}]}
            match j is
                JArray {items} -> __native_arraylist_length {xs = items}
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test JObject construction`() {
        val result = eval("""
            use std::codec::json::{Json}

            let j: Json = JObject {fields = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "age", value = JInt {val = 30}}
            ]}
            match j is
                JObject {fields} -> __native_arraylist_length {xs = fields}
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    // === Serialization Tests ===

    @Test
    fun `test to_string with JNull`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JNull}
        """)
        assertTrue(result is MezzeString)
        assertEquals("null", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with JBool true`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JBool {val = True}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("true", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with JBool false`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JBool {val = False}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("false", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with JInt`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JInt {val = 42}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("42", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with JString`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JString {val = "hello"}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("\"hello\"", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with JArray`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JArray {items = [JInt {val = 1}, JInt {val = 2}]}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("[1,2]", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with JObject`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JObject {fields = [{key = "name", value = JString {val = "Alice"}}]}}
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("name"))
        assertTrue(str.contains("Alice"))
    }

    @Test
    fun `test to_string with empty array`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JArray {items = []}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("[]", (result as MezzeString).value)
    }

    @Test
    fun `test to_string with empty object`() {
        val result = eval("""
            use std::codec::json::{Json, to_string}

            to_string {json = JObject {fields = []}}
        """)
        assertTrue(result is MezzeString)
        assertEquals("{}", (result as MezzeString).value)
    }

    // === Parsing Tests ===

    @Test
    fun `test parse_str with null`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}

            let parsed = parse_str {s = "null"}
            match parsed is
                Ok {val} -> match val is
                    JNull -> True
                    _ -> False
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test parse_str with boolean true`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}

            let parsed = parse_str {s = "true"}
            match parsed is
                Ok {val} -> match val is
                    JBool {val = v} -> v
                    _ -> False
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test parse_str with integer`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}

            let parsed = parse_str {s = "42"}
            match parsed is
                Ok {val} -> match val is
                    JInt {val = v} -> v
                    _ -> 0
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test parse_str with string`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}

            let parsed = parse_str {s = "\"hello\""}
            match parsed is
                Ok {val} -> match val is
                    JString {val = v} -> v
                    _ -> ""
                _ -> ""
        """)
        assertTrue(result is MezzeString)
        assertEquals("hello", (result as MezzeString).value)
    }

    @Test
    fun `test parse_str with array`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}

            let parsed = parse_str {s = "[1, 2, 3]"}
            match parsed is
                Ok {val} -> match val is
                    JArray {items} -> __native_arraylist_length {xs = items}
                    _ -> 0
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test parse_str with object`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}

            let parsed = parse_str {s = "{\"name\": \"Alice\", \"age\": 30}"}
            match parsed is
                Ok {val} -> match val is
                    JObject {fields} -> __native_arraylist_length {xs = fields}
                    _ -> 0
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    fun `test parse_str with invalid json`() {
        val result = eval("""
            use std::codec::json::{parse_str}

            let parsed = parse_str {s = "{invalid}"}
            match parsed is
                Ok {val} -> False
                _ -> True
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Field Transformation Tests ===

    @Test
    fun `test transform_field_name identity`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = Identity}
        """)
        assertTrue(result is MezzeString)
        assertEquals("userName", (result as MezzeString).value)
    }

    @Test
    fun `test transform_field_name snake_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = SnakeCase}
        """)
        assertTrue(result is MezzeString)
        assertEquals("user_name", (result as MezzeString).value)
    }

    @Test
    fun `test transform_field_name camel_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "user_name", transform = CamelCase}
        """)
        assertTrue(result is MezzeString)
        assertEquals("userName", (result as MezzeString).value)
    }

    @Test
    fun `test transform_field_name kebab_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = KebabCase}
        """)
        assertTrue(result is MezzeString)
        assertEquals("user-name", (result as MezzeString).value)
    }

    @Test
    fun `test transform_field_name screaming_snake_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = ScreamingSnakeCase}
        """)
        assertTrue(result is MezzeString)
        assertEquals("USER_NAME", (result as MezzeString).value)
    }

    // === transform_fields Tests ===

    @Test
    fun `test transform_fields on object with snake_case`() {
        val result = eval("""
            use std::codec::json::{Json, transform_fields, to_string}
            use std::codec::core::{FieldTransform}

            let obj: Json = JObject {fields = [
                {key = "firstName", value = JString {val = "Alice"}},
                {key = "lastName", value = JString {val = "Smith"}}
            ]}
            let transformed = transform_fields {json = obj, transform = SnakeCase}
            to_string {json = transformed}
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("first_name"))
        assertTrue(str.contains("last_name"))
    }

    @Test
    fun `test transform_fields on nested object`() {
        val result = eval("""
            use std::codec::json::{Json, transform_fields, to_string}
            use std::codec::core::{FieldTransform}

            let inner: Json = JObject {fields = [{key = "createdAt", value = JString {val = "2024"}}]}
            let outer: Json = JObject {fields = [{key = "userData", value = inner}]}
            let transformed = transform_fields {json = outer, transform = SnakeCase}
            to_string {json = transformed}
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("user_data"))
        assertTrue(str.contains("created_at"))
    }

    @Test
    fun `test transform_fields with identity is no-op`() {
        val result = eval("""
            use std::codec::json::{Json, transform_fields, to_string}
            use std::codec::core::{FieldTransform}

            let obj: Json = JObject {fields = [{key = "userName", value = JString {val = "Alice"}}]}
            let transformed = transform_fields {json = obj, transform = Identity}
            to_string {json = transformed}
        """)
        assertTrue(result is MezzeString)
        val str = (result as MezzeString).value
        assertTrue(str.contains("userName"))
    }

    // === Config Tests ===

    @Test
    fun `test default_config`() {
        val result = eval("""
            use std::codec::json::{default_config}
            use std::codec::core::{FieldTransform}

            match default_config.field_transform is
                Identity -> True
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test config constructor`() {
        val result = eval("""
            use std::codec::json::{config}
            use std::codec::core::{FieldTransform}

            let cfg = config {
                pretty = True,
                indent = 4,
                field_transform = SnakeCase,
                skip_null = True
            }
            cfg.pretty
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Pretty Printing Tests ===

    @Test
    fun `test serialize_to_string with pretty config`() {
        val result = eval("""
            use std::codec::json::{Json, serialize_to_string, JsonConfig}
            use std::codec::core::{FieldTransform}

            let obj: Json = JObject {fields = [{key = "x", value = JInt {val = 1}}]}
            let cfg: JsonConfig = {pretty = True, indent = 2, field_transform = Identity, skip_null = False}
            let s = serialize_to_string {json = obj, config = cfg}
            __native_string_contains {s = s, substring = "\n"}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test serialize_to_string with compact config`() {
        val result = eval("""
            use std::codec::json::{Json, serialize_to_string, JsonConfig}
            use std::codec::core::{FieldTransform}

            let obj: Json = JObject {fields = [{key = "x", value = JInt {val = 1}}]}
            let cfg: JsonConfig = {pretty = False, indent = 2, field_transform = Identity, skip_null = False}
            let s = serialize_to_string {json = obj, config = cfg}
            __native_string_contains {s = s, substring = "\n"}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    // === Roundtrip Tests ===

    @Test
    fun `test roundtrip parse and serialize`() {
        val result = eval("""
            use std::codec::json::{parse_str, to_string}

            let original = "{\"count\":42}"
            let parsed = parse_str {s = original}
            match parsed is
                Ok {val} -> do
                    let serialized = to_string {json = val}
                    serialized == "{\"count\":42}"
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }
}
