package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFails

/**
 * Integration tests for scoped effects type checking.
 *
 * These tests verify that the 'scope' variable is:
 * - Available in operations when initialize_scope is present
 * - Available in finalize_scope
 * - NOT available in initialize_scope itself
 * - NOT available when initialize_scope is absent
 */
class ScopedEffectsIntegrationTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(source: String) {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `scope is available in operations when initialize_scope is present`() {
        val source = """
            effect Pool where {
                query: { sql: Str } -> Str <Pool>
            }

            impl Pool for PgPool where {
                initialize_scope = {} -> { pool_id = 42 },
                finalize_scope = {} -> {},
                query = { sql } ->
                    do
                        let id = scope.pool_id
                        "result"
            }
        """.trimIndent()

        // Should not throw - scope.pool_id should be accessible
        typeCheck(source)
    }

    @Test
    fun `scope is available in finalize_scope`() {
        val source = """
            effect Pool where {
                query: { sql: Str } -> Str <Pool>
            }

            impl Pool for PgPool where {
                initialize_scope = {} -> { pool_id = 42 },
                finalize_scope = {} ->
                    do
                        let id = scope.pool_id
                        {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should not throw - scope.pool_id should be accessible in finalize_scope
        typeCheck(source)
    }

    @Test
    fun `scope is NOT available in initialize_scope`() {
        val source = """
            effect Pool where {
                query: { sql: Str } -> Str <Pool>
            }

            impl Pool for PgPool where {
                initialize_scope = {} ->
                    do
                        let x = scope.something
                        { pool_id = 42 },
                finalize_scope = {} -> {},
                query = { sql } -> "result"
            }
        """.trimIndent()

        // Should fail - scope is not available in initialize_scope
        val error = assertFails { typeCheck(source) }
        assertTrue(
            error.message?.contains("scope") == true ||
            error.message?.contains("undefined") == true ||
            error.message?.contains("Undefined") == true,
            "Expected error about 'scope' not being available: ${error.message}"
        )
    }

    @Test
    fun `scope is NOT available without initialize_scope`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
                info = { msg } ->
                    do
                        let x = scope.something
                        {}
            }
        """.trimIndent()

        // Should fail - no initialize_scope means no scope
        val error = assertFails { typeCheck(source) }
        assertTrue(
            error.message?.contains("scope") == true ||
            error.message?.contains("undefined") == true ||
            error.message?.contains("Undefined") == true,
            "Expected error about 'scope' not being available: ${error.message}"
        )
    }

    @Test
    fun `scope has correct field types`() {
        val source = """
            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter where {
                initialize_scope = {} -> { count = 0, name = "counter" },
                finalize_scope = {} -> {},
                increment = {} ->
                    do
                        let n: Int = scope.count
                        let s: Str = scope.name
                        n + 1
            }
        """.trimIndent()

        // Should not throw - scope fields have correct types
        typeCheck(source)
    }

    @Test
    fun `scope field type mismatch produces error`() {
        val source = """
            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Counter for SimpleCounter where {
                initialize_scope = {} -> { count = 0 },
                finalize_scope = {} -> {},
                increment = {} ->
                    do
                        let s: Str = scope.count
                        0
            }
        """.trimIndent()

        // Should fail - scope.count is Int, not String
        val error = assertFails { typeCheck(source) }
        assertTrue(
            error.message?.contains("type") == true ||
            error.message?.contains("Type") == true,
            "Expected type mismatch error: ${error.message}"
        )
    }

    @Test
    fun `scope cannot be used as variable name`() {
        val source = """
            let scope = 42
        """.trimIndent()

        // Should fail - scope is a reserved word
        val error = assertFails { parser.parseParsedModule(source, "test.mz") }
        assertTrue(
            error.message?.contains("scope") == true &&
            error.message?.contains("reserved") == true,
            "Expected 'scope is reserved' error: ${error.message}"
        )
    }
}
