package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for string module functions.
 * Verifies that importing and using string utility functions works correctly.
 */
class StringModuleTest {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        // Set project root for module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun parseAndTypeCheck(source: String): com.funk.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test string concat function`() {
        val source = """
            use std::string
            let result = string::concat { l = "Hello, ", r = "World!" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string length function`() {
        val source = """
            use std::string
            let result = string::length { s = "Hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string to_upper function`() {
        val source = """
            use std::string
            let result = string::to_upper { s = "hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string to_lower function`() {
        val source = """
            use std::string
            let result = string::to_lower { s = "HELLO" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string trim function`() {
        val source = """
            use std::string
            let result = string::trim { s = "  hello  " }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string reverse function`() {
        val source = """
            use std::string
            let result = string::reverse { s = "hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string starts_with function`() {
        val source = """
            use std::string
            let result = string::starts_with { s = "Hello, World!", prefix = "Hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string ends_with function`() {
        val source = """
            use std::string
            let result = string::ends_with { s = "Hello, World!", suffix = "World!" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string contains function`() {
        val source = """
            use std::string
            let result = string::contains { s = "Hello, World!", substring = "World" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string is_empty function`() {
        val source = """
            use std::string
            let result = string::is_empty { s = "" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string repeat function`() {
        val source = """
            use std::string
            let result = string::repeat { s = "Ha", n = 3 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple string operations`() {
        val source = """
            use std::string
            let hello = "hello"
            let upper = string::to_upper { s = hello }
            let len = string::length { s = upper }
            let trimmed = string::trim { s = "  test  " }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string split function`() {
        val source = """
            use std::string
            let result = string::split { s = "a,b,c", delimiter = "," }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string join function`() {
        val source = """
            use std::string
            let result = string::join { strings = ["one", "two", "three"], separator = ", " }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test split then join round trip`() {
        val source = """
            use std::string
            let split_func = { s } -> string::split { s = s, delimiter = "," }
            let join_func = { parts } -> string::join { strings = parts, separator = "," }
            let round_trip = { s } -> do
                let parts = split_func { s = s }
                join_func { parts = parts }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test split with empty delimiter`() {
        val source = """
            use std::string
            let result = string::split { s = "abc", delimiter = "" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test join with newline separator`() {
        val source = """
            use std::string
            let lines = ["line1", "line2", "line3"]
            let result = string::join { strings = lines, separator = "\n" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
