package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for result module functions.
 * Verifies that importing and using result utility functions works correctly.
 */
class ResultModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        // Set project root for module resolution
        TestUtils.setupProjectRoot()
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `test result is_ok function`() {
        val source = """
            use std::result
            let ok_val = Ok { val = 42 }
            let result = result::is_ok { result = ok_val }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result is_err function`() {
        val source = """
            use std::result
            let err_val = Err { error = "failed" }
            let result = result::is_err { result = err_val }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result get_or_else function`() {
        val source = """
            use std::result
            let ok_val = Ok { val = 42 }
            let result = result::get_or_else { result = ok_val, def = 0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result map function`() {
        val source = """
            use std::result
            let ok_val = Ok { val = 21 }
            let doubled = result::map { result = ok_val, f = { val } -> val * 2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result map_err function`() {
        val source = """
            use std::result
            let err_val = Err { error = "error" }
            let mapped = result::map_err { result = err_val, f = { error } -> error }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result flat_map function`() {
        val source = """
            use std::result
            let ok_val = Ok { val = 42 }
            let result = result::flat_map { result = ok_val, f = { val } -> if val > 0 then Ok { val = val } else Err { error = "negative" } }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result or_else function`() {
        val source = """
            use std::result
            let err_val = Err { error = "failed" }
            let result = result::or_else { result = err_val, f = { error } -> Ok { val = 0 } }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result fold function`() {
        val source = """
            use std::result
            let ok_val = Ok { val = 42 }
            let result = result::fold {
                result = ok_val,
                on_err = { error } -> 0,
                on_ok = { val } -> val
            }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result to_option function`() {
        val source = """
            use std::result
            let ok_val = Ok { val = 42 }
            let opt = result::to_option { result = ok_val }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result from_option function`() {
        val source = """
            use std::result
            let some_val = Some { val = 42 }
            let res = result::from_option { opt = some_val, error = "no value" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test result map2 function`() {
        val source = """
            use std::result
            let ok_a = Ok { val = 10 }
            let ok_b = Ok { val = 32 }
            let combined = result::map2 {
                result_a = ok_a,
                result_b = ok_b,
                f = { a, b } -> a + b
            }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
