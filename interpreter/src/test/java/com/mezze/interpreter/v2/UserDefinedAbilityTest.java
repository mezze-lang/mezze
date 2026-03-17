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
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            {}
            """));
    }

    @Test
    void testAbilityWithMultipleOperations() {
        assertDoesNotThrow(() -> MezzeTestHelper.run("""
            ability Show for 'a where {
                show: {} -> Str,
                debug: {} -> Str
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
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            42.encode {}
            """);
        assertEquals("int", result);
    }

    @Test
    void testImplAbilityForString() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Str where {
                encode = {} -> it
            }

            "hello".encode {}
            """);
        assertEquals("hello", result);
    }

    @Test
    void testMultipleImplsForDifferentTypes() {
        // Test Int impl
        Object intResult = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            impl Encoder for Str where {
                encode = {} -> "string"
            }

            42.encode {}
            """);
        assertEquals("int", intResult);

        // Test String impl
        Object stringResult = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            impl Encoder for Str where {
                encode = {} -> "string"
            }

            "hello".encode {}
            """);
        assertEquals("string", stringResult);
    }

    @Test
    void testImplWithMultipleOperations() {
        Object result = MezzeTestHelper.run("""
            ability Show for 'a where {
                show: {} -> Str,
                debug: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "shown",
                debug = {} -> "debugged"
            }

            42.debug {}
            """);
        assertEquals("debugged", result);
    }

    // ========================
    // Ability Method Calls
    // ========================

    @Test
    void testAbilityMethodCallInLet() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "encoded"
            }

            let x = 42
            let encoded = x.encode {}
            encoded
            """);
        assertEquals("encoded", result);
    }

    @Test
    void testAbilityMethodCallInFunction() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "encoded"
            }

            let encode_int = {x} -> x.encode {}
            encode_int {x = 42}
            """);
        assertEquals("encoded", result);
    }

    @Test
    void testAbilityMethodCallInDoBlock() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "in_do"
            }

            do
                let x = 42
                x.encode {}
            """);
        assertEquals("in_do", result);
    }

    // ========================
    // Inspect Ability
    // ========================

    @Test
    void testInspectOnInt() {
        Object result = MezzeTestHelper.run("""
            42.to_dbg_str {}
            """);
        assertEquals("42", result);
    }

    @Test
    void testInspectOnString() {
        Object result = MezzeTestHelper.run("""
            "hello".to_dbg_str {}
            """);
        assertEquals("\"hello\"", result);
    }

    @Test
    void testInspectOnBool() {
        Object result1 = MezzeTestHelper.run("True.to_dbg_str {}");
        Object result2 = MezzeTestHelper.run("False.to_dbg_str {}");
        assertEquals("True", result1);
        assertEquals("False", result2);
    }

    @Test
    void testInspectOnArray() {
        Object result = MezzeTestHelper.run("""
            [1, 2, 3].to_dbg_str {}
            """);
        assertEquals("[1, 2, 3]", result);
    }

    @Test
    void testInspectOnRecord() {
        Object result = MezzeTestHelper.run("""
            {x = 1, y = 2}.to_dbg_str {}
            """);
        // Record field order may vary, so check contains
        String str = (String) result;
        assertTrue(str.contains("x") && str.contains("1"));
        assertTrue(str.contains("y") && str.contains("2"));
    }

    @Test
    void testInspectInLambda() {
        Object result = MezzeTestHelper.run("""
            let to_dbg_str = {val} -> val.to_dbg_str {}
            to_dbg_str {val = 42}
            """);
        assertEquals("42", result);
    }

    @Test
    void testInspectInStringInterpolation() {
        Object result = MezzeTestHelper.run("""
            let val = 42
            "Value: $(val.to_dbg_str {})"
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

            ability Serializer for 'a where {
                serialize: {} -> Str
            }

            impl Serializer for Int where {
                serialize = {} -> int::to_str {n = it}
            }

            123.serialize {}
            """);
        assertEquals("123", result);
    }

    @Test
    void testAbilityWithCustomType() {
        Object result = MezzeTestHelper.run("""
            type Point = { x: Int, y: Int }

            ability Stringify for 'a where {
                to_str: {} -> Str
            }

            impl Stringify for Point where {
                to_str = {} -> "point"
            }

            let p: Point = {x = 1, y = 2}
            p.to_str {}
            """);
        assertEquals("point", result);
    }

    @Test
    void testAbilityWithVariantType() {
        Object result = MezzeTestHelper.run("""
            type Maybe 'a = | Nothing, Just {val: 'a} |

            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for (Maybe Int) where {
                encode = {} ->
                    match it is
                        Nothing -> "nothing"
                        Just {val = x} -> "just"
            }

            let x = Just {val = 42}
            x.encode {}
            """);
        assertEquals("just", result);
    }

    // ========================
    // Polymorphic Functions with Ability Constraints
    // (Dictionary Passing Tests)
    // ========================

    @Test
    void testPolymorphicFunctionWithAbilityConstraint() {
        // This test exercises dictionary passing:
        // - encode_value has `with Encoder 'a` constraint
        // - When called with Int, the Encoder Int impl is passed as dictionary
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int_encoded"
            }

            impl Encoder for Str where {
                encode = {} -> "string_encoded"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            encode_value {x = 42}
            """);
        assertEquals("int_encoded", result);
    }

    @Test
    void testPolymorphicFunctionPassesDifferentImpls() {
        // Test that the polymorphic function works correctly with Int
        Object resultInt = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            encode_value {x = 42}
            """);
        assertEquals("int", resultInt);

        // Test that the polymorphic function works correctly with String
        Object resultStr = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Str where {
                encode = {} -> "string"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            encode_value {x = "hello"}
            """);
        assertEquals("string", resultStr);
    }

    @Test
    void testPolymorphicFunctionWithMultipleAbilityConstraints() {
        // Function with multiple ability constraints
        // Each constraint needs its dictionary passed
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            ability Comparer for 'a where {
                compare: {r: 'a} -> Bool
            }

            impl Encoder for Int where {
                encode = {} -> "encoded"
            }

            impl Comparer for Int where {
                compare = {r} -> it == r
            }

            let encode_if_equal: {x: 'a, y: 'a} -> Str with Encoder 'a, Comparer 'a =
                {x, y} -> if x.compare {r = y}
                    then x.encode {}
                    else "not equal"

            encode_if_equal {x = 5, y = 5}
            """);
        assertEquals("encoded", result);
    }

    @Test
    void testNestedPolymorphicFunctionCalls() {
        // Polymorphic function calling another polymorphic function
        // Dictionaries need to be propagated through the call chain
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int_value"
            }

            let inner_encode: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            let outer_encode: {x: 'a} -> Str with Encoder 'a =
                {x} -> inner_encode {x = x}

            outer_encode {x = 42}
            """);
        assertEquals("int_value", result);
    }

    @Test
    void testPolymorphicFunctionInHigherOrderContext() {
        // Polymorphic function passed as argument
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "encoded_int"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            let apply_encoder = {f, val} -> f {x = val}

            apply_encoder {f = encode_value, val = 42}
            """);
        assertEquals("encoded_int", result);
    }

    @Test
    void testTopLevelPolymorphicFunctionSingleCall() {
        // Simple test: call polymorphic function once at top level
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            encode_value {x = 42}
            """);
        assertEquals("int", result);
    }

    @Test
    void testDoBlockSingleCallToPolymorphicFunction() {
        // Test: call polymorphic function once inside do block
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            do
                let result = encode_value {x = 42}
                result
            """);
        assertEquals("int", result);
    }

    @Test
    void testDoBlockTwoCallsSameType() {
        // Test: call polymorphic function twice with same type
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            do
                let result1 = encode_value {x = 42}
                let result2 = encode_value {x = 99}
                "$(result1) and $(result2)"
            """);
        assertEquals("int and int", result);
    }

    @Test
    void testDoBlockTwoCallsDifferentTypesNoReturn() {
        // Test: call polymorphic function twice with DIFFERENT types
        // Just return a simple string to check if typechecking passes
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            impl Encoder for Str where {
                encode = {} -> "string"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            do
                let result_int = encode_value {x = 42}
                let result_str = encode_value {x = "hello"}
                "done"
            """);
        assertEquals("done", result);
    }

    @Test
    void testDoBlockTwoCallsDifferentTypes() {
        // Test: call polymorphic function twice with DIFFERENT types
        // This is the minimal failing case
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            impl Encoder for Str where {
                encode = {} -> "string"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            do
                let result_int = encode_value {x = 42}
                let result_str = encode_value {x = "hello"}
                result_int
            """);
        assertEquals("int", result);
    }

    @Test
    void testLetPolymorphismWithAbilityConstraints() {
        // This tests let-polymorphism: the same polymorphic function
        // should be callable with different types in the same scope
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int"
            }

            impl Encoder for Str where {
                encode = {} -> "string"
            }

            let encode_value: {x: 'a} -> Str with Encoder 'a =
                {x} -> x.encode {}

            do
                let result_int = encode_value {x = 42}
                let result_str = encode_value {x = "hello"}
                "$(result_int) and $(result_str)"
            """);
        assertEquals("int and string", result);
    }

    // ========================
    // Method Call Syntax: value.method {}
    // ========================

    @Test
    void testMethodCallSyntaxOnUserDefinedAbility() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "int_encoded"
            }

            42.encode {}
            """);
        assertEquals("int_encoded", result);
    }

    @Test
    void testMethodCallSyntaxWithStringReceiver() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Str where {
                encode = {} -> "str_encoded"
            }

            "hello".encode {}
            """);
        assertEquals("str_encoded", result);
    }

    @Test
    void testMethodCallSyntaxWithCustomType() {
        Object result = MezzeTestHelper.run("""
            type Point = { x: Int, y: Int }

            ability Stringify for 'a where {
                to_str: {} -> Str
            }

            impl Stringify for Point where {
                to_str = {} -> "point"
            }

            let p: Point = {x = 1, y = 2}
            p.to_str {}
            """);
        assertEquals("point", result);
    }

    @Test
    void testMethodCallSyntaxInExpression() {
        Object result = MezzeTestHelper.run("""
            ability Encoder for 'a where {
                encode: {} -> Str
            }

            impl Encoder for Int where {
                encode = {} -> "encoded"
            }

            let x = 42
            let result = x.encode {}
            result
            """);
        assertEquals("encoded", result);
    }
}
