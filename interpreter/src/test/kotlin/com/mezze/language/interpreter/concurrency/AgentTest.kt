package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for Clojure-style Agents (async atoms).
 */
class AgentTest {

    private val parser = MezzeParser()

    private fun eval(source: String): Any? {
        // Clear module cache to ensure clean state between tests
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        val program = parser.parse(source)
        // Create fresh TypeChecker for each test to avoid type variable pollution between tests
        val typeChecker = TypeChecker()
        val typeCheckResult = typeChecker.typeCheck(program)
        return MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
    }

    // ========== Basic Creation and Read ==========

    @Test
    fun `test agent creation`() {
        val result = eval("""
            use std::concurrent::agent::{agent, deref_agent}

            let counter = agent {value = 42}
            deref_agent {agent = counter}
        """.trimIndent())

        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test agent with record value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, deref_agent}

            let stats = agent {value = {count = 0, sum = 0}}
            let current = deref_agent {agent = stats}
            current.count
        """.trimIndent())

        assertEquals(0L, (result as MezzeInt).value)
    }

    // ========== Async Updates ==========

    @Test
    fun `test agent send single update`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let counter = agent {value = 0}
            send {agent = counter, f = {current} -> current + 1}
            await_agent {agent = counter}
            deref_agent {agent = counter}
        """.trimIndent())

        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    fun `test agent send multiple updates`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let counter = agent {value = 0}
            send {agent = counter, f = {current} -> current + 1}
            send {agent = counter, f = {current} -> current + 1}
            send {agent = counter, f = {current} -> current + 1}
            await_agent {agent = counter}
            deref_agent {agent = counter}
        """.trimIndent())

        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test agent updates are sequential`() {
        // Each update should see the result of the previous one
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let counter = agent {value = 1}
            send {agent = counter, f = {current} -> current * 2}  # 2
            send {agent = counter, f = {current} -> current * 2}  # 4
            send {agent = counter, f = {current} -> current * 2}  # 8
            await_agent {agent = counter}
            deref_agent {agent = counter}
        """.trimIndent())

        assertEquals(8L, (result as MezzeInt).value)
    }

    // ========== Error Handling ==========

    @Test
    fun `test agent no error initially`() {
        val result = eval("""
            use std::concurrent::agent::{agent, agent_error}

            let counter = agent {value = 0}
            agent_error {agent = counter}
        """.trimIndent())

        val variant = result as MezzeVariant
        assertEquals("None", variant.constructor)
    }

    @Test
    fun `test agent has_error false initially`() {
        val result = eval("""
            use std::concurrent::agent::{agent, has_error}

            let counter = agent {value = 0}
            has_error {agent = counter}
        """.trimIndent())

        assertEquals(false, (result as MezzeBool).value)
    }

    // ========== Await with Timeout ==========

    @Test
    fun `test agent await_agent_timeout success`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, await_agent_timeout}

            let counter = agent {value = 0}
            send {agent = counter, f = {current} -> current + 1}
            await_agent_timeout {agent = counter, timeout_ms = 5000}
        """.trimIndent())

        assertEquals(true, (result as MezzeBool).value)
    }

    // ========== Restart ==========

    @Test
    fun `test agent restart sets new value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent, restart_agent}

            let counter = agent {value = 0}
            send {agent = counter, f = {current} -> current + 10}
            await_agent {agent = counter}

            restart_agent {agent = counter, value = 100}
            deref_agent {agent = counter}
        """.trimIndent())

        assertEquals(100L, (result as MezzeInt).value)
    }

    // ========== Complex Value Types ==========

    @Test
    fun `test agent with record value updates`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let stats = agent {value = {count = 0, sum = 0}}
            send {agent = stats, f = {current} -> {count = current.count + 1, sum = current.sum + 10}}
            send {agent = stats, f = {current} -> {count = current.count + 1, sum = current.sum + 20}}
            await_agent {agent = stats}

            let final = deref_agent {agent = stats}
            final.sum
        """.trimIndent())

        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun `test agent with string value`() {
        val result = eval("""
            use std::concurrent::agent::{agent, send, deref_agent, await_agent}

            let log = agent {value = "start"}
            send {agent = log, f = {current} -> "updated"}
            await_agent {agent = log}
            deref_agent {agent = log}
        """.trimIndent())

        assertEquals("updated", (result as MezzeString).value)
    }
}
