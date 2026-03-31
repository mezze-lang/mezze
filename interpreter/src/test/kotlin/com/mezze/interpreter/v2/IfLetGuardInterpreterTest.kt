package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Integration tests for if-let guards in match expressions.
 * Tests that the interpreter correctly evaluates if-let bindings.
 */
class IfLetGuardInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `if let extracts value from Some`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> 0

            test {key = 1}
        """.trimIndent()

        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun `if let falls through to next case on None`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> -1

            test {key = 2}
        """.trimIndent()

        val result = eval(code)
        assertEquals(-1L, result)
    }

    @Test
    fun `if let with nested record pattern`() {
        val code = """
            let getPerson: {id: Int} -> | Some {val: {name: Str, age: Int}}, None | = {id} ->
                match id is
                    1 -> Some {val = {name = "Alice", age = 30}}
                    _ -> None

            let test = {id: Int} ->
                match id is
                    i if let Some {val = {name, age}} = getPerson {id = i} -> age
                    _ -> 0

            test {id = 1}
        """.trimIndent()

        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `if let with main pattern binding`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = k} -> k + v
                    _ -> 0

            test {x = 1}
        """.trimIndent()

        // Both k (from main pattern) and v (from if-let) should be accessible
        val result = eval(code)
        assertEquals(101L, result)  // 1 + 100
    }

    @Test
    fun `if let with global option variable`() {
        val code = """
            let globalOption: | Some {val: Int}, None | = Some {val = 42}

            let test = {x: Int} ->
                match x is
                    n if let Some {val = v} = globalOption -> n + v
                    _ -> 0

            test {x = 10}
        """.trimIndent()

        val result = eval(code)
        assertEquals(52L, result)  // 10 + 42
    }

    @Test
    fun `if let with field access`() {
        val code = """
            let container: {value: | Some {val: Int}, None |} = {
                value = Some {val = 42}
            }

            let test = {x: Int} ->
                match x is
                    n if let Some {val = v} = container.value -> n + v
                    _ -> 0

            test {x = 8}
        """.trimIndent()

        val result = eval(code)
        assertEquals(50L, result)  // 8 + 42
    }

    @Test
    fun `multiple match cases with if let`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    2 -> Some {val = 200}
                    _ -> None

            let test = {x: Int} ->
                match x is
                    0 -> -1
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> 0

            let r1 = test {x = 0}
            let r2 = test {x = 1}
            let r3 = test {x = 2}
            let r4 = test {x = 3}

            r1 + r2 + r3 + r4
        """.trimIndent()

        // r1 = -1, r2 = 100, r3 = 200, r4 = 0
        val result = eval(code)
        assertEquals(299L, result)
    }
}
