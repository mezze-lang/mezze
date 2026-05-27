package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for CancellationToken.
 *
 * CancellationToken provides cooperative cancellation for async operations.
 * Key features:
 * - Check cancellation status
 * - Register callbacks to run on cancellation
 * - Create child tokens (hierarchical cancellation)
 * - Integration with structured concurrency scopes
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CancellationTokenTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Basic Token Operations
    // =============================================================================

    @Test
    fun `test create token`() {
        val code = """
            use std::effects::async::{token, is_token_cancelled}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let ct = token {}
            is_token_cancelled {token = ct}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test cancel token`() {
        val code = """
            use std::effects::async::{token, cancel_token, is_token_cancelled}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let ct = token {}
            let before = is_token_cancelled {token = ct}
            cancel_token {token = ct, reason = "Test cancellation"}
            let after = is_token_cancelled {token = ct}
            {before = before, after = after}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(false, result.getField("before"))
        assertEquals(true, result.getField("after"))
    }

    @Test
    fun `test get cancellation reason`() {
        val code = """
            use std::effects::async::{token, cancel_token, get_reason}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let ct = token {}
            cancel_token {token = ct, reason = "User requested"}
            match get_reason {token = ct} is
                Some {val = reason} -> reason
                None -> "no reason"
        """
        val result = eval(code)
        assertEquals("User requested", result)
    }

    @Test
    fun `test uncancelled token has no reason`() {
        val code = """
            use std::effects::async::{token, get_reason}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let ct = token {}
            match get_reason {token = ct} is
                Some {val = _} -> False
                None -> True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // Child Tokens
    // =============================================================================

    @Test
    fun `test child token cancelled when parent cancelled`() {
        val code = """
            use std::effects::async::{token, cancel_token, create_child, is_token_cancelled}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let parent = token {}
            let child = create_child {token = parent}

            let child_before = is_token_cancelled {token = child}
            cancel_token {token = parent, reason = "Parent cancelled"}
            let child_after = is_token_cancelled {token = child}

            {before = child_before, after = child_after}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(false, result.getField("before"))
        assertEquals(true, result.getField("after"))
    }

    @Test
    fun `test parent not cancelled when child cancelled`() {
        val code = """
            use std::effects::async::{token, cancel_token, create_child, is_token_cancelled}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let parent = token {}
            let child = create_child {token = parent}

            cancel_token {token = child, reason = "Child cancelled"}
            let parent_status = is_token_cancelled {token = parent}
            let child_status = is_token_cancelled {token = child}

            {parent = parent_status, child = child_status}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(false, result.getField("parent"))
        assertEquals(true, result.getField("child"))
    }

    @Test
    fun `test deeply nested child tokens`() {
        val code = """
            use std::effects::async::{token, cancel_token, create_child, is_token_cancelled}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let root = token {}
            let level1 = create_child {token = root}
            let level2 = create_child {token = level1}
            let level3 = create_child {token = level2}

            cancel_token {token = root, reason = "Root cancelled"}

            {
                root = is_token_cancelled {token = root},
                level1 = is_token_cancelled {token = level1},
                level2 = is_token_cancelled {token = level2},
                level3 = is_token_cancelled {token = level3}
            }
        """
        val result = eval(code) as MezzeRecord
        assertEquals(true, result.getField("root"))
        assertEquals(true, result.getField("level1"))
        assertEquals(true, result.getField("level2"))
        assertEquals(true, result.getField("level3"))
    }

    // =============================================================================
    // Combinators
    // =============================================================================

    @Test
    fun `test with_cancellation on active token`() {
        val code = """
            use std::effects::async::{token, with_cancellation}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let ct = token {}
            let result = with_cancellation {token = ct, f = {} -> 42}
            match result is
                Some {val = v} -> v
                None -> -1
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_cancellation on cancelled token`() {
        val code = """
            use std::effects::async::{token, cancel_token, with_cancellation}
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let ct = token {}
            cancel_token {token = ct, reason = "Already cancelled"}
            let result = with_cancellation {token = ct, f = {} -> 42}
            match result is
                Some {val = _} -> False
                None -> True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // Integration with Async Scopes
    // =============================================================================

    @Test
    fun `test scope has cancellation token`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_get_token, scope_is_cancelled}
            use std::effects::async::{is_token_cancelled}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let token = scope_get_token {sc = sc}
                    let scope_cancelled = scope_is_cancelled {sc = sc}
                    scope_cancelled
                }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test cancellation propagates to scope tasks when scope cancelled`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scope_cancel_all, scope_is_cancelled}

            perform LoomAsync in do
                Async.scoped {f = {sc} -> do
                    let p = scope_spawn {sc = sc, f = {} -> 42}
                    let before = scope_is_cancelled {sc = sc}
                    scope_cancel_all {sc = sc}
                    let after = scope_is_cancelled {sc = sc}
                    {before = before, after = after}
                }
        """
        val result = eval(code) as MezzeRecord
        assertEquals(false, result.getField("before"))
        assertEquals(true, result.getField("after"))
    }
}
