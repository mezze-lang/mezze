package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Tests for module imports with record types and parameter matching.
 * This tests the specific issue where importing modules with functions that
 * call native functions causes "closed record" type errors.
 */
class ModuleImportRecordTypeTest {

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
    fun `test simple function with record parameters`() {
        val source = """
            let add = { l: Int, r: Int } -> l + r
            let result = add { l = 5, r = 3 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test function calling stdlib function with same record parameters`() {
        // Post-#109: user code cannot call __native_* directly; delegate to stdlib.
        val source = """
            use std::int
            let myAdd = { l: Int, r: Int } -> int::add { l = l, r = r }
            let result = myAdd { l = 5, r = 3 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test importing int module add function`() {
        val source = """
            use std::int
            let result = int::add { l = 5, r = 3 }
        """.trimIndent()

        // This should NOT throw an error about closed records
        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test importing int module subtract function`() {
        val source = """
            use std::int
            let result = int::subtract { l = 10, r = 4 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test importing int module to_str function`() {
        val source = """
            use std::int
            let result = int::to_str { n = 42 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test importing int module and calling multiple functions`() {
        val source = """
            use std::int
            let sum = int::add { l = 5, r = 3 }
            let diff = int::subtract { l = 10, r = 4 }
            let product = int::multiply { l = 6, r = 7 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test nested function calls with record parameters`() {
        val source = """
            use std::int
            let result = int::add { l = int::multiply { l = 2, r = 3 }, r = 4 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test function with explicit closed record type annotation`() {
        val source = """
            let myFunc: { x: Int } -> Int = { x } -> x + 1
            let result = myFunc { x = 5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test error on calling function with extra record fields`() {
        val source = """
            let myFunc = { x: Int } -> x + 1
            let result = myFunc { x = 5, y = 10 }
        """.trimIndent()

        // This SHOULD throw an error
        assertThrows<TypeCheckError> {
            parseAndTypeCheck(source)
        }
    }
}
