package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

/**
 * Integration tests for the Return effect from stdlib
 *
 * Tests that the return mechanism works end-to-end with:
 * - WithReturn handler (returns value directly)
 * - WithSome handler (wraps in Some)
 * - Helper functions (return, return_if, return_some)
 *
 * DISABLED: Return effect depends on Continuation/Delimited which is not yet implemented
 */
@Disabled("Depends on Continuation/Delimited which is not yet implemented")
class ReturnEffectTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
        // Set project root for stdlib module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

    // ========================================================================
    // Test 1: Basic Return with WithReturn Handler
    // ========================================================================

    @Test
    fun `test basic return with WithReturn handler`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let check_number = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check_number {x = -5}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkString)
        assertEquals("negative", (result as FunkString).value)
    }

    // ========================================================================
    // Test 2: Normal Path Without Early Return
    // ========================================================================

    @Test
    fun `test normal path without early return`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let check_number = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check_number {x = 5}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkString)
        assertEquals("positive", (result as FunkString).value)
    }

    // ========================================================================
    // Test 3: Multiple Guard Clauses
    // ========================================================================

    @Test
    fun `test multiple guard clauses with return`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let classify = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}

                    if x == 0 then
                        Return.return {val = "zero"}
                    else {}

                    if x > 100 then
                        Return.return {val = "too large"}
                    else {}

                    "normal"

            classify {x = 0}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkString)
        assertEquals("zero", (result as FunkString).value)
    }

    // ========================================================================
    // Test 4: Helper Function - return
    // ========================================================================

    @Test
    fun `test return helper function`() {
        val source = """
            use std::effects::early_return::{return, WithReturn}

            let check = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        return {val = "negative"}
                    else {}
                    "positive"

            check {x = -10}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkString)
        assertEquals("negative", (result as FunkString).value)
    }

    // ========================================================================
    // Test 5: Helper Function - return_if
    // ========================================================================

    @Test
    fun `test return_if helper function`() {
        val source = """
            use std::effects::early_return::{return_if, WithReturn}

            let validate = {x} ->
                perform WithReturn in do
                    return_if {condition = x < 0, val = "negative"}
                    return_if {condition = x == 0, val = "zero"}
                    return_if {condition = x > 100, val = "too large"}
                    "ok"

            validate {x = 150}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkString)
        assertEquals("too large", (result as FunkString).value)
    }

    // ========================================================================
    // Test 6: Return with Int Values
    // ========================================================================

    @Test
    fun `test return with integer values`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let get_value = {x} ->
                perform WithReturn in do
                    if x == 0 then
                        Return.return {val = 100}
                    else if x == 1 then
                        Return.return {val = 200}
                    else {}
                    300

            get_value {x = 1}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(200L, (result as FunkInt).value)
    }

    // ========================================================================
    // Test 7: First Return Wins
    // ========================================================================

    @Test
    fun `test first return wins in sequence`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let test = {} ->
                perform WithReturn in do
                    Return.return {val = "first"}
                    Return.return {val = "second"}
                    "third"

            test {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkString)
        assertEquals("first", (result as FunkString).value)
    }

    // ========================================================================
    // Test 8: Normal Path Returns Final Value
    // ========================================================================

    @Test
    fun `test normal path returns final value`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let test = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = 0}
                    else {}
                    x * 2

            test {x = 21}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }

    // ========================================================================
    // Test 9: Return with Bool Values
    // ========================================================================

    @Test
    fun `test return with boolean values`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}

            let is_valid = {x} ->
                perform WithReturn in do
                    if x < 0 then
                        Return.return {val = false}
                    else {}
                    true

            is_valid {x = -1}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkBool)
        assertEquals(false, (result as FunkBool).value)
    }

    // ========================================================================
    // Test 10: Helper Function - return_some
    // ========================================================================

    @Test
    fun `test return_some helper with Some value`() {
        val source = """
            use std::effects::early_return::{return_some, WithReturn}

            let process = {x} ->
                perform WithReturn in do
                    return_some {option = Some {val = x}}
                    "fallback"

            process {x = 42}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }
}
