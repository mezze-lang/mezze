package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/early_return module.
 * Tests early return effect with WithReturn, WithSome, and DiscardReturn handlers.
 */
class EarlyReturnEffectTest {

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
        assertTrue(result is MezzeString)
        assertEquals("negative", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("positive", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("zero", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("negative", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("too large", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("ok", (result as MezzeString).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeString)
        assertEquals("fallback", (result as MezzeString).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(200L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeBool)
        assertEquals(false, (result as MezzeBool).value)
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
        assertTrue(result is MezzeString)
        assertEquals("first", (result as MezzeString).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === WithSome Handler Tests ===

    @Test
    fun `test WithSome wraps return in Some`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithSome}
            use std::control::continuation

            let check = {x} ->
                perform WithSome { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "found negative"}
                    else {}
                    "all positive"

            check {x = -5}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Some", (result as MezzeVariant).constructor)
    }

    @Test
    fun `test WithSome normal path returns value directly`() {
        val result = eval("""
            use std::effects::early_return::{Return, WithSome}
            use std::control::continuation

            let check = {x} ->
                perform WithSome { cont = continuation::new {} } in do
                    if x < 0 then
                        Return.return {val = "found negative"}
                    else {}
                    "all positive"

            check {x = 5}
        """)
        // Normal path returns the value directly (not wrapped in Some)
        assertTrue(result is MezzeString)
        assertEquals("all positive", (result as MezzeString).value)
    }

}
