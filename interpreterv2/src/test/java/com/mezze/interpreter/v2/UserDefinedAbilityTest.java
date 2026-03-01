package com.mezze.interpreter.v2;

import com.mezze.interpreter.v2.values.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for user-defined abilities in V2 interpreter.
 *
 * These tests verify:
 * - Ability declaration
 * - Ability implementation (impl)
 * - Ability method calls (Ability.method {args})
 * - Multiple implementations for different types
 * - Ability constraints on functions
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*UserDefinedAbilityTest*"
 */
public class UserDefinedAbilityTest {

    @BeforeEach
    void clearCaches() {
        com.mezze.language.typechecker.ModuleResolver.clearCache();
        com.mezze.language.typechecker.TypeChecker.clearCache();
    }

    // ========================
    // Basic Ability Declaration
    // ========================

    @Test
    void testSimpleAbilityDeclaration() {
        // Just declaring an ability should not throw
        assertDoesNotThrow(() -> MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            {}
            """));
    }

    @Test
    void testAbilityWithMultipleOperations() {
        assertDoesNotThrow(() -> MezzeTestHelper.run("""
            ability Show 'a where {
                show: {val: 'a} -> String,
                debug: {val: 'a} -> String
            }

            {}
            """));
    }

    // ========================
    // Ability Implementations
    // ========================

    @Test
    void testImplAbilityForInt() {
        Object result = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder Int where {
                encode: {val: Int} -> String = {val} -> "int"
            }

            Encoder.encode {val = 42}
            """);
        assertEquals("int", result);
    }

    @Test
    void testImplAbilityForString() {
        Object result = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder String where {
                encode: {val: String} -> String = {val} -> val
            }

            Encoder.encode {val = "hello"}
            """);
        assertEquals("hello", result);
    }

    @Test
    void testMultipleImplsForDifferentTypes() {
        // Test Int impl
        Object intResult = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder Int where {
                encode: {val: Int} -> String = {val} -> "int"
            }

            impl Encoder String where {
                encode: {val: String} -> String = {val} -> "string"
            }

            Encoder.encode {val = 42}
            """);
        assertEquals("int", intResult);

        // Test String impl
        Object stringResult = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder Int where {
                encode: {val: Int} -> String = {val} -> "int"
            }

            impl Encoder String where {
                encode: {val: String} -> String = {val} -> "string"
            }

            Encoder.encode {val = "hello"}
            """);
        assertEquals("string", stringResult);
    }

    @Test
    void testImplWithMultipleOperations() {
        Object result = MezzeTestHelper.run("""
            ability Show 'a where {
                show: {val: 'a} -> String,
                debug: {val: 'a} -> String
            }

            impl Show Int where {
                show: {val: Int} -> String = {val} -> "shown",
                debug: {val: Int} -> String = {val} -> "debugged"
            }

            Show.debug {val = 42}
            """);
        assertEquals("debugged", result);
    }

    // ========================
    // Ability Method Calls
    // ========================

    @Test
    void testAbilityMethodCallInLet() {
        Object result = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder Int where {
                encode: {val: Int} -> String = {val} -> "encoded"
            }

            let x = 42
            let encoded = Encoder.encode {val = x}
            encoded
            """);
        assertEquals("encoded", result);
    }

    @Test
    void testAbilityMethodCallInFunction() {
        Object result = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder Int where {
                encode: {val: Int} -> String = {val} -> "encoded"
            }

            let encode_int = {x} -> Encoder.encode {val = x}
            encode_int {x = 42}
            """);
        assertEquals("encoded", result);
    }

    @Test
    void testAbilityMethodCallInDoBlock() {
        Object result = MezzeTestHelper.run("""
            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder Int where {
                encode: {val: Int} -> String = {val} -> "in_do"
            }

            do
                let x = 42
                Encoder.encode {val = x}
            """);
        assertEquals("in_do", result);
    }

    // ========================
    // Inspect Ability
    // ========================

    @Test
    void testInspectOnInt() {
        Object result = MezzeTestHelper.run("""
            Inspect.to_str {val = 42}
            """);
        assertEquals("42", result);
    }

    @Test
    void testInspectOnString() {
        Object result = MezzeTestHelper.run("""
            Inspect.to_str {val = "hello"}
            """);
        assertEquals("\"hello\"", result);
    }

    @Test
    void testInspectOnBool() {
        Object result1 = MezzeTestHelper.run("Inspect.to_str {val = True}");
        Object result2 = MezzeTestHelper.run("Inspect.to_str {val = False}");
        assertEquals("True", result1);
        assertEquals("False", result2);
    }

    @Test
    void testInspectOnArray() {
        Object result = MezzeTestHelper.run("""
            Inspect.to_str {val = [1, 2, 3]}
            """);
        assertEquals("[1, 2, 3]", result);
    }

    @Test
    void testInspectOnRecord() {
        Object result = MezzeTestHelper.run("""
            Inspect.to_str {val = {x = 1, y = 2}}
            """);
        // Record field order may vary, so check contains
        String str = (String) result;
        assertTrue(str.contains("x") && str.contains("1"));
        assertTrue(str.contains("y") && str.contains("2"));
    }

    @Test
    void testInspectInLambda() {
        Object result = MezzeTestHelper.run("""
            let to_string = {val} -> Inspect.to_str {val = val}
            to_string {val = 42}
            """);
        assertEquals("42", result);
    }

    @Test
    void testInspectInStringInterpolation() {
        Object result = MezzeTestHelper.run("""
            let val = 42
            "Value: $(Inspect.to_str {val = val})"
            """);
        assertEquals("Value: 42", result);
    }

    // ========================
    // Complex Ability Usage
    // ========================

    @Test
    void testAbilityWithStdlib() {
        Object result = MezzeTestHelper.run("""
            use std::int

            ability Serializer 'a where {
                serialize: {val: 'a} -> String
            }

            impl Serializer Int where {
                serialize: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            Serializer.serialize {val = 123}
            """);
        assertEquals("123", result);
    }

    @Test
    void testAbilityWithCustomType() {
        Object result = MezzeTestHelper.run("""
            type Point = { x: Int, y: Int }

            ability Stringify 'a where {
                to_str: {val: 'a} -> String
            }

            impl Stringify Point where {
                to_str: {val: Point} -> String = {val} -> "point"
            }

            let p: Point = {x = 1, y = 2}
            Stringify.to_str {val = p}
            """);
        assertEquals("point", result);
    }

    @Test
    void testAbilityWithVariantType() {
        Object result = MezzeTestHelper.run("""
            type Maybe 'a = | Nothing, Just {val: 'a} |

            ability Encoder 'a where {
                encode: {val: 'a} -> String
            }

            impl Encoder (Maybe Int) where {
                encode: {val: Maybe Int} -> String = {val} ->
                    match val is
                        Nothing -> "nothing"
                        Just {val = x} -> "just"
            }

            let x = Just {val = 42}
            Encoder.encode {val = x}
            """);
        assertEquals("just", result);
    }
}
