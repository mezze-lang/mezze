package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/async/cancellation module.
 * Tests cancellation tokens for cooperative task cancellation.
 * Migrated from V1 to V2 with unboxed values.
 */
class CancellationModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Token Operations ===

    @Test
    fun `test create cancellation token`() {
        val result = eval("""
            use std::effects::async::{token, is_token_cancelled}

            let ct = token {}
            is_token_cancelled {token = ct}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test cancel marks token as cancelled`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, is_token_cancelled}

            let ct = token {}
            cancel_token {token = ct, reason = "test"}
            is_token_cancelled {token = ct}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test multiple cancellations are idempotent`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, is_token_cancelled}

            let ct = token {}
            cancel_token {token = ct, reason = "first"}
            cancel_token {token = ct, reason = "second"}
            cancel_token {token = ct, reason = "third"}
            is_token_cancelled {token = ct}
        """)
        assertEquals(true, result)
    }

    // === Token Independence ===

    @Test
    fun `test tokens are independent`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, is_token_cancelled}

            let token1 = token {}
            let token2 = token {}
            cancel_token {token = token1, reason = "cancel one"}
            let c1 = is_token_cancelled {token = token1}
            let c2 = is_token_cancelled {token = token2}
            if c1 && (c2 == False) then 1 else 0
        """)
        assertEquals(1L, result)
    }

    // === with_cancellation Helper ===

    @Test
    fun `test with_cancellation runs when not cancelled`() {
        val result = eval("""
            use std::effects::async::{token, with_cancellation}

            let ct = token {}
            match with_cancellation {token = ct, f = {} -> 42} is
                Some {val} -> val
                None -> 0
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_cancellation returns None when cancelled`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, with_cancellation}

            let ct = token {}
            cancel_token {token = ct, reason = "test"}
            match with_cancellation {token = ct, f = {} -> 42} is
                Some {val} -> val
                None -> 0
        """)
        assertEquals(0L, result)
    }

    // Note: throw_if_cancelled requires __native_cancellation_token_throw_if_cancelled
    // which is not implemented in V2. The V1 tests also did not test this function.

    // === Child Tokens ===

    @Test
    fun `test child token cancelled when parent cancelled`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, create_child, is_token_cancelled}

            let parent = token {}
            let child = create_child {token = parent}

            let child_before = is_token_cancelled {token = child}
            cancel_token {token = parent, reason = "Parent cancelled"}
            let child_after = is_token_cancelled {token = child}

            if (child_before == False) && child_after then 1 else 0
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test parent not cancelled when child cancelled`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, create_child, is_token_cancelled}

            let parent = token {}
            let child = create_child {token = parent}

            cancel_token {token = child, reason = "Child cancelled"}
            let parent_status = is_token_cancelled {token = parent}
            let child_status = is_token_cancelled {token = child}

            if (parent_status == False) && child_status then 1 else 0
        """)
        assertEquals(1L, result)
    }

    // === Cancellation with Async ===

    @Test
    fun `test cancellation in async context`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync}
            use std::effects::async::{token, cancel_token, is_token_cancelled}

            perform CoopAsync in do
                let ct = token {}
                let p = Async.spawn {f = {} -> do
                    cancel_token {token = ct, reason = "from async"}
                    42
                }
                let _ = Async.await {p = p}
                is_token_cancelled {token = ct}
        """)
        assertEquals(true, result)
    }

    // === Get Reason ===

    @Test
    fun `test get cancellation reason`() {
        val result = eval("""
            use std::effects::async::{token, cancel_token, get_reason}

            let ct = token {}
            cancel_token {token = ct, reason = "User requested"}
            match get_reason {token = ct} is
                Some {val = reason} -> reason
                None -> "no reason"
        """)
        assertEquals("User requested", result)
    }

    @Test
    fun `test uncancelled token has no reason`() {
        val result = eval("""
            use std::effects::async::{token, get_reason}

            let ct = token {}
            match get_reason {token = ct} is
                Some {val} -> False
                None -> True
        """)
        assertEquals(true, result)
    }
}
