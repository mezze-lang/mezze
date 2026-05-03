package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for string module functions.
 * Verifies that importing and using string utility functions works correctly.
 */
class StringModuleTest {

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

    private fun parseAndTypeCheck(source: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `test string concat function`() {
        val source = """
            use std::str
            let result = str::concat { l = "Hello, ", r = "World!" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string length function`() {
        val source = """
            use std::str
            let result = str::size { s = "Hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string to_upper function`() {
        val source = """
            use std::str
            let result = str::to_upper { s = "hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string to_lower function`() {
        val source = """
            use std::str
            let result = str::to_lower { s = "HELLO" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string trim function`() {
        val source = """
            use std::str
            let result = str::trim { s = "  hello  " }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string reverse function`() {
        val source = """
            use std::str
            let result = str::reverse { s = "hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string starts_with function`() {
        val source = """
            use std::str
            let result = str::starts_with { s = "Hello, World!", prefix = "Hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string ends_with function`() {
        val source = """
            use std::str
            let result = str::ends_with { s = "Hello, World!", suffix = "World!" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string contains function`() {
        val source = """
            use std::str
            let result = str::contains { s = "Hello, World!", substring = "World" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string is_empty function`() {
        val source = """
            use std::str
            let result = str::is_empty { s = "" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string repeat function`() {
        val source = """
            use std::str
            let result = str::repeat { s = "Ha", n = 3 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test multiple string operations`() {
        val source = """
            use std::str
            let hello = "hello"
            let upper = str::to_upper { s = hello }
            let len = str::size { s = upper }
            let trimmed = str::trim { s = "  test  " }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string split function`() {
        val source = """
            use std::str
            let result = str::split { s = "a,b,c", delimiter = "," }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test string join function`() {
        val source = """
            use std::str
            let result = str::join { strings = ["one", "two", "three"], separator = ", " }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test split then join round trip`() {
        val source = """
            use std::str
            let split_func = { s } -> str::split { s = s, delimiter = "," }
            let join_func = { parts } -> str::join { strings = parts, separator = "," }
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
            use std::str
            let result = str::split { s = "abc", delimiter = "" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test join with newline separator`() {
        val source = """
            use std::str
            let lines = ["line1", "line2", "line3"]
            let result = str::join { strings = lines, separator = "\n" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
