package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeList
import com.mezze.language.interpreter.TestUtils
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

class StringModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return MezzeTestHelper.getLastExecutionContext()
    }

    @Test
    fun `test string concat`() {
        val source = """
            use std::string
            let result = string::concat { l = "hello", r = " world" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello world", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string length`() {
        val source = """
            use std::string
            let result = string::length { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test string length - empty`() {
        val source = """
            use std::string
            let result = string::length { s = "" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test string is_empty - true`() {
        val source = """
            use std::string
            let result = string::is_empty { s = "" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string is_empty - false`() {
        val source = """
            use std::string
            let result = string::is_empty { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string to_upper`() {
        val source = """
            use std::string
            let result = string::to_upper { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("HELLO", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string to_lower`() {
        val source = """
            use std::string
            let result = string::to_lower { s = "HELLO" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string trim`() {
        val source = """
            use std::string
            let result = string::trim { s = "  hello  " }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string reverse`() {
        val source = """
            use std::string
            let result = string::reverse { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("olleh", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string starts_with - true`() {
        val source = """
            use std::string
            let result = string::starts_with { s = "hello world", prefix = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string starts_with - false`() {
        val source = """
            use std::string
            let result = string::starts_with { s = "hello world", prefix = "world" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string ends_with - true`() {
        val source = """
            use std::string
            let result = string::ends_with { s = "hello world", suffix = "world" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string ends_with - false`() {
        val source = """
            use std::string
            let result = string::ends_with { s = "hello world", suffix = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string contains - true`() {
        val source = """
            use std::string
            let result = string::contains { s = "hello world", substring = "lo wo" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(true, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string contains - false`() {
        val source = """
            use std::string
            let result = string::contains { s = "hello world", substring = "xyz" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(false, (variables["result"] as MezzeBool).value)
    }

    @Test
    fun `test string repeat`() {
        val source = """
            use std::string
            let result = string::repeat { s = "ab", n = 3 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("ababab", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string repeat - zero times`() {
        val source = """
            use std::string
            let result = string::repeat { s = "ab", n = 0 }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("", (variables["result"] as MezzeString).value)
    }

    // === Additional tests for missing functions ===

    @Test
    fun `test string to_string - identity`() {
        val source = """
            use std::string
            let result = string::to_string { s = "hello" }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string split`() {
        val source = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "a,b,c", delimiter = "," }
            let len = array::length { xs = parts }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3L, (variables["len"] as MezzeInt).value)
    }

    @Test
    fun `test string split - no delimiter found`() {
        val source = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "hello", delimiter = "," }
            let len = array::length { xs = parts }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1L, (variables["len"] as MezzeInt).value)
    }

    @Test
    fun `test string split - empty string`() {
        val source = """
            use std::string
            use std::collections::array
            let parts = string::split { s = "", delimiter = "," }
            let len = array::length { xs = parts }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(1L, (variables["len"] as MezzeInt).value)
    }

    @Test
    fun `test string join`() {
        val source = """
            use std::string
            let parts = ["a", "b", "c"]
            let result = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("a,b,c", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string join - empty array`() {
        val source = """
            use std::string
            let parts: [String] = []
            let result = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string join - single element`() {
        val source = """
            use std::string
            let parts = ["hello"]
            let result = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("hello", (variables["result"] as MezzeString).value)
    }

    @Test
    fun `test string split and join roundtrip`() {
        val source = """
            use std::string
            let original = "one,two,three"
            let parts = string::split { s = original, delimiter = "," }
            let rejoined = string::join { strings = parts, separator = "," }
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals("one,two,three", (variables["rejoined"] as MezzeString).value)
    }
}
