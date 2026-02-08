package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * Tests for type checking literal patterns in match expressions
 */
class LiteralPatternTypeCheckingTest {

    private val parser = FunkParser()

    private fun parseAndTypeCheck(code: String): Program {
        // CRITICAL FIX: Create fresh TypeChecker for each test to avoid state leakage
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        val typeChecker = TypeChecker()
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    // ========== Valid Integer Literal Patterns ==========

    @Test
    fun `should accept integer literal patterns matching Int scrutinee`() {
        val code = """
            let classify = {n} ->
                match n is
                  0 -> "zero"
                  1 -> "one"
                  _ -> "other"
            let result = classify { n = 5 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should accept integer literal patterns with pipe`() {
        val code = """
            let is_small = {n} ->
                match n is
                  1 | 2 | 3 -> True
                  _ -> False
            let result = is_small { n = 2 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should accept integer literal patterns with guards`() {
        val code = """
            let describe = {n, verbose} ->
                match n is
                  0 if verbose -> "exactly zero (verbose)"
                  0 -> "zero"
                  _ -> "other"
            let result = describe { n = 0, verbose = True }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Valid String Literal Patterns ==========

    @Test
    fun `should accept string literal patterns matching String scrutinee`() {
        val code = """
            let greet = {name} ->
                match name is
                  "Alice" -> "Hello Alice!"
                  "Bob" -> "Hi Bob!"
                  _ -> "Hello stranger!"
            let result = greet { name = "Alice" }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should accept empty string literal pattern`() {
        val code = """
            let check_empty = {s} ->
                match s is
                  "" -> True
                  _ -> False
            let result = check_empty { s = "" }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should accept string literal patterns with pipe`() {
        val code = """
            let is_greeting = {word} ->
                match word is
                  "hello" | "hi" | "hey" -> True
                  _ -> False
            let result = is_greeting { word = "hello" }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Valid Boolean Literal Patterns ==========

    @Test
    fun `should accept boolean literal patterns matching Bool scrutinee`() {
        val code = """
            let to_string = {b} ->
                match b is
                  True ->"yes"
                  False ->"no"
            let result = to_string { b = True }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should accept single boolean literal pattern with wildcard`() {
        val code = """
            let is_active = {flag} ->
                match flag is
                  True ->"active"
                  _ -> "inactive"
            let result = is_active { flag = False }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Mixed Literal and Variable Patterns ==========

    @Test
    fun `should reject mixed literal and variable patterns with incompatible types`() {
        val code = """
            let process = {n} ->
                match n is
                  0 -> "zero"
                  x -> x + 1
            let result = process { n = 5 }
        """.trimIndent()

        // This should fail because branches return incompatible types: String vs Int
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Check that the error message mentions incompatible types (either generic unification error or specific ability error)
        assertTrue(
            exception.message?.contains("cannot unify", ignoreCase = true) == true ||
            exception.message?.contains("type mismatch", ignoreCase = true) == true ||
            exception.message?.contains("incompatible types", ignoreCase = true) == true ||
            exception.message?.contains("does not implement ability", ignoreCase = true) == true,
            "Exception message should contain 'cannot unify', 'type mismatch', 'incompatible types', or 'does not implement ability', but was: '${exception.message}'"
        )
    }

    // ========== Nested Match with Literal Patterns ==========

    @Test
    fun `should accept nested match with literal patterns`() {
        val code = """
            let classify_pair = {x, y} ->
                match x is
                  0 -> match y is
                         0 -> "both zero"
                         _ -> "x is zero"
                  _ -> "x is not zero"
            let result = classify_pair { x = 0, y = 0 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Return Type Inference with Literal Patterns ==========

    @Test
    fun `should infer return type from literal pattern branches`() {
        val code = """
            let get_value = {n} ->
                match n is
                  0 -> 100
                  1 -> 200
                  _ -> 0
            let result = get_value { n = 1 } + 10
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `should infer String return type from string literal pattern branches`() {
        val code = """
            let describe = {cmd} ->
                match cmd is
                  "start" -> "starting"
                  "stop" -> "stopping"
                  _ -> "unknown"
            let result = describe { cmd = "start" }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Complex Expressions in Match Body ==========

    @Test
    fun `should accept complex expressions in literal pattern branches`() {
        val code = """
            let calculate = {n} ->
                match n is
                  0 -> 0
                  1 -> 1
                  x -> x * x + x + 1
            let result = calculate { n = 5 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Literal Patterns in Do Expressions ==========

    @Test
    fun `should accept literal patterns in match inside do`() {
        val code = """
            let result = do
                let x = 5
                let y = match x is
                          0 -> "zero"
                          _ -> "non-zero"
                y
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== HTTP Status Code Style Matching ==========

    @Test
    fun `should accept HTTP status code style integer matching`() {
        val code = """
            let describe_status = {code} ->
                match code is
                  200 -> "OK"
                  201 -> "Created"
                  400 -> "Bad Request"
                  404 -> "Not Found"
                  500 -> "Internal Server Error"
                  _ -> "Unknown Status"
            let result = describe_status { code = 404 }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== Command Parsing Style String Matching ==========

    @Test
    fun `should accept command parsing style string matching`() {
        val code = """
            let parse_command = {input} ->
                match input is
                  "help" -> "Showing help..."
                  "version" -> "v1.0.0"
                  "exit" -> "Goodbye!"
                  _ -> "Unknown command"
            let result = parse_command { input = "help" }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
