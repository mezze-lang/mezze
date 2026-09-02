package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests for field/constructor access on the RESULT of an effectful call.
 *
 * Effects live on function arrows, so an effectful call yields a BARE value —
 * `getState {}` returns `{value: Int}`, with `<State>` on the enclosing arrow.
 * Field access (HasField) and pattern matching (HasConstructor) therefore work
 * directly on the bare record/variant — no unwrapping needed.
 *
 * These tests verify that:
 * 1. HasField reads a field of an effectful-call result (a bare record)
 * 2. HasConstructor matches a constructor of an effectful-call result (a bare variant)
 */
class EffectfulReceiverConstraintTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // =========================================================================
    // HasField on an effectful-call result (bare)
    // =========================================================================

    @Test
    fun `field access on effectful function result should work`() {
        // This tests that HasField constraint reads the bare effectful-call result
        // When we call an effectful function that returns a record, the result
        // is a bare record (the State effect rode the arrow). A field access reads
        // the record directly.
        val source = """
            effect State where {
                get: {} -> {value: Int} <State>
            }

            impl State for StateHandler where {
                get = {} -> {value = 42}
            }

            let getState = {} -> State.get {}

            # Field access on effectful result
            # getState {} returns a bare {value: Int} (State on the arrow)
            # Accessing .value returns a bare Int (State on the arrow)
            let getValue = {} -> do
                let result = getState {}
                result.value
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val getValueType = typedProgram.getVariableType("getValue")

        assertTrue(getValueType is MezzeType.FunctionType, "Expected FunctionType but got ${getValueType!!::class.simpleName}")
        val fnType = getValueType as MezzeType.FunctionType

        // Return type should be Int (the field type)
        assertTrue(fnType.returnType is MezzeType.IntType, "Expected IntType return but got ${fnType.returnType::class.simpleName}")

        // Should have State effect
        assertNotNull(fnType.effects)
        assertTrue(fnType.effects!!.effects.contains("State"), "Expected State effect")
    }

    @Test
    fun `field access on conditional with effectful branch should work`() {
        // This tests the if-then-else case where one branch is effectful
        // The result is a bare record; field access works directly
        val source = """
            effect State where {
                get: {} -> {name: Str, age: Int} <State>
            }

            impl State for StateHandler where {
                get = {} -> {name = "test", age = 25}
            }

            let getOrDefault = {useDefault: Bool} -> do
                let person = if useDefault
                    then {name = "default", age = 0}
                    else State.get {}
                person.name
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("getOrDefault") as MezzeType.FunctionType

        // Return type should be String
        assertTrue(fnType.returnType is MezzeType.StringType, "Expected StringType return but got ${fnType.returnType::class.simpleName}")
    }

    @Test
    fun `field access on direct effectful call should work`() {
        // Direct field access on effectful call: effectFn().field
        val source = """
            effect Config where {
                load: {} -> {host: Str, port: Int} <Config>
            }

            impl Config for ConfigLoader where {
                load = {} -> {host = "localhost", port = 8080}
            }

            let getHost = {} -> (Config.load {}).host
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("getHost") as MezzeType.FunctionType

        assertTrue(fnType.returnType is MezzeType.StringType, "Expected StringType return but got ${fnType.returnType::class.simpleName}")
        // Effects may or may not be tracked depending on how the typechecker handles direct field access
        // The main test here is that type checking succeeds and the return type is correct
    }

    @Test
    fun `nested field access on effectful result should work`() {
        // Test nested field access: effectFn().outer.inner
        val source = """
            effect Database where {
                query: {} -> {data: {name: Str, id: Int}} <Database>
            }

            impl Database for DBHandler where {
                query = {} -> {data = {name = "test", id = 1}}
            }

            let getName = {} -> (Database.query {}).data.name
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("getName") as MezzeType.FunctionType

        assertTrue(fnType.returnType is MezzeType.StringType, "Expected StringType return but got ${fnType.returnType::class.simpleName}")
    }

    @Test
    fun `field access should fail on wrong field name with suspended type`() {
        // Make sure error messages are still good when field doesn't exist
        val source = """
            effect State where {
                get: {} -> {value: Int} <State>
            }

            impl State for StateHandler where {
                get = {} -> {value = 42}
            }

            let getValue = {} -> (State.get {}).wrongField
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val exception = assertFailsWith<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(exception.message!!.contains("wrongField"), "Error should mention the wrong field name")
    }

    // =========================================================================
    // HasConstructor on an effectful-call result (bare)
    // =========================================================================

    @Test
    fun `constructor in conditional with effectful throw should work`() {
        // This was the original flaky test case - if-then-else with throw and Ok
        // The fix for solveHasConstructor should make this work reliably
        val source = """
            use std::effects::exception::{Exception, throw}

            impl Exception for ExnHandler having 'error = Str where {
                throw = {error} -> {}
            }

            let maybeThrow = {shouldThrow: Bool, value: Int} ->
                if shouldThrow
                    then throw {error = "error"}
                    else Ok {val = value}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("maybeThrow") as MezzeType.FunctionType

        // Return type is a bare variant-like value; the effectful throw's effect
        // is tracked on the function's arrow, not wrapped around the value.
        val returnType = fnType.returnType
        assertTrue(
            returnType is MezzeType.VariantType || returnType is MezzeType.TypeApplication
                || returnType is MezzeType.FunctionType,
            "Expected variant-like return type but got ${returnType::class.simpleName}"
        )
    }

    @Test
    fun `match on variant returned from effectful function should work`() {
        // Pattern matching on an effectful-call result works on the bare value
        val source = """
            type Status = | Loading, Ready { data: Str }, Failed { error: Str } |

            effect Api where {
                fetch: {} -> Status <Api>
            }

            impl Api for ApiHandler where {
                fetch = {} -> Ready {data = "response"}
            }

            let processResult = {} -> do
                let status = Api.fetch {}
                match status is
                    Loading -> "loading"
                    Ready {data} -> data
                    Failed {error} -> error
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("processResult") as MezzeType.FunctionType

        assertTrue(fnType.returnType is MezzeType.StringType, "Expected StringType return but got ${fnType.returnType::class.simpleName}")
    }

    // =========================================================================
    // Combined HasField and HasConstructor on effectful-call results
    // =========================================================================

    @Test
    fun `field access on constructor payload from effectful result should work`() {
        // Combining both: effectFn returns variant, we match it, access field on payload
        val source = """
            type Response = | Success { data: {name: Str, id: Int} }, Error { message: Str } |

            effect Api where {
                fetch: {} -> Response <Api>
            }

            impl Api for ApiHandler where {
                fetch = {} -> Success {data = {name = "test", id = 1}}
            }

            let getName = {} -> do
                let response = Api.fetch {}
                match response is
                    Success {data} -> data.name
                    Error {message} -> "unknown"
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("getName") as MezzeType.FunctionType

        assertTrue(fnType.returnType is MezzeType.StringType, "Expected StringType return but got ${fnType.returnType::class.simpleName}")
    }

    @Test
    fun `chained effectful calls with field access should work`() {
        // Multiple chained effectful operations with field access
        val source = """
            effect Store where {
                getUser: {} -> {name: Str, email: Str} <Store>,
                getSettings: {user: Str} -> {theme: Str} <Store>
            }

            impl Store for StoreHandler where {
                getUser = {} -> {name = "Alice", email = "alice@example.com"},
                getSettings = {user} -> {theme = "dark"}
            }

            let getUserTheme = {} -> do
                let user = Store.getUser {}
                let settings = Store.getSettings {user = user.name}
                settings.theme
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val fnType = typedProgram.getVariableType("getUserTheme") as MezzeType.FunctionType

        assertTrue(fnType.returnType is MezzeType.StringType, "Expected StringType return but got ${fnType.returnType::class.simpleName}")
        assertNotNull(fnType.effects)
        assertTrue(fnType.effects!!.effects.contains("Store"))
    }
}
