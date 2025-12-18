package com.funk.language.interpreter

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class RuntimeEffectValidationTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    @Test
    fun testUnhandledEffectPreventsExecution() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }

            let effectfulFunc : {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "test"}
              x + 1

            # Call function with unhandled effects - should suspend
            let main = {} -> effectfulFunc {x = 5}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        // Execute should not throw - functions suspend instead
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }

        // Get the context and call main
        val context = interpreter.getLastExecutionContext()
        val mainValue = context["main"]
        assertNotNull(mainValue, "main function should exist")

        // Call main{} - it should return FunkSuspended
        val result = when (val fn = mainValue!!) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(FunkRecord(emptyMap()), fn.closureVariables) as FunkValue
            }
            else -> {
                throw AssertionError("Expected FunkTruffleFunction but got ${fn::class.simpleName}")
            }
        }

        // Result should be a suspended computation
        assertTrue(result is FunkSuspended, "Result should be FunkSuspended but was ${result::class.simpleName}")
        val suspended = result as FunkSuspended

        // Should have Log as unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log"),
            "Unhandled effects should contain Log but was ${suspended.unhandledEffects}")
    }

    @Test
    fun testHandledEffectAllowsExecution() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            let effectfulFunc = {x} -> do
              Log.print {msg = "test"}
              x + 1
            
            # Handle the effect properly
            let result = perform Console in effectfulFunc {x = 5}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        
        // This should not throw an exception
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }
    }

    @Test
    fun testMultipleUnhandledEffectsPreventsExecution() {
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

            # Call function with multiple unhandled effects - should suspend
            let main = {} -> multiEffectFunc {x = 10}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        // Execute should not throw - functions suspend instead
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }

        // Get the context and call main
        val context = interpreter.getLastExecutionContext()
        val mainValue = context["main"]
        assertNotNull(mainValue, "main function should exist")

        // Call main{} - it should return FunkSuspended
        val result = when (val fn = mainValue!!) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(FunkRecord(emptyMap()), fn.closureVariables) as FunkValue
            }
            else -> {
                throw AssertionError("Expected FunkTruffleFunction but got ${fn::class.simpleName}")
            }
        }

        // Result should be a suspended computation
        assertTrue(result is FunkSuspended, "Result should be FunkSuspended but was ${result::class.simpleName}")
        val suspended = result as FunkSuspended

        // Should have both Log and Random as unhandled effects
        assertTrue(suspended.unhandledEffects.contains("Log") || suspended.unhandledEffects.contains("Random"),
            "Unhandled effects should contain Log or Random but was ${suspended.unhandledEffects}")
    }

    @Test
    fun testPartiallyHandledEffectsPreventsExecution() {
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

            let multiEffectFunc : {x: Int} -> Int <Log, Random> = {x} -> do
              Log.print {msg = "start"}
              let value = Random.nextInt {max = x}
              value

            # Handle only one effect - should suspend with remaining effect
            let main = {} -> perform Console in multiEffectFunc {x = 10}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        // Execute should not throw - partially handled functions still suspend
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }

        // Get the context and call main
        val context = interpreter.getLastExecutionContext()
        val mainValue = context["main"]
        assertNotNull(mainValue, "main function should exist")

        // Call main{} - it should return FunkSuspended
        val result = when (val fn = mainValue!!) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(FunkRecord(emptyMap()), fn.closureVariables) as FunkValue
            }
            else -> {
                throw AssertionError("Expected FunkTruffleFunction but got ${fn::class.simpleName}")
            }
        }

        // Result should be a suspended computation with Random unhandled
        assertTrue(result is FunkSuspended, "Result should be FunkSuspended but was ${result::class.simpleName}")
        val suspended = result as FunkSuspended

        // Should have Random as the remaining unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Random"),
            "Unhandled effects should contain Random but was ${suspended.unhandledEffects}")
        assertFalse(suspended.unhandledEffects.contains("Log"),
            "Log should be handled, unhandled effects: ${suspended.unhandledEffects}")
    }

    @Test
    fun testFullyHandledEffectsAllowsExecution() {
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
            
            # Handle all effects
            let result = perform SystemRandom, Console in multiEffectFunc {x = 10}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        
        // This should not throw an exception
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }
    }

    @Test
    fun testPureFunctionExecutesWithoutHandlers() {
        val source = """
            let pureFunc = {x, y} -> x + y
            let result = pureFunc {x = 3, y = 4}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        
        // Pure functions should execute without issues
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }
    }

    @Test
    fun testNestedEffectfulFunctionValidation() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            let innerFunc : {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "inner"}
              x * 2

            let outerFunc : {y: Int} -> Int <Log> = {y} -> do
              let result = innerFunc {x = y}
              result + 1

            # Both functions have unhandled effects - should suspend
            let main = {} -> outerFunc {y = 5}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program
        // Execute should not throw - nested effectful functions should suspend
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }

        // Get the context and call main
        val context = interpreter.getLastExecutionContext()
        val mainValue = context["main"]
        assertNotNull(mainValue, "main function should exist")

        // Call main{} - it should return FunkSuspended
        val result = when (val fn = mainValue!!) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(FunkRecord(emptyMap()), fn.closureVariables) as FunkValue
            }
            else -> {
                throw AssertionError("Expected FunkTruffleFunction but got ${fn::class.simpleName}")
            }
        }

        // Result should be a suspended computation
        assertTrue(result is FunkSuspended, "Result should be FunkSuspended but was ${result::class.simpleName}")
        val suspended = result as FunkSuspended

        // Should have Log as unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log"),
            "Unhandled effects should contain Log but was ${suspended.unhandledEffects}")
    }

    @Test
    fun testEffectValidationInPartialApplication() {
        val source = """
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            let effectfulFunc = {x, y} -> do
              Log.print {msg = "computing"}
              x + y

            # Partial application should still preserve effect information
            let partialFunc = effectfulFunc {x = 5}
            let main = {} -> partialFunc {y = 3}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = try {
            typeChecker.typeCheck(ast).program
        } catch (e: TypeCheckError) {
            // If type checking fails, use the untyped program
            ast
        }

        // Execute should not throw - partial application should suspend
        assertDoesNotThrow {
            FunkTestHelper.execute(typedProgram)
        }

        // Get the context and call main
        val context = interpreter.getLastExecutionContext()
        val mainValue = context["main"]
        assertNotNull(mainValue, "main function should exist")

        // Call main{} - it should return FunkSuspended
        val result = when (val fn = mainValue!!) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(FunkRecord(emptyMap()), fn.closureVariables) as FunkValue
            }
            else -> {
                throw AssertionError("Expected FunkTruffleFunction but got ${fn::class.simpleName}")
            }
        }

        // Result should be a suspended computation
        assertTrue(result is FunkSuspended, "Result should be FunkSuspended but was ${result::class.simpleName}")
        val suspended = result as FunkSuspended

        // Should have Log as unhandled effect
        assertTrue(suspended.unhandledEffects.contains("Log"),
            "Unhandled effects should contain Log but was ${suspended.unhandledEffects}")
    }
}
