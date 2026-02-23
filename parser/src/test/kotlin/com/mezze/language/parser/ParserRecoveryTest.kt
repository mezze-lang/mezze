package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParserRecoveryTest {

    @Test
    fun `parseWithRecovery collects multiple errors`() {
        val source = """
            let good1 = 42
            
            let bad1 = {
            
            let good2 = "hello"
            
            let bad2 = [1, 2,
            
            let good3 = 100
        """.trimIndent()

        val parser = MezzeParser()
        val result = parser.parseWithRecovery(source)

        // Should have collected multiple errors
        assertTrue(result.errors.isNotEmpty(), "Should have parse errors")

        // Should have parsed the good declarations
        assertTrue(result.program.items.isNotEmpty(), "Should have parsed some items")

        // Specifically, good1 and good2 and good3 should be parsed
        val letBindings = result.program.items.filterIsInstance<com.mezze.language.ast.LetBinding>()
        val names = letBindings.map { it.variableName }
        assertTrue("good1" in names, "Should have parsed good1")
    }

    @Test
    fun `parseWithRecovery returns empty errors for valid code`() {
        val source = """
            let x = 1
            let y = 2
            let z = x + y
        """.trimIndent()

        val parser = MezzeParser()
        val result = parser.parseWithRecovery(source)

        assertTrue(result.isSuccess, "Should parse without errors")
        assertTrue(result.errors.isEmpty(), "Should have no errors")
        assertEquals(3, result.program.items.size, "Should have 3 items")
    }

    @Test
    fun `parseWithRecovery handles type declaration errors`() {
        val source = """
            type Good1 = { x: Int }
            
            type Bad = {
            
            type Good2 = | A, B |
        """.trimIndent()

        val parser = MezzeParser()
        val result = parser.parseWithRecovery(source)

        assertTrue(result.hasErrors, "Should have errors")

        // Should have parsed the good type declarations
        val typeDecls = result.program.items.filterIsInstance<com.mezze.language.ast.TypeDeclaration>()
        val names = typeDecls.map { it.name }
        assertTrue("Good1" in names || "Good2" in names, "Should have parsed at least one good type")
    }
}
