package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/environment module.
 * Tests environment and system information effects with MockEnvironment and SystemEnvironment handlers.
 */
class EnvironmentEffectTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === SystemEnvironment Tests (actual system) ===

    @Test
    fun `test SystemEnvironment current_dir returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let dir = Environment.current_dir {}
                __native_string_length { s = dir } > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment os_name returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let name = Environment.os_name {}
                __native_string_length { s = name } > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment process_id returns positive number`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let pid = Environment.process_id {}
                pid > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment arch returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let arch = Environment.arch {}
                __native_string_length { s = arch } > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment os_version returns non-empty string`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let version = Environment.os_version {}
                __native_string_length { s = version } > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment process_args returns array`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let args = Environment.process_args {}
                __native_arraylist_length { xs = args } >= 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment get_all_env returns array`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let vars = Environment.get_all_env {}
                __native_arraylist_length { xs = vars } >= 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test SystemEnvironment get_env returns Option`() {
        val result = eval("""
            use std::effects::environment::{Environment, SystemEnvironment}

            perform SystemEnvironment in do
                let path = Environment.get_env { name = "PATH" }
                # Just check it returns successfully, it's an Option
                True
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test get_env convenience function`() {
        val result = eval("""
            use std::effects::environment::{get_env, SystemEnvironment}

            perform SystemEnvironment in do
                let path = get_env { name = "PATH" }
                # Just check it returns successfully, it's an Option
                True
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test current_dir convenience function`() {
        val result = eval("""
            use std::effects::environment::{current_dir, SystemEnvironment}

            perform SystemEnvironment in do
                let dir = current_dir {}
                __native_string_length { s = dir } > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test os_info convenience function`() {
        val result = eval("""
            use std::effects::environment::{os_info, SystemEnvironment}

            perform SystemEnvironment in do
                let info = os_info {}
                __native_string_length { s = info.name } > 0
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }
}
