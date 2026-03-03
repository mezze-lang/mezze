package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertDoesNotThrow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class BuiltinEffectsTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: MezzeInterpreter

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        interpreter = MezzeInterpreter()
    }

    @Test
    fun testBuiltinLogEffect() {
        val source = """
            # Declare Log effect
            effect Log where {
              print : {msg: String} -> {} <Log>
            }

            # Use native implementation
            impl Log for NativeConsole where {
              print : {msg: String} -> {} = {msg} -> __native_console_print {msg = msg}
            }

            # Test the effect
            let result = perform NativeConsole in Log.print {msg = "Hello World!"}

            result
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow { typeChecker.typeCheck(program) }

        // This should execute and print to console
        val result = MezzeTestHelper.execute(program)
        // Result should be unit
        assertTrue(result is MezzeUnit || (result is MezzeRecord && (result as MezzeRecord).fields.isEmpty()))
    }

    @Test
    fun testBuiltinRandomEffect() {
        val source = """
            # Declare Random effect
            effect Random where {
              nextInt : {min: Int, max: Int} -> Int <Random>
            }

            # Use native implementation
            impl Random for NativeRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> __native_random_int {min = min, max = max}
            }

            # Test the effect
            let result = perform NativeRandom in Random.nextInt {min = 1, max = 10}

            result
        """.trimIndent()

        val program = parser.parse(source)
        assertDoesNotThrow { typeChecker.typeCheck(program) }

        val result = MezzeTestHelper.execute(program)
        assertTrue(result is MezzeInt)
        val randomValue = (result as MezzeInt).value
        assertTrue(randomValue >= 1 && randomValue <= 10, "Random value $randomValue should be between 1 and 10")
    }

    @Test
    fun testParameterExtractionImprovement() {
        val source = """
            # Test that parameter names are correctly extracted
            effect TestEffect where {
              multiParam : {first: String, second: Int, third: Float} -> String <TestEffect>
            }

            impl TestEffect for TestHandler where {
              multiParam : {first: String, second: Int, third: Float} -> String =
                {first, second, third} -> first
            }

            # This should work with proper parameter extraction
            let result = perform TestHandler in TestEffect.multiParam {first = "Hello", second = 42, third = 3.14}

            result
        """.trimIndent()

        val program = parser.parse(source)

        // This test verifies that our parameter extraction improvement works
        // The impl should create a MezzeTruffleFunction with parameters ["first", "second", "third"]
        // instead of the old simplified ["args"]
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        assertTrue(result is MezzeString)
        assertEquals("Hello", (result as MezzeString).value)
    }

    @Test
    fun testNativeFunctionIntegration() {
        // Test that basic arithmetic native operations work
        // These are handled through builtin abilities (NumAdd, NumSub, etc.)
        val source = """
            # Test native operations through arithmetic operators
            let x = 10 + 20
            let y = x * 2
            y
        """.trimIndent()

        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)

        // This should execute without throwing an exception
        assertDoesNotThrow {
            val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances)
            // Native operations should execute successfully
            assertTrue(result is MezzeInt)
            assertEquals(60L, (result as MezzeInt).value)
        }
    }
}
