package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/early_return module.
 * Tests early return effect with WithReturn, WithSome, and DiscardReturn handlers.
 * Migrated from V1 to V2 with unboxed values.
 */
class EarlyReturnEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === WithReturn Handler Tests ===

    @Test
    fun `test WithReturn early return`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let check_number = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check_number {x = -5}
        """)
        assertEquals("negative", result)
    }

    @Test
    fun `test WithReturn normal path`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let check_number = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check_number {x = 5}
        """)
        assertEquals("positive", result)
    }

    // === Multiple Guard Clauses ===

    @Test
    fun `test multiple guard clauses`() {
        val result = eval("""
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
        """)
        assertEquals("zero", result)
    }

    // === return Helper Function ===

    @Test
    fun `test return helper function`() {
        val result = eval("""
            use std::effects::early_return::{return, WithReturn}
            use std::control::continuation

            let check = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        return {val = "negative"}
                    else {}
                    "positive"

            check {x = -10}
        """)
        assertEquals("negative", result)
    }

    // === return_if Helper Function ===

    @Test
    fun `test return_if helper function`() {
        val result = eval("""
            use std::effects::early_return::{return_if, WithReturn}
            use std::control::continuation

            let validate = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    return_if {condition = x < 0, val = "negative"}
                    return_if {condition = x == 0, val = "zero"}
                    return_if {condition = x > 100, val = "too large"}
                    "ok"

            validate {x = 150}
        """)
        assertEquals("too large", result)
    }

    @Test
    fun `test return_if with false condition continues`() {
        val result = eval("""
            use std::effects::early_return::{return_if, WithReturn}
            use std::control::continuation

            let validate = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    return_if {condition = x < 0, val = "negative"}
                    "ok"

            validate {x = 50}
        """)
        assertEquals("ok", result)
    }

    // === return_some Helper Function ===

    @Test
    fun `test return_some with Some value`() {
        val result = eval("""
            use std::effects::early_return::{return_some, WithReturn}
            use std::control::continuation

            let process = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    return_some {option = Some {val = x}}
                    "fallback"

            process {x = 42}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test return_some with None continues`() {
        val result = eval("""
            use std::effects::early_return::{return_some, WithReturn}
            use std::control::continuation

            let process = {} ->
                perform WithReturn { cont = continuation::new {} } in do
                    return_some {option = None}
                    "fallback"

            process {}
        """)
        assertEquals("fallback", result)
    }

    // === Return with Different Types ===

    @Test
    fun `test return with integer values`() {
        val result = eval("""
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
        """)
        assertEquals(200L, result)
    }

    @Test
    fun `test return with boolean values`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let is_valid = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = False}
                    else {}
                    True

            is_valid {x = -1}
        """)
        assertEquals(false, result)
    }

    // === First Return Wins ===

    @Test
    fun `test first return wins`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let test = {} ->
                perform WithReturn { cont = continuation::new {} } in do
                    Return.return {val = "first"}
                    Return.return {val = "second"}
                    "third"

            test {}
        """)
        assertEquals("first", result)
    }

    // === Normal Path Returns Final Value ===

    @Test
    fun `test normal path returns computed value`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithReturn}
            use std::control::continuation

            let test = {x} ->
                perform WithReturn { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = 0}
                    else {}
                    x * 2

            test {x = 21}
        """)
        assertEquals(42L, result)
    }
}
