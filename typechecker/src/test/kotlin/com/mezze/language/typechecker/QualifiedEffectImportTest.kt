package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Tests for qualified effect name imports and impl declarations.
 *
 * Key behaviors tested:
 * 1. Effects must be explicitly imported to be used in impl declarations
 * 2. Module imports (use module) don't bring effects into unqualified scope
 * 3. Selective imports (use module::{ Effect }) bring effects into scope
 * 4. Qualified effect names (module::Effect) work in impl declarations
 */
class QualifiedEffectImportTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // EFFECT MUST BE IMPORTED TESTS
    // ========================================================================

    @Test
    fun `impl with undefined effect should error`() {
        val source = """
            impl UndefinedEffect for MyHandler {} where {
                op = {} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("not defined or not imported") == true,
            "Error should mention effect not imported: ${exception.message}")
    }

    @Test
    fun `impl with locally defined effect works`() {
        val source = """
            effect MyEffect where {
                op: {} -> {} <MyEffect>
            }

            impl MyEffect for MyHandler {} where {
                op = {} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    // ========================================================================
    // QUALIFIED EFFECT NAME IN IMPL TESTS
    // ========================================================================

    @Test
    fun `impl with qualified effect name and module import works`() {
        val source = """
            use std::effects::log

            impl log::Log for TestLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `impl with unqualified effect name and selective import works`() {
        val source = """
            use std::effects::log::{ Log }

            impl Log for TestLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `impl with unqualified effect name and module import should error`() {
        val source = """
            use std::effects::log

            impl Log for TestLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("not defined or not imported") == true,
            "Error should mention effect not imported: ${exception.message}")
    }

    // ========================================================================
    // EFFECT OPERATIONS WITH QUALIFIED IMPORTS
    // ========================================================================

    @Test
    fun `effect operations work with qualified module access`() {
        val source = """
            use std::effects::log

            let test = {} -> log::info { msg = "hello" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `effect operations work with selective import`() {
        val source = """
            use std::effects::log::{ info }

            let test = {} -> info { msg = "hello" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    // ========================================================================
    // PERFORM EXPRESSION WITH QUALIFIED EFFECT IMPL
    // ========================================================================

    @Test
    fun `perform with qualified effect impl works`() {
        val source = """
            use std::effects::log

            impl log::Log for TestLogger {} where {
                log = {level, msg} -> {}
            }

            let main = {} -> perform TestLogger {} in log::info { msg = "test" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `perform with selective import works`() {
        val source = """
            use std::effects::log::{ Log, info }

            impl Log for TestLogger {} where {
                log = {level, msg} -> {}
            }

            let main = {} -> perform TestLogger {} in info { msg = "test" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `deeply qualified effect name works`() {
        val source = """
            use std::effects::log

            impl log::Log for DeepLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `impl handler correctly matches effect from expression`() {
        // This tests that when we write `impl log::Log`, it correctly
        // handles the `Log` effect that log::info produces
        val source = """
            use std::effects::log

            impl log::Log for MatchLogger {} where {
                log = {level, msg} -> {}
            }

            let test = {} -> perform MatchLogger {} in log::info { msg = "hello" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `multiple effects with mixed import styles work`() {
        val source = """
            use std::effects::log
            use std::effects::exception::{ Exception }

            impl log::Log for MultiLogger {} where {
                log = {level, msg} -> {}
            }

            let test = {} -> perform MultiLogger {} in log::info { msg = "test" }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }
}
