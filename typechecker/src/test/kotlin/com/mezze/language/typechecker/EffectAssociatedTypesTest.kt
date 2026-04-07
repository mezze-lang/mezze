package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for effect associated types with `having` keyword
 */
class EffectAssociatedTypesTest {

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
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    @Test
    fun `test effect with associated type type checks`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test effect implementation with associated type binding type checks`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {}
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test effect implementation with complex type binding type checks`() {
        // Define List type first, then use Nil for empty list
        val code = """
            type List 'a = | Cons { head: 'a, tail: (List 'a) }, Nil |

            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for ListState having 'state = (List Str) where {
                get = {} -> Nil,
                put = {value} -> {}
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test error when impl missing associated type binding`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter {} where {
                get = {} -> 42,
                put = {value} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("declares associated types") == true)
        assertTrue(exception.message?.contains("does not provide bindings") == true)
    }

    @Test
    fun `test error when impl has binding but effect doesn't`() {
        val code = """
            effect Log where {
                info: {msg: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger having 'state = Int where {
                info = {msg} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("provides bindings for") == true)
        assertTrue(exception.message?.contains("does not declare any associated types") == true)
    }

    @Test
    fun `test error when associated type names don't match`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for Counter having 'wrong = Int where {
                get = {} -> 42
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        // With multiple associated types support, this is now caught as missing bindings
        assertTrue(exception.message?.contains("missing bindings for") == true)
        assertTrue(exception.message?.contains("'state") == true)
    }

    @Test
    fun `test effect with associated type used in perform block`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {}
            }

            let getUseState = {} -> perform Counter in do
                let x = State.get {}
                State.put { value = x + 1 }
                x
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getUseState") as MezzeType.FunctionType
        println("[TEST DO BLOCK] Actual type: ${funcType.returnType}")
        println("[TEST DO BLOCK] Type class: ${funcType.returnType?.javaClass?.simpleName}")
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test reader effect with associated type`() {
        val code = """
            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl Reader for StringReader having 'env = Str where {
                ask = {} -> "hello"
            }

            let getReadEnv = {} -> perform StringReader in Reader.ask {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getReadEnv") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test multiple effects with different associated types`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0,
                put = {value} -> {}
            }

            impl Reader for StringReader having 'env = Str where {
                ask = {} -> "hello"
            }

            let getCombined = {} -> perform IntState, StringReader in do
                let s = State.get {}
                let e = Reader.ask {}
                s
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test effect operation return type correctly substituted`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for BoolState having 'state = Bool where {
                get = {} -> True
            }

            let getResult = {} -> perform BoolState in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        val actualType = funcType.returnType
        val typeClass = actualType?.javaClass?.simpleName
        val typeStr = actualType?.let { prettyPrintType(it) }
        // Bool in Mezze is represented as BoolType internally (or VariantType with True/False)
        assertTrue(
            actualType is MezzeType.BoolType ||
            (actualType is MezzeType.VariantType &&
             actualType.constructors.containsKey("True") &&
             actualType.constructors.containsKey("False")),
            "Expected Bool but got: $typeClass ($typeStr)"
        )
    }

    @Test
    fun `test effect operation parameter type correctly substituted`() {
        val code = """
            effect State having 'state where {
                put: {value: 'state} -> {} <State>
            }

            impl State for FloatState having 'state = Float where {
                put = {value} -> {}
            }

            let getPutFloat = {} -> perform FloatState in State.put { value = 3.14 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test error when implementation return type doesn't match substituted type`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for BadCounter having 'state = Int where {
                get = {} -> "not an int"
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("type") == true || exception.message?.contains("Str") == true)
    }

    @Test
    fun `test effect operation with associated type in both parameter and return`() {
        val code = """
            effect Transform having 'a where {
                transform: {input: 'a} -> 'a <Transform>
            }

            impl Transform for StringTransform having 'a = Str where {
                transform = {input} -> input
            }

            let getResult = {} -> perform StringTransform in Transform.transform { input = "hello" }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test multiple operations all using same associated type`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>,
                modify: {f: {s: 'state} -> 'state} -> {} <State>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0,
                put = {value} -> {},
                modify = {f} -> {}
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test record type with associated type as field`() {
        val code = """
            effect State having 'state where {
                get: {} -> {value: 'state, count: Int} <State>
            }

            impl State for RecordState having 'state = Str where {
                get = {} -> {value = "hello", count = 1}
            }

            let getResult = {} -> perform RecordState in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.RecordType)
    }

    @Test
    fun `test nested type with associated type`() {
        val code = """
            effect State having 'state where {
                get: {} -> (Option 'state) <State>
            }

            impl State for OptionState having 'state = Int where {
                get = {} -> Some { val = 42 }
            }

            let getResult = {} -> perform OptionState in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.TypeApplication)
    }

    @Test
    fun `test performer parameters combined with associated type`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ConfigState {initial: Int} having 'state = Int where {
                get = {} -> initial
            }

            let getResult = {} -> perform ConfigState {initial = 100} in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test list of associated type`() {
        // Define List type first, then use Nil for empty list
        val code = """
            type List 'a = | Cons { head: 'a, tail: (List 'a) }, Nil |

            effect Collection having 'item where {
                getAll: {} -> (List 'item) <Collection>
            }

            impl Collection for StringCollection having 'item = Str where {
                getAll = {} -> Nil
            }

            let getResult = {} -> perform StringCollection in Collection.getAll {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        val returnType = funcType.returnType
        println("[TEST LIST] Actual type: $returnType")
        println("[TEST LIST] Type class: ${returnType?.javaClass?.simpleName}")
        // List T can be VariantType, TypeApplication, or ListType (when using built-in Cons/Nil)
        assertTrue(returnType is MezzeType.VariantType || returnType is MezzeType.TypeApplication || returnType is MezzeType.ListType)
    }

    @Test
    fun `test error when parameter type doesn't match associated type binding`() {
        val code = """
            effect State having 'state where {
                put: {value: 'state} -> {} <State>
            }

            impl State for BadState having 'state = Int where {
                put = {value} -> {}
            }

            let getBadPut = {} -> perform BadState in State.put { value = "wrong type" }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        // Should fail because "wrong type" is String but we bound 'state = Int
        assertTrue(exception.message != null)
    }

    // ===== Tests for Multiple Associated Types =====

    @Test
    fun `test effect with two associated types type checks`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> {} <Operation>,
                fail: {error: 'err} -> {} <Operation>
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test effect with three associated types type checks`() {
        val code = """
            effect Triple having 'a, 'b, 'c where {
                getFirst: {} -> 'a <Triple>,
                getSecond: {} -> 'b <Triple>,
                getThird: {} -> 'c <Triple>
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test implementation with two associated type bindings type checks`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> {} <Operation>,
                fail: {error: 'err} -> {} <Operation>
            }

            impl Operation for IntStringOperation having 'ok = Int, 'err = Str where {
                succeed = {value} -> {},
                fail = {error} -> {}
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test implementation with three associated type bindings type checks`() {
        val code = """
            effect Triple having 'a, 'b, 'c where {
                getFirst: {} -> 'a <Triple>,
                getSecond: {} -> 'b <Triple>,
                getThird: {} -> 'c <Triple>
            }

            impl Triple for IntStringBoolTriple having 'a = Int, 'b = Str, 'c = Bool where {
                getFirst = {} -> 42,
                getSecond = {} -> "hello",
                getThird = {} -> True
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple associated types used in perform block`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>,
                fail: {error: 'err} -> 'err <Operation>
            }

            impl Operation for IntStringOperation having 'ok = Int, 'err = Str where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let getUseResult = {} -> perform IntStringOperation in do
                let x = Operation.succeed { value = 42 }
                let y = Operation.fail { error = "oops" }
                x
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getUseResult") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test type substitution works correctly for each associated type`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>,
                fail: {error: 'err} -> 'err <Operation>
            }

            impl Operation for BoolFloatOperation having 'ok = Bool, 'err = Float where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let getSuccessResult = {} -> perform BoolFloatOperation in Operation.succeed { value = True }
            let getFailResult = {} -> perform BoolFloatOperation in Operation.fail { error = 3.14 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val successFuncType = program.getVariableType("getSuccessResult") as MezzeType.FunctionType
        // Bool is represented as BoolType internally (not VariantType)
        assertTrue(successFuncType.returnType is MezzeType.BoolType || successFuncType.returnType is MezzeType.VariantType)

        val failFuncType = program.getVariableType("getFailResult") as MezzeType.FunctionType
        assertTrue(failFuncType.returnType is MezzeType.FloatType)
    }

    @Test
    fun `test error when missing one of multiple associated type bindings`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> {} <Operation>,
                fail: {error: 'err} -> {} <Operation>
            }

            impl Operation for PartialOperation having 'ok = Int where {
                succeed = {value} -> {},
                fail = {error} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("missing bindings for") == true)
        assertTrue(exception.message?.contains("'err") == true)
    }

    @Test
    fun `test error when missing multiple associated type bindings`() {
        val code = """
            effect Triple having 'a, 'b, 'c where {
                getFirst: {} -> 'a <Triple>,
                getSecond: {} -> 'b <Triple>,
                getThird: {} -> 'c <Triple>
            }

            impl Triple for PartialTriple having 'a = Int where {
                getFirst = {} -> 42,
                getSecond = {} -> "oops",
                getThird = {} -> True
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        assertTrue(exception.message?.contains("missing bindings for") == true)
        // Should mention both 'b and 'c are missing
        assertTrue(exception.message?.contains("'b") == true)
        assertTrue(exception.message?.contains("'c") == true)
    }

    @Test
    fun `test error when providing wrong binding name with multiple types`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> {} <Operation>
            }

            impl Operation for BadOperation having 'ok = Int, 'wrong = Str where {
                succeed = {value} -> {}
            }
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            typeCheck(code)
        }

        // Should catch that 'wrong is not declared and 'err is missing
        assertTrue(exception.message?.contains("'wrong") == true || exception.message?.contains("'err") == true)
    }

    @Test
    fun `test complex types as multiple associated type bindings`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> {} <Operation>,
                fail: {error: 'err} -> {} <Operation>
            }

            impl Operation for ComplexOperation having 'ok = [Int], 'err = {code: Int, message: Str} where {
                succeed = {value} -> {},
                fail = {error} -> {}
            }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple associated types with performer parameters`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>,
                fail: {error: 'err} -> 'err <Operation>
            }

            impl Operation for ConfigurableOperation {defaultOk: Int, defaultErr: Str} having 'ok = Int, 'err = Str where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let getResult = {} -> perform ConfigurableOperation {defaultOk = 0, defaultErr = "error"} in Operation.succeed { value = 100 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getResult") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    @Test
    fun `test multiple effects each with multiple associated types`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>,
                fail: {error: 'err} -> 'err <Operation>
            }

            effect State having 'state, 'change where {
                get: {} -> 'state <State>,
                update: {delta: 'change} -> {} <State>
            }

            impl Operation for IntStringOperation having 'ok = Int, 'err = Str where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            impl State for IntFloatState having 'state = Int, 'change = Float where {
                get = {} -> 0,
                update = {delta} -> {}
            }

            let getCombined = {} -> perform IntStringOperation, IntFloatState in do
                let r = Operation.succeed { value = 42 }
                let s = State.get {}
                r
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("getCombined") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.IntType)
    }

    // ========================================================================
    // INTEGRATION TESTS FOR POLYMORPHIC HANDLERS (ToResult, ToOption)
    // ========================================================================

    @Test
    fun `test ToResult handler propagates error type to Result`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToResult {} having 'error = Str where {
                throw = {error} -> Err { error = error }
            }

            let mayFail: {} -> (Result Int Str) = {} ->
                perform ToResult in do
                    Exception.throw { error = "something went wrong" }
                    Ok { val = 42 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val funcType = program.getVariableType("mayFail") as MezzeType.FunctionType
        assertTrue(funcType.returnType is MezzeType.TypeApplication || funcType.returnType is MezzeType.VariantType)
    }

    @Test
    fun `test ToResult handler with Int error type`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToIntResult {} having 'error = Int where {
                throw = {error} -> Err { error = error }
            }

            let mayFailWithCode: {} -> (Result Str Int) = {} ->
                perform ToIntResult in do
                    Exception.throw { error = 404 }
                    Ok { val = "success" }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ToResult handler with record error type`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToRecordResult {} having 'error = {code: Int, message: Str} where {
                throw = {error} -> Err { error = error }
            }

            let mayFailWithDetails: {} -> (Result Int {code: Int, message: Str}) = {} ->
                perform ToRecordResult in do
                    Exception.throw { error = {code = 500, message = "Internal error"} }
                    Ok { val = 42 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test ToOption handler discards error type`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToOption {} having 'error = Str where {
                throw = {error} -> None
            }

            let maybeValue: {} -> (Option Int) = {} ->
                perform ToOption in do
                    Exception.throw { error = "ignored" }
                    Some { val = 42 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test nested handlers with different error types`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToStringResult {} having 'error = Str where {
                throw = {error} -> Err { error = error }
            }

            impl Exception for ToIntResult {} having 'error = Int where {
                throw = {error} -> Err { error = error }
            }

            let nested: {} -> (Result (Result Int Int) Str) = {} ->
                perform ToStringResult in do
                    Exception.throw { error = "outer error" }
                    let inner = perform ToIntResult in do
                        Exception.throw { error = 42 }
                        Ok { val = 100 }
                    Ok { val = inner }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test polymorphic function with Exception effect`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            let throwIfZero: {n: Int} -> Int <Exception having 'error = Str> = {n} ->
                match n == 0 is
                    True -> Exception.throw { error = "cannot be zero" }
                    False -> n

            impl Exception for ToResult {} having 'error = Str where {
                throw = {error} -> Err { error = error }
            }

            let safeDiv: {a: Int, b: Int} -> (Result Int Str) = {a, b} ->
                perform ToResult in do
                    Exception.throw { error = "test" }
                    Ok { val = a / b }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test effect annotation validates against throw sites`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            let stringError: {} -> Int <Exception having 'error = Str> = {} ->
                do
                    Exception.throw { error = "error message" }
                    42
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test mismatch between annotated error type and throw site fails`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            let wrongErrorType: {} -> Int <Exception having 'error = Str> = {} ->
                do
                    Exception.throw { error = 42 }
                    0
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test multiple throws with same error type in handler`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToResult {} having 'error = Str where {
                throw = {error} -> Err { error = error }
            }

            let multipleThrows: {} -> (Result Int Str) = {} ->
                perform ToResult in do
                    Exception.throw { error = "first error" }
                    Exception.throw { error = "second error" }
                    Ok { val = 42 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple throws with different error types fails`() {
        val code = """
            effect Exception having 'error where {
                throw: {error: 'error} -> 'a <Exception>
            }

            impl Exception for ToResult {} having 'error = Str where {
                throw = {error} -> Err { error = error }
            }

            let inconsistentThrows: {} -> (Result Int Str) = {} ->
                perform ToResult in do
                    Exception.throw { error = "string error" }
                    Exception.throw { error = 42 }
                    Ok { val = 0 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }
}
