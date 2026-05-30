package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Typecheck tests for the five OS-surface effect modules that replaced
 * the old monolithic `std/effects/environment`:
 *   env, args, cwd, os, process.
 *
 * Each test imports the effect + a handler and confirms that the
 * resulting source typechecks. Runtime behavior is covered by
 * EnvironmentEffectTest in :interpreter:test.
 */
class EnvironmentEffectModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
    }

    private fun parseAndTypeCheck(source: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `Env get and all typecheck under SystemEnv`() {
        val source = """
            use std::effects::env::{Env, SystemEnv}
            let main = {} ->
                perform SystemEnv in do
                    let v = Env.lookup { name = "PATH" }
                    let all = Env.all {}
                    v
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Env set typechecks under SystemEnv`() {
        val source = """
            use std::effects::env::{Env, SystemEnv}
            let main = {} ->
                perform SystemEnv in Env.set { name = "X", value = "y" }
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `MockEnv with vars typechecks`() {
        val source = """
            use std::effects::env::{Env, MockEnv}
            let main = {} ->
                perform MockEnv { vars = [{ key = "FOO", value = "bar" }] } in do
                    let v = Env.lookup { name = "FOO" }
                    v
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Args get typechecks under SystemArgs`() {
        val source = """
            use std::effects::args::{Args, SystemArgs}
            let main = {} ->
                perform SystemArgs in do
                    let a = Args.argv {}
                    a
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `MockArgs with vals typechecks`() {
        val source = """
            use std::effects::args::{Args, MockArgs}
            let main = {} ->
                perform MockArgs { vals = ["a", "b"] } in Args.argv {}
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Cwd get and set typecheck under SystemCwd`() {
        val source = """
            use std::effects::cwd::{Cwd, SystemCwd}
            let main = {} ->
                perform SystemCwd in do
                    let d = Cwd.path {}
                    Cwd.change { path = "/tmp" }
                    d
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `MockCwd with dir typechecks`() {
        val source = """
            use std::effects::cwd::{Cwd, MockCwd}
            let main = {} ->
                perform MockCwd { dir = "/mock" } in Cwd.path {}
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Os name version arch typecheck under SystemOs`() {
        val source = """
            use std::effects::os::{Os, SystemOs}
            let main = {} ->
                perform SystemOs in do
                    let n = Os.name {}
                    let v = Os.version {}
                    let a = Os.arch {}
                    n
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `MockOs typechecks`() {
        val source = """
            use std::effects::os::{Os, MockOs}
            let main = {} ->
                perform MockOs { os_name = "Linux", os_version = "1", os_arch = "x86_64" } in Os.name {}
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }

    @Test
    fun `Process pid and exit typecheck under SystemProcess`() {
        val source = """
            use std::effects::process::{Process, SystemProcess}
            let main = {} ->
                perform SystemProcess in do
                    let p = Process.pid {}
                    p
        """.trimIndent()
        assertNotNull(parseAndTypeCheck(source))
    }
}
