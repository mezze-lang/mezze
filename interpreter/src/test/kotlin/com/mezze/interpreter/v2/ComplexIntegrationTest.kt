package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for complex integration scenarios.
 * Migrated from V1 to V2 with unboxed values.
 */
class ComplexIntegrationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // TODO: These tests are commented out because they use advanced features not yet fully implemented
    // Uncomment and fix as more language features are implemented

    /*
    @Test
    fun `test variant-based state machine`() {
        val code = """
            type State = | Idle, Running { progress: Int }, Done { result: String } |
            type Event = | Start, Progress { amount: Int }, Complete, Stop, Reset |

            let initialState: State = Idle

            let transition = {state: State, event: Event} ->
                match state is
                    Idle ->
                        match event is
                            Start -> Running {progress = 0}
                            _ -> state
                    Running {progress} ->
                        match event is
                            Progress {amount} -> Running {progress = progress + amount}
                            Complete -> Done {result = "finished"}
                            Stop -> Idle
                            _ -> state
                    Done {result} ->
                        match event is
                            Reset -> Idle
                            _ -> state

            let state1 = transition {state = initialState, event = Start}
            let state2 = transition {state = state1, event = Progress {amount = 50}}
            let state3 = transition {state = state2, event = Complete}

            match state3 is
                Done {result} -> result
                _ -> "not done"
        """
        val result = eval(code)
        assertEquals("finished", result)
    }

    @Test
    fun `test recursive data structure processing`() {
        val code = """
            type IntList = | Empty, Node { value: Int, next: IntList } |

            let sumList = {list: IntList} -> match list is
                Empty -> 0
                Node {value, next} -> value + sumList {list = next}

            let list: IntList = Node {value = 1, next = Node {value = 2, next = Node {value = 3, next = Empty}}}
            sumList {list = list}
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    @Test
    fun `test higher-order functions with complex data`() {
        val code = """
            type IntList = | Empty, Node { value: Int, next: IntList } |

            let map = {fn, list: IntList} -> match list is
                Empty -> Empty
                Node {value, next} -> Node {value = fn {x = value}, next = map {fn = fn, list = next}}

            let double = {x} -> x * 2
            let list: IntList = Node {value = 1, next = Node {value = 2, next = Empty}}
            let doubled = map {fn = double, list = list}

            match doubled is
                Node {value, next} -> value
                Empty -> -1
        """
        val result = eval(code)
        assertEquals(2L, result) // First element is 1 * 2 = 2
    }

    @Test
    fun `test complex record manipulation with functions`() {
        val code = """
            type Person = {name: Str, age: Int}
            type Changes = {updateName: Bool, newName: Str, updateAge: Bool, newAge: Int}

            let updatePerson = {person: Person, changes: Changes} ->
                {...person,
                    name = if changes.updateName then changes.newName else person.name,
                    age = if changes.updateAge then changes.newAge else person.age
                }

            let person: Person = {name = "Alice", age = 30}
            let changes: Changes = {updateName = True, newName = "Alicia", updateAge = False, newAge = 25}
            let updated = updatePerson {person = person, changes = changes}
            updated.name
        """
        val result = eval(code)
        assertEquals("Alicia", result)
    }

    @Test
    fun `test functional programming patterns`() {
        val code = """
            let compose = {f, g} -> {x} -> f {y = g {x = x}}
            let add1 = {x} -> x + 1
            let mult2 = {y} -> y * 2
            let add1ThenMult2 = compose {f = mult2, g = add1}
            add1ThenMult2 {x = 5}
        """
        val result = eval(code)
        assertEquals(12L, result) // (5 + 1) * 2 = 12
    }

    @Test
    fun `test complex data processing pipeline - low`() {
        val code = """
            let step1 = {x} -> x * 2
            let step2 = {x} -> x + 10
            let step3 = {x} -> if x > 50 then "high" else "low"

            let processData = {data} ->
                data |> step1 {x = @} |> step2 {x = @} |> step3 {x = @}

            processData {data = 15}
        """
        val result = eval(code)
        assertEquals("low", result)  // 15 * 2 + 10 = 40 -> "low"
    }

    @Test
    fun `test complex data processing pipeline - high`() {
        val code = """
            let step1 = {x} -> x * 2
            let step2 = {x} -> x + 10
            let step3 = {x} -> if x > 50 then "high" else "low"

            let processData = {data} ->
                data |> step1 {x = @} |> step2 {x = @} |> step3 {x = @}

            processData {data = 25}
        """
        val result = eval(code)
        assertEquals("high", result) // 25 * 2 + 10 = 60 -> "high"
    }

    @Test
    fun `test error handling with variants and complex logic - success`() {
        val code = """
            type DivResult = | Ok { value: Int }, Error { message: String } |

            let divide = {a, b} ->
                if b == 0
                then Error {message = "Division by zero"}
                else Ok {value = a / b}

            let processResult = {result: DivResult} -> match result is
                Ok {value} -> value * 2
                Error {message} -> -1

            let result1 = divide {a = 10, b = 2}
            processResult {result = result1}
        """
        val result = eval(code)
        assertEquals(10L, result) // (10/2) * 2 = 10
    }

    @Test
    fun `test error handling with variants and complex logic - error`() {
        val code = """
            type DivResult = | Ok { value: Int }, Error { message: String } |

            let divide = {a, b} ->
                if b == 0
                then Error {message = "Division by zero"}
                else Ok {value = a / b}

            let processResult = {result: DivResult} -> match result is
                Ok {value} -> value * 2
                Error {message} -> -1

            let result2 = divide {a = 10, b = 0}
            processResult {result = result2}
        """
        val result = eval(code)
        assertEquals(-1L, result) // Error case
    }
    */
}
