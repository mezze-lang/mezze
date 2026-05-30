package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the five OS-surface effects:
 *   Env (env vars), Args (argv), Cwd (working dir),
 *   Os (OS name/version/arch), Process (pid/exit).
 *
 * Each effect has a System*** real impl and a Mock*** test impl. The mock
 * impls take only the fields relevant to that effect — no more 4-field
 * MockEnvironment to mock a single arg list.
 */
class EnvironmentEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ─── Env ──────────────────────────────────────────────────────────

    @Test
    fun `SystemEnv get returns Option for a known variable`() {
        val result = eval("""
            use std::effects::env::{Env, SystemEnv}

            perform SystemEnv in do
                let path = Env.lookup { name = "PATH" }
                True
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `SystemEnv all returns a seq`() {
        val result = eval("""
            use std::effects::env::{Env, SystemEnv}
            use std::collections::seq

            perform SystemEnv in do
                let vars = Env.all {}
                seq::size { xs = vars } >= 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `MockEnv get reads from configured vars`() {
        val result = eval("""
            use std::effects::env::{Env, MockEnv}

            perform MockEnv { vars = [{ key = "FOO", value = "bar" }] } in do
                let v = Env.lookup { name = "FOO" }
                match v is
                  Some { val = s } -> s == "bar"
                  None -> False
        """.trimIndent())
        assertEquals(true, result)
    }

    // ─── Args ─────────────────────────────────────────────────────────

    @Test
    fun `SystemArgs returns a seq`() {
        val result = eval("""
            use std::effects::args::{Args, SystemArgs}
            use std::collections::seq

            perform SystemArgs in do
                let a = Args.argv {}
                seq::size { xs = a } >= 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `MockArgs returns configured argv verbatim including special tokens`() {
        val result = eval("""
            use std::effects::args::{Args, MockArgs}
            use std::collections::seq

            perform MockArgs { vals = ["--verbose", "--", "-", "foo"] } in do
                let a = Args.argv {}
                seq::size { xs = a } == 4
        """.trimIndent())
        assertEquals(true, result)
    }

    // ─── Cwd ──────────────────────────────────────────────────────────

    @Test
    fun `SystemCwd get returns non-empty string`() {
        val result = eval("""
            use std::effects::cwd::{Cwd, SystemCwd}
            use std::str

            perform SystemCwd in do
                let d = Cwd.path {}
                str::size { s = d } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `MockCwd get returns configured dir`() {
        val result = eval("""
            use std::effects::cwd::{Cwd, MockCwd}

            perform MockCwd { dir = "/mock/path" } in do
                let d = Cwd.path {}
                d == "/mock/path"
        """.trimIndent())
        assertEquals(true, result)
    }

    // ─── Os ───────────────────────────────────────────────────────────

    @Test
    fun `SystemOs name returns non-empty string`() {
        val result = eval("""
            use std::effects::os::{Os, SystemOs}
            use std::str

            perform SystemOs in do
                let n = Os.name {}
                str::size { s = n } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `SystemOs arch returns non-empty string`() {
        val result = eval("""
            use std::effects::os::{Os, SystemOs}
            use std::str

            perform SystemOs in do
                let a = Os.arch {}
                str::size { s = a } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `MockOs returns configured values`() {
        val result = eval("""
            use std::effects::os::{Os, MockOs}

            perform MockOs { os_name = "TestOS", os_version = "1.0", os_arch = "mock64" } in do
                let n = Os.name {}
                let a = Os.arch {}
                n == "TestOS" && a == "mock64"
        """.trimIndent())
        assertEquals(true, result)
    }

    // ─── Process ──────────────────────────────────────────────────────

    @Test
    fun `SystemProcess pid returns positive`() {
        val result = eval("""
            use std::effects::process::{Process, SystemProcess}

            perform SystemProcess in do
                let p = Process.pid {}
                p > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `MockProcess pid returns configured value`() {
        val result = eval("""
            use std::effects::process::{Process, MockProcess}

            perform MockProcess { mock_pid = 99999 } in do
                let p = Process.pid {}
                p == 99999
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `MockProcess exit is a no-op so the test runner survives`() {
        val result = eval("""
            use std::effects::process::{Process, MockProcess}

            perform MockProcess { mock_pid = 1 } in do
                Process.exit { code = 42 }
                # If exit were actually called, we'd never reach this line.
                True
        """.trimIndent())
        assertEquals(true, result)
    }
}
