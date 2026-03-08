package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/exception module.
 * Tests exception handling with ToResult, ToOption, Ignore, Abort, and Crash handlers.
 * Migrated from V1 to V2 with unboxed values.
 */
class ExceptionEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === ToResult Handler Tests ===

    @Test
    fun `test ToResult returns Ok on success`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Ok {val = a / b}

            let test_divide = {a, b} -> do
                let r = perform ToResult {cont = continuation::new {}} in divide {a = a, b = b}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> "err"

            test_divide {a = 10, b = 2}
        """)
        assertEquals("ok", result)
    }

    @Test
    fun `test ToResult returns Err on exception`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Ok {val = a / b}

            let test_divide = {a, b} -> do
                let r = perform ToResult {cont = continuation::new {}} in divide {a = a, b = b}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            test_divide {a = 10, b = 0}
        """)
        assertEquals("Division by zero", result)
    }

    // === ToOption Handler Tests ===

    @Test
    fun `test ToOption returns None on exception`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToOption}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Some {val = a / b}

            let test_divide = {a, b} -> do
                let r = perform ToOption {cont = continuation::new {}} in divide {a = a, b = b}
                match r is
                    Some {val} -> "some"
                    None -> "none"

            test_divide {a = 10, b = 0}
        """)
        assertEquals("none", result)
    }

    // === Ignore Handler Tests ===

    @Test
    fun `test Ignore returns unit on exception`() {
        val result = eval("""
            use std::effects::exception::{Exception, Ignore}
            use std::control::continuation

            let may_fail = {x} ->
                if x == 0 then
                    Exception.throw {error = "Failed!"}
                else
                    {}

            let test_ignore = {x} -> do
                let result = perform Ignore {cont = continuation::new {}} in may_fail {x = x}
                42

            test_ignore {x = 0}
        """)
        assertEquals(42L, result)
    }

    // === throw Helper Function Tests ===

    @Test
    fun `test throw helper function`() {
        val result = eval("""
            use std::effects::exception::{throw, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    throw {error = "negative"}
                else
                    Ok {val = x}

            let test_check = {x} -> do
                let r = perform ToResult {cont = continuation::new {}} in check {x = x}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            test_check {x = -5}
        """)
        assertEquals("negative", result)
    }

    // === unwrap Helper Function Tests ===

    @Test
    fun `test unwrap with Ok value`() {
        val result = eval("""
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let test_unwrap = {} -> do
                let good_result = Ok {val = 42}
                let r = perform ToResult {cont = continuation::new {}} in Ok {val = unwrap {result = good_result}}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> "err"

            test_unwrap {}
        """)
        assertEquals("ok", result)
    }

    @Test
    fun `test unwrap with Err throws exception`() {
        val result = eval("""
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let try_unwrap = {} -> do
                let bad_result = Err {error = "Something failed"}
                let r = perform ToResult {cont = continuation::new {}} in do
                    let val = unwrap {result = bad_result}
                    Ok {val = val}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            try_unwrap {}
        """)
        assertEquals("Something failed", result)
    }

    // === Multiple Exceptions Tests ===

    @Test
    fun `test first exception wins`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let multi_throw = {} -> do
                Exception.throw {error = "First error"}
                Exception.throw {error = "Second error"}
                Ok {val = "unreachable"}

            let test_multi = {} -> do
                let r = perform ToResult {cont = continuation::new {}} in multi_throw {}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            test_multi {}
        """)
        assertEquals("First error", result)
    }

    // === Exception in Conditional Tests ===

    @Test
    fun `test exception in if branch`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check_value = {x} ->
                if x > 10 then
                    Exception.throw {error = "Too large"}
                else
                    Ok {val = x}

            let test_check = {x} -> do
                let r = perform ToResult {cont = continuation::new {}} in check_value {x = x}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> "err"

            test_check {x = 15}
        """)
        assertEquals("err", result)
    }

    // === Exception with Different Error Types ===

    @Test
    fun `test exception with integer error`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    Exception.throw {error = -1}
                else
                    Ok {val = x}

            let test_check = {x} -> do
                let r = perform ToResult {cont = continuation::new {}} in check {x = x}
                match r is
                    Ok {val} -> 0
                    Err {error} -> error

            test_check {x = -5}
        """)
        assertEquals(-1L, result)
    }

    @Test
    fun `test exception with record error`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    Exception.throw {error = {code = 400, message = "Bad request"}}
                else
                    Ok {val = x}

            let test_check = {x} -> do
                let r = perform ToResult {cont = continuation::new {}} in check {x = x}
                match r is
                    Ok {val} -> 0
                    Err {error} -> error.code

            test_check {x = -5}
        """)
        assertEquals(400L, result)
    }
}
