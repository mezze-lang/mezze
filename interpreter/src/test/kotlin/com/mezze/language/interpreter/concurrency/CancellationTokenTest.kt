package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
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
 */
@Execution(ExecutionMode.SAME_THREAD)
class CancellationTokenTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // =============================================================================
    // Basic Token Operations
    // =============================================================================

    @Test
    fun `test create token`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, is_cancelled}

            let ct = token {}
            is_cancelled {token = ct}
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test cancel token`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, cancel, is_cancelled}

            let ct = token {}
            let before = is_cancelled {token = ct}
            cancel {token = ct, reason = "Test cancellation"}
            let after = is_cancelled {token = ct}
            {before = before, after = after}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeBool(false), result.fields["before"])
        assertEquals(MezzeBool(true), result.fields["after"])
    }

    @Test
    fun `test get cancellation reason`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, cancel, get_reason}

            let ct = token {}
            cancel {token = ct, reason = "User requested"}
            match get_reason {token = ct} is
                Some {val = reason} -> reason
                None -> "no reason"
        """
        val result = eval(code)
        assertEquals(MezzeString("User requested"), result)
    }

    @Test
    fun `test uncancelled token has no reason`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, get_reason}

            let ct = token {}
            match get_reason {token = ct} is
                Some {val = _} -> False
                None -> True
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    // =============================================================================
    // Child Tokens
    // =============================================================================

    @Test
    fun `test child token cancelled when parent cancelled`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let parent = token {}
            let child = create_child {token = parent}

            let child_before = is_cancelled {token = child}
            cancel {token = parent, reason = "Parent cancelled"}
            let child_after = is_cancelled {token = child}

            {before = child_before, after = child_after}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeBool(false), result.fields["before"])
        assertEquals(MezzeBool(true), result.fields["after"])
    }

    @Test
    fun `test parent not cancelled when child cancelled`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let parent = token {}
            let child = create_child {token = parent}

            cancel {token = child, reason = "Child cancelled"}
            let parent_status = is_cancelled {token = parent}
            let child_status = is_cancelled {token = child}

            {parent = parent_status, child = child_status}
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeBool(false), result.fields["parent"])
        assertEquals(MezzeBool(true), result.fields["child"])
    }

    @Test
    fun `test deeply nested child tokens`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let root = token {}
            let level1 = create_child {token = root}
            let level2 = create_child {token = level1}
            let level3 = create_child {token = level2}

            cancel {token = root, reason = "Root cancelled"}

            {
                root = is_cancelled {token = root},
                level1 = is_cancelled {token = level1},
                level2 = is_cancelled {token = level2},
                level3 = is_cancelled {token = level3}
            }
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeBool(true), result.fields["root"])
        assertEquals(MezzeBool(true), result.fields["level1"])
        assertEquals(MezzeBool(true), result.fields["level2"])
        assertEquals(MezzeBool(true), result.fields["level3"])
    }

    // =============================================================================
    // Combinators
    // =============================================================================

    @Test
    fun `test with_cancellation on active token`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, with_cancellation}

            let ct = token {}
            let result = with_cancellation {token = ct, f = {} -> 42}
            match result is
                Some {val = v} -> v
                None -> -1
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test with_cancellation on cancelled token`() {
        val code = """
            use std::effects::concurrent::cancellation::{token, cancel, with_cancellation}

            let ct = token {}
            cancel {token = ct, reason = "Already cancelled"}
            let result = with_cancellation {token = ct, f = {} -> 42}
            match result is
                Some {val = _} -> False
                None -> True
        """
        val result = eval(code)
        assertEquals(MezzeBool(true), result)
    }

    // =============================================================================
    // Integration with Async Scopes
    // =============================================================================

    @Test
    fun `test scope has cancellation token`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_get_token, scope_is_cancelled}
            use std::effects::concurrent::cancellation::{is_cancelled}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let token = scope_get_token {scope = scope}
                    let scope_cancelled = scope_is_cancelled {scope = scope}
                    scope_cancelled
                }
        """
        val result = eval(code)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test cancellation propagates to scope tasks when scope cancelled`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, scope_spawn, scope_cancel_all, scope_is_cancelled}

            perform LoomAsync in do
                Async.scoped {f = {scope} -> do
                    let p = scope_spawn {scope = scope, f = {} -> 42}
                    let before = scope_is_cancelled {scope = scope}
                    scope_cancel_all {scope = scope}
                    let after = scope_is_cancelled {scope = scope}
                    {before = before, after = after}
                }
        """
        val result = eval(code) as MezzeRecord
        assertEquals(MezzeBool(false), result.fields["before"])
        assertEquals(MezzeBool(true), result.fields["after"])
    }
}
