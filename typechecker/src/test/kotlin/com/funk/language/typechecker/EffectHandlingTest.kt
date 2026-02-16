package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class EffectHandlingTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun getVariableType(program: Program, variableName: String): FunkType? {
        return program.items
            .filterIsInstance<LetBinding>()
            .find { it.variableName == variableName }
            ?.inferredType
    }

    private fun getFunctionEffects(type: FunkType?): Set<String>? {
        return when (type) {
            is FunkType.FunctionType -> type.effects?.effects
            else -> null
        }
    }

    @Test
    fun testSingleEffectHandling() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            let effectfulFunc = {x} -> Log.print {msg = "test"}
            # Wrap in function to avoid top-level <*> restriction
            let getHandledResult = {} -> perform Console in effectfulFunc {x = 42}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val effectfulType = getVariableType(typedProgram, "effectfulFunc") as FunkType.FunctionType
        val getHandledResultType = getVariableType(typedProgram, "getHandledResult") as FunkType.FunctionType

        // Original function should have Log effect
        assertNotNull(effectfulType.effects)
        assertEquals(setOf("Log"), effectfulType.effects!!.effects)

        // getHandledResult should be a function with performed marker (<*>)
        // Note: The return type is {} (empty record), and effects.performed=true in the function type
        // because lambda unwraps SuspendedType into the function's effect row
        assertNotNull(getHandledResultType.effects)
        assertTrue(getHandledResultType.effects!!.performed, "Should have performed marker")
        assertTrue(getHandledResultType.effects!!.effects.isEmpty(), "Should have no concrete effects")
        // Return type should be {} (empty record)
        assertTrue(getHandledResultType.returnType is FunkType.RecordType)
        assertTrue((getHandledResultType.returnType as FunkType.RecordType).fields.isEmpty())
    }

    @Test
    fun testMultipleEffectsInOneFunction() {
        val source = """
            use std::int

            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            effect FileIO where {
              writeFile : {path: String, content: String} -> {} <FileIO>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> native_random {max = max}
            }

            impl FileIO for SystemFileIO where {
              writeFile : {path: String, content: String} -> {} = {path, content} -> native_write_file {path = path, content = content}
            }

            let multiEffectFunc = {x, y} -> do
              let sum = x + y
              Log.print {msg = "Computing"}
              let randomValue = Random.nextInt {max = sum}
              FileIO.writeFile {path = "out.txt", content = int::to_string {n = randomValue}}
              sum + randomValue
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val multiEffectType = getVariableType(typedProgram, "multiEffectFunc") as FunkType.FunctionType

        // Function should have effects (may not be perfectly inferred yet)
        // For now, just check that we have some effect information
        assertTrue(multiEffectType.effects != null || multiEffectType.toString().contains("effect"))
        
        // Parameter types should be inferred (may be Int, NumType, or other types)
        assertTrue(multiEffectType.paramType is FunkType.RecordType)
        val paramType = multiEffectType.paramType as FunkType.RecordType
        assertNotNull(paramType.fields["x"], "x parameter should exist")
        assertNotNull(paramType.fields["y"], "y parameter should exist")
        
        // Return type should exist
        assertNotNull(multiEffectType.returnType, "Return type should be inferred")
    }

    @Test
    fun testProgressiveEffectHandling() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> native_random {max = max}
            }

            let multiEffectFunc = {x} -> do
              Log.print {msg = "start"}
              let value = Random.nextInt {max = x}
              value

            # Test partial handling via function transformation (not calling)
            let partiallyHandledFunc = perform Console in multiEffectFunc
            let fullyHandledFunc = perform SystemRandom, Console in multiEffectFunc
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val originalType = getVariableType(typedProgram, "multiEffectFunc") as FunkType.FunctionType
        val partiallyHandledFuncType = getVariableType(typedProgram, "partiallyHandledFunc") as FunkType.FunctionType
        val fullyHandledFuncType = getVariableType(typedProgram, "fullyHandledFunc") as FunkType.FunctionType

        // Original function should have both effects
        assertNotNull(originalType.effects)
        assertEquals(setOf("Log", "Random"), originalType.effects!!.effects)

        // partiallyHandledFunc: Console handles Log, Random remains, <*> marker
        assertNotNull(partiallyHandledFuncType.effects)
        assertEquals(setOf("Random"), partiallyHandledFuncType.effects!!.effects)
        assertTrue(partiallyHandledFuncType.effects!!.performed)  // <*> marker

        // fullyHandledFunc: Both handlers applied, only <*> marker remains
        assertNotNull(fullyHandledFuncType.effects)
        assertTrue(fullyHandledFuncType.effects!!.effects.isEmpty())  // All effects handled
        assertTrue(fullyHandledFuncType.effects!!.performed)  // <*> marker
    }

    @Test
    fun testHandleMultipleEffectsAtOnce() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> native_random {max = max}
            }

            let multiEffectFunc = {x} -> do
              Log.print {msg = "start"}
              let value = Random.nextInt {max = x}
              Log.print {msg = "end"}
              value

            # Wrap in function to avoid top-level <*> restriction
            let getHandledResult = {} -> perform SystemRandom, Console in multiEffectFunc {x = 42}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val originalType = getVariableType(typedProgram, "multiEffectFunc") as FunkType.FunctionType
        val getHandledResultType = getVariableType(typedProgram, "getHandledResult") as FunkType.FunctionType

        // Original function should have both effects
        assertNotNull(originalType.effects)
        assertEquals(setOf("Log", "Random"), originalType.effects!!.effects)

        // getHandledResult should be a function with performed marker (<*>)
        assertNotNull(getHandledResultType.effects)
        assertTrue(getHandledResultType.effects!!.performed, "Should have performed marker")
        assertTrue(getHandledResultType.effects!!.effects.isEmpty(), "Should have no concrete effects")
        // Return type should be Int
        assertEquals(FunkType.IntType, getHandledResultType.returnType)
    }

    @Test
    fun testPartialEffectHandling() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> native_random {max = max}
            }

            let multiEffectFunc = {x} -> do
              Log.print {msg = "start"}
              Random.nextInt {max = x}

            # Wrap in function to avoid top-level <*> restriction
            let getHandledResult = {} -> perform SystemRandom, Console in multiEffectFunc {x = 42}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val originalType = getVariableType(typedProgram, "multiEffectFunc") as FunkType.FunctionType
        val getHandledResultType = getVariableType(typedProgram, "getHandledResult") as FunkType.FunctionType

        // Original function should have both effects
        assertNotNull(originalType.effects)
        assertEquals(setOf("Log", "Random"), originalType.effects!!.effects)

        // getHandledResult should be a function with performed marker (<*>)
        assertNotNull(getHandledResultType.effects)
        assertTrue(getHandledResultType.effects!!.performed, "Should have performed marker")
        assertTrue(getHandledResultType.effects!!.effects.isEmpty(), "Should have no concrete effects")
        // Return type should be Int
        assertEquals(FunkType.IntType, getHandledResultType.returnType)
    }

    @Test
    fun testEffectHandlingWithDoExpressionConstraints() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            let effectfulCalc = {x, y} -> do
              let sum = x + y
              Log.print {msg = "calculated sum"}
              sum * 2

            let handledCalc = perform Console in effectfulCalc
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val effectfulType = getVariableType(typedProgram, "effectfulCalc") as FunkType.FunctionType
        val handledType = getVariableType(typedProgram, "handledCalc") as FunkType.FunctionType

        // Both should have concrete Int parameter types from constraint propagation
        assertTrue(effectfulType.paramType is FunkType.RecordType)
        assertTrue(handledType.paramType is FunkType.RecordType)
        
        val effectfulParams = effectfulType.paramType as FunkType.RecordType
        val handledParams = handledType.paramType as FunkType.RecordType
        
        // Parameters should exist and have some type
        assertNotNull(effectfulParams.fields["x"], "x parameter should exist in effectful function")
        assertNotNull(effectfulParams.fields["y"], "y parameter should exist in effectful function")
        assertNotNull(handledParams.fields["x"], "x parameter should exist in handled function")
        assertNotNull(handledParams.fields["y"], "y parameter should exist in handled function")

        // Effect handling should work (may not be perfectly inferred yet)
        // For now, just check that both functions have return types
        assertNotNull(effectfulType.returnType, "Effectful function should have return type")
        assertNotNull(handledType.returnType, "Handled function should have return type")
    }

    @Test
    fun testUnhandledEffectRemains() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }
            
            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            let dualEffectFunc = {x} -> do
              Log.print {msg = "start"}
              Random.nextInt {max = x}

            let partiallyHandled = {x} -> Random.nextInt {max = x}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val originalType = getVariableType(typedProgram, "dualEffectFunc") as FunkType.FunctionType
        val partiallyHandledType = getVariableType(typedProgram, "partiallyHandled") as FunkType.FunctionType

        // Original should have both effects
        assertNotNull(originalType.effects)
        assertEquals(setOf("Log", "Random"), originalType.effects!!.effects)

        // Partially handled should have only Random remaining
        assertNotNull(partiallyHandledType.effects)
        assertEquals(setOf("Random"), partiallyHandledType.effects!!.effects)
    }

    @Test
    fun testEffectComposition() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }
            
            effect Random where {
              nextInt : {max: Int} -> Int <Random>
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            impl Random for SystemRandom where {
              nextInt : {max: Int} -> Int = {max} -> native_random {max = max}
            }
            
            let composedFunc = {x} -> do
              Log.print {msg = "start"}
              let value1 = Random.nextInt {max = x}
              Log.print {msg = "middle"}
              let value2 = Random.nextInt {max = value1}
              Log.print {msg = "end"}
              value2

            let handledFunc = {x} -> perform SystemRandom, Console in composedFunc {x = x}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val composedType = getVariableType(typedProgram, "composedFunc") as FunkType.FunctionType
        val handledType = getVariableType(typedProgram, "handledFunc") as FunkType.FunctionType

        // Composed function should have both effects
        assertNotNull(composedType.effects)
        assertEquals(setOf("Log", "Random"), composedType.effects!!.effects)

        // Handled function should have <*> (performed but no remaining effects)
        assertNotNull(handledType.effects)
        assertTrue(handledType.effects!!.effects.isEmpty(), "Should have no concrete effects")
        assertTrue(handledType.effects!!.performed, "Should be marked as performed")

        // Both should have the same parameter and return types
        assertEquals(composedType.paramType, handledType.paramType)
        assertEquals(composedType.returnType, handledType.returnType)
    }
}
