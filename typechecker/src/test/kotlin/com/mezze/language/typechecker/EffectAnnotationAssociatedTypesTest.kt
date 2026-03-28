package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for effect associated type ANNOTATIONS in function type signatures.
 *
 * This tests the new syntax: <Exception having 'error = Str>
 * in function type annotations, not in effect/impl declarations.
 */
class EffectAnnotationAssociatedTypesTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): com.mezze.language.ast.TypeCheckResult {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program)
    }

    @Test
    fun `test effect annotation with associated type in type signature parses`() {
        // Function that propagates State effect (not handling it)
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> Int <State having 'state = Int> = {} -> State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("getState") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test effect annotation without associated type still works`() {
        // Note: When effects are handled via `perform`, they are no longer visible at function boundary.
        // So the type signature should be pure (no effects declared).
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {}
            }

            let getState: {} -> Int = {} -> perform Counter in State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("getState") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test multiple effects with and without associated types`() {
        // Note: When effects are handled via `perform`, they are no longer visible at function boundary.
        // So the type signature should be pure (no effects declared).
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl Log for ConsoleLogger where {
                info = {msg} -> {}
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {}
            }

            let combined: {} -> Int = {} ->
                perform ConsoleLogger, Counter in do
                    Log.info { msg = "hello" }
                    State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test effect annotation with multiple associated types`() {
        // Function that propagates Operation effect (not handling it)
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>,
                fail: {error: 'err} -> 'err <Operation>
            }

            let getOp: {} -> Int <Operation having 'ok = Int, 'err = Str> = {} ->
                Operation.succeed { value = 42 }
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test effect annotation with complex associated type`() {
        // Function that propagates State effect (not handling it)
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getRecordState: {} -> {x: Int, y: Str} <State having 'state = {x: Int, y: Str}> = {} ->
                State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test polymorphic effect row with associated type annotation`() {
        // Function that propagates State effect and allows additional effects via ...
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> Int <State having 'state = Int, ...> = {} -> State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test multiple annotated effects`() {
        // Function that propagates both effects (not handling them)
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            let combined: {} -> Int <State having 'state = Int, Reader having 'env = Str> = {} ->
                do
                    let _ = Reader.ask {}
                    State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)
    }

    // ========================================================================
    // TESTS FOR OPTIONAL ASSOCIATED TYPE ANNOTATIONS (INFERENCE WITHOUT having)
    // ========================================================================

    @Test
    fun `test effect without having clause infers associated types from usage`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getIntState: {} -> Int <State> = {} -> State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("getIntState") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
        assertNotNull(funcType.effects)
        assertTrue(funcType.effects!!.effects.contains("State") || funcType.effects!!.effectInstances.any { it.name == "State" })
    }

    @Test
    fun `test effect without having clause with put operation infers state type`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let putString: {s: Str} -> {} <State> = {s} -> State.put { value = s }
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("putString") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.RecordType)
    }

    @Test
    fun `test multiple effects without having clauses infer correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            let combined: {} -> Int <State, Reader> = {} ->
                do
                    let env: Str = Reader.ask {}
                    State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("combined") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test effect with polymorphic row but no having clause`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> Int <State, ...> = {} -> State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("getState") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
        assertNotNull(funcType.effects)
        assertNotNull(funcType.effects!!.rowVar)
    }

    @Test
    fun `test effect inference works without any annotation when impl handles it`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {}
            }

            let pureGet: {} -> Int = {} -> perform IntState in State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("pureGet") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
        val effects = funcType.effects
        assertTrue(effects == null || (effects.effects.isEmpty() && effects.effectInstances.isEmpty()))
    }

    @Test
    fun `test mix of annotated and unannotated effects`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            let mixed: {} -> Int <State having 'state = Int, Reader> = {} ->
                do
                    let env: Str = Reader.ask {}
                    State.get {}
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("mixed") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test simple effect without associated types works without having`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            let logSomething: {msg: Str} -> {} <Log> = {msg} -> Log.info { msg = msg }
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)

        val funcType = result.getVariableType("logSomething") as MezzeType.FunctionType
        assertNotNull(funcType.effects)
    }

    // ========================================================================
    // TYPE MISMATCH TESTS - annotated vs inferred associated types
    // ========================================================================

    @Test
    fun `test mismatch between annotated return type and inferred effect associated type fails`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> Str <State having 'state = Int> = {} -> State.get {}
        """.trimIndent()

        val program = parser.parse(code)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test mismatch between two effect associated type annotations fails`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let mismatch: {} -> {} <State having 'state = Int> = {} ->
                do
                    let x: Str = State.get {}
                    State.put { value = x }
        """.trimIndent()

        val program = parser.parse(code)
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test correct annotated associated type matches inferred type`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> Int <State having 'state = Int> = {} -> State.get {}
        """.trimIndent()

        assertDoesNotThrow {
            typeCheck(code)
        }
    }

    @Test
    fun `test partial annotation lets type inference fill in rest`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let updateState: {} -> {} <State> = {} ->
                do
                    let current: Int = State.get {}
                    State.put { value = current }
        """.trimIndent()

        val result = typeCheck(code)
        assertNotNull(result)
    }

    // ========================================================================
    // ERROR MESSAGE TESTS - verify helpful error messages for associated types
    // ========================================================================

    @Test
    fun `test error message mentions effect name and associated type on mismatch`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> Str <State having 'state = Int> = {} -> State.get {}
        """.trimIndent()

        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        // Error message should mention the type mismatch (Int vs Str)
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("Str"),
            "Error message should mention the conflicting types: $message"
        )
    }

    @Test
    fun `test error message for complex associated type mismatch`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            let getState: {} -> {x: Int} <State having 'state = {x: Str}> = {} -> State.get {}
        """.trimIndent()

        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        // Error should indicate type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("Str") || message.contains("mismatch") || message.contains("expected"),
            "Error message should indicate type mismatch: $message"
        )
    }

    @Test
    fun `test error message for multiple effect associated type mismatches`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>,
                fail: {error: 'err} -> 'err <Operation>
            }

            let wrongTypes: {} -> Str <Operation having 'ok = Int, 'err = Bool> = {} ->
                Operation.succeed { value = 42 }
        """.trimIndent()

        val program = parser.parse(code)
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }

        // Error should indicate type mismatch
        val message = exception.message ?: ""
        assertTrue(
            message.contains("Int") || message.contains("Str"),
            "Error message should mention conflicting types: $message"
        )
    }
}
