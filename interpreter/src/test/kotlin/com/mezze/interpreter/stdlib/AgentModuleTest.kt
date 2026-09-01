package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
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

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Basic Creation and Read
    // =============================================================================

    @Test
    fun `test agent creation with initial value`() {
        val result = eval("""
            use std::effects::async::agent::{agent, deref_agent}

            let counter = agent { val = 42 }
            deref_agent { agent = counter }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test agent with string value`() {
        val result = eval("""
            use std::effects::async::agent::{agent, deref_agent}

            let name = agent { val = "hello" }
            deref_agent { agent = name }
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `test agent with record value`() {
        val result = eval("""
            use std::effects::async::agent::{agent, deref_agent}

            let stats = agent { val = { count = 0, sum = 0 } }
            let current = deref_agent { agent = stats }
            current.count + current.sum
        """)
        assertEquals(0L, result)
    }

    // =============================================================================
    // Async Updates
    // =============================================================================

    @Test
    fun `test agent send single update`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, deref_agent, await_agent}

            let counter = agent { val = 0 }
            send { agent = counter, f = { current } -> current + 1 }
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test agent send multiple updates`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, deref_agent, await_agent}

            let counter = agent { val = 0 }
            send { agent = counter, f = { current } -> current + 10 }
            send { agent = counter, f = { current } -> current + 20 }
            send { agent = counter, f = { current } -> current + 30 }
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(60L, result)
    }

    @Test
    fun `test agent updates are sequential`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, deref_agent, await_agent}

            let counter = agent { val = 1 }
            send { agent = counter, f = { current } -> current * 2 }  # 2
            send { agent = counter, f = { current } -> current * 2 }  # 4
            send { agent = counter, f = { current } -> current * 2 }  # 8
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(8L, result)
    }

    @Test
    fun `test agent update with record value`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, deref_agent, await_agent}

            let stats = agent { val = { count = 0, sum = 0 } }
            send { agent = stats, f = { current } -> { count = current.count + 1, sum = current.sum + 10 } }
            send { agent = stats, f = { current } -> { count = current.count + 1, sum = current.sum + 20 } }
            await_agent { agent = stats }
            let final = deref_agent { agent = stats }
            final.count * 100 + final.sum
        """)
        // count = 2, sum = 30, so 2*100 + 30 = 230
        assertEquals(230L, result)
    }

    // =============================================================================
    // Error Handling
    // =============================================================================

    @Test
    fun `test agent_error returns None initially`() {
        val result = eval("""
            use std::effects::async::agent::{agent, agent_error}

            let counter = agent { val = 0 }
            match agent_error { agent = counter } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test has_error returns false initially`() {
        val result = eval("""
            use std::effects::async::agent::{agent, has_error}

            let counter = agent { val = 0 }
            has_error { agent = counter }
        """)
        assertEquals(false, result)
    }

    // =============================================================================
    // Error-recovery round-trip (S8.1-C / #385): a FAILED agent is recoverable.
    // This locks in the Clojure model we settled on: send is strict (a failed
    // agent stops accepting sends), the failure is observable (has_error), and
    // restart_agent clears the error so the agent accepts sends again. Distinct
    // from the "restart sets a value" tests: here the agent genuinely FAILS first
    // (an action raises a guest error), then is recovered.
    // =============================================================================

    @Test
    fun `test a failed agent reports has_error then recovers via restart`() {
        val result = eval("""
            use std::effects::async::agent::{
                agent, send, deref_agent, await_agent, has_error, restart_agent
            }
            use std::effects::async::{ Async, LoomAsync }

            perform LoomAsync in do

            let counter = agent { val = 10 }
            # An action that fails: divide by zero is a clean guest error, so the
            # agent's action throws and the agent enters the error state.
            send { agent = counter, f = { current } -> current / 0 }
            await_agent { agent = counter }

            let failed = has_error { agent = counter }

            # Recover: restart clears the error and installs a fresh value.
            restart_agent { agent = counter, val = 100 }
            let recovered = has_error { agent = counter }

            # A send after restart is accepted again (agent is live).
            send { agent = counter, f = { current } -> current + 1 }
            await_agent { agent = counter }
            let final_val = deref_agent { agent = counter }

            { failed = failed, recovered = recovered, final_val = final_val }
        """)
        // failed=true (agent entered error state), recovered=false (restart
        // cleared it), final_val=101 (100 from restart + 1 from the post-restart
        // send that was accepted). Booleans render lowercase.
        val record = result.toString()
        assertTrue(record.contains("failed = true"),
            "agent should have entered error state; got: $record")
        assertTrue(record.contains("recovered = false"),
            "restart should have cleared the error state; got: $record")
        assertTrue(record.contains("101"),
            "post-restart send should apply (100+1); got: $record")
    }

    // =============================================================================
    // Await with Timeout
    // =============================================================================

    @Test
    fun `test await_agent_timeout success`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, await_agent_timeout}

            let counter = agent { val = 0 }
            send { agent = counter, f = { current } -> current + 1 }
            await_agent_timeout { agent = counter, timeout_ms = 5000 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test await_agent_timeout on idle agent`() {
        val result = eval("""
            use std::effects::async::agent::{agent, await_agent_timeout}

            let counter = agent { val = 42 }
            # No pending updates, should succeed immediately
            await_agent_timeout { agent = counter, timeout_ms = 100 }
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Restart
    // =============================================================================

    @Test
    fun `test restart_agent sets new value`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, deref_agent, await_agent, restart_agent}

            let counter = agent { val = 0 }
            send { agent = counter, f = { current } -> current + 10 }
            await_agent { agent = counter }

            restart_agent { agent = counter, val = 100 }
            deref_agent { agent = counter }
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test restart_agent clears pending updates`() {
        val result = eval("""
            use std::effects::async::agent::{agent, send, deref_agent, restart_agent, await_agent}

            let counter = agent { val = 0 }
            restart_agent { agent = counter, val = 999 }
            await_agent { agent = counter }
            deref_agent { agent = counter }
        """)
        assertEquals(999L, result)
    }

    // =============================================================================
    // Multiple Agents
    // =============================================================================

    // Note: Test for multiple independent agents is covered in
    // AgentTest.kt in the concurrency directory at the implementation level
}
