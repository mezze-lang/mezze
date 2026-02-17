package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EffectParserTest {
    private val parser = MezzeParser()

    @Test
    fun testPerformExpressionWithSingleHandler() {
        val source = """
            perform Console in Log.print {msg = "hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val topLevel = program.items[0] as TopLevelExpression
        val performExpr = topLevel.expression as PerformExpression

        assertEquals(1, performExpr.handlers.size)
        assertEquals("Console", performExpr.handlers[0].name)
        assertNull(performExpr.handlers[0].parameters)

        // Check the expression being performed
        assertTrue(performExpr.expression is FunctionCall)
        val funcCall = performExpr.expression as FunctionCall
        assertTrue(funcCall.function is MemberAccess)

        val memberAccess = funcCall.function as MemberAccess
        // Handle both Identifier and VariantConstruction cases
        val receiverName = when (memberAccess.receiver) {
            is Identifier -> (memberAccess.receiver as Identifier).name
            is VariantConstruction -> (memberAccess.receiver as VariantConstruction).constructor
            else -> fail("Expected receiver to be Identifier or VariantConstruction, got ${memberAccess.receiver::class.simpleName}")
        }
        assertEquals("Log", receiverName)
        assertEquals("print", memberAccess.member)
    }

    @Test
    fun testPerformExpressionWithMultipleHandlers() {
        val source = """
            perform Console, MathRandom, FileSystem in someFunction {}
        """.trimIndent()

        val program = parser.parse(source)
        val topLevel = program.items[0] as TopLevelExpression
        val performExpr = topLevel.expression as PerformExpression

        assertEquals(3, performExpr.handlers.size)
        assertEquals("Console", performExpr.handlers[0].name)
        assertEquals("MathRandom", performExpr.handlers[1].name)
        assertEquals("FileSystem", performExpr.handlers[2].name)
    }

    @Test
    fun testPerformExpressionWithParameterizedHandler() {
        val source = """
            perform FileLogger {path = "/tmp/log.txt"} in Log.print {msg = "hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val topLevel = program.items[0] as TopLevelExpression
        val performExpr = topLevel.expression as PerformExpression

        assertEquals(1, performExpr.handlers.size)
        assertEquals("FileLogger", performExpr.handlers[0].name)
        assertNotNull(performExpr.handlers[0].parameters)

        val params = performExpr.handlers[0].parameters!!
        assertEquals(1, params.entries.size)
        val firstEntry = params.entries[0] as RecordFieldEntry
        assertEquals("path", firstEntry.name)
        assertTrue(firstEntry.value is StringLiteral)
        assertEquals("/tmp/log.txt", (firstEntry.value as StringLiteral).value)
    }

    @Test
    fun testPerformExpressionWithMixedParameterizedHandlers() {
        val source = """
            perform Console, FileLogger {path = "/var/log/app.log"}, SystemRandom in someFunction {x = 1, y = 2}
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val topLevelExpr = program.items[0] as TopLevelExpression
        val performExpr = topLevelExpr.expression as PerformExpression

        // Should have 3 handlers
        assertEquals(3, performExpr.handlers.size)

        // First handler: Console (no parameters)
        assertEquals("Console", performExpr.handlers[0].name)
        assertNull(performExpr.handlers[0].parameters)

        // Second handler: FileLogger with parameters
        assertEquals("FileLogger", performExpr.handlers[1].name)
        assertNotNull(performExpr.handlers[1].parameters)
        val fileLoggerParams = performExpr.handlers[1].parameters!!
        assertEquals(1, fileLoggerParams.entries.size)
        val fileLoggerEntry = fileLoggerParams.entries[0] as RecordFieldEntry
        assertEquals("path", fileLoggerEntry.name)
        assertEquals("/var/log/app.log", (fileLoggerEntry.value as StringLiteral).value)

        // Third handler: SystemRandom (no parameters)
        assertEquals("SystemRandom", performExpr.handlers[2].name)
        assertNull(performExpr.handlers[2].parameters)

        // Expression should be a function call
        assertTrue(performExpr.expression is FunctionCall)
        val functionCall = performExpr.expression as FunctionCall
        assertEquals("someFunction", (functionCall.function as Identifier).name)
    }

    @Test
    fun testEffectDeclarationWithoutHaving() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val effectDecl = program.items[0] as EffectDeclaration
        assertEquals("Log", effectDecl.name)
        assertNull(effectDecl.associatedType)
        assertEquals(1, effectDecl.operations.size)
        assertEquals("info", effectDecl.operations[0].name)
    }

    @Test
    fun testEffectDeclarationWithHaving() {
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>,
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val effectDecl = program.items[0] as EffectDeclaration
        assertEquals("State", effectDecl.name)
        assertEquals("'state", effectDecl.associatedType)
        assertEquals(2, effectDecl.operations.size)

        // Check get operation
        assertEquals("get", effectDecl.operations[0].name)

        // Check put operation
        assertEquals("put", effectDecl.operations[1].name)
    }

    @Test
    fun testEffectDeclarationWithHavingDifferentTypeVarName() {
        val source = """
            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val effectDecl = program.items[0] as EffectDeclaration
        assertEquals("Reader", effectDecl.name)
        assertEquals("'env", effectDecl.associatedType)
        assertEquals(1, effectDecl.operations.size)
        assertEquals("ask", effectDecl.operations[0].name)
    }

    @Test
    fun testEffectDeclarationHavingRequiresTypeVariable() {
        val source = """
            effect State having state where {
                get: {} -> state <State>
            }
        """.trimIndent()

        try {
            parser.parse(source)
            fail("Should have thrown ParseException for non-type-variable in having")
        } catch (e: ParseException) {
            assertTrue(e.message?.contains("Type variable must start with '") == true)
        }
    }

    @Test
    fun testEffectImplementationWithoutHaving() {
        val source = """
            effect Log where {
                info: {msg: String} -> {} <Log>
            }

            impl Log for ConsoleLogger {} where {
                info = {msg} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val implDecl = program.items[1] as ImplDeclaration
        assertEquals("Log", implDecl.effectName)
        assertEquals("ConsoleLogger", implDecl.performerName)
        assertNull(implDecl.associatedTypeBinding)
        assertEquals(1, implDecl.operations.size)
    }

    @Test
    fun testEffectImplementationWithHaving() {
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>,
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {},
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val implDecl = program.items[1] as ImplDeclaration
        assertEquals("State", implDecl.effectName)
        assertEquals("Counter", implDecl.performerName)
        assertNotNull(implDecl.associatedTypeBinding)
        assertEquals("'state", implDecl.associatedTypeBinding!!.first)

        val typeAnnotation = implDecl.associatedTypeBinding!!.second
        assertTrue(typeAnnotation is IdentifierTypeAnnotation)
        assertEquals("Int", (typeAnnotation as IdentifierTypeAnnotation).name)

        assertEquals(2, implDecl.operations.size)
        assertEquals("get", implDecl.operations[0].name)
        assertEquals("put", implDecl.operations[1].name)
    }

    @Test
    fun testEffectImplementationWithHavingComplexType() {
        val source = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ListState having 'state = (List String) where {
                get = {} -> []
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val implDecl = program.items[1] as ImplDeclaration
        assertEquals("State", implDecl.effectName)
        assertEquals("ListState", implDecl.performerName)
        assertNotNull(implDecl.associatedTypeBinding)
        assertEquals("'state", implDecl.associatedTypeBinding!!.first)

        val typeAnnotation = implDecl.associatedTypeBinding!!.second
        assertTrue(typeAnnotation is TypeApplicationAnnotation)
        val typeApp = typeAnnotation as TypeApplicationAnnotation
        assertEquals("List", typeApp.constructor)
        assertEquals(1, typeApp.args.size)
    }

    @Test
    fun testEffectImplementationHavingRequiresTypeVariable() {
        val source = """
            impl State for Counter having state = Int where {
                get = {} -> 42
            }
        """.trimIndent()

        try {
            parser.parse(source)
            fail("Should have thrown ParseException for non-type-variable in impl having")
        } catch (e: ParseException) {
            assertTrue(e.message?.contains("Type variable must start with '") == true)
        }
    }

    // ========================================================================
    // PERFORMED MARKER <*> TESTS
    // ========================================================================

    @Test
    fun testPerformedMarkerOnly() {
        val source = """
            let f: {} -> Int <*> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val pattern = letBinding.pattern as VariablePattern
        assertEquals("f", pattern.name)

        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertTrue(effectRow.effects.isEmpty())
        assertFalse(effectRow.isPolymorphic)
        assertTrue(effectRow.isPerformed)
    }

    @Test
    fun testPerformedMarkerWithEffects() {
        val source = """
            let f: {} -> Int <Log, *> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertEquals(listOf("Log"), effectRow.effects)
        assertFalse(effectRow.isPolymorphic)
        assertTrue(effectRow.isPerformed)
    }

    @Test
    fun testPerformedMarkerWithMultipleEffects() {
        val source = """
            let f: {} -> Int <Log, State, *> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertEquals(listOf("Log", "State"), effectRow.effects)
        assertFalse(effectRow.isPolymorphic)
        assertTrue(effectRow.isPerformed)
    }

    @Test
    fun testEmptyEffectRowNotPerformed() {
        val source = """
            let f: {} -> Int <> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        // Empty effect row <> should result in null effects (pure function)
        // since effects.isEmpty() && !isPolymorphic && !isPerformed
        assertNull(typeAnnotation.effects)
    }

    @Test
    fun testEffectsWithoutPerformedMarker() {
        val source = """
            let f: {} -> Int <Log> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertEquals(listOf("Log"), effectRow.effects)
        assertFalse(effectRow.isPolymorphic)
        assertFalse(effectRow.isPerformed)
    }

    @Test
    fun testPolymorphicEffectRowNotPerformed() {
        val source = """
            let f: {} -> Int <...> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertTrue(effectRow.effects.isEmpty())
        assertTrue(effectRow.isPolymorphic)
        assertFalse(effectRow.isPerformed)
    }

    @Test
    fun testEffectsWithPolymorphicNotPerformed() {
        val source = """
            let f: {} -> Int <Log, ...> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertEquals(listOf("Log"), effectRow.effects)
        assertTrue(effectRow.isPolymorphic)
        assertFalse(effectRow.isPerformed)
    }

    @Test
    fun testPerformedMarkerWithQualifiedEffect() {
        val source = """
            let f: {} -> Int <console::Log, *> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!
        assertEquals(listOf("console::Log"), effectRow.effects)
        assertFalse(effectRow.isPolymorphic)
        assertTrue(effectRow.isPerformed)
    }
}
