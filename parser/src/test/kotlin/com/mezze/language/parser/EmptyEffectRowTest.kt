package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmptyEffectRowTest {

    private val parser = MezzeParser()

    @Test
    fun testEffectOperationWithoutEffectRow() {
        val source = """
            effect Log where {
              log : {message: String} -> Unit
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val effectDecl = module.decls[0] as ParsedEffectDecl

        assertEquals("Log", effectDecl.name)
        assertEquals(1, effectDecl.operations.size)

        val operation = effectDecl.operations[0]
        assertEquals("log", operation.name)
        assertEquals("Unit", (operation.returnType as ParsedNamedType).name)
        assertTrue(operation.effects.isEmpty()) // No effects should be present
    }

    @Test
    fun testEffectOperationWithEmptyEffectRow() {
        val source = """
            effect Log where {
              log : {message: String} -> Unit <>
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val effectDecl = module.decls[0] as ParsedEffectDecl

        assertEquals("Log", effectDecl.name)
        assertEquals(1, effectDecl.operations.size)

        val operation = effectDecl.operations[0]
        assertEquals("log", operation.name)
        assertEquals("Unit", (operation.returnType as ParsedNamedType).name)
        assertTrue(operation.effects.isEmpty()) // Empty effect row should result in empty list
    }

    @Test
    fun testEffectOperationWithNonEmptyEffectRow() {
        val source = """
            effect Database where {
              query : {sql: String} -> String <Log>
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val effectDecl = module.decls[0] as ParsedEffectDecl

        assertEquals("Database", effectDecl.name)
        assertEquals(1, effectDecl.operations.size)

        val operation = effectDecl.operations[0]
        assertEquals("query", operation.name)
        assertEquals("String", (operation.returnType as ParsedNamedType).name)
        assertEquals(listOf("Log"), operation.effects) // Should have Log effect
    }

    @Test
    fun testMixedEffectOperations() {
        val source = """
            effect Mixed where {
              pureOp : {x: Int} -> Int,
              logOp : {msg: String} -> Unit <Log>,
              multiOp : {data: String} -> String <Log, Database>
            }
        """.trimIndent()

        val module = parser.parseParsedModule(source)
        val effectDecl = module.decls[0] as ParsedEffectDecl

        assertEquals("Mixed", effectDecl.name)
        assertEquals(3, effectDecl.operations.size)

        // Pure operation (no effects)
        val pureOp = effectDecl.operations[0]
        assertEquals("pureOp", pureOp.name)
        assertTrue(pureOp.effects.isEmpty())

        // Single effect operation
        val logOp = effectDecl.operations[1]
        assertEquals("logOp", logOp.name)
        assertEquals(listOf("Log"), logOp.effects)

        // Multiple effects operation
        val multiOp = effectDecl.operations[2]
        assertEquals("multiOp", multiOp.name)
        assertEquals(listOf("Log", "Database"), multiOp.effects)
    }
}
