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

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testBasicEffectPerformerExecution() {
        val source = """
            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            perform Terminal in Console.print {msg = "Hello World"}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        // Should execute without error and return unit
        assertTrue(result is FunkRecord)
        assertEquals(0, (result as FunkRecord).fields.size) // Unit type
    }


    @Test
    fun testParameterizedPerformer() {
        val source = """
            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            impl Log for FileLogger {path: String} where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = "$(path): $(msg)"}
            }
            
            perform FileLogger {path = "app.log"} in Log.info {msg = "Test message"}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is FunkRecord)
    }

    @Test
    fun testEffectTransformation() {
        val source = """
            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            impl Log for ConsoleLogger where {
              info : {msg: String} -> {} = {msg} -> Console.print {msg = "[INFO] $(msg)"}
            }
            
            let myFunction : {msg: String} -> {} <Log> = {msg} -> Log.info {msg = msg}
            
            perform Terminal, ConsoleLogger in myFunction {msg = "Hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is FunkRecord)
    }

    @Test
    fun testMultipleEffectChaining() {
        val source = """
            effect Console where {
              print : {msg: String} -> {} <Console>
            }

            effect Log where {
              info : {msg: String} -> {} <Log>
            }

            effect App where {
              run : {name: String} -> String <App>
            }

            impl Console for Terminal where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
            
            impl Log for ConsoleLogger where {
              info : {msg: String} -> {} = {msg} -> Console.print {msg = "[LOG] $(msg)"}
            }

            impl App for MyApp where {
              run : {name: String} -> String = {name} -> do
                Log.info {msg = "Starting app for $(name)"}
                "App result for $(name)"
            }
            
            perform Terminal, ConsoleLogger, MyApp in App.run {name = "TestApp"}
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)

        assertTrue(result is FunkString)
        assertEquals("App result for TestApp", (result as FunkString).value)
    }

    // Note: Full lazy effects (effects without handlers returning FunkSuspended)
    // are not yet fully implemented. The current architecture focuses on the hot path:
    // effects inside `perform` blocks execute immediately with zero overhead.
    // Lazy effects will be implemented when needed for advanced use cases.
    // For now, FunkSuspended is implemented and ready for continuation-based effects.
}
