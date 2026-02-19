package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.LetBinding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Tests for imported performer (effect handler) resolution.
 *
 * When importing a performer like Console from std::effects::log,
 * the typechecker must correctly track that Console handles the Log effect.
 * This allows `perform Console in` to properly remove Log from the effect row.
 *
 * Regression test for: When importing performers directly (not via effect import),
 * the performerToEffect mapping wasn't being populated, causing Log to remain
 * unhandled even after `perform Console in`.
 */
class ImportedPerformerTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `imported performer should handle effect - basic case`() {
        // This is the core regression test
        // Previously, `main` had type {} -> {} <Log, *> instead of {} -> {} <*>
        val source = """
            use std::effects::log::{println, Console}

            let main = {} -> perform Console in do
                println { msg = "Hello!" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        // Find main binding and check its type
        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType
        assertNotNull(mainType, "main should have inferred type")
        assertTrue(mainType is MezzeType.FunctionType, "main should be a function type")

        val funcType = mainType as MezzeType.FunctionType
        val effectRow = funcType.effects

        assertNotNull(effectRow, "main should have effect row")
        assertTrue(effectRow!!.performed, "main should have performed marker")
        assertTrue(
            effectRow.effects.isEmpty(),
            "main should have no unhandled effects, but has: ${effectRow.effects}"
        )
    }

    @Test
    fun `imported performer should handle effect - multiple effects`() {
        val source = """
            use std::effects::log::{println, info, Console}

            let main = {} -> perform Console in do
                println { msg = "Hello!" }
                info { msg = "Info message" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(mainType, "main should be a function type")

        val effectRow = mainType!!.effects
        assertNotNull(effectRow, "main should have effect row")
        assertTrue(effectRow!!.performed, "main should have performed marker")
        assertTrue(
            effectRow.effects.isEmpty(),
            "All Log effects should be handled by Console, but has: ${effectRow.effects}"
        )
    }

    @Test
    fun `imported performer should work when effect is also imported`() {
        // Importing both the effect (Log) and performer (Console)
        val source = """
            use std::effects::log::{Log, println, Console}

            let logSomething = { msg } -> Log.println { msg = msg }

            let main = {} -> perform Console in do
                logSomething { msg = "Hello!" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(mainType, "main should be a function type")

        val effectRow = mainType!!.effects
        assertTrue(
            effectRow != null && effectRow.performed && effectRow.effects.isEmpty(),
            "main should be {} -> {} <*>"
        )
    }

    @Test
    fun `checkProgramForExecution should pass for valid main with imported performer`() {
        val source = """
            use std::effects::log::{println, Console}

            let main = {} -> perform Console in do
                println { msg = "Hello!" }
        """.trimIndent()

        val program = parser.parse(source)

        // This should NOT throw - it was throwing before the fix
        val result = typeChecker.checkProgramForExecution(program)

        assertTrue(
            result is TypeChecker.TypeCheckResult.Success,
            "checkProgramForExecution should succeed for valid main"
        )
    }

    @Test
    fun `nested performs with imported performer should work`() {
        val source = """
            use std::effects::log::{println, Console}

            let inner = {} -> println { msg = "inner" }
            let outer = {} -> perform Console in inner {}

            let main = {} -> perform Console in do
                outer {}
                println { msg = "done" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.checkProgramForExecution(program)

        assertTrue(
            result is TypeChecker.TypeCheckResult.Success,
            "Nested performs should work"
        )
    }

    @Test
    fun `unhandled effect should remain in effect row`() {
        // Without perform, Log should remain unhandled
        val source = """
            use std::effects::log::{println, Console}

            let test = {} -> println { msg = "Hello!" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val testBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "test" }

        assertNotNull(testBinding, "test should be defined")

        val testType = testBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(testType, "test should be a function type")

        val effectRow = testType!!.effects
        assertNotNull(effectRow, "test should have effect row")
        assertTrue(
            effectRow!!.effects.contains("Log"),
            "test should have unhandled Log effect"
        )
    }

    // ========== CUSTOM EFFECT TESTS ==========

    @Test
    fun `custom effect and handler in same file should work`() {
        val source = """
            effect MyEffect where {
                doSomething: { x: Int } -> Int <MyEffect>
            }

            impl MyEffect for MyHandler where {
                doSomething: { x: Int } -> Int = { x } -> x * 2
            }

            let useEffect = { n } -> MyEffect.doSomething { x = n }

            let main = {} -> perform MyHandler in useEffect { n = 5 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(mainType, "main should be a function type")

        val effectRow = mainType!!.effects
        assertNotNull(effectRow, "main should have effect row")
        assertTrue(effectRow!!.performed, "main should have performed marker")
        assertTrue(
            effectRow.effects.isEmpty(),
            "MyEffect should be handled by MyHandler, but has: ${effectRow.effects}"
        )
    }

    @Test
    fun `custom effect with multiple operations should work`() {
        val source = """
            effect Storage where {
                get: { key: String } -> String <Storage>,
                set: { key: String, value: String } -> {} <Storage>
            }

            impl Storage for MemoryStorage where {
                get: { key: String } -> String = { key } -> "value",
                set: { key: String, value: String } -> {} = { key, value } -> {}
            }

            let useStorage = { k, v } -> do
                Storage.set { key = k, value = v }
                Storage.get { key = k }

            let main = {} -> perform MemoryStorage in useStorage { k = "test", v = "hello" }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(mainType, "main should be a function type")

        val effectRow = mainType!!.effects
        assertTrue(
            effectRow != null && effectRow.performed && effectRow.effects.isEmpty(),
            "All Storage effects should be handled"
        )
    }

    @Test
    fun `multiple custom effects with multiple handlers should work`() {
        val source = """
            effect Logger where {
                log: { msg: String } -> {} <Logger>
            }

            effect Counter where {
                increment: {} -> Int <Counter>
            }

            impl Logger for ConsoleLogger where {
                log: { msg: String } -> {} = { msg } -> {}
            }

            impl Counter for SimpleCounter where {
                increment: {} -> Int = {} -> 1
            }

            let doWork = {} -> do
                Logger.log { msg = "Starting" }
                let n = Counter.increment {}
                Logger.log { msg = "Done" }
                n

            let main = {} -> perform ConsoleLogger, SimpleCounter in doWork {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(mainType, "main should be a function type")

        val effectRow = mainType!!.effects
        assertTrue(
            effectRow != null && effectRow.performed && effectRow.effects.isEmpty(),
            "Both Logger and Counter should be handled, but has: ${effectRow?.effects}"
        )
    }

    @Test
    fun `handler that adds effects should propagate them`() {
        // FileLogger handles Log but adds FileSystem effect
        val source = """
            effect Log where {
                info: { msg: String } -> {} <Log>
            }

            effect FileSystem where {
                write: { path: String, content: String } -> {} <FileSystem>
            }

            impl FileSystem for DiskFS where {
                write: { path: String, content: String } -> {} = { path, content } -> {}
            }

            impl Log for FileLogger where {
                info: { msg: String } -> {} <FileSystem> = { msg } ->
                    FileSystem.write { path = "/log.txt", content = msg }
            }

            let logMessage = { text } -> Log.info { msg = text }

            let main = {} -> perform DiskFS in (perform FileLogger in logMessage { text = "test" })
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val mainBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main should be defined")

        val mainType = mainBinding!!.inferredType as? MezzeType.FunctionType
        assertNotNull(mainType, "main should be a function type")

        val effectRow = mainType!!.effects
        assertTrue(
            effectRow != null && effectRow.performed && effectRow.effects.isEmpty(),
            "Both Log (via FileLogger) and FileSystem (via DiskFS) should be handled"
        )
    }

    // ========== ABILITY TESTS ==========

    @Test
    fun `custom ability and impl in same file should work`() {
        val source = """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            impl Show Int where {
                show: { val: Int } -> String = { val } -> "an integer"
            }

            let result = Show.show { val = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val resultBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "result" }

        assertNotNull(resultBinding, "result should be defined")
        assertEquals(MezzeType.StringType, resultBinding!!.inferredType)
    }

    @Test
    fun `ability with multiple impls for different types should work`() {
        val source = """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            impl Show Int where {
                show: { val: Int } -> String = { val } -> "an integer"
            }

            impl Show String where {
                show: { val: String } -> String = { val } -> val
            }

            impl Show Bool where {
                show: { val: Bool } -> String = { val } ->
                    match val is
                        True -> "true"
                        False -> "false"
            }

            let intStr = Show.show { val = 42 }
            let strStr = Show.show { val = "hello" }
            let boolStr = Show.show { val = True }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val intStrBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "intStr" }
        val strStrBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "strStr" }
        val boolStrBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "boolStr" }

        assertNotNull(intStrBinding, "intStr should be defined")
        assertNotNull(strStrBinding, "strStr should be defined")
        assertNotNull(boolStrBinding, "boolStr should be defined")

        assertEquals(MezzeType.StringType, intStrBinding!!.inferredType)
        assertEquals(MezzeType.StringType, strStrBinding!!.inferredType)
        assertEquals(MezzeType.StringType, boolStrBinding!!.inferredType)
    }

    @Test
    fun `ability with multiple methods should work`() {
        val source = """
            ability Serializable 'a where {
                serialize: { val: 'a } -> String,
                deserialize: { str: String } -> 'a
            }

            impl Serializable Int where {
                serialize: { val: Int } -> String = { val } -> "int",
                deserialize: { str: String } -> Int = { str } -> 0
            }

            let serialized = Serializable.serialize { val = 42 }
            # Use deserialized value to constrain its type to Int
            let deserialized: Int = Serializable.deserialize { str = "42" }
            let sum = deserialized + 1
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        val serializedBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "serialized" }
        val sumBinding = typedProgram.items.filterIsInstance<LetBinding>()
            .find { it.variableName == "sum" }

        assertNotNull(serializedBinding, "serialized should be defined")
        assertNotNull(sumBinding, "sum should be defined")

        assertEquals(MezzeType.StringType, serializedBinding!!.inferredType)
        assertEquals(MezzeType.IntType, sumBinding!!.inferredType)
    }

    @Test
    fun `ability imported from stdlib codec should work`() {
        // Test importing actual stdlib ability
        val source = """
            use std::codec::{Encode, EncodeError, CodecConfig, FieldTransform}

            # Just verify the imports work - Encode is a multi-param ability
            let config = { transform = Identity, compact = False }
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)

        // Should pass type checking
        assertNotNull(result.program, "Program should type check")
    }

    // ========== ERROR MESSAGE TESTS ==========

    @Test
    fun `error message should suggest ability qualifier for local ability operation`() {
        // User wrote "show" instead of "Show.show"
        val source = """
            ability Show 'a where {
                show: { val: 'a } -> String
            }

            impl Show Int where {
                show: { val: Int } -> String = { val } -> "int"
            }

            let result = show { val = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        val errorMessage = error.message ?: ""

        assertTrue(
            errorMessage.contains("Show.show"),
            "Error message should suggest 'Show.show', but got: $errorMessage"
        )
        assertTrue(
            errorMessage.contains("ability method"),
            "Error message should mention 'ability method', but got: $errorMessage"
        )
    }

    @Test
    fun `error message should suggest effect qualifier for local effect operation`() {
        // User wrote "doSomething" instead of "MyEffect.doSomething"
        val source = """
            effect MyEffect where {
                doSomething: { x: Int } -> Int <MyEffect>
            }

            impl MyEffect for MyHandler where {
                doSomething: { x: Int } -> Int = { x } -> x
            }

            let result = {} -> doSomething { x = 42 }
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        val errorMessage = error.message ?: ""

        assertTrue(
            errorMessage.contains("MyEffect.doSomething"),
            "Error message should suggest 'MyEffect.doSomething', but got: $errorMessage"
        )
        assertTrue(
            errorMessage.contains("effect operation"),
            "Error message should mention 'effect operation', but got: $errorMessage"
        )
    }

    @Test
    fun `error message should suggest effect qualifier for imported effect operation`() {
        // User wrote "println" instead of "Log.println" with imported Log effect
        val source = """
            use std::effects::log::{Log, Console}

            let main = {} -> perform Console in println { msg = "hello" }
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        val errorMessage = error.message ?: ""

        assertTrue(
            errorMessage.contains("Log.println"),
            "Error message should suggest 'Log.println', but got: $errorMessage"
        )
        assertTrue(
            errorMessage.contains("effect operation"),
            "Error message should mention 'effect operation', but got: $errorMessage"
        )
    }
}
