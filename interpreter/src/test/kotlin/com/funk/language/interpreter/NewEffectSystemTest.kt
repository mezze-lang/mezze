package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NewEffectSystemTest {

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
    fun testBasicEffectPerformerExecution() {
        val source = """
            effect Console {
              print : {msg: String} -> {}
            }
            
            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            perform Terminal in Console.print {msg = "Hello World"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        // Should execute without error and return unit
        assertTrue(result is FunkRecord)
        assertEquals(0, (result as FunkRecord).fields.size) // Unit type
    }

    @Test
    fun testSameEffectPerformerReuse() {
        // Test that a performer can delegate to another performer that's available in context
        val source = """
            effect Log {
              info : {msg: String} -> {}
            }
            
            impl Log for BasicLogger where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = "[INFO] " ++ msg}
            }
            
            impl Log for EnhancedLogger where {
              info : {msg: String} -> {} = {msg} -> BasicLogger.info {msg = "[ENHANCED] " ++ msg}
            }
            
            perform EnhancedLogger in Log.info {msg = "Test"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkRecord)
    }

    @Test
    fun testParameterizedPerformer() {
        val source = """
            effect Log {
              info : {msg: String} -> {}
            }
            
            impl Log for FileLogger {path: String} where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = path ++ ": " ++ msg}
            }
            
            perform FileLogger {path = "app.log"} in Log.info {msg = "Test message"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkRecord)
    }

    @Test
    fun testEffectTransformation() {
        val source = """
            effect Console {
              print : {msg: String} -> {}
            }
            
            effect Log {
              info : {msg: String} -> {}
            }
            
            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            impl Log for ConsoleLogger where {
              info : {msg: String} -> {} = {msg} -> Console.print {msg = "[INFO] " ++ msg}
            }
            
            let myFunction : {msg: String} -> {} <Log> = {msg} -> Log.info {msg = msg}
            
            perform Terminal, ConsoleLogger in myFunction {msg = "Hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkRecord)
    }

    @Test
    fun testMultipleEffectChaining() {
        val source = """
            effect Console {
              print : {msg: String} -> {}
            }
            
            effect Log {
              info : {msg: String} -> {}
            }
            
            effect App {
              run : {name: String} -> String
            }
            
            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            impl Log for ConsoleLogger where {
              info : {msg: String} -> {} = {msg} -> Console.print {msg = "[LOG] " ++ msg}
            }
            
            impl App for MyApp where {
              run : {name: String} -> String = {name} -> do
                Log.info {msg = "Starting app for " ++ name}
                "App result for " ++ name
            }
            
            perform Terminal, ConsoleLogger, MyApp in App.run {name = "TestApp"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkString)
        assertEquals("App result for TestApp", (result as FunkString).value)
    }
}
