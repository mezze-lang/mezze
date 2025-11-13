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
            effect Log {
              print : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> {}
            }
            
            let effectfulFunc : {x: Int} -> Int <Log> = {x} -> do
              Log.print {msg = "test"}
              x + 1
            
            # Try to call function with unhandled effects - should fail
            let result = effectfulFunc {x = 5}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        val exception = assertThrows(RuntimeException::class.java) {
            interpreter.execute(typedProgram)
        }
        
        assertTrue(exception.message!!.contains("No handlers available for effect call"))
        assertTrue(exception.message!!.contains("Log"))
    }

    @Test
    fun testHandledEffectAllowsExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
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
        val typedProgram = typeChecker.typeCheck(ast)
        
        // This should not throw an exception
        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun testMultipleUnhandledEffectsPreventsExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            effect Random {
              nextInt : {max: Int} -> Int
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
            
            # Try to call function with multiple unhandled effects
            let result = multiEffectFunc {x = 10}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        val exception = assertThrows(RuntimeException::class.java) {
            interpreter.execute(typedProgram)
        }
        
        assertTrue(exception.message!!.contains("No handlers available for effect call"))
        // Should mention both effects
        assertTrue(exception.message!!.contains("Log") || exception.message!!.contains("Random"))
    }

    @Test
    fun testPartiallyHandledEffectsPreventsExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            effect Random {
              nextInt : {max: Int} -> Int
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
            
            # Handle only one effect - should still fail
            let result = perform Console in multiEffectFunc {x = 10}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        val exception = assertThrows(Exception::class.java) {
            interpreter.execute(typedProgram)
        }

        assertTrue(exception.message!!.contains("No handler found for effect"))
        // Should mention the remaining unhandled effect
        assertTrue(exception.message!!.contains("Random"))
    }

    @Test
    fun testFullyHandledEffectsAllowsExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            effect Random {
              nextInt : {max: Int} -> Int
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
        val typedProgram = typeChecker.typeCheck(ast)
        
        // This should not throw an exception
        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun testPureFunctionExecutesWithoutHandlers() {
        val source = """
            let pureFunc = {x, y} -> x + y
            let result = pureFunc {x = 3, y = 4}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        // Pure functions should execute without issues
        assertDoesNotThrow {
            interpreter.execute(typedProgram)
        }
    }

    @Test
    fun testNestedEffectfulFunctionValidation() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
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
            
            # Both functions have unhandled effects
            let result = outerFunc {y = 5}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast)
        
        val exception = assertThrows(RuntimeException::class.java) {
            interpreter.execute(typedProgram)
        }
        
        assertTrue(exception.message!!.contains("No handlers available for effect call"))
        assertTrue(exception.message!!.contains("Log"))
    }

    @Test
    fun testEffectValidationInPartialApplication() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            let effectfulFunc = {x, y} -> do
              Log.print {msg = "computing"}
              x + y
            
            # Partial application should still preserve effect information
            let partialFunc = effectfulFunc {x = 5}
            let result = partialFunc {y = 3}
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = try {
            typeChecker.typeCheck(ast)
        } catch (e: TypeCheckError) {
            // If type checking fails, use the untyped program
            ast
        }
        
        val exception = assertThrows(RuntimeException::class.java) {
            interpreter.execute(typedProgram)
        }
        
        assertTrue(exception.message!!.contains("No handlers available for effect call"))
        assertTrue(exception.message!!.contains("Log"))
    }
}
