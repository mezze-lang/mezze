package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.TypeCheckError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ComprehensiveEffectInterpreterTest {
    
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter
    
    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }
    
    @Test
    fun testBasicEffectExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            perform Console in Log.print {msg = "Hello from effect test!"}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should execute successfully and return unit
        assertTrue(result is FunkRecord)
        val record = result as FunkRecord
        assertTrue(record.fields.isEmpty()) // Unit type
    }
    
    @Test
    fun testRandomEffectExecution() {
        val source = """
            effect Random {
              nextInt : {min: Int, max: Int} -> Int
            }
            
            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> native_random_int {min = min, max = max}
            }
            
            perform MathRandom in Random.nextInt {min = 1, max = 100}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return an integer between 1 and 100
        assertTrue(result is FunkInt)
        val intValue = (result as FunkInt).value
        assertTrue(intValue >= 1 && intValue <= 100)
    }
    
    @Test
    fun testMultipleEffectsExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            effect Random {
              nextInt : {min: Int, max: Int} -> Int
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> native_random_int {min = min, max = max}
            }
            
            # Direct effect calls with multiple handlers
            perform MathRandom, Console in do
              Log.print {msg = "Generating random number"}
              let num = Random.nextInt {min = 1, max = 10}
              Log.print {msg = "Generated: " ++ intToString {val = num}}
              num
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return an integer
        assertTrue(result is FunkInt)
        val intValue = (result as FunkInt).value
        assertTrue(intValue >= 1 && intValue <= 10)
    }
    
    @Test
    fun testDoBlockWithEffectsExecution() {
        val source = """
            effect Log {
              info : {msg: String} -> {}
            }
            
            impl Log for Console where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = "[INFO] " ++ msg}
            }
            
            # Computation with effects in do block
            let computation = {} ->
              do
                Log.info {msg = "Starting computation"}
                let x = 5
                let y = 10
                Log.info {msg = "Computing " ++ intToString {val = x} ++ " + " ++ intToString {val = y}}
                let result = x + y
                Log.info {msg = "Result: " ++ intToString {val = result}}
                result
            
            # Execute computation
            perform Console in computation {}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return 15
        assertTrue(result is FunkInt)
        assertEquals(15, (result as FunkInt).value)
    }
    
    @Test
    fun testUseExpressionExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            perform Console in Log.print {msg = "Hello from use expression!"}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should execute successfully and return unit
        assertTrue(result is FunkRecord)
        val record = result as FunkRecord
        assertTrue(record.fields.isEmpty()) // Unit type
    }
    
    @Test
    fun testEffectDeclarationRegistration() {
        val source = """
            effect Log {
              print : {msg: String} -> {},
              debug : {msg: String, level: Int} -> {}
            }
            
            # Just declare the effect, don't use it
            let dummy = 42
            dummy
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return 42
        assertTrue(result is FunkInt)
        assertEquals(42, (result as FunkInt).value)
    }
    
    @Test
    fun testImplDeclarationRegistration() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            # Just declare the impl, don't use it
            let dummy = "test"
            dummy
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return "test"
        assertTrue(result is FunkString)
        assertEquals("test", (result as FunkString).value)
    }
    
    @Test
    fun testMultipleImplOperationsExecution() {
        val source = """
            effect Log {
              info : {msg: String} -> {},
              error : {msg: String} -> {},
              debug : {msg: String} -> {}
            }
            
            impl Log for Console where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = "[INFO] " ++ msg}
              error : {msg: String} -> {} = {msg} -> native_print {msg = "[ERROR] " ++ msg}
              debug : {msg: String} -> {} = {msg} -> native_print {msg = "[DEBUG] " ++ msg}
            }
            
            # Test function with multiple log operations
            let testLogs = {} ->
              do
                Log.info {msg = "This is info"}
                Log.error {msg = "This is error"}
                Log.debug {msg = "This is debug"}
                "All logs completed"
            
            # Execute with handler
            perform Console in testLogs {}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return the string
        assertTrue(result is FunkString)
        assertEquals("All logs completed", (result as FunkString).value)
    }
    
    @Test
    fun testNestedEffectCallsExecution() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            # Simple nested effect calls
            perform Console in do
              Log.print {msg = "Outer function start"}
              Log.print {msg = "Inner function start"}
              let value = 42
              Log.print {msg = "Inner function end"}
              Log.print {msg = "Outer function end"}
              value
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return 42
        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }
    
    @Test
    fun testEffectCallWithComplexArguments() {
        val source = """
            effect Math {
              add : {x: Int, y: Int} -> Int,
              multiply : {x: Int, y: Int} -> Int
            }
            
            impl Math for Calculator where {
              add : {x: Int, y: Int} -> Int = {x, y} -> x + y
              multiply : {x: Int, y: Int} -> Int = {x, y} -> x * y
            }
            
            # Complex computation with multiple effect calls
            let computation = {} ->
              do
                let a = 3
                let b = 4
                let sum = Math.add {x = a, y = b}
                let product = Math.multiply {x = sum, y = 2}
                product
            
            # Execute computation
            perform Calculator in computation {}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return (3 + 4) * 2 = 14
        assertTrue(result is FunkInt)
        assertEquals(14, (result as FunkInt).value)
    }
    
    @Test
    fun testEffectCallErrorHandling() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }

            # No implementation declared - this should cause an error
            perform NonExistentHandler in Log.print {msg = "This should fail"}
        """.trimIndent()

        val program = parser.parse(source)

        // Should throw a TypeCheckError when trying to type check (handler not in scope)
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
    }
    
    @Test
    fun testNativeFunctionIntegration() {
        val source = """
            # Test direct native function call
            native_print {msg = "Direct native call test"}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should execute successfully (result might be FunkNull for unit type)
        assertTrue(result is FunkRecord || result is FunkNull)
    }
    
    @Test
    fun testNativeRandomFunctionIntegration() {
        val source = """
            # Test direct native random call
            native_random_int {min = 1, max = 10}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should return an integer between 1 and 10
        assertTrue(result is FunkInt)
        val intValue = (result as FunkInt).value
        assertTrue(intValue >= 1 && intValue <= 10)
    }
    
    @Test
    fun testHandlerMappingCorrectness() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }
            
            effect Debug {
              log : {msg: String} -> {}
            }
            
            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = "[LOG] " ++ msg}
            }
            
            impl Debug for DebugConsole where {
              log : {msg: String} -> {} = {msg} -> native_print {msg = "[DEBUG] " ++ msg}
            }
            
            # Direct multiple effect calls
            perform DebugConsole, Console in do
              Log.print {msg = "This is from Log effect"}
              Debug.log {msg = "This is from Debug effect"}
              "Mapping test complete"
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should execute successfully
        assertTrue(result is FunkString)
        assertEquals("Mapping test complete", (result as FunkString).value)
    }
    
    @Test
    fun testParameterizedImplExecution() {
        // Note: This test will work once parameterized handler instantiation is implemented
        val source = """
            effect Logging {
              log : {msg: String} -> {}
            }
            
            impl Logging for FileLogger {path: String} where {
              log : {msg: String} -> {} = {msg} -> native_print {msg = "[FILE] " ++ msg}
            }
            
            # For now, this will use empty parameters
            perform FileLogger in Logging.log {msg = "Parameterized test"}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)
        
        // Should execute successfully even with empty parameters
        assertTrue(result is FunkRecord)
        val record = result as FunkRecord
        assertTrue(record.fields.isEmpty()) // Unit type
    }
}
