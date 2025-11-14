package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for std/effects/environment module functions.
 * Verifies that importing and using environment effect functions works correctly.
 */
class EnvironmentEffectModuleTest {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        typeChecker = TypeChecker()
        // Set project root for module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun parseAndTypeCheck(source: String): com.funk.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    @Test
    fun `test SystemEnvironment get function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let path = Environment.get_env { name = "PATH" }
                    path
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment set function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in Environment.set_env { name = "MY_VAR", value = "test" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment current_dir function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let dir = Environment.current_dir {}
                    dir
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment change_dir function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in Environment.change_dir { path = "/tmp" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment os_name function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let os = Environment.os_name {}
                    os
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment os_version function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let version = Environment.os_version {}
                    version
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment arch function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let arch = Environment.arch {}
                    arch
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test SystemEnvironment process_id function`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let pid = Environment.process_id {}
                    pid
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test MockEnvironment with custom env vars`() {
        val source = """
            use std::effects::environment::{Environment, MockEnvironment}
            let main = {} ->
                perform MockEnvironment {
                    env_vars = [],
                    current_directory = "/mock",
                    mock_os = "MockOS"
                } in do
                    let os = Environment.os_name {}
                    os
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test combining environment operations`() {
        val source = """
            use std::effects::environment::{Environment, SystemEnvironment}
            let main = {} ->
                perform SystemEnvironment in do
                    let os = Environment.os_name {}
                    let arch = Environment.arch {}
                    os
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
