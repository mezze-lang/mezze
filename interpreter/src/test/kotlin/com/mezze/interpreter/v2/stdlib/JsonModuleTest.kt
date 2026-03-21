package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/json module.
 * Migrated from V1 to V2 with unboxed values.
 */
class JsonModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

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
        assertEquals("null", result)
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
        assertEquals(true, result)
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
        assertEquals(42L, result)
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
        assertEquals(true, result)
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
        assertEquals("hello", result)
    }

    @Test
    fun `test JArray construction`() {
        val result = eval("""
            use std::codec::json::{Json}
            use std::collections::seq

            let j: Json = JArray {items = [JInt {val = 1}, JInt {val = 2}, JInt {val = 3}]}
            match j is
                JArray {items} -> seq::length {xs = items}
                _ -> 0
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test JObject construction`() {
        val result = eval("""
            use std::codec::json::{Json}
            use std::collections::seq

            let j: Json = JObject {fields = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "age", value = JInt {val = 30}}
            ]}
            match j is
                JObject {fields} -> seq::length {xs = fields}
                _ -> 0
        """)
        assertEquals(2L, result)
    }

    // === Serialization Tests ===

    @Test
    fun `test to_str with JNull`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JNull}
        """)
        assertEquals("null", result)
    }

    @Test
    fun `test to_str with JBool true`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JBool {val = True}}
        """)
        assertEquals("true", result)
    }

    @Test
    fun `test to_str with JBool false`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JBool {val = False}}
        """)
        assertEquals("false", result)
    }

    @Test
    fun `test to_str with JInt`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JInt {val = 42}}
        """)
        assertEquals("42", result)
    }

    @Test
    fun `test to_str with JString`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JString {val = "hello"}}
        """)
        assertEquals("\"hello\"", result)
    }

    @Test
    fun `test to_str with JArray`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JArray {items = [JInt {val = 1}, JInt {val = 2}]}}
        """)
        assertEquals("[1,2]", result)
    }

    @Test
    fun `test to_str with JObject`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}
            use std::str

            let str = to_str {json = JObject {fields = [{key = "name", value = JString {val = "Alice"}}]}}
            str::contains {s = str, substring = "name"}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test to_str with empty array`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JArray {items = []}}
        """)
        assertEquals("[]", result)
    }

    @Test
    fun `test to_str with empty object`() {
        val result = eval("""
            use std::codec::json::{Json, to_str}

            to_str {json = JObject {fields = []}}
        """)
        assertEquals("{}", result)
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
        assertEquals(true, result)
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
        assertEquals(true, result)
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
        assertEquals(42L, result)
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
        assertEquals("hello", result)
    }

    @Test
    fun `test parse_str with array`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}
            use std::collections::seq

            let parsed = parse_str {s = "[1, 2, 3]"}
            match parsed is
                Ok {val} -> match val is
                    JArray {items} -> seq::length {xs = items}
                    _ -> 0
                _ -> 0
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test parse_str with object`() {
        val result = eval("""
            use std::codec::json::{Json, parse_str}
            use std::collections::seq

            let parsed = parse_str {s = "{\"name\": \"Alice\", \"age\": 30}"}
            match parsed is
                Ok {val} -> match val is
                    JObject {fields} -> seq::length {xs = fields}
                    _ -> 0
                _ -> 0
        """)
        assertEquals(2L, result)
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
        assertEquals(true, result)
    }

    // === Field Transformation Tests ===

    @Test
    fun `test transform_field_name identity`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = Identity}
        """)
        assertEquals("userName", result)
    }

    @Test
    fun `test transform_field_name snake_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = SnakeCase}
        """)
        assertEquals("user_name", result)
    }

    @Test
    fun `test transform_field_name camel_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "user_name", transform = CamelCase}
        """)
        assertEquals("userName", result)
    }

    @Test
    fun `test transform_field_name kebab_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = KebabCase}
        """)
        assertEquals("user-name", result)
    }

    @Test
    fun `test transform_field_name screaming_snake_case`() {
        val result = eval("""
            use std::codec::json::{transform_field_name}
            use std::codec::core::{FieldTransform}

            transform_field_name {name = "userName", transform = ScreamingSnakeCase}
        """)
        assertEquals("USER_NAME", result)
    }

    // === transform_fields Tests ===

    @Test
    fun `test transform_fields on object with snake_case`() {
        val result = eval("""
            use std::codec::json::{Json, transform_fields, to_str}
            use std::codec::core::{FieldTransform}
            use std::str

            let obj: Json = JObject {fields = [
                {key = "firstName", value = JString {val = "Alice"}},
                {key = "lastName", value = JString {val = "Smith"}}
            ]}
            let transformed = transform_fields {json = obj, transform = SnakeCase}
            let str = to_str {json = transformed}
            str::contains {s = str, substring = "first_name"}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test transform_fields on nested object`() {
        val result = eval("""
            use std::codec::json::{Json, transform_fields, to_str}
            use std::codec::core::{FieldTransform}
            use std::str

            let inner: Json = JObject {fields = [{key = "createdAt", value = JString {val = "2024"}}]}
            let outer: Json = JObject {fields = [{key = "userData", value = inner}]}
            let transformed = transform_fields {json = outer, transform = SnakeCase}
            let str = to_str {json = transformed}
            str::contains {s = str, substring = "user_data"}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test transform_fields with identity is no-op`() {
        val result = eval("""
            use std::codec::json::{Json, transform_fields, to_str}
            use std::codec::core::{FieldTransform}
            use std::str

            let obj: Json = JObject {fields = [{key = "userName", value = JString {val = "Alice"}}]}
            let transformed = transform_fields {json = obj, transform = Identity}
            let str = to_str {json = transformed}
            str::contains {s = str, substring = "userName"}
        """)
        assertEquals(true, result)
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
        assertEquals(true, result)
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
        assertEquals(true, result)
    }

    // === Pretty Printing Tests ===

    @Test
    fun `test serialize_to_str with pretty config`() {
        val result = eval("""
            use std::codec::json::{Json, serialize_to_str, JsonConfig}
            use std::codec::core::{FieldTransform}
            use std::str

            let obj: Json = JObject {fields = [{key = "x", value = JInt {val = 1}}]}
            let cfg: JsonConfig = {pretty = True, indent = 2, field_transform = Identity, skip_null = False}
            let s = serialize_to_str {json = obj, config = cfg}
            str::contains {s = s, substring = "\n"}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test serialize_to_str with compact config`() {
        val result = eval("""
            use std::codec::json::{Json, serialize_to_str, JsonConfig}
            use std::codec::core::{FieldTransform}
            use std::str

            let obj: Json = JObject {fields = [{key = "x", value = JInt {val = 1}}]}
            let cfg: JsonConfig = {pretty = False, indent = 2, field_transform = Identity, skip_null = False}
            let s = serialize_to_str {json = obj, config = cfg}
            str::contains {s = s, substring = "\n"}
        """)
        assertEquals(false, result)
    }

    // === Roundtrip Tests ===

    @Test
    fun `test roundtrip parse and serialize`() {
        val result = eval("""
            use std::codec::json::{parse_str, to_str}

            let original = "{\"count\":42}"
            let parsed = parse_str {s = original}
            match parsed is
                Ok {val} -> do
                    let serialized = to_str {json = val}
                    serialized == "{\"count\":42}"
                _ -> False
        """)
        assertEquals(true, result)
    }
}
