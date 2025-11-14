package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Tests for option module functions.
 * Verifies that importing and using option utility functions works correctly.
 */
class OptionModuleTest {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        typeChecker = TypeChecker()
        // Set project root for module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    private fun parseAndTypeCheck(source: String): com.funk.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    @Test
    fun `test option is_some function`() {
        val source = """
            use std::option
            let some_val = Some { val = 42 }
            let result = option::is_some { opt = some_val }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option is_none function`() {
        val source = """
            use std::option
            let none_val = None
            let result = option::is_none { opt = none_val }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option get_or_else function`() {
        val source = """
            use std::option
            let some_val = Some { val = 42 }
            let result = option::get_or_else { opt = some_val, def = 0 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option map function`() {
        val source = """
            use std::option
            let some_val = Some { val = 21 }
            let doubled = option::map { opt = some_val, f = { val } -> val * 2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option filter function`() {
        val source = """
            use std::option
            let some_val = Some { val = 42 }
            let filtered = option::filter { opt = some_val, p = { val } -> val > 10 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option flat_map function`() {
        val source = """
            use std::option
            let some_val = Some { val = 42 }
            let result = option::flat_map { opt = some_val, f = { val } -> if val > 0 then Some { val = val } else None }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option or_else function`() {
        val source = """
            use std::option
            let none_val = None
            let some_val = Some { val = 42 }
            let result = option::or_else { opt1 = none_val, opt2 = some_val }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test option fold function`() {
        val source = """
            use std::option
            let some_val = Some { val = 42 }
            let result = option::fold {
                opt = some_val,
                on_none = {} -> 0,
                on_some = { val } -> val
            }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
