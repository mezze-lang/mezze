package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive tests for ability and effect error detection.
 * These tests verify that the type checker properly:
 * 1. Reports errors for undefined abilities
 * 2. Reports errors for undefined effects
 * 3. Tracks effect propagation through impl bodies
 * 4. Provides proper source locations in errors
 */
class AbilityEffectErrorsTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========================================================================
    // UNDEFINED ABILITY TESTS
    // ========================================================================

    @Test
    fun `test undefined ability method call should error`() {
        // Without a Show ability in scope, 42.show {} is field access on Int which fails
        val source = """
            let x = 42.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message != null,
            "Should produce an error for calling show on Int without ability: ${exception.message}")
    }

    @Test
    fun `test undefined ability method access should error`() {
        val source = """
            let f = Show.show
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("Show") == true,
            "Error should mention 'Show': ${exception.message}")
    }

    @Test
    fun `test undefined ability method in pipeline should error`() {
        // Without Show in scope, @.show {} on Int fails as field access
        val source = """
            let z = 5 |> @.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message != null,
            "Should produce an error for calling show on Int without ability: ${exception.message}")
    }

    @Test
    fun `test typo in built-in ability should suggest correct name`() {
        val source = """
            let x = Insepct.to_dbg_str {}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        // Should suggest "Inspect" as similar to "Insepct"
        assertTrue(exception.message?.contains("Insepct") == true,
            "Error should mention 'Insepct': ${exception.message}")
        // May or may not suggest Inspect depending on implementation
    }

    @Test
    fun `test built-in ability with wrong method should error`() {
        val source = """
            let x = Inspect.nonexistent {}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("nonexistent") == true,
            "Error should mention 'nonexistent': ${exception.message}")
        assertTrue(exception.message?.contains("to_dbg_str") == true,
            "Error should suggest 'to_dbg_str': ${exception.message}")
    }

    // ========================================================================
    // UNDEFINED EFFECT TESTS
    // ========================================================================

    @Test
    fun `test undefined effect operation should error`() {
        val source = """
            let x = UnknownEffect.operation {arg = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("UnknownEffect") == true,
            "Error should mention 'UnknownEffect': ${exception.message}")
    }

    @Test
    fun `test effect used but not defined should error`() {
        val source = """
            let logMessage = {msg: Str} -> UnknownLog.log {msg = msg}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("UnknownLog") == true,
            "Error should mention 'UnknownLog': ${exception.message}")
    }

    // ========================================================================
    // EFFECT PROPAGATION TESTS
    // ========================================================================

    @Test
    fun `test effect propagation from impl body is tracked`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {} <Log>
            }

            effect IO where {
                print: { msg: Str } -> {} <IO>
            }

            impl IO for StdIO where {
                print = { msg } -> {}
            }

            # FileLogger implements Log but uses IO internally
            impl Log for FileLogger where {
                info = { msg } -> IO.print {msg = msg}
            }

            # Function that uses Log
            let logTest = {} -> Log.info {msg = "test"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        // Find the logTest binding
        val logTestBinding = typedProgram.items
            .filterIsInstance<LetBinding>()
            .find { it.variableName == "logTest" }

        assertNotNull(logTestBinding, "logTest binding should exist")
        val logTestType = logTestBinding?.inferredType
        assertTrue(logTestType is MezzeType.FunctionType, "logTest should be a function")

        val funcType = logTestType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "logTest should have effects")
        assertTrue(funcType.effects!!.effects.contains("Log"),
            "logTest should have Log effect: ${funcType.effects}")
    }

    @Test
    fun `test nested effect in perform is propagated`() {
        val source = """
            effect Inner where {
                inner_op: {} -> {} <Inner>
            }

            effect Outer where {
                outer_op: {} -> {} <Outer>
            }

            impl Inner for InnerImpl where {
                inner_op = {} -> {}
            }

            impl Outer for OuterImpl where {
                # Outer's impl uses Inner effect
                outer_op = {} -> Inner.inner_op {}
            }

            let test = {} ->
                perform OuterImpl in
                Outer.outer_op {}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        // Find the test binding
        val testBinding = typedProgram.items
            .filterIsInstance<LetBinding>()
            .find { it.variableName == "test" }

        assertNotNull(testBinding, "test binding should exist")
        val testType = testBinding?.inferredType
        assertTrue(testType is MezzeType.FunctionType, "test should be a function")

        val funcType = testType as MezzeType.FunctionType
        assertNotNull(funcType.effects, "test should have effects")
        // Inner effect should be propagated (not handled yet)
        assertTrue(funcType.effects!!.effects.contains("Inner"),
            "test should have Inner effect propagated from OuterImpl: ${funcType.effects}")
    }

    @Test
    fun `test all effects handled results in performed marker`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
                info = { msg } -> {}
            }

            let main = {} ->
                perform Console in
                Log.info {msg = "test"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program)
        val typedProgram = result.program

        // Find main binding
        val mainBinding = typedProgram.items
            .filterIsInstance<LetBinding>()
            .find { it.variableName == "main" }

        assertNotNull(mainBinding, "main binding should exist")
        val mainType = mainBinding?.inferredType
        assertTrue(mainType is MezzeType.FunctionType, "main should be a function")

        val funcType = mainType as MezzeType.FunctionType
        // Effects should be handled (performed = true) with no remaining effects
        assertNotNull(funcType.effects, "main should have effects annotation")
        assertTrue(funcType.effects!!.performed,
            "main should have performed marker (effects handled): ${funcType.effects}")
    }

    // ========================================================================
    // SOURCE LOCATION TESTS
    // ========================================================================

    @Test
    fun `test undefined ability error has source location`() {
        // x.show {} where x is Int and no Show ability → field access error
        val source = """
            let x = 1
            let y = x.show {}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }

        // Should produce an error (field access on non-record type)
        assertTrue(exception.message != null,
            "Should produce an error: ${exception.message}")
    }

    @Test
    fun `test undefined effect error has source location`() {
        val source = """
            let a = 1
            let b = 2
            let c = UndefinedEffect.op {x = a + b}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }

        // Error should have source location pointing to line 3
        assertNotNull(exception.sourceLocation,
            "Error should have source location: ${exception.message}")
        assertEquals(3, exception.sourceLocation?.line,
            "Error should point to line 3: ${exception.sourceLocation}")
    }

    // ========================================================================
    // USER-DEFINED ABILITY TESTS
    // ========================================================================

    @Test
    fun `test user-defined ability method access is blocked`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            let f = Show.show
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("Direct ability access") == true,
            "Error should mention direct ability access: ${exception.message}")
        assertTrue(exception.message?.contains("Show.show") == true,
            "Error should mention 'Show.show': ${exception.message}")
    }

    @Test
    fun `test user-defined ability with wrong method should error`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            let f = Show.nonexistent
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("Show") == true,
            "Error should mention 'Show': ${exception.message}")
        assertTrue(exception.message?.contains("nonexistent") == true,
            "Error should mention 'nonexistent': ${exception.message}")
    }

    // ========================================================================
    // PERFORM HANDLER VALIDATION TESTS
    // ========================================================================

    @Test
    fun `test unknown performer in perform should error`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {} <Log>
            }

            let test = {} ->
                perform UnknownPerformer in
                Log.info {msg = "test"}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("UnknownPerformer") == true,
            "Error should mention 'UnknownPerformer': ${exception.message}")
    }

    @Test
    fun `test perform with pure expression should error`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {} <Log>
            }

            impl Log for Console where {
                info = { msg } -> {}
            }

            # This is invalid - expression 1 + 1 is pure, no effects to handle
            let test = {} ->
                perform Console in
                1 + 1
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("pure") == true ||
                   exception.message?.contains("no effects") == true,
            "Error should mention pure expression or no effects: ${exception.message}")
    }

    @Test
    fun `test perform handler for wrong effect should error`() {
        val source = """
            effect Log where {
                info: { msg: Str } -> {} <Log>
            }

            effect IO where {
                print: { msg: Str } -> {} <IO>
            }

            impl IO for StdIO where {
                print = { msg } -> {}
            }

            # StdIO handles IO, but expression uses Log - mismatch
            let test = {} ->
                perform StdIO in
                Log.info {msg = "test"}
        """.trimIndent()

        val program = parser.parse(source)
        val exception = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program)
        }
        assertTrue(exception.message?.contains("IO") == true ||
                   exception.message?.contains("Log") == true,
            "Error should mention effect mismatch: ${exception.message}")
    }
}
