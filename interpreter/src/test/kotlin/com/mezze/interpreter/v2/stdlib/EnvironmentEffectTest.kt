package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/environment module.
 * Tests environment and system information effects with MockEnvironment and SystemEnvironment handlers.
 * Migrated from V1 to V2 with unboxed values.
 */
class EnvironmentEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === SystemEnvironment Tests (actual system) ===

    @Test
    fun `test SystemEnvironment current_dir returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let dir = Environment.current_dir {}
                __native_string_length { s = dir } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment os_name returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let name = Environment.os_name {}
                __native_string_length { s = name } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment process_id returns positive number`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let pid = Environment.process_id {}
                pid > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment arch returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let arch = Environment.arch {}
                __native_string_length { s = arch } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment os_version returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let version = Environment.os_version {}
                __native_string_length { s = version } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment process_args returns array`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let args = Environment.process_args {}
                __native_arraylist_length { xs = args } >= 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment get_all_env returns array`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let vars = Environment.get_all_env {}
                __native_arraylist_length { xs = vars } >= 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test SystemEnvironment get_env returns Option`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let path = Environment.get_env { name = "PATH" }
                # Just check it returns successfully, it's an Option
                True
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test get_env convenience function`() {
        val result = eval("""
            use std::effects::environment::{get_env, SystemEnvironment}

            perform SystemEnvironment in do
                let path = get_env { name = "PATH" }
                # Just check it returns successfully, it's an Option
                True
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test current_dir convenience function`() {
        val result = eval("""
            use std::effects::environment::{current_dir, SystemEnvironment}

            perform SystemEnvironment in do
                let dir = current_dir {}
                __native_string_length { s = dir } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test os_info convenience function`() {
        val result = eval("""
            use std::effects::environment::{os_info, SystemEnvironment}

            perform SystemEnvironment in do
                let info = os_info {}
                __native_string_length { s = info.name } > 0
        """.trimIndent())
        assertEquals(true, result)
    }

    // === MockEnvironment Tests ===

    @Test
    fun `test MockEnvironment current_dir returns configured value`() {
        val result = eval("""
            use std::effects::environment::{Environment, MockEnvironment}

            perform MockEnvironment {
                env_vars = [],
                current_directory = "/mock/test/dir",
                mock_os = "MockOS"
            } in do
                let dir = Environment.current_dir {}
                dir == "/mock/test/dir"
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test MockEnvironment os_name returns configured value`() {
        val result = eval("""
            use std::effects::environment::{Environment, MockEnvironment}

            perform MockEnvironment {
                env_vars = [],
                current_directory = "/",
                mock_os = "TestOS"
            } in do
                let name = Environment.os_name {}
                name == "TestOS"
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test MockEnvironment process_id returns fixed value`() {
        val result = eval("""
            use std::effects::environment::{Environment, MockEnvironment}

            perform MockEnvironment {
                env_vars = [],
                current_directory = "/",
                mock_os = "MockOS"
            } in do
                let pid = Environment.process_id {}
                pid == 12345
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test MockEnvironment arch returns mock value`() {
        val result = eval("""
            use std::effects::environment::{Environment, MockEnvironment}

            perform MockEnvironment {
                env_vars = [],
                current_directory = "/",
                mock_os = "MockOS"
            } in do
                let arch = Environment.arch {}
                arch == "mock64"
        """.trimIndent())
        assertEquals(true, result)
    }

    @Test
    fun `test MockEnvironment os_version returns mock value`() {
        val result = eval("""
            use std::effects::environment::{Environment, MockEnvironment}

            perform MockEnvironment {
                env_vars = [],
                current_directory = "/",
                mock_os = "MockOS"
            } in do
                let version = Environment.os_version {}
                version == "Mock 1.0"
        """.trimIndent())
        assertEquals(true, result)
    }
}
