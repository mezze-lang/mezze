package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for the new effect performer system in V2 interpreter.
 * Migrated from V1 to V2 with unboxed values.
 */
class NewEffectSystemTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /** Helper to check if result is unit (either MezzeUnit singleton or empty MezzeRecord) */
    private fun isUnitResult(result: Any): Boolean {
        return MezzeTestHelper.isUnit(result) ||
               (result is MezzeRecord && result.fieldCount == 0)
    }

    @Test
    fun testBasicEffectPerformerExecution() {
        val source = """
            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            impl Console for Terminal where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            perform Terminal in Console.print {msg = "Hello World"}
        """.trimIndent()

        val result = eval(source)

        // Should execute without error and return unit
        assertTrue(isUnitResult(result))
    }


    @Test
    fun testParameterizedPerformer() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Log for FileLogger {path: Str} where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            perform FileLogger {path = "app.log"} in Log.info {msg = "Test message"}
        """.trimIndent()

        val result = eval(source)

        assertTrue(isUnitResult(result))
    }

    @Test
    fun testEffectTransformation() {
        val source = """
            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            impl Console for Terminal where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            impl Log for ConsoleLogger where {
              info : {msg: Str} -> {} = {msg} -> Console.print {msg = "[INFO] $(msg)"}
            }

            let myFunction : {msg: Str} -> {} <Log> = {msg} -> Log.info {msg = msg}

            perform Terminal, ConsoleLogger in myFunction {msg = "Hello"}
        """.trimIndent()

        val result = eval(source)

        assertTrue(isUnitResult(result))
    }

    @Test
    fun testMultipleEffectChaining() {
        val source = """
            effect Console where {
              print : {msg: Str} -> {} <Console>
            }

            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect App where {
              run : {name: Str} -> Str <App>
            }

            impl Console for Terminal where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            impl Log for ConsoleLogger where {
              info : {msg: Str} -> {} = {msg} -> Console.print {msg = "[LOG] $(msg)"}
            }

            impl App for MyApp where {
              run : {name: Str} -> Str = {name} -> do
                Log.info {msg = "Starting app for $(name)"}
                "App result for $(name)"
            }

            perform Terminal, ConsoleLogger, MyApp in App.run {name = "TestApp"}
        """.trimIndent()

        val result = eval(source)

        assertTrue(result is String)
        assertEquals("App result for TestApp", result)
    }

    // Note: Full lazy effects (effects without handlers returning suspended values)
    // are not yet fully implemented. The current architecture focuses on the hot path:
    // effects inside `perform` blocks execute immediately with zero overhead.
    // Lazy effects will be implemented when needed for advanced use cases.
}
