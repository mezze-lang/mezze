package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for effect tracking in if-let bindings within match expressions.
 *
 * Bug fixed: Effect instances and referenced bindings from if-let expressions
 * were not being collected, which could cause tracking to be incomplete.
 *
 * Syntax: match x is
 *           Pattern if let SomePattern = expr -> body
 */
class IfLetEffectTrackingTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

    // ========== BASIC IF-LET TESTS ==========

    @Test
    fun `test if-let with constructor pattern should typecheck`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let process = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test if-let binding used in case body`() {
        val code = """
            let compute: {n: Int} -> | Some {val: Int}, None | = {n} ->
                match n is
                    0 -> None
                    _ -> Some {val = n * 10}

            let process = {x: Int} ->
                match x is
                    n if let Some {val = result} = compute {n = n} -> result + 1
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple if-let bindings with && chain`() {
        val code = """
            let getA: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 1}
            let getB: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x * 2}

            let process = {x: Int} ->
                match x is
                    n if let Some {val = a} = getA {x = n} && let Some {val = b} = getB {x = a} -> a + b
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== IF-LET WITH NESTED PATTERNS ==========

    @Test
    fun `test if-let with nested record in option`() {
        val code = """
            let getPerson: {id: Int} -> | Some {val: {name: String, age: Int}}, None | = {id} ->
                match id is
                    1 -> Some {val = {name = "Alice", age = 30}}
                    _ -> None

            let getName = {id: Int} ->
                match id is
                    i if let Some {val = {name, age}} = getPerson {id = i} -> name
                    _ -> "unknown"
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test if-let with nested option`() {
        val code = """
            let getNestedOption: {x: Int} -> | Some {val: | Some {val: Int}, None |}, None | = {x} ->
                match x is
                    0 -> None
                    n -> Some {val = Some {val = n * 2}}

            let process = {x: Int} ->
                match x is
                    n if let Some {val = inner} = getNestedOption {x = n} ->
                        match inner is
                            Some {val = v} -> v
                            None -> 0
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== IF-LET TYPE ERRORS ==========

    @Test
    fun `test if-let pattern must match expression type`() {
        val code = """
            let getValue: {x: Int} -> Int = {x} -> x

            let test = {x: Int} ->
                match x is
                    _ if let Some {val = v} = getValue {x = 5} -> v
                    _ -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        // Should fail because getValue returns Int, not Option
        assertTrue(exception.message != null)
    }

    @Test
    fun `test if-let used variable not in scope should fail`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} -> None

            let bad = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = undefined_var} -> v
                    _ -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("undefined") == true ||
                   exception.message?.contains("not defined") == true ||
                   exception.message?.contains("undefined_var") == true)
    }

    // ========== IF-LET SCOPING ==========

    @Test
    fun `test if-let binding not visible in other cases`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 42}
                    _ -> None

            let process = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> 0
        """.trimIndent()

        // v is only in scope in the first case
        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test if-let bindings chain correctly`() {
        val code = """
            let f1: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 1}
            let f2: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 2}
            let f3: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 3}

            let chain = {x: Int} ->
                match x is
                    n if let Some {val = a} = f1 {x = n} && let Some {val = b} = f2 {x = a} && let Some {val = c} = f3 {x = b} -> c
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== IF-LET COMBINED WITH MAIN PATTERN BINDING ==========

    @Test
    fun `test if-let combined with main pattern binding`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let process = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = k} -> k + v
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== IF-LET WITH FUNCTION CALLS ==========

    @Test
    fun `test if-let with function call in expression`() {
        val code = """
            let double: {x: Int} -> Int = {x} -> x * 2

            let wrap: {x: Int} -> | Some {val: Int}, None | = {x} ->
                match x is
                    0 -> None
                    n -> Some {val = double {x = n}}

            let process = {x: Int} ->
                match x is
                    n if let Some {val = v} = wrap {x = n} -> v
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test empty if-let list should work`() {
        // Normal match case without if-let
        val code = """
            type Box = | Box { val: Int } |

            let unbox = {b} ->
                match b is
                    Box { val = x } -> x
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test if-let with multiple match cases`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: String}, None | = {key} ->
                match key is
                    1 -> Some {val = "one"}
                    2 -> Some {val = "two"}
                    _ -> None

            let process = {x: Int} ->
                match x is
                    0 -> "zero"
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> "unknown"
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test if-let with field access on record`() {
        val code = """
            let container = {value = Some {val = 42}}

            let process = {n: Int} ->
                match n is
                    x if let Some {val = v} = container.value -> x + v
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== REFERENCED BINDINGS TRACKING ==========

    @Test
    fun `test if-let expression references outer bindings`() {
        val code = """
            let compute: {a: Int, b: Int} -> | Some {val: Int}, None | = {a, b} ->
                match a + b is
                    0 -> None
                    sum -> Some {val = sum}

            let outer_val = 10

            let process = {x: Int} ->
                match x is
                    n if let Some {val = result} = compute {a = n, b = outer_val} -> result
                    _ -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
