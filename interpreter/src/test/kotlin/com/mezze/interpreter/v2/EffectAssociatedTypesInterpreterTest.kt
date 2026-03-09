package com.mezze.interpreter.v2

import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeVariant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for runtime behavior of effects with associated types
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class EffectAssociatedTypesInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test state effect with Int executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 42,
                put = {value} -> {}
            }

            perform Counter in State.get {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
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

            perform StringState in State.get {}
        """.trimIndent()

        val result = eval(code)
        assertEquals("hello world", result)
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

            perform IntState in State.put { value = 123 }
        """.trimIndent()

        val result = eval(code)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord
        // Empty record (unit)
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

            perform StringReader in Reader.ask {}
        """.trimIndent()

        val result = eval(code)
        assertEquals("config value", result)
    }

    @Test
    fun `test state effect with List type executes correctly - length`() {
        val code = """
            use std::collections::seq

            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ListState having 'state = [Int] where {
                get = {} -> [1, 2, 3]
            }

            let xs = perform ListState in State.get {}
            seq::length { xs = xs }
        """.trimIndent()

        assertEquals(3L, eval(code))
    }

    @Test
    fun `test state effect with List type executes correctly - first element`() {
        val code = """
            use std::collections::seq

            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ListState having 'state = [Int] where {
                get = {} -> [1, 2, 3]
            }

            let xs = perform ListState in State.get {}
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(1L, eval(code))
    }

    @Test
    fun `test state effect with List type executes correctly - second element`() {
        val code = """
            use std::collections::seq

            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ListState having 'state = [Int] where {
                get = {} -> [1, 2, 3]
            }

            let xs = perform ListState in State.get {}
            match seq::get { xs = xs, i = 1 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(2L, eval(code))
    }

    @Test
    fun `test state effect with List type executes correctly - third element`() {
        val code = """
            use std::collections::seq

            effect State having 'state where {
                get: {} -> 'state <State>
            }

            impl State for ListState having 'state = [Int] where {
                get = {} -> [1, 2, 3]
            }

            let xs = perform ListState in State.get {}
            match seq::get { xs = xs, i = 2 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(3L, eval(code))
    }

    @Test
    fun `test combined get and put operations execute correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 10,
                put = {value} -> {}
            }

            let getResult = {} -> perform Counter in do
                let x = State.get {}
                State.put { value = x + 5 }
                x

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(10L, result)
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

            let getResult = {} -> perform IntState, StringReader in do
                let num = State.get {}
                let str = Reader.ask {}
                num

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
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

            perform RecordState in State.get {}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("Alice", result.getField("name"))
        assertEquals(30L, result.getField("age"))
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
            let getResult = {} -> getState {}

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `test state effect with Bool type executes correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for BoolState having 'state = Bool where {
                get = {} -> True,
                put = {value} -> {}
            }

            let getResult = {} -> perform BoolState in do
                let flag = State.get {}
                State.put { value = False }
                flag

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test stateful operations with get modify put sequence`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 10,
                put = {value} -> {}
            }

            let getResult = {} -> perform Counter in do
                let x = State.get {}
                let y = x + 5
                State.put { value = y }
                y

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(15L, result)
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

            perform ConfigState {initial = 999} in State.get {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(999L, result)
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

            let getResult = {} -> perform IntState, StringReader in do
                let num = State.get {}
                let str = Reader.ask {}
                num

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(10L, result)
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

            perform Doubler in Transform.transform { input = 21 }
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test collection effect with list of associated type - length`() {
        val code = """
            use std::collections::seq

            effect Collection having 'item where {
                getAll: {} -> ['item] <Collection>
            }

            impl Collection for IntCollection having 'item = Int where {
                getAll = {} -> [10, 20, 30]
            }

            let xs = perform IntCollection in Collection.getAll {}
            seq::length { xs = xs }
        """.trimIndent()

        assertEquals(3L, eval(code))
    }

    @Test
    fun `test collection effect with list of associated type - first element`() {
        val code = """
            use std::collections::seq

            effect Collection having 'item where {
                getAll: {} -> ['item] <Collection>
            }

            impl Collection for IntCollection having 'item = Int where {
                getAll = {} -> [10, 20, 30]
            }

            let xs = perform IntCollection in Collection.getAll {}
            match seq::get { xs = xs, i = 0 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(10L, eval(code))
    }

    @Test
    fun `test collection effect with list of associated type - second element`() {
        val code = """
            use std::collections::seq

            effect Collection having 'item where {
                getAll: {} -> ['item] <Collection>
            }

            impl Collection for IntCollection having 'item = Int where {
                getAll = {} -> [10, 20, 30]
            }

            let xs = perform IntCollection in Collection.getAll {}
            match seq::get { xs = xs, i = 1 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(20L, eval(code))
    }

    @Test
    fun `test collection effect with list of associated type - third element`() {
        val code = """
            use std::collections::seq

            effect Collection having 'item where {
                getAll: {} -> ['item] <Collection>
            }

            impl Collection for IntCollection having 'item = Int where {
                getAll = {} -> [10, 20, 30]
            }

            let xs = perform IntCollection in Collection.getAll {}
            match seq::get { xs = xs, i = 2 } is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        assertEquals(30L, eval(code))
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

            perform MaybeIntState in State.get {}
        """.trimIndent()

        val result = eval(code) as MezzeVariant
        assertEquals("Some", result.constructorName)
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

            perform RecordState in State.get {}
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("data", result.getField("value"))
        assertEquals(123L, result.getField("id"))
    }

    @Test
    fun `test multiple operations with same associated type execute correctly`() {
        val code = """
            effect State having 'state where {
                get: {} -> 'state <State>,
                put: {value: 'state} -> {} <State>,
                modify: {delta: Int} -> 'state <State>
            }

            impl State for Counter having 'state = Int where {
                get = {} -> 50,
                put = {value} -> {},
                modify = {delta} -> 50 + delta
            }

            let getResult = {} -> perform Counter in do
                let x = State.get {}
                let y = State.modify { delta = 10 }
                State.put { value = y }
                y

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(60L, result)
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
            let getResult = {} -> getFromState {}

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals("from function", result)
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
                get = {} -> True
            }

            impl Reader for IntReader having 'env = Int where {
                ask = {} -> 777
            }

            let getCombined = {} -> perform BoolState, IntReader in do
                let flag = State.get {}
                let num = Reader.ask {}
                num

            getCombined {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(777L, result)
    }

    // ===== Tests for Multiple Associated Types =====

    @Test
    fun `test effect with two associated types executes correctly`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            impl Outcome for IntStringResult having 'ok = Int, 'err = String where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let successVal = perform IntStringResult in Outcome.succeed { value = 42 }
            let failVal = perform IntStringResult in Outcome.fail { error = "oops" }
            { success = successVal, failure = failVal }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(42L, result.getField("success"))
        assertEquals("oops", result.getField("failure"))
    }

    @Test
    fun `test effect with three associated types executes correctly`() {
        val code = """
            effect Triple having 'a, 'b, 'c where {
                getFirst: {} -> 'a <Triple>,
                getSecond: {} -> 'b <Triple>,
                getThird: {} -> 'c <Triple>
            }

            impl Triple for IntStringBoolTriple having 'a = Int, 'b = String, 'c = Bool where {
                getFirst = {} -> 100,
                getSecond = {} -> "hello",
                getThird = {} -> True
            }

            let a = perform IntStringBoolTriple in Triple.getFirst {}
            let b = perform IntStringBoolTriple in Triple.getSecond {}
            let c = perform IntStringBoolTriple in Triple.getThird {}
            { first = a, second = b, third = c }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(100L, result.getField("first"))
        assertEquals("hello", result.getField("second"))
        assertEquals(true, result.getField("third"))
    }

    @Test
    fun `test multiple associated types in perform block`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            impl Outcome for BoolFloatResult having 'ok = Bool, 'err = Float where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let getResult = {} -> perform BoolFloatResult in do
                let success = Outcome.succeed { value = True }
                let failure = Outcome.fail { error = 3.14 }
                success

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test type substitution for each associated type executes correctly`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            impl Outcome for StringIntResult having 'ok = String, 'err = Int where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let s = perform StringIntResult in Outcome.succeed { value = "success" }
            let e = perform StringIntResult in Outcome.fail { error = 404 }
            { success = s, error = e }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals("success", result.getField("success"))
        assertEquals(404L, result.getField("error"))
    }

    @Test
    fun `test complex types as multiple associated type bindings execute correctly`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            impl Outcome for ComplexResult having 'ok = Int, 'err = {code: Int, message: String} where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let num = perform ComplexResult in Outcome.succeed { value = 42 }
            let err = perform ComplexResult in Outcome.fail { error = {code = 500, message = "error"} }
            { num = num, err = err }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(42L, result.getField("num"))

        val err = result.getField("err") as MezzeRecord
        assertEquals(500L, err.getField("code"))
        assertEquals("error", err.getField("message"))
    }

    @Test
    fun `test multiple effects each with multiple associated types execute correctly`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            effect State having 'state, 'change where {
                get: {} -> 'state <State>,
                update: {delta: 'change} -> 'state <State>
            }

            impl Outcome for IntStringResult having 'ok = Int, 'err = String where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            impl State for IntFloatState having 'state = Int, 'change = Float where {
                get = {} -> 100,
                update = {delta} -> 100
            }

            let getCombined = {} -> perform IntStringResult, IntFloatState in do
                let r = Outcome.succeed { value = 42 }
                let s = State.get {}
                let u = State.update { delta = 3.14 }
                r

            getCombined {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test multiple associated types with performer parameters execute correctly`() {
        val code = """
            effect State having 'state, 'error where {
                get: {} -> 'state <State>,
                getError: {} -> 'error <State>
            }

            impl State for ConfigurableState {initialState: Int, defaultError: String} having 'state = Int, 'error = String where {
                get = {} -> initialState,
                getError = {} -> defaultError
            }

            let s = perform ConfigurableState {initialState = 100, defaultError = "none"} in State.get {}
            let e = perform ConfigurableState {initialState = 100, defaultError = "error"} in State.getError {}
            { state = s, error = e }
        """.trimIndent()

        val result = eval(code) as MezzeRecord
        assertEquals(100L, result.getField("state"))
        assertEquals("error", result.getField("error"))
    }

    @Test
    fun `test nested operations with multiple associated types execute correctly`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            impl Outcome for IntStringResult having 'ok = Int, 'err = String where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let compute = {x: Int} -> perform IntStringResult in do
                let y = Outcome.succeed { value = x + 10 }
                y

            let getResult = {} -> compute { x = 32 }

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test multiple associated types in conditional logic execute correctly`() {
        val code = """
            effect Outcome having 'ok, 'err where {
                succeed: {value: 'ok} -> 'ok <Result>,
                fail: {error: 'err} -> 'err <Result>
            }

            impl Outcome for BoolStringResult having 'ok = Bool, 'err = String where {
                succeed = {value} -> value,
                fail = {error} -> error
            }

            let check = {flag: Bool} -> if flag then
                perform BoolStringResult in Outcome.succeed { value = True }
            else
                perform BoolStringResult in Outcome.succeed { value = False }

            let getResult = {} -> check { flag = True }

            getResult {}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, result)
    }
}
