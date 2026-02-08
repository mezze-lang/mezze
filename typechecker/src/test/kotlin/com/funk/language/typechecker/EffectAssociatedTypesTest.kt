package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
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

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test effect with associated type type checks`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
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
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
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
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for ListState having 'state = (List String) where {
                get = {} -> Nil
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
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter {} where {
                get = {} -> 42
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
                info: {msg: String} -> {} <Log>
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
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
                put = {value} -> {}
            }

            let useState = perform Counter in do
                let x = State.get {}
                State.put { value = x + 1 }
                x
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "useState" }
        println("[TEST DO BLOCK] Actual type: ${binding.inferredType}")
        println("[TEST DO BLOCK] Type class: ${binding.inferredType?.javaClass?.simpleName}")
        assertTrue(binding.inferredType is FunkType.IntType)
    }

    @Test
    fun `test reader effect with associated type`() {
        val code = """
            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl Reader for StringReader having 'env = String where {
                ask = {} -> "hello"
            }

            let readEnv = perform StringReader in Reader.ask {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "readEnv" }
        assertTrue(binding.inferredType is FunkType.StringType)
    }

    @Test
    fun `test multiple effects with different associated types`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0
                put = {value} -> {}
            }

            impl Reader for StringReader having 'env = String where {
                ask = {} -> "hello"
            }

            let combined = perform IntState, StringReader in do
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

            let result = perform BoolState in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        val actualType = binding.inferredType
        val typeClass = actualType?.javaClass?.simpleName
        val typeString = actualType?.let { prettyPrintType(it) }
        // Bool in Funk is a variant type with True/False constructors
        assertTrue(
            actualType is FunkType.VariantType &&
            actualType.constructors.containsKey("True") &&
            actualType.constructors.containsKey("False"),
            "Expected Bool (VariantType with True/False) but got: $typeClass ($typeString)"
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

            let putFloat = perform FloatState in State.put { value = 3.14 }
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

        assertTrue(exception.message?.contains("type") == true || exception.message?.contains("String") == true)
    }

    @Test
    fun `test effect operation with associated type in both parameter and return`() {
        val code = """
            effect Transform having 'a where {
                transform: {input: 'a} -> 'a <Transform>
            }

            impl Transform for StringTransform having 'a = String where {
                transform = {input} -> input
            }

            let result = perform StringTransform in Transform.transform { input = "hello" }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        assertTrue(binding.inferredType is FunkType.StringType)
    }

    @Test
    fun `test multiple operations all using same associated type`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
                modify: {f: {s: 'state} -> 'state} -> {} <State>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 0
                put = {value} -> {}
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

            impl State for RecordState having 'state = String where {
                get = {} -> {value = "hello", count = 1}
            }

            let result = perform RecordState in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        assertTrue(binding.inferredType is FunkType.RecordType)
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

            let result = perform OptionState in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        assertTrue(binding.inferredType is FunkType.TypeApplication)
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

            let result = perform ConfigState {initial = 100} in State.get {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        assertTrue(binding.inferredType is FunkType.IntType)
    }

    @Test
    fun `test list of associated type`() {
        // Define List type first, then use Nil for empty list
        val code = """
            type List 'a = | Cons { head: 'a, tail: (List 'a) }, Nil |

            effect Collection having 'item where {
                getAll: {} -> (List 'item) <Collection>
            }

            impl Collection for StringCollection having 'item = String where {
                getAll = {} -> Nil
            }

            let result = perform StringCollection in Collection.getAll {}
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        println("[TEST LIST] Actual type: ${binding.inferredType}")
        println("[TEST LIST] Type class: ${binding.inferredType?.javaClass?.simpleName}")
        // List T can be VariantType, TypeApplication, or ListType (when using built-in Cons/Nil)
        assertTrue(binding.inferredType is FunkType.VariantType || binding.inferredType is FunkType.TypeApplication || binding.inferredType is FunkType.ListType)
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

            let badPut = perform BadState in State.put { value = "wrong type" }
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
                succeed: {value: 'ok} -> {} <Operation>
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
                getFirst: {} -> 'a <Triple>
                getSecond: {} -> 'b <Triple>
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
                succeed: {value: 'ok} -> {} <Operation>
                fail: {error: 'err} -> {} <Operation>
            }

            impl Operation for IntStringOperation having 'ok = Int, 'err = String where {
                succeed = {value} -> {}
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
                getFirst: {} -> 'a <Triple>
                getSecond: {} -> 'b <Triple>
                getThird: {} -> 'c <Triple>
            }

            impl Triple for IntStringBoolTriple having 'a = Int, 'b = String, 'c = Bool where {
                getFirst = {} -> 42
                getSecond = {} -> "hello"
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
                succeed: {value: 'ok} -> 'ok <Operation>
                fail: {error: 'err} -> 'err <Operation>
            }

            impl Operation for IntStringOperation having 'ok = Int, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let useResult = perform IntStringOperation in do
                let x = Operation.succeed { value = 42 }
                let y = Operation.fail { error = "oops" }
                x
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "useResult" }
        assertTrue(binding.inferredType is FunkType.IntType)
    }

    @Test
    fun `test type substitution works correctly for each associated type`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>
                fail: {error: 'err} -> 'err <Operation>
            }

            impl Operation for BoolFloatOperation having 'ok = Bool, 'err = Float where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let successResult = perform BoolFloatOperation in Operation.succeed { value = True }
            let failResult = perform BoolFloatOperation in Operation.fail { error = 3.14 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val successBinding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "successResult" }
        assertTrue(successBinding.inferredType is FunkType.VariantType)

        val failBinding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "failResult" }
        assertTrue(failBinding.inferredType is FunkType.FloatType)
    }

    @Test
    fun `test error when missing one of multiple associated type bindings`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> {} <Operation>
                fail: {error: 'err} -> {} <Operation>
            }

            impl Operation for PartialOperation having 'ok = Int where {
                succeed = {value} -> {}
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
                getFirst: {} -> 'a <Triple>
                getSecond: {} -> 'b <Triple>
                getThird: {} -> 'c <Triple>
            }

            impl Triple for PartialTriple having 'a = Int where {
                getFirst = {} -> 42
                getSecond = {} -> "oops"
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

            impl Operation for BadOperation having 'ok = Int, 'wrong = String where {
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
                succeed: {value: 'ok} -> {} <Operation>
                fail: {error: 'err} -> {} <Operation>
            }

            impl Operation for ComplexOperation having 'ok = [Int], 'err = {code: Int, message: String} where {
                succeed = {value} -> {}
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
                succeed: {value: 'ok} -> 'ok <Operation>
                fail: {error: 'err} -> 'err <Operation>
            }

            impl Operation for ConfigurableOperation {defaultOk: Int, defaultErr: String} having 'ok = Int, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let result = perform ConfigurableOperation {defaultOk = 0, defaultErr = "error"} in Operation.succeed { value = 100 }
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "result" }
        assertTrue(binding.inferredType is FunkType.IntType)
    }

    @Test
    fun `test multiple effects each with multiple associated types`() {
        val code = """
            effect Operation having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Operation>
                fail: {error: 'err} -> 'err <Operation>
            }

            effect State having 'state, 'change where {
                get: {} -> 'state <State>
                update: {delta: 'change} -> {} <State>
            }

            impl Operation for IntStringOperation having 'ok = Int, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            impl State for IntFloatState having 'state = Int, 'change = Float where {
                get = {} -> 0
                update = {delta} -> {}
            }

            let combined = perform IntStringOperation, IntFloatState in do
                let r = Operation.succeed { value = 42 }
                let s = State.get {}
                r
        """.trimIndent()

        val program = typeCheck(code)
        assertNotNull(program)

        val binding = program.items.filterIsInstance<LetBinding>().first { it.variableName == "combined" }
        assertTrue(binding.inferredType is FunkType.IntType)
    }
}
