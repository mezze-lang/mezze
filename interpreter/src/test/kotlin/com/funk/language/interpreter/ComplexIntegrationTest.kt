package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ComplexIntegrationTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    // TODO: These tests are commented out because they use advanced features not yet fully implemented
    // Uncomment and fix as more language features are implemented

    /*
    @Test
    fun `test variant-based state machine`() {
        val source = """
            let initialState = Idle {}
            
            let transition = {state, event} ->
                match state {
                    | Idle {} -> 
                        match event {
                            | Start {} -> Running {progress = 0}
                            | _ -> state
                        }
                    | Running {progress} ->
                        match event {
                            | Progress {amount} -> Running {progress = progress + amount}
                            | Complete {} -> Done {result = "finished"}
                            | Stop {} -> Idle {}
                            | _ -> state
                        }
                    | Done {result} ->
                        match event {
                            | Reset {} -> Idle {}
                            | _ -> state
                        }
                }
                
            let state1 = transition {state = initialState, event = Start {}}
            let state2 = transition {state = state1, event = Progress {amount = 50}}
            let state3 = transition {state = state2, event = Complete {}}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val state1 = variables["state1"] as FunkVariant
        assertEquals("Running", state1.constructor)
        
        val state2 = variables["state2"] as FunkVariant
        assertEquals("Running", state2.constructor)
        
        val state3 = variables["state3"] as FunkVariant
        assertEquals("Done", state3.constructor)
    }

    @Test
    fun `test recursive data structure processing`() {
        val source = """
            let sumList = {list} ->
                match list {
                    | Empty {} -> 0
                    | Node {value, next} -> value + sumList {list = next}
                }
                
            let list = Node {value = 1, next = Node {value = 2, next = Node {value = 3, next = Empty {}}}}
            let total = sumList {list = list}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(6L, (variables["total"] as FunkInt).value)
    }

    @Test
    fun `test higher-order functions with complex data`() {
        val source = """
            let map = {fn, list} ->
                match list {
                    | Empty {} -> Empty {}
                    | Node {value, next} -> Node {value = fn {x = value}, next = map {fn = fn, list = next}}
                }
                
            let double = {x} -> x * 2
            let list = Node {value = 1, next = Node {value = 2, next = Empty {}}}
            let doubled = map {fn = double, list = list}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val result = variables["doubled"] as FunkVariant
        assertEquals("Node", result.constructor)
    }

    @Test
    fun `test complex record manipulation with functions`() {
        val source = """
            let updatePerson = {person, changes} ->
                {person |
                    name = if changes.updateName then changes.newName else person.name,
                    age = if changes.updateAge then changes.newAge else person.age
                }
                
            let person = {name = "Alice", age = 30}
            let changes = {updateName = true, newName = "Alicia", updateAge = false, newAge = 25}
            let updated = updatePerson {person = person, changes = changes}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val updated = variables["updated"] as FunkRecord
        assertEquals("Alicia", (updated.fields["name"] as FunkString).value)
        assertEquals(30L, (updated.fields["age"] as FunkInt).value)
    }

    @Test
    fun `test functional programming patterns`() {
        val source = """
            let compose = {f, g} -> {x} -> f {y = g {x = x}}
            let add1 = {x} -> x + 1
            let mult2 = {x} -> x * 2
            let add1ThenMult2 = compose {f = mult2, g = add1}
            let result = add1ThenMult2 {x = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(12L, (variables["result"] as FunkInt).value) // (5 + 1) * 2 = 12
    }

    @Test
    fun `test complex data processing pipeline`() {
        val source = """
            let processData = {data} ->
                data 
                |> {x} -> x * 2
                |> {x} -> x + 10
                |> {x} -> if x > 50 then "high" else "low"
                
            let result1 = processData {data = 15}
            let result2 = processData {data = 25}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("low", (variables["result1"] as FunkString).value)  // 15 * 2 + 10 = 40 -> "low"
        assertEquals("high", (variables["result2"] as FunkString).value) // 25 * 2 + 10 = 60 -> "high"
    }

    @Test
    fun `test error handling with variants and complex logic`() {
        val source = """
            let divide = {a, b} ->
                if b == 0
                then Error {message = "Division by zero"}
                else Ok {value = a / b}
                
            let processResult = {result} ->
                match result {
                    | Ok {value} -> value * 2
                    | Error {message} -> -1
                }
                
            let result1 = divide {a = 10, b = 2}
            let processed1 = processResult {result = result1}
            
            let result2 = divide {a = 10, b = 0}
            let processed2 = processResult {result = result2}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(10L, (variables["processed1"] as FunkInt).value) // (10/2) * 2 = 10
        assertEquals(-1L, (variables["processed2"] as FunkInt).value) // Error case
    }
    */
}
