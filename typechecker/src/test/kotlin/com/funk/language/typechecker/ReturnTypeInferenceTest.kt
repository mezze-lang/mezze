package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Test suite for Return effect type inference and handler validation
 *
 * Tests that the Return effect properly infers return types and validates
 * that handlers match the actual values returned by functions.
 */
class ReturnTypeInferenceTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(source: String) {
        val program = parser.parse(source)
        typeChecker.typeCheck(program).program
    }

    // ========================================================================
    // Test 1: Basic String Return Type Inference
    // ========================================================================

    @Test
    fun `test infer String return type`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let check_value : {x: Int} -> String = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else
                        "positive"

            let result = check_value {x = -5}
        """.trimIndent()

        // Should pass - handler matches return type
        typeCheck(code)
    }

    // ========================================================================
    // Test 2: Polymorphic Handler Works with Any Type
    // ========================================================================

    @Test
    fun `test polymorphic handler works with any type`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let get_number : {} -> Int = {} ->
                perform WithReturn in
                    Return.return {val = 42}
        """.trimIndent()

        // Should pass - handler is polymorphic
        typeCheck(code)
    }

    // ========================================================================
    // Test 3: Correct Handler Type Works
    // ========================================================================

    @Test
    fun `test correct handler type works`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for IntReturn having 'result = Int where {
                return = {val} -> val
            }

            pub let get_number : {} -> Int = {} ->
                perform IntReturn in
                    Return.return {val = 42}
        """.trimIndent()

        // Should pass - handler matches return type
        typeCheck(code)
    }

    // ========================================================================
    // Test 4: Multiple Return Statements with Same Type
    // ========================================================================

    @Test
    fun `test multiple returns with same type`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let classify : {x: Int} -> String = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else if x == 0 then
                        Return.return {val = "zero"}
                    else if x > 100 then
                        Return.return {val = "large"}
                    else
                        "normal"
        """.trimIndent()

        // Should pass - all returns are String
        typeCheck(code)
    }

    // ========================================================================
    // Test 5: Variant Return Types
    // ========================================================================

    @Test
    fun `test variant return types work correctly`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            type Status = | Success {msg: String}, Failed {error: String} |

            impl Return for StatusReturn having 'result = Status where {
                return = {val} -> val
            }

            pub let process : {x: Int} -> Status = {x} ->
                perform StatusReturn in do
                    if x < 0 then
                        Return.return {val = Failed {error = "negative"}}
                    else
                        Success {msg = "ok"}
        """.trimIndent()

        // Should pass - returns match Status type
        typeCheck(code)
    }

    // ========================================================================
    // Test 6: Return in Match Expression
    // ========================================================================

    @Test
    fun `test return in match expression branches`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            type Color = | Red, Blue, Green |

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let color_name : {c: Color} -> String = {c} ->
                perform WithReturn in
                    match c is
                        Red -> Return.return {val = "red"}
                        Blue -> Return.return {val = "blue"}
                        Green -> Return.return {val = "green"}
        """.trimIndent()

        // Should pass - all branches return String
        typeCheck(code)
    }

    // ========================================================================
    // Test 7: Nested Perform Blocks
    // ========================================================================

    @Test
    fun `test nested perform blocks with return`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let inner : {x: Int} -> String = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "inner negative"}
                    else
                        "inner ok"

            pub let outer : {x: Int} -> String = {x} ->
                perform WithReturn in do
                    let result = inner {x = x}
                    if result == "inner ok" then
                        Return.return {val = "outer ok"}
                    else
                        "outer done"
        """.trimIndent()

        // Should pass - each perform has correct return type
        typeCheck(code)
    }

    // ========================================================================
    // Test 8: Return with Record Types
    // ========================================================================

    @Test
    fun `test return with record types`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let get_user : {id: Int} -> {name: String, age: Int} = {id} ->
                perform WithReturn in do
                    if id == 0 then
                        Return.return {val = {name = "Admin", age = 0}}
                    else
                        {name = "User", age = 25}
        """.trimIndent()

        // Should pass - returns match record type
        typeCheck(code)
    }

    // ========================================================================
    // Test 9: WithSome Handler
    // ========================================================================

    @Test
    fun `test WithSome handler wraps in Some`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithSome having 'result = 'r where {
                return = {val} -> Some {val = val}
            }

            pub let find : {x: Int} -> Int = {x} ->
                perform WithSome in do
                    if x > 10 then
                        Return.return {val = x}
                    else
                        -1
        """.trimIndent()

        // Should pass - WithSome wraps return in Some
        typeCheck(code)
    }

    // ========================================================================
    // Test 10: Return in Do Block
    // ========================================================================

    @Test
    fun `test return in do block with multiple statements`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let validate : {name: String, age: Int} -> String = {name, age} ->
                perform WithReturn in do
                    if name == "" then
                        Return.return {val = "Name required"}
                    else {}

                    if age < 0 then
                        Return.return {val = "Invalid age"}
                    else {}

                    "Valid"
        """.trimIndent()

        // Should pass - all returns are String
        typeCheck(code)
    }

    // ========================================================================
    // Test 11: Return with Int Type
    // ========================================================================

    @Test
    fun `test return with int type`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let test_int : {} -> Int = {} ->
                perform WithReturn in Return.return {val = 42}
        """.trimIndent()

        // Should pass - WithReturn is polymorphic
        typeCheck(code)
    }

    // ========================================================================
    // Test 12: Return Without Explicit Return Call (Normal Path)
    // ========================================================================

    @Test
    fun `test normal path without explicit return`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let process : {x: Int} -> String = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "early"}
                    else {}

                    "normal"
        """.trimIndent()

        // Should pass - can have normal path without explicit return
        typeCheck(code)
    }

    // ========================================================================
    // Test 13: Return in Nested If Expressions
    // ========================================================================

    @Test
    fun `test return in deeply nested if expressions`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            pub let check : {a: Int, b: Int, c: Int} -> String = {a, b, c} ->
                perform WithReturn in do
                    if a < 0 then
                        Return.return {val = "a negative"}
                    else
                        if b < 0 then
                            Return.return {val = "b negative"}
                        else
                            if c < 0 then
                                Return.return {val = "c negative"}
                            else
                                "all positive"
        """.trimIndent()

        // Should pass - all returns are String
        typeCheck(code)
    }

    // ========================================================================
    // Test 14: Helper Function return_if
    // ========================================================================

    @Test
    fun `test return_if helper function`() {
        val code = """
            effect Return having 'result where {
                return: {val: 'result} -> 'a <Return>
            }

            impl Return for WithReturn having 'result = 'r where {
                return = {val} -> val
            }

            let return_if = {condition, val} ->
                if condition then
                    Return.return {val}
                else
                    {}

            pub let validate : {x: Int} -> String = {x} ->
                perform WithReturn in do
                    return_if {condition = x < 0, val = "negative"}
                    return_if {condition = x == 0, val = "zero"}
                    return_if {condition = x > 100, val = "too large"}
                    "ok"
        """.trimIndent()

        // Should pass - helper works correctly
        typeCheck(code)
    }

    // ========================================================================
    // Test 15: Effect Without Associated Types (Should Not Check)
    // ========================================================================

    @Test
    fun `test effect without associated types is not checked`() {
        val code = """
            effect Log where {
                log: {msg: String} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
                log = {msg} -> {}
            }

            pub let test : {} -> {} = {} ->
                perform ConsoleLogger in Log.log {msg = "test"}
        """.trimIndent()

        // Should pass - no associated types to check
        typeCheck(code)
    }
}
