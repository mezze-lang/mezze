package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for the Return effect from stdlib
 *
 * Tests that the return mechanism works end-to-end with:
 * - WithReturn handler (returns value directly)
 * - WithSome handler (wraps in Some)
 * - Helper functions (return, return_if, return_some)
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class ReturnEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Test 1: Basic Return with WithReturn Handler
    // ========================================================================

    @Test
    fun `test basic return with WithReturn handler`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let check_number = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check_number {x = -5}
        """.trimIndent()

        val result = eval(source)
        assertEquals("negative", result)
    }

    // ========================================================================
    // Test 2: Normal Path Without Early Return
    // ========================================================================

    @Test
    fun `test normal path without early return`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let check_number = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check_number {x = 5}
        """.trimIndent()

        val result = eval(source)
        assertEquals("positive", result)
    }

    // ========================================================================
    // Test 3: Multiple Guard Clauses
    // ========================================================================

    @Test
    fun `test multiple guard clauses with return`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let classify = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
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

        val result = eval(source)
        assertEquals("zero", result)
    }

    // ========================================================================
    // Test 4: Helper Function - return
    // ========================================================================

    @Test
    fun `test return helper function`() {
        val source = """
            use std::effects::early_return::{return, WithReturn}
            use std::control::continuation

            let check = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        return {val = "negative"}
                    else {}
                    "positive"

            check {x = -10}
        """.trimIndent()

        val result = eval(source)
        assertEquals("negative", result)
    }

    // ========================================================================
    // Test 5: Helper Function - return_if
    // ========================================================================

    @Test
    fun `test return_if helper function`() {
        val source = """
            use std::effects::early_return::{return_if, WithReturn}
            use std::control::continuation

            let validate = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    return_if {condition = x < 0, val = "negative"}
                    return_if {condition = x == 0, val = "zero"}
                    return_if {condition = x > 100, val = "too large"}
                    "ok"

            validate {x = 150}
        """.trimIndent()

        val result = eval(source)
        assertEquals("too large", result)
    }

    // ========================================================================
    // Test 6: Return with Int Values
    // ========================================================================

    @Test
    fun `test return with integer values`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let get_value = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x == 0 then
                        Return.return {val = 100}
                    else if x == 1 then
                        Return.return {val = 200}
                    else {}
                    300

            get_value {x = 1}
        """.trimIndent()

        val result = eval(source)
        assertEquals(200L, result)
    }

    // ========================================================================
    // Test 7: First Return Wins
    // ========================================================================

    @Test
    fun `test first return wins in sequence`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let test = {} ->
                perform WithReturn { cont = continuation::new {} } in do
                    Return.return {val = "first"}
                    Return.return {val = "second"}
                    "third"

            test {}
        """.trimIndent()

        val result = eval(source)
        assertEquals("first", result)
    }

    // ========================================================================
    // Test 8: Normal Path Returns Final Value
    // ========================================================================

    @Test
    fun `test normal path returns final value`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let test = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = 0}
                    else {}
                    x * 2

            test {x = 21}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    // ========================================================================
    // Test 9: Return with Bool Values
    // ========================================================================

    @Test
    fun `test return with boolean values`() {
        val source = """
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let is_valid = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = False}
                    else {}
                    True

            is_valid {x = -1}
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    // ========================================================================
    // Test 10: Helper Function - return_some
    // ========================================================================

    @Test
    fun `test return_some helper with Some value`() {
        val source = """
            use std::effects::early_return::{return_some, WithReturn}
            use std::control::continuation

            let process = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    return_some {option = Some {val = x}}
                    "fallback"

            process {x = 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }
}
