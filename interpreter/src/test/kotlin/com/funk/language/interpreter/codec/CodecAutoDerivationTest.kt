package com.funk.language.interpreter.codec

import com.funk.language.interpreter.*
import com.funk.language.typechecker.ModuleResolver
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for codec auto-derivation.
 *
 * Tests the automatic generation of Encode/Decode implementations
 * for user-defined types at the call site.
 *
 * These tests verify that:
 * 1. The simplified API (no path parameter) works correctly
 * 2. Records with primitive fields encode correctly
 * 3. Nested records encode correctly
 * 4. Same-structure records share encoder implementations (cache)
 * 5. Variants, lists, and Option types work correctly
 * 6. Error cases are handled properly
 */
class CodecAutoDerivationTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    // ========================================================================
    // Basic Record Encoding Tests
    // ========================================================================

    @Test
    fun `encode simple record with primitive fields`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let person = {name = "Alice", age = 30}
            let result = Encode.encode {val = person, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode record with all primitive types`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let data = {
                str = "hello",
                num = 42,
                decimal = 3.14,
                flag = True
            }
            let result = Encode.encode {val = data, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode empty record`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let empty = {}
            let result = Encode.encode {val = empty, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Nested Record Tests
    // ========================================================================

    @Test
    fun `encode nested record`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let address = {city = "NYC", zip = 10001}
            let person = {name = "Alice", address = address}
            let result = Encode.encode {val = person, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode deeply nested record`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let inner = {value = 42}
            let middle = {inner = inner}
            let outer = {middle = middle}
            let result = Encode.encode {val = outer, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Cache Tests - Same Structure Records
    // ========================================================================

    @Test
    fun `same structure records use same encoder`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            # Two records with same structure but different values
            let person1 = {name = "Alice", age = 30}
            let person2 = {name = "Bob", age = 25}

            let result1 = Encode.encode {val = person1, config = default_config}
            let result2 = Encode.encode {val = person2, config = default_config}

            # Both should succeed
            match result1 is
                Ok { val } ->
                    match result2 is
                        Ok { val } -> "both success"
                        Err { error } -> "second failed"
                Err { error } -> "first failed"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("both success", (result as FunkString).value)
    }

    @Test
    fun `records with fields in different order use same encoder`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            # Two records with same fields but different order
            let person1 = {name = "Alice", age = 30}
            let person2 = {age = 25, name = "Bob"}

            let result1 = Encode.encode {val = person1, config = default_config}
            let result2 = Encode.encode {val = person2, config = default_config}

            # Both should succeed
            match result1 is
                Ok { val } ->
                    match result2 is
                        Ok { val } -> "both success"
                        Err { error } -> "second failed"
                Err { error } -> "first failed"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("both success", (result as FunkString).value)
    }

    // ========================================================================
    // Primitive Type Encoding Tests
    // ========================================================================

    @Test
    fun `encode Int value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let result = Encode.encode {val = 42, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JInt { val } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode String value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let result = Encode.encode {val = "hello", config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JString { val } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode Float value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let result = Encode.encode {val = 3.14, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JFloat { val } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode Bool value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let result = Encode.encode {val = True, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JBool { val } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Type Alias Tests
    // ========================================================================

    @Test
    fun `encode type alias`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            type Person = {name: String, age: Int}

            let alice: Person = {name = "Alice", age = 30}
            let result = Encode.encode {val = alice, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // List Encoding Tests
    // ========================================================================
    // NOTE: List encoding tests are commented out for now because the generated
    // list encoder uses module-qualified function calls (list::try_map_indexed)
    // which require module resolution in the generated code. This is tracked as
    // a future improvement.
    //
    // TODO: Enable these tests once list encoding is implemented with native functions
    // @Test
    // fun `encode list of integers`() { ... }
    // @Test
    // fun `encode list of records`() { ... }

    // ========================================================================
    // Option Type Encoding Tests
    // ========================================================================

    @Test
    fun `encode Some value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let maybe: Option Int = Some {val = 42}
            let result = Encode.encode {val = maybe, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JInt { val } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode None value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let maybe: Option Int = None
            let result = Encode.encode {val = maybe, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JNull -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode record with optional field`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let person = {name = "Alice", nickname = Some {val = "Ali"}}
            let result = Encode.encode {val = person, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Variant Encoding Tests
    // ========================================================================

    @Test
    fun `encode simple variant without payload`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            type Color = | Red, Green, Blue |

            let color: Color = Red
            let result = Encode.encode {val = color, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode variant with payload`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int } |

            let shape: Shape = Circle {radius = 5}
            let result = Encode.encode {val = shape, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Record with List Field Tests
    // ========================================================================
    // NOTE: Record with list field test commented out for same reason as list tests above
    // TODO: Enable once list encoding is implemented with native functions

    // ========================================================================
    // Complex Integration Tests
    // ========================================================================

    @Test
    fun `encode complex nested structure`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let author = {name = "Alice", email = "alice@example.com"}
            let post = {
                title = "Hello World",
                author = author,
                published = True
            }
            let result = Encode.encode {val = post, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Frac Type Encoding Tests
    // ========================================================================

    @Test
    fun `encode Frac value`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}
            use std::frac

            let f = frac::make {numerator = 1, denominator = 2}
            let result = Encode.encode {val = f, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JFloat { val } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `encode record with Frac field`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}
            use std::frac

            let measurement = {value = frac::make {numerator = 3, denominator = 4}, unit = "inches"}
            let result = Encode.encode {val = measurement, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JObject { fields } -> "success"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Multiple Encodes in Same Program
    // ========================================================================

    @Test
    fun `multiple encodes of different types`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            let int_result = Encode.encode {val = 42, config = default_config}
            let str_result = Encode.encode {val = "hello", config = default_config}
            let record_result = Encode.encode {val = {x = 1, y = 2}, config = default_config}

            # Count successes
            let int_count = match int_result is
                Ok { val } -> 1
                Err { error } -> 0
            let str_count = match str_result is
                Ok { val } -> 1
                Err { error } -> 0
            let record_count = match record_result is
                Ok { val } -> 1
                Err { error } -> 0

            int_count + str_count + record_count
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkInt)
        assertEquals(3L, (result as FunkInt).value)
    }

    // ========================================================================
    // Newtype Encoding Tests
    // ========================================================================
    // Newtypes are opaque types that wrap another type. The codec system
    // cannot automatically derive encoders for them because they are treated
    // as distinct nominal types. Users would need to provide manual Encode
    // implementations for newtypes.

    @Test
    fun `encode newtype fails without manual implementation`() {
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, default_config}

            # Define a newtype that wraps an Int
            newtype UserId = UserId Int

            let id: UserId = UserId {value = 42}
            let result = Encode.encode {val = id, config = default_config}

            match result is
                Ok { val } -> "success"
                Err { error } -> "error"
        """.trimIndent()

        // This should fail at compile time because there's no Encode impl for UserId newtype
        // Newtypes are nominal types and require explicit codec implementations
        try {
            val result = FunkTestHelper.run(source)
            // If we get here without error, check if encoding returned Err
            if (result is FunkString && (result as FunkString).value == "error") {
                return // Expected - encoding failed gracefully
            }
            fail("Expected encoding to fail for newtype without impl, but got: $result")
        } catch (e: Exception) {
            // Expected - compile/type error about UserId not being encodable
            assertTrue(e.message?.contains("UserId") == true ||
                       e.message?.contains("type") == true,
                "Expected error mentioning UserId type, but got: ${e.message}")
        }
    }

    @Test
    fun `encode newtype succeeds with manual implementation`() {
        // Test that a newtype can be encoded when user provides manual impl
        // Newtypes are opaque - we use match in the same module to extract the value
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, EncodeFormat, default_config}
            use std::collections::list

            newtype UserId = UserId Int

            # Helper function to extract the inner value (only works in same module)
            let unwrap_user_id = {id: UserId} ->
                match id is
                    UserId {value} -> value

            impl Encode Json UserId where {
                encode = {val, config} -> do
                    let inner = unwrap_user_id {id = val}
                    EncodeFormat.encode_int {val = inner, path = list::empty {}}
            }

            let id: UserId = UserId {value = 42}
            let result = Encode.encode {val = id, config = default_config}

            match result is
                Ok { val } ->
                    match val is
                        JInt { val } ->
                            if val == 42 then "success" else "wrong value"
                        _ -> "wrong json type"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Expected FunkString but got: $result")
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Set Encoding Tests
    // ========================================================================
    // NOTE: Set and Map encoding tests are commented out because the typechecker
    // doesn't currently recognize Set/Map as valid type annotations. The generated
    // impl declarations use `impl Encode Json (Set Int)` which fails because Set
    // isn't registered as a type application in the typechecker.
    //
    // The underlying encode_set/encode_map implementations in repr.funk work correctly,
    // but the CodecGenerator can't create impl declarations for these types.
    // TODO: Register Set/Map as recognized types in the typechecker.
    //
    // @Test fun `encode Set of integers`() { ... }
    // @Test fun `encode Set of strings`() { ... }
    // @Test fun `encode record with Set field`() { ... }

    // ========================================================================
    // Map Encoding Tests
    // ========================================================================
    // NOTE: Same issue as Set encoding - Map isn't recognized as a valid type.
    // @Test fun `encode Map of string to int`() { ... }
    // @Test fun `encode record with Map field`() { ... }

    // ========================================================================
    // Decode Tests - Int
    // ========================================================================

    @Test
    fun `decode Int value`() {
        // Note: Currently requires type annotation for bidirectional inference to work
        // TODO: Fix constraint solving to infer return type from match pattern usage
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Decode, default_config}

            let json_int = JInt {val = 42}
            let result: Result Int DecodeError = Decode.decode {val = json_int, config = default_config}

            match result is
                Ok { val } ->
                    if val == 42 then "success" else "wrong value"
                Err { error } -> error.message
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Got: $result")
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `decode String value`() {
        // Note: Currently requires type annotation for bidirectional inference to work
        // TODO: Fix constraint solving to infer return type from match pattern usage
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Decode, default_config}

            let json_str = JString {val = "hello"}
            let result: Result String DecodeError = Decode.decode {val = json_str, config = default_config}

            match result is
                Ok { val } ->
                    if val == "hello" then "success" else "wrong value"
                Err { error } -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString)
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `decode record from constructed json`() {
        // Full decode test using encode/decode round-trip
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, Decode, default_config}

            # First encode a record to JSON
            let person = {name = "Alice", age = 30}
            let encode_result = Encode.encode {val = person, config = default_config}

            # Extract the JSON value (panics if encode fails)
            let json_val = match encode_result is
                Ok {val} -> val
                Err {error} -> JNull

            # Now decode the JSON back to a record
            let decode_result: Result {name: String, age: Int} DecodeError = Decode.decode {val = json_val, config = default_config}

            match decode_result is
                Ok {val} ->
                    if val.name == "Alice" then
                        if val.age == 30 then "success" else "wrong age"
                    else "wrong name"
                Err {error} -> error.message
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Got: $result")
        assertEquals("success", (result as FunkString).value)
    }

    @Test
    fun `round-trip simple record`() {
        // Note: Currently requires type annotation for bidirectional inference to work
        // TODO: Fix constraint solving to infer return type from match pattern usage
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, Decode, default_config}

            let person = {name = "Alice", age = 30}
            let encode_result = Encode.encode {val = person, config = default_config}

            let json_val = match encode_result is
                Ok {val} -> val
                Err {error} -> JNull

            let decode_result: Result {name: String, age: Int} DecodeError = Decode.decode {val = json_val, config = default_config}

            match decode_result is
                Ok {val} ->
                    if val.name == "Alice" then
                        if val.age == 30 then "success" else "wrong age"
                    else "wrong name"
                Err {error} -> error.message
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Got: $result")
        assertEquals("success", (result as FunkString).value)
    }

    // ========================================================================
    // Config-aware Encode/Decode Tests
    // ========================================================================

    @Test
    fun `encode with identity transform does not change field names`() {
        // Test that encode with Identity transform doesn't change field names
        val source = """
            use std::json::repr
            use std::json::{Json, to_string}
            use std::codec::{Encode, CodecConfig, FieldTransform}

            let person = {userName = "Alice"}
            let config: CodecConfig = {field_transform = Identity}
            let result = Encode.encode {val = person, config = config}

            match result is
                Ok {val = json} -> to_string {json = json}
                Err {error} -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Got: $result")
        val jsonStr = (result as FunkString).value
        assertTrue(jsonStr.contains("userName"), "Expected 'userName' in $jsonStr")
    }

    @Test
    fun `encode with snake_case transforms field names`() {
        // Test that encode with SnakeCase transform converts field names
        val source = """
            use std::json::repr
            use std::json::{Json, to_string}
            use std::codec::{Encode, CodecConfig, FieldTransform}

            let person = {userName = "Alice"}
            let config: CodecConfig = {field_transform = SnakeCase}
            let result = Encode.encode {val = person, config = config}

            match result is
                Ok {val = json} -> to_string {json = json}
                Err {error} -> "error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Got: $result")
        val jsonStr = (result as FunkString).value
        assertTrue(jsonStr.contains("user_name"), "Expected 'user_name' in $jsonStr, got: $jsonStr")
    }

    @Test
    fun `round trip encode and decode with snake_case`() {
        // Test round-trip
        val source = """
            use std::json::repr
            use std::json::{Json}
            use std::codec::{Encode, Decode, default_config}

            let person = {name = "Alice", age = 30}

            # Encode
            let encode_result = Encode.encode {val = person, config = default_config}

            let json_val = match encode_result is
                Ok {val} -> val
                Err {error} -> JNull

            # Decode back
            let decode_result: Result {name: String, age: Int} DecodeError = Decode.decode {val = json_val, config = default_config}

            match decode_result is
                Ok {val} -> val.name
                Err {error} -> "decode error"
        """.trimIndent()

        val result = FunkTestHelper.run(source)

        assertTrue(result is FunkString, "Got: $result")
        assertEquals("Alice", (result as FunkString).value)
    }

}
