package com.mezze.language.interpreter.stdlib

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
 * Tests for std/concurrent/cancellation module.
 *
 * CancellationToken provides cooperative cancellation for async operations:
 * - Check cancellation status with is_cancelled
 * - Cancel with a reason via cancel
 * - Create child tokens for hierarchical cancellation
 * - Integration with async scopes
 */
@Execution(ExecutionMode.SAME_THREAD)
class CancellationModuleTest {

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
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, is_cancelled}

            let ct = token {}
            is_cancelled { token = ct }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test cancel token`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, is_cancelled}

            let ct = token {}
            let before = is_cancelled { token = ct }
            cancel { token = ct, reason = "Test cancellation" }
            let after = is_cancelled { token = ct }
            { before = before, after = after }
        """)
        val record = result as MezzeRecord
        assertEquals(MezzeBool(false), record.fields["before"])
        assertEquals(MezzeBool(true), record.fields["after"])
    }

    @Test
    fun `test get cancellation reason`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, get_reason}

            let ct = token {}
            cancel { token = ct, reason = "User requested" }
            match get_reason { token = ct } is
                Some { val = reason } -> reason
                None -> "no reason"
        """)
        assertEquals(MezzeString("User requested"), result)
    }

    @Test
    fun `test uncancelled token has no reason`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, get_reason}

            let ct = token {}
            match get_reason { token = ct } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test cancel is idempotent`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, is_cancelled}

            let ct = token {}
            cancel { token = ct, reason = "first" }
            cancel { token = ct, reason = "second" }
            cancel { token = ct, reason = "third" }
            is_cancelled { token = ct }
        """)
        assertEquals(MezzeBool(true), result)
    }

    // =============================================================================
    // Child Tokens
    // =============================================================================

    @Test
    fun `test child token cancelled when parent cancelled`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let parent = token {}
            let child = create_child { token = parent }

            let child_before = is_cancelled { token = child }
            cancel { token = parent, reason = "Parent cancelled" }
            let child_after = is_cancelled { token = child }

            { before = child_before, after = child_after }
        """)
        val record = result as MezzeRecord
        assertEquals(MezzeBool(false), record.fields["before"])
        assertEquals(MezzeBool(true), record.fields["after"])
    }

    @Test
    fun `test parent not cancelled when child cancelled`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let parent = token {}
            let child = create_child { token = parent }

            cancel { token = child, reason = "Child cancelled" }
            let parent_status = is_cancelled { token = parent }
            let child_status = is_cancelled { token = child }

            { parent = parent_status, child = child_status }
        """)
        val record = result as MezzeRecord
        assertEquals(MezzeBool(false), record.fields["parent"])
        assertEquals(MezzeBool(true), record.fields["child"])
    }

    @Test
    fun `test deeply nested child tokens`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let root = token {}
            let level1 = create_child { token = root }
            let level2 = create_child { token = level1 }
            let level3 = create_child { token = level2 }

            cancel { token = root, reason = "Root cancelled" }

            {
                root = is_cancelled { token = root },
                level1 = is_cancelled { token = level1 },
                level2 = is_cancelled { token = level2 },
                level3 = is_cancelled { token = level3 }
            }
        """)
        val record = result as MezzeRecord
        assertEquals(MezzeBool(true), record.fields["root"])
        assertEquals(MezzeBool(true), record.fields["level1"])
        assertEquals(MezzeBool(true), record.fields["level2"])
        assertEquals(MezzeBool(true), record.fields["level3"])
    }

    @Test
    fun `test sibling tokens are independent`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, create_child, is_cancelled}

            let parent = token {}
            let child1 = create_child { token = parent }
            let child2 = create_child { token = parent }

            cancel { token = child1, reason = "Child1 cancelled" }

            {
                parent = is_cancelled { token = parent },
                child1 = is_cancelled { token = child1 },
                child2 = is_cancelled { token = child2 }
            }
        """)
        val record = result as MezzeRecord
        assertEquals(MezzeBool(false), record.fields["parent"])
        assertEquals(MezzeBool(true), record.fields["child1"])
        assertEquals(MezzeBool(false), record.fields["child2"])
    }

    // =============================================================================
    // Combinators
    // =============================================================================

    @Test
    fun `test with_cancellation on active token`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, with_cancellation}

            let ct = token {}
            let result = with_cancellation { token = ct, f = {} -> 42 }
            match result is
                Some { val = v } -> v
                None -> -1
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test with_cancellation on cancelled token`() {
        val result = eval("""
            use std::effects::concurrent::cancellation::{token, cancel, with_cancellation}

            let ct = token {}
            cancel { token = ct, reason = "Already cancelled" }
            let result = with_cancellation { token = ct, f = {} -> 42 }
            match result is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    // =============================================================================
    // Integration with Async Scopes
    // =============================================================================

    @Test
    fun `test scope has cancellation token`() {
        val result = eval("""
            use std::effects::async::{Async, LoomAsync, scope_get_token, scope_is_cancelled}
            use std::effects::concurrent::cancellation::{is_cancelled}

            perform LoomAsync in do
                Async.scoped { f = { sc } -> do
                    let token = scope_get_token { sc = sc }
                    let scope_cancelled = scope_is_cancelled { sc = sc }
                    scope_cancelled
                }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test scope_cancel_all cancels scope`() {
        val result = eval("""
            use std::effects::async::{Async, LoomAsync, scope_spawn, scope_cancel_all, scope_is_cancelled}

            perform LoomAsync in do
                Async.scoped { f = { sc } -> do
                    let p = scope_spawn { sc = sc, f = {} -> 42 }
                    let before = scope_is_cancelled { sc = sc }
                    scope_cancel_all { sc = sc }
                    let after = scope_is_cancelled { sc = sc }
                    { before = before, after = after }
                }
        """)
        val record = result as MezzeRecord
        assertEquals(MezzeBool(false), record.fields["before"])
        assertEquals(MezzeBool(true), record.fields["after"])
    }
}
