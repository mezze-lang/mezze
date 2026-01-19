package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled

/**
 * Tests for runtime behavior of effects with associated types
 *
 * ENABLED: Continuation/Delimited now implemented!
 */
class EffectAssociatedTypesInterpreterTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        // Set project root for stdlib module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
        // Clear module cache for fresh resolution
        com.funk.language.typechecker.ModuleResolver.clearCache()
    }

    private fun execute(code: String): FunkValue? {
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    private fun executeWithContext(code: String): Map<String, FunkValue> {
        val program = parser.parse(code)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    @Test
    fun `test state effect with Int executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42
                put = {value} -> {}
            }

            let result = perform Counter in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test state effect with String executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for StringState having 'state = String where {
                get = {} -> "hello world"
            }

            let result = perform StringState in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals("hello world", (context["result"] as FunkString).value)
    }

    @Test
    fun `test state effect put operation executes correctly`() {
        val code = """
            effect State having 'state where {
                put: {value: 'state} -> {} <State>
            }

            impl State for IntState having 'state = Int where {
                put = {value} -> {}
            }

            let result = perform IntState in State.put { value = 123 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue(context["result"] is FunkRecord)
        val record = context["result"] as FunkRecord
        assertTrue(record.fields.isEmpty())
    }

    @Test
    fun `test reader effect with associated type executes correctly`() {
        val code = """
            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl Reader for StringReader having 'env = String where {
                ask = {} -> "config value"
            }

            let result = perform StringReader in Reader.ask {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals("config value", (context["result"] as FunkString).value)
    }

    @Test
    fun `test state effect with List type executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ListState having 'state = [Int] where {
                get = {} -> [1, 2, 3]
            }

            let result = perform ListState in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue(context["result"] is FunkArrayList)
        val list = context["result"] as FunkArrayList
        assertEquals(3, list.elements.size)
        assertEquals(1L, (list.elements[0] as FunkInt).value)
        assertEquals(2L, (list.elements[1] as FunkInt).value)
        assertEquals(3L, (list.elements[2] as FunkInt).value)
    }

    @Test
    fun `test combined get and put operations execute correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 10
                put = {value} -> {}
            }

            let result = perform Counter in do
                let x = State.get {}
                State.put { value = x + 5 }
                x
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(10L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test multiple effects with different associated types execute correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 42
            }

            impl Reader for StringReader having 'env = String where {
                ask = {} -> "hello"
            }

            let result = perform IntState, StringReader in do
                let num = State.get {}
                let str = Reader.ask {}
                num
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test state effect with record type executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for RecordState having 'state = {name: String, age: Int} where {
                get = {} -> {name = "Alice", age = 30}
            }

            let result = perform RecordState in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue(context["result"] is FunkRecord)
        val record = context["result"] as FunkRecord
        assertEquals("Alice", (record.fields["name"] as FunkString).value)
        assertEquals(30L, (record.fields["age"] as FunkInt).value)
    }

    @Test
    fun `test state effect in function executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 100
            }

            let getState = {} -> perform IntState in State.get {}
            let result = getState {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(100L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test state effect with Bool type executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for BoolState having 'state = Bool where {
                get = {} -> true
                put = {value} -> {}
            }

            let result = perform BoolState in do
                let flag = State.get {}
                State.put { value = false }
                flag
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue((context["result"] as FunkBool).value)
    }

    @Test
    fun `test stateful operations with get modify put sequence`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 10
                put = {value} -> {}
            }

            let result = perform Counter in do
                let x = State.get {}
                let y = x + 5
                State.put { value = y }
                y
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(15L, (context["result"] as FunkInt).value)
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

            let result = perform ConfigState {initial = 999} in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(999L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test nested perform blocks with associated types`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl State for IntState having 'state = Int where {
                get = {} -> 10
            }

            impl Reader for StringReader having 'env = String where {
                ask = {} -> "config"
            }

            let result = perform IntState, StringReader in do
                let num = State.get {}
                let str = Reader.ask {}
                num
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(10L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test transform effect with same type in parameter and return`() {
        val code = """
            effect Transform having 'a where {
                transform: {input: 'a} -> 'a <Transform>
            }

            impl Transform for Doubler having 'a = Int where {
                transform = {input} -> input + input
            }

            let result = perform Doubler in Transform.transform { input = 21 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test collection effect with list of associated type`() {
        val code = """
            effect Collection having 'item where {
                getAll: {} -> ['item] <Collection>
            }

            impl Collection for IntCollection having 'item = Int where {
                getAll = {} -> [10, 20, 30]
            }

            let result = perform IntCollection in Collection.getAll {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue(context["result"] is FunkArrayList)
        val list = context["result"] as FunkArrayList
        assertEquals(3, list.elements.size)
        assertEquals(10L, (list.elements[0] as FunkInt).value)
        assertEquals(20L, (list.elements[1] as FunkInt).value)
        assertEquals(30L, (list.elements[2] as FunkInt).value)
    }

    @Test
    fun `test nested type Option with associated type`() {
        val code = """
            effect State having 'state where {
                get: {} -> (Option 'state) <State>
            }

            impl State for MaybeIntState having 'state = Int where {
                get = {} -> Some { val = 100 }
            }

            let result = perform MaybeIntState in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue(context["result"] is FunkVariant)
        val variant = context["result"] as FunkVariant
        assertEquals("Some", variant.constructor)
    }

    @Test
    fun `test record with associated type field executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> {value: 'state, id: Int} <State>
            }

            impl State for RecordState having 'state = String where {
                get = {} -> {value = "data", id = 123}
            }

            let result = perform RecordState in State.get {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue(context["result"] is FunkRecord)
        val record = context["result"] as FunkRecord
        assertEquals("data", (record.fields["value"] as FunkString).value)
        assertEquals(123L, (record.fields["id"] as FunkInt).value)
    }

    @Test
    fun `test multiple operations with same associated type execute correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
                put: {value: 'state} -> {} <State>
                modify: {delta: Int} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 50
                put = {value} -> {}
                modify = {delta} -> 50 + delta
            }

            let result = perform Counter in do
                let x = State.get {}
                let y = State.modify { delta = 10 }
                State.put { value = y }
                y
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(60L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test effect with associated type in function context`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for StringState having 'state = String where {
                get = {} -> "from function"
            }

            let getFromState = {} -> perform StringState in State.get {}
            let result = getFromState {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals("from function", (context["result"] as FunkString).value)
    }

    @Test
    fun `test multiple different effects with different associated types in sequence`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>
            }

            effect Reader having 'env where {
                ask: {} -> 'env <Reader>
            }

            impl State for BoolState having 'state = Bool where {
                get = {} -> true
            }

            impl Reader for IntReader having 'env = Int where {
                ask = {} -> 777
            }

            let combined = perform BoolState, IntReader in do
                let flag = State.get {}
                let num = Reader.ask {}
                num
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(777L, (context["combined"] as FunkInt).value)
    }

    // ===== Tests for Multiple Associated Types =====

    @Test
    fun `test effect with two associated types executes correctly`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            impl Result for IntStringResult having 'ok = Int, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let successVal = perform IntStringResult in Result.succeed { value = 42 }
            let failVal = perform IntStringResult in Result.fail { error = "oops" }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["successVal"] as FunkInt).value)
        assertEquals("oops", (context["failVal"] as FunkString).value)
    }

    @Test
    fun `test effect with three associated types executes correctly`() {
        val code = """
            effect Triple having 'a, 'b, 'c where {
                getFirst: {} -> 'a <Triple>
                getSecond: {} -> 'b <Triple>
                getThird: {} -> 'c <Triple>
            }

            impl Triple for IntStringBoolTriple having 'a = Int, 'b = String, 'c = Bool where {
                getFirst = {} -> 100
                getSecond = {} -> "hello"
                getThird = {} -> true
            }

            let a = perform IntStringBoolTriple in Triple.getFirst {}
            let b = perform IntStringBoolTriple in Triple.getSecond {}
            let c = perform IntStringBoolTriple in Triple.getThird {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(100L, (context["a"] as FunkInt).value)
        assertEquals("hello", (context["b"] as FunkString).value)
        assertTrue((context["c"] as FunkBool).value)
    }

    @Test
    fun `test multiple associated types in perform block`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            impl Result for BoolFloatResult having 'ok = Bool, 'err = Float where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let result = perform BoolFloatResult in do
                let success = Result.succeed { value = true }
                let failure = Result.fail { error = 3.14 }
                success
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue((context["result"] as FunkBool).value)
    }

    @Test
    fun `test type substitution for each associated type executes correctly`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            impl Result for StringIntResult having 'ok = String, 'err = Int where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let s = perform StringIntResult in Result.succeed { value = "success" }
            let e = perform StringIntResult in Result.fail { error = 404 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals("success", (context["s"] as FunkString).value)
        assertEquals(404L, (context["e"] as FunkInt).value)
    }

    @Test
    fun `test complex types as multiple associated type bindings execute correctly`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            impl Result for ComplexResult having 'ok = Int, 'err = {code: Int, message: String} where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let num = perform ComplexResult in Result.succeed { value = 42 }
            let err = perform ComplexResult in Result.fail { error = {code = 500, message = "error"} }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["num"] as FunkInt).value)

        assertTrue(context["err"] is FunkRecord)
        val err = context["err"] as FunkRecord
        assertEquals(500L, (err.fields["code"] as FunkInt).value)
        assertEquals("error", (err.fields["message"] as FunkString).value)
    }

    @Test
    fun `test multiple effects each with multiple associated types execute correctly`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            effect State having 'state, 'change where {
                get: {} -> 'state <State>
                update: {delta: 'change} -> 'state <State>
            }

            impl Result for IntStringResult having 'ok = Int, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            impl State for IntFloatState having 'state = Int, 'change = Float where {
                get = {} -> 100
                update = {delta} -> 100
            }

            let combined = perform IntStringResult, IntFloatState in do
                let r = Result.succeed { value = 42 }
                let s = State.get {}
                let u = State.update { delta = 3.14 }
                r
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["combined"] as FunkInt).value)
    }

    @Test
    fun `test multiple associated types with performer parameters execute correctly`() {
        val code = """
            effect State having 'state, 'error where {
                get: {} -> 'state <State>
                getError: {} -> 'error <State>
            }

            impl State for ConfigurableState {initialState: Int, defaultError: String} having 'state = Int, 'error = String where {
                get = {} -> initialState
                getError = {} -> defaultError
            }

            let s = perform ConfigurableState {initialState = 100, defaultError = "none"} in State.get {}
            let e = perform ConfigurableState {initialState = 100, defaultError = "error"} in State.getError {}
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(100L, (context["s"] as FunkInt).value)
        assertEquals("error", (context["e"] as FunkString).value)
    }

    @Test
    fun `test nested operations with multiple associated types execute correctly`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            impl Result for IntStringResult having 'ok = Int, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let compute = {x: Int} -> perform IntStringResult in do
                let y = Result.succeed { value = x + 10 }
                y

            let result = compute { x = 32 }
        """.trimIndent()

        val context = executeWithContext(code)
        assertEquals(42L, (context["result"] as FunkInt).value)
    }

    @Test
    fun `test multiple associated types in conditional logic execute correctly`() {
        val code = """
            effect Result having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>
                fail: {error: 'err} -> 'err <Result>
            }

            impl Result for BoolStringResult having 'ok = Bool, 'err = String where {
                succeed = {value} -> value
                fail = {error} -> error
            }

            let check = {flag: Bool} -> if flag then
                perform BoolStringResult in Result.succeed { value = true }
            else
                perform BoolStringResult in Result.succeed { value = false }

            let result = check { flag = true }
        """.trimIndent()

        val context = executeWithContext(code)
        assertTrue((context["result"] as FunkBool).value)
    }
}
