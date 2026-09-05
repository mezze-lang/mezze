package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std::codec::json — variant construction, parse/serialize, and
 * the Tier-2/3 dispatch surface that JsonC ships.
 *
 * Migrated from the old codec (`fmt=json_fmt`, `JsonConfig`, `transform_fields`)
 * to the new Tier-1..4 stack:
 *   * `Json` variants are constructed directly as data.
 *   * `to_str` / `serialize_to_str` sit on top of the format-neutral native
 *     JSON serializer.
 *   * Field-name transforms (`SnakeCase`, `CamelCase`, …) are applied at
 *     ENCODE time by the compiler-emitted walker (see design doc §"Field-name
 *     transform") — there is no longer a post-facto `transform_fields`
 *     helper to test at the `Json`-value level.
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
                JArray {items} -> seq::size {xs = items}
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
                JObject {fields} -> seq::size {xs = fields}
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
                    JArray {items} -> seq::size {xs = items}
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
                    JObject {fields} -> seq::size {xs = fields}
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

    // === Field Transformation Tests (helpers in std::codec::core) ===

    @Test
    fun `test transform_field_name identity`() {
        val result = eval("""
            use std::codec::core::{transform_field_name, FieldTransform}

            transform_field_name {name = "userName", transform = Identity}
        """)
        assertEquals("userName", result)
    }

    @Test
    fun `test transform_field_name snake_case`() {
        val result = eval("""
            use std::codec::core::{transform_field_name, FieldTransform}

            transform_field_name {name = "userName", transform = SnakeCase}
        """)
        assertEquals("user_name", result)
    }

    @Test
    fun `test transform_field_name camel_case`() {
        val result = eval("""
            use std::codec::core::{transform_field_name, FieldTransform}

            transform_field_name {name = "user_name", transform = CamelCase}
        """)
        assertEquals("userName", result)
    }

    @Test
    fun `test transform_field_name kebab_case`() {
        val result = eval("""
            use std::codec::core::{transform_field_name, FieldTransform}

            transform_field_name {name = "userName", transform = KebabCase}
        """)
        assertEquals("user-name", result)
    }

    @Test
    fun `test transform_field_name screaming_snake_case`() {
        val result = eval("""
            use std::codec::core::{transform_field_name, FieldTransform}

            transform_field_name {name = "userName", transform = ScreamingSnakeCase}
        """)
        assertEquals("USER_NAME", result)
    }

    // === Config Tests ===

    @Test
    fun `test json_default is Identity transform`() {
        val result = eval("""
            use std::codec::json::{json_default}
            use std::codec::core::{FieldTransform}

            match json_default.shared.field_transform is
                Identity -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test json_pretty preset`() {
        val result = eval("""
            use std::codec::json::{json_pretty}

            json_pretty.pretty
        """)
        assertEquals(true, result)
    }

    // === Pretty Printing Tests ===

    @Test
    fun `test serialize_to_str with pretty config`() {
        val result = eval("""
            use std::codec::json::{Json, serialize_to_str, json_pretty}
            use std::str

            let obj: Json = JObject {fields = [{key = "x", value = JInt {val = 1}}]}
            let s = serialize_to_str {json = obj, config = json_pretty}
            str::contains {s = s, substring = "\n"}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test serialize_to_str with compact config`() {
        val result = eval("""
            use std::codec::json::{Json, serialize_to_str, json_default}
            use std::str

            let obj: Json = JObject {fields = [{key = "x", value = JInt {val = 1}}]}
            let s = serialize_to_str {json = obj, config = json_default}
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
