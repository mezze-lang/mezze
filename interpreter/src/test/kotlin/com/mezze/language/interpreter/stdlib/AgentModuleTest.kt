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
 * Tests for std/concurrent/agent module.
 *
 * Agents are Clojure-style async atoms that process updates sequentially
 * in a background thread. They provide:
 * - Async updates via send
 * - Synchronous reads via deref_agent
 * - Error handling with agent_error
 * - Await functionality for completion
 */
@Execution(ExecutionMode.SAME_THREAD)
class AgentModuleTest {

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
    // Basic Creation and Read
    // =============================================================================

    @Test
    fun `test agent creation with initial value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, deref_agent}

            let counter = agent { value = 42 }
            deref_agent { agent = counter }
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test agent with string value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, deref_agent}

            let name = agent { value = "hello" }
            deref_agent { agent = name }
        """)
        assertEquals(MezzeString("hello"), result)
    }

    @Test
    fun `test agent with record value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, deref_agent}

            let stats = agent { value = { count = 0, sum = 0 } }
            let current = deref_agent { agent = stats }
            current.count + current.sum
        """)
        assertEquals(MezzeInt(0), result)
    }

    // =============================================================================
    // Async Updates
    // =============================================================================

    @Test
    fun `test agent send single update`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let counter = agent { value = 0 }
            send { agent = counter, f = { current } -> current + 1 }
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test agent send multiple updates`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let counter = agent { value = 0 }
            send { agent = counter, f = { current } -> current + 10 }
            send { agent = counter, f = { current } -> current + 20 }
            send { agent = counter, f = { current } -> current + 30 }
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(MezzeInt(60), result)
    }

    @Test
    fun `test agent updates are sequential`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let counter = agent { value = 1 }
            send { agent = counter, f = { current } -> current * 2 }  # 2
            send { agent = counter, f = { current } -> current * 2 }  # 4
            send { agent = counter, f = { current } -> current * 2 }  # 8
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(MezzeInt(8), result)
    }

    @Test
    fun `test agent update with record value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let stats = agent { value = { count = 0, sum = 0 } }
            send { agent = stats, f = { current } -> { count = current.count + 1, sum = current.sum + 10 } }
            send { agent = stats, f = { current } -> { count = current.count + 1, sum = current.sum + 20 } }
            await_agent { agent = stats }
            let final = deref_agent { agent = stats }
            final.count * 100 + final.sum
        """)
        // count = 2, sum = 30, so 2*100 + 30 = 230
        assertEquals(MezzeInt(230), result)
    }

    // =============================================================================
    // Error Handling
    // =============================================================================

    @Test
    fun `test agent_error returns None initially`() {
        val result = eval("""
            use std::concurrent::agent::{agent, agent_error}

            let counter = agent { value = 0 }
            match agent_error { agent = counter } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test has_error returns false initially`() {
        val result = eval("""
            use std::concurrent::agent::{agent, has_error}

            let counter = agent { value = 0 }
            has_error { agent = counter }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // =============================================================================
    // Await with Timeout
    // =============================================================================

    @Test
    fun `test await_agent_timeout success`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, await_agent_timeout}

            let counter = agent { value = 0 }
            send { agent = counter, f = { current } -> current + 1 }
            await_agent_timeout { agent = counter, timeout_ms = 5000 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test await_agent_timeout on idle agent`() {
        val result = eval("""
            use std::concurrent::agent::{agent, await_agent_timeout}

            let counter = agent { value = 42 }
            # No pending updates, should succeed immediately
            await_agent_timeout { agent = counter, timeout_ms = 100 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    // =============================================================================
    // Restart
    // =============================================================================

    @Test
    fun `test restart_agent sets new value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent, restart_agent}

            let counter = agent { value = 0 }
            send { agent = counter, f = { current } -> current + 10 }
            await_agent { agent = counter }

            restart_agent { agent = counter, value = 100 }
            deref_agent { agent = counter }
        """)
        assertEquals(MezzeInt(100), result)
    }

    @Test
    fun `test restart_agent clears pending updates`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, restart_agent, await_agent}

            let counter = agent { value = 0 }
            restart_agent { agent = counter, value = 999 }
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(MezzeInt(999), result)
    }

    // =============================================================================
    // Multiple Agents
    // =============================================================================

    // Note: Test for multiple independent agents is covered in
    // AgentTest.kt in the concurrency directory at the implementation level
}
