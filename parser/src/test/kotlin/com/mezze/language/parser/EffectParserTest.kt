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

    // ========================================================================
    // EFFECT ASSOCIATED TYPE ANNOTATION TESTS
    // ========================================================================

    @Test
    fun testEffectWithSingleAssociatedType() {
        val source = """
            let f: {} -> Int <Exception having 'error = String> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        assertNotNull(letBinding.typeAnnotation)
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        assertNotNull(typeAnnotation.effects)
        val effectRow = typeAnnotation.effects!!

        // Should have no simple effects, one effect annotation
        assertTrue(effectRow.effects.isEmpty())
        assertEquals(1, effectRow.effectAnnotations.size)

        val exceptionEffect = effectRow.effectAnnotations[0]
        assertEquals("Exception", exceptionEffect.name)
        assertEquals(1, exceptionEffect.associatedTypes.size)
        assertTrue(exceptionEffect.associatedTypes.containsKey("'error"))
        assertEquals("String", (exceptionEffect.associatedTypes["'error"] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testEffectWithMultipleAssociatedTypes() {
        val source = """
            let f: {} -> Int <Generator having 'item = Int, 'result = String> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val genEffect = effectRow.effectAnnotations[0]
        assertEquals("Generator", genEffect.name)
        assertEquals(2, genEffect.associatedTypes.size)
        assertTrue(genEffect.associatedTypes.containsKey("'item"))
        assertTrue(genEffect.associatedTypes.containsKey("'result"))
        assertEquals("Int", (genEffect.associatedTypes["'item"] as IdentifierTypeAnnotation).name)
        assertEquals("String", (genEffect.associatedTypes["'result"] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testMixedSimpleAndAnnotatedEffects() {
        val source = """
            let f: {} -> Int <Log, Exception having 'error = String> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // Log is a simple effect
        assertEquals(listOf("Log"), effectRow.effects)

        // Exception is an annotated effect
        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("Exception", effectRow.effectAnnotations[0].name)
        assertEquals(1, effectRow.effectAnnotations[0].associatedTypes.size)
    }

    @Test
    fun testMultipleAnnotatedEffects() {
        val source = """
            let f: {} -> Int <Exception having 'error = String, State having 'state = Int> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertTrue(effectRow.effects.isEmpty())
        assertEquals(2, effectRow.effectAnnotations.size)

        val exceptionEffect = effectRow.effectAnnotations[0]
        assertEquals("Exception", exceptionEffect.name)
        assertEquals(1, exceptionEffect.associatedTypes.size)
        assertEquals("String", (exceptionEffect.associatedTypes["'error"] as IdentifierTypeAnnotation).name)

        val stateEffect = effectRow.effectAnnotations[1]
        assertEquals("State", stateEffect.name)
        assertEquals(1, stateEffect.associatedTypes.size)
        assertEquals("Int", (stateEffect.associatedTypes["'state"] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testAnnotatedEffectWithPolymorphicRow() {
        val source = """
            let f: {} -> Int <Exception having 'error = String, ...> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        assertTrue(effectRow.isPolymorphic)
        assertFalse(effectRow.isPerformed)
    }

    @Test
    fun testAnnotatedEffectWithPerformedMarker() {
        val source = """
            let f: {} -> Int <Exception having 'error = String, *> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        assertFalse(effectRow.isPolymorphic)
        assertTrue(effectRow.isPerformed)
    }

    @Test
    fun testComplexMixedEffectRow() {
        val source = """
            let f: {} -> Int <Log, Exception having 'error = String, State having 'state = Int, FileSystem, ...> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // Simple effects
        assertEquals(listOf("Log", "FileSystem"), effectRow.effects)

        // Annotated effects
        assertEquals(2, effectRow.effectAnnotations.size)
        assertEquals("Exception", effectRow.effectAnnotations[0].name)
        assertEquals("State", effectRow.effectAnnotations[1].name)

        assertTrue(effectRow.isPolymorphic)
        assertFalse(effectRow.isPerformed)
    }

    @Test
    fun testEffectWithComplexAssociatedType() {
        val source = """
            let f: {} -> Int <State having 'state = (List String)> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val stateEffect = effectRow.effectAnnotations[0]
        val stateType = stateEffect.associatedTypes["'state"]
        assertTrue(stateType is TypeApplicationAnnotation)
        assertEquals("List", (stateType as TypeApplicationAnnotation).constructor)
    }

    @Test
    fun testEffectWithRecordAssociatedType() {
        val source = """
            let f: {} -> Int <State having 'state = {x: Int, y: Int}> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val stateEffect = effectRow.effectAnnotations[0]
        val stateType = stateEffect.associatedTypes["'state"]
        assertTrue(stateType is RecordTypeAnnotation)
        assertEquals(2, (stateType as RecordTypeAnnotation).entries.size)
    }

    @Test
    fun testQualifiedEffectWithAssociatedType() {
        val source = """
            let f: {} -> Int <std::exception::Exception having 'error = String> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("std::exception::Exception", effectRow.effectAnnotations[0].name)
        assertEquals(1, effectRow.effectAnnotations[0].associatedTypes.size)
    }

    // ========================================================================
    // LOOKAHEAD DISAMBIGUATION TESTS
    // ========================================================================

    @Test
    fun testDisambiguateAssociatedTypeFromNextEffect() {
        // This tests that the parser correctly distinguishes between:
        // - "State having 'state = Int, OtherEffect" (two effects)
        // - "State having 'state = Int, 'other = String" (one effect with two associated types)
        val source = """
            let f: {} -> Int <State having 'state = Int, OtherEffect> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // Should have one annotated effect (State) and one simple effect (OtherEffect)
        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("State", effectRow.effectAnnotations[0].name)
        assertEquals(1, effectRow.effectAnnotations[0].associatedTypes.size)
        assertEquals(listOf("OtherEffect"), effectRow.effects)
    }

    @Test
    fun testMultipleAssociatedTypesInSingleEffect() {
        // This tests that multiple associated types are correctly parsed as part of one effect
        val source = """
            let f: {} -> Int <State having 'state = Int, 'result = String> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // Should have one annotated effect with two associated types
        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("State", effectRow.effectAnnotations[0].name)
        assertEquals(2, effectRow.effectAnnotations[0].associatedTypes.size)
        assertTrue(effectRow.effectAnnotations[0].associatedTypes.containsKey("'state"))
        assertTrue(effectRow.effectAnnotations[0].associatedTypes.containsKey("'result"))
        assertTrue(effectRow.effects.isEmpty())
    }

    @Test
    fun testDisambiguateAssociatedTypeWithPolymorphicMarker() {
        // After associated types, "..." should be polymorphic marker, not an effect
        val source = """
            let f: {} -> Int <State having 'state = Int, ...> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("State", effectRow.effectAnnotations[0].name)
        assertEquals(1, effectRow.effectAnnotations[0].associatedTypes.size)
        assertTrue(effectRow.isPolymorphic)
        assertFalse(effectRow.isPerformed)
    }

    @Test
    fun testDisambiguateAssociatedTypeWithPerformedMarker() {
        // After associated types, "*" should be performed marker, not an effect
        val source = """
            let f: {} -> Int <State having 'state = Int, *> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("State", effectRow.effectAnnotations[0].name)
        assertEquals(1, effectRow.effectAnnotations[0].associatedTypes.size)
        assertFalse(effectRow.isPolymorphic)
        assertTrue(effectRow.isPerformed)
    }

    @Test
    fun testDisambiguateMultipleEffectsWithMixedAnnotations() {
        // Complex test: Effect1 with 'a = X, Effect2 (simple), Effect3 with 'b = Y
        val source = """
            let f: {} -> Int <Exception having 'error = String, Log, State having 'state = Int> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // Should have two annotated effects and one simple effect
        assertEquals(2, effectRow.effectAnnotations.size)
        assertEquals("Exception", effectRow.effectAnnotations[0].name)
        assertEquals("State", effectRow.effectAnnotations[1].name)
        assertEquals(listOf("Log"), effectRow.effects)
    }

    // ========================================================================
    // COMPLEX TYPE ARGUMENT TESTS
    // ========================================================================

    @Test
    fun testNestedOptionArrayType() {
        val source = """
            let f: {} -> Int <State having 'state = (Option [Int])> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val stateType = effectRow.effectAnnotations[0].associatedTypes["'state"]
        assertTrue(stateType is TypeApplicationAnnotation)
        val optionType = stateType as TypeApplicationAnnotation
        assertEquals("Option", optionType.constructor)
        assertEquals(1, optionType.args.size)
        assertTrue(optionType.args[0] is SeqTypeAnnotation)
    }

    @Test
    fun testDeeplyNestedGenericType() {
        val source = """
            let f: {} -> Int <State having 'state = (Result (Option String) Int)> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val stateType = effectRow.effectAnnotations[0].associatedTypes["'state"]
        assertTrue(stateType is TypeApplicationAnnotation)
        val resultType = stateType as TypeApplicationAnnotation
        assertEquals("Result", resultType.constructor)
        assertEquals(2, resultType.args.size)
        assertTrue(resultType.args[0] is TypeApplicationAnnotation)
        assertEquals("Option", (resultType.args[0] as TypeApplicationAnnotation).constructor)
    }

    @Test
    fun testFunctionTypeAsAssociatedType() {
        val source = """
            let f: {} -> Int <State having 'state = ({x: Int} -> String)> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val stateType = effectRow.effectAnnotations[0].associatedTypes["'state"]
        assertTrue(stateType is FunctionTypeAnnotation)
        val funcType = stateType as FunctionTypeAnnotation
        assertTrue(funcType.paramType is RecordTypeAnnotation)
        assertTrue(funcType.returnType is IdentifierTypeAnnotation)
    }

    @Test
    fun testArrayOfRecordsAsAssociatedType() {
        val source = """
            let f: {} -> Int <State having 'state = [{id: Int, name: String}]> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val stateType = effectRow.effectAnnotations[0].associatedTypes["'state"]
        assertTrue(stateType is SeqTypeAnnotation)
        val arrayType = stateType as SeqTypeAnnotation
        assertTrue(arrayType.elementType is RecordTypeAnnotation)
        assertEquals(2, (arrayType.elementType as RecordTypeAnnotation).entries.size)
    }

    @Test
    fun testMultipleComplexAssociatedTypes() {
        val source = """
            let f: {} -> Int <Generator having 'item = (Option String), 'result = (Result Int String)> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(1, effectRow.effectAnnotations.size)
        val genEffect = effectRow.effectAnnotations[0]
        assertEquals("Generator", genEffect.name)
        assertEquals(2, genEffect.associatedTypes.size)

        val itemType = genEffect.associatedTypes["'item"]
        assertTrue(itemType is TypeApplicationAnnotation)
        assertEquals("Option", (itemType as TypeApplicationAnnotation).constructor)

        val resultType = genEffect.associatedTypes["'result"]
        assertTrue(resultType is TypeApplicationAnnotation)
        assertEquals("Result", (resultType as TypeApplicationAnnotation).constructor)
    }

    // ========================================================================
    // PARTIAL ASSOCIATED TYPE ANNOTATION TESTS
    // ========================================================================

    @Test
    fun testEffectWithoutHavingClause() {
        // Effect with associated types declared but used without `having` annotation
        val source = """
            let f: {} -> Int <State> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // Should be parsed as a simple effect (not annotated)
        assertEquals(listOf("State"), effectRow.effects)
        assertTrue(effectRow.effectAnnotations.isEmpty())
    }

    @Test
    fun testMixedAnnotatedAndUnannotatedEffects() {
        // One effect with having, one without (partial annotation)
        val source = """
            let f: {} -> Int <State, Exception having 'error = String> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        // State is simple, Exception is annotated
        assertEquals(listOf("State"), effectRow.effects)
        assertEquals(1, effectRow.effectAnnotations.size)
        assertEquals("Exception", effectRow.effectAnnotations[0].name)
    }

    @Test
    fun testPartialAnnotationInMiddle() {
        // Effects: annotated, simple, annotated
        val source = """
            let f: {} -> Int <Exception having 'error = String, Log, State having 'state = Int> = {} -> 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val letBinding = program.items[0] as LetBinding
        val typeAnnotation = letBinding.typeAnnotation as FunctionTypeAnnotation
        val effectRow = typeAnnotation.effects!!

        assertEquals(listOf("Log"), effectRow.effects)
        assertEquals(2, effectRow.effectAnnotations.size)
    }

    // ========================================================================
    // QUALIFIED EFFECT NAME IN IMPL DECLARATION TESTS
    // ========================================================================

    @Test
    fun testImplWithQualifiedEffectName() {
        val source = """
            impl log::Log for ConsoleLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val implDecl = program.items[0] as ImplDeclaration
        assertEquals("log::Log", implDecl.effectName)
        assertEquals("ConsoleLogger", implDecl.performerName)
        assertEquals(1, implDecl.operations.size)
        assertEquals("log", implDecl.operations[0].name)
    }

    @Test
    fun testImplWithDeeplyQualifiedEffectName() {
        val source = """
            impl std::effects::log::Log for FileLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val implDecl = program.items[0] as ImplDeclaration
        assertEquals("std::effects::log::Log", implDecl.effectName)
        assertEquals("FileLogger", implDecl.performerName)
    }

    @Test
    fun testImplWithQualifiedEffectNameAndParams() {
        val source = """
            impl effects::State for Counter {initial: Int} where {
                get = {} -> 0,
                put = {value} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val implDecl = program.items[0] as ImplDeclaration
        assertEquals("effects::State", implDecl.effectName)
        assertEquals("Counter", implDecl.performerName)
        assertEquals(1, implDecl.performerParams.size)
        assertEquals("initial", implDecl.performerParams[0].name)
        assertEquals(2, implDecl.operations.size)
    }

    @Test
    fun testImplWithQualifiedEffectNameAndHaving() {
        val source = """
            impl effects::State for ListState having 'state = [Int] where {
                get = {} -> [],
                put = {value} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val implDecl = program.items[0] as ImplDeclaration
        assertEquals("effects::State", implDecl.effectName)
        assertEquals("ListState", implDecl.performerName)
        assertNotNull(implDecl.associatedTypeBinding)
        assertEquals("'state", implDecl.associatedTypeBinding!!.first)
    }

    @Test
    fun testImplWithUnqualifiedEffectNameStillWorks() {
        val source = """
            impl Log for SimpleLogger {} where {
                log = {level, msg} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val implDecl = program.items[0] as ImplDeclaration
        assertEquals("Log", implDecl.effectName)
        assertEquals("SimpleLogger", implDecl.performerName)
    }
}
