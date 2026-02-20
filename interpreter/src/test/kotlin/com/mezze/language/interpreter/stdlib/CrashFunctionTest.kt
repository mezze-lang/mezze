package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Tests for the crash built-in function.
 *
 * crash is a built-in function (no import needed) that terminates the program
 * with an error message, similar to Rust's panic!.
 */
class CrashFunctionTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `crash function type checks correctly`() {
        val source = """
            let fail = {} -> crash { msg = "Something went wrong" }
        """.trimIndent()

        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // Should type check without throwing an exception
        assertDoesNotThrow {
            typeChecker.typeCheck(program, projectRoot)
        }
    }

    @Test
    fun `crash function throws exception when called`() {
        val source = """
            let main = {} -> crash { msg = "Test crash message" }
            main {}
        """.trimIndent()

        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)

        // The crash function should throw an exception
        val exception = assertThrows<Exception> {
            MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        }

        assertTrue(
            exception.message?.contains("crash") == true ||
            exception.message?.contains("Test crash message") == true,
            "Exception should mention crash or the message: ${exception.message}"
        )
    }

    @Test
    fun `crash returns polymorphic type - can be used anywhere`() {
        // crash returns 'a which means it can be used in any context
        val source = """
            let get_int = {} ->
                if True then 42 else crash { msg = "unreachable" }

            let get_string = {} ->
                if True then "hello" else crash { msg = "unreachable" }
        """.trimIndent()

        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // Should type check without errors - crash works with any return type
        assertDoesNotThrow {
            typeChecker.typeCheck(program, projectRoot)
        }
    }

    @Test
    fun `crash is available without import`() {
        // crash should be a built-in, no import needed
        val source = """
            let test = {} -> crash { msg = "no import needed" }
        """.trimIndent()

        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))

        // Should type check - crash is built-in
        assertDoesNotThrow {
            typeChecker.typeCheck(program, projectRoot)
        }
    }
}
