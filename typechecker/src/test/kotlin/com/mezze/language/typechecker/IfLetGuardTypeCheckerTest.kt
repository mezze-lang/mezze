package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Tests for if-let guards in match expressions.
 *
 * Syntax: match x is
 *           Pattern if let SomePattern = expr -> body
 *
 * The if-let guard is used when:
 * 1. You're matching on one value (the scrutinee)
 * 2. But want to conditionally bind another value based on a pattern match
 *
 * Common use case: Looking up a key in a map and extracting the value if found
 *   match key is
 *       k if let Some {val = v} = lookup {key = k} -> v  // Only if lookup succeeds
 *       _ -> default_value                                // Fallback
 */
class IfLetGuardTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String) {
        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        assertNotNull(result.program)
    }

    // ========== Basic if let Guards ==========

    @Test
    fun `if let with constructor pattern extracts bound variables`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: String}, None | = {key} ->
                match key is
                    1 -> Some {val = "one"}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v
                    _ -> "not found"

            let result = test {key = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `if let extracts value from option`() {
        val code = """
            let maybeCompute: {x: Int} -> | Some {val: Int}, None | = {x} ->
                match x is
                    0 -> None
                    n -> Some {val = n * 2}

            let test = {x: Int} ->
                match x is
                    n if let Some {val = result} = maybeCompute {x = n} -> result
                    _ -> 0

            let r = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `if let with nested record in option`() {
        val code = """
            let getPerson: {id: Int} -> | Some {val: {name: String, age: Int}}, None | = {id} ->
                match id is
                    1 -> Some {val = {name = "Alice", age = 30}}
                    _ -> None

            let test = {id: Int} ->
                match id is
                    i if let Some {val = {name, age}} = getPerson {id = i} -> name
                    _ -> "unknown"

            let result = test {id = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `if let with global option variable`() {
        val code = """
            let globalOption: | Some {val: Int}, None | = Some {val = 42}

            let test = {x: Int} ->
                match x is
                    n if let Some {val = v} = globalOption -> n + v
                    _ -> 0

            let result = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `if let with field access on option`() {
        val code = """
            let container: {value: | Some {val: Int}, None |} = {
                value = Some {val = 42}
            }

            let test = {x: Int} ->
                match x is
                    n if let Some {val = v} = container.value -> n + v
                    _ -> 0

            let result = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    // ========== Type Checking Errors ==========

    @Test
    fun `if let binding variable has correct type`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: String}, None | = {key} ->
                match key is
                    1 -> Some {val = "one"}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v + 1
                    _ -> "not found"
        """.trimIndent()

        // This should fail because v is String but we're trying to add 1 to it
        assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
    }

    @Test
    fun `if let pattern must match expression type`() {
        val code = """
            let getValue: {x: Int} -> Int = {x} -> x * 2

            let test = {x: Int} ->
                match x is
                    _ if let Some {val = v} = getValue {x = 5} -> v
                    _ -> 0
        """.trimIndent()

        // This should fail because getValue returns Int, not an Option type
        assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
    }

    // ========== Backward Compatibility ==========

    @Test
    fun `regular guard without if let still works`() {
        val code = """
            let check = {x: Int} ->
                match x is
                    n if n > 0 -> "positive"
                    _ -> "non-positive"
            let result = check {x = 5}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `match without any guard still works`() {
        val code = """
            let maybeValue: | Some {val: Int}, None | = Some {val = 42}

            let test = {} ->
                match maybeValue is
                    Some {val = v} -> v
                    None -> 0

            let result = test {}
        """.trimIndent()

        typeCheck(code)
    }

    // ========== Complex Cases ==========

    @Test
    fun `if let with nested option`() {
        val code = """
            let getNestedOption: {x: Int} -> | Some {val: | Some {val: Int}, None |}, None | = {x} ->
                match x is
                    0 -> None
                    _ -> Some {val = Some {val = 42}}

            let test = {x: Int} ->
                match x is
                    n if let Some {val = inner} = getNestedOption {x = n} ->
                        match inner is
                            Some {val = v} -> v
                            None -> -1
                    _ -> 0

            let result = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `if let combined with main pattern binding`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: Int}, None | = {key} ->
                match key is
                    1 -> Some {val = 100}
                    _ -> None

            let test = {x: Int} ->
                match x is
                    k if let Some {val = v} = lookup {key = k} -> k + v
                    _ -> 0

            let result = test {x = 1}
        """.trimIndent()

        // Both k (from main pattern) and v (from if-let) should be accessible
        typeCheck(code)
    }

    @Test
    fun `if let with multiple match cases`() {
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

            let result = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    // ========== Multiple If-Let Bindings with && ==========

    @Test
    fun `multiple if let bindings with && chain`() {
        val code = """
            let getA: {x: Int} -> | Some {val: Int}, None | = {x} ->
                match x is
                    1 -> Some {val = 10}
                    _ -> None

            let getB: {x: Int} -> | Some {val: Int}, None | = {x} ->
                match x is
                    10 -> Some {val = 20}
                    _ -> None

            let test = {x: Int} ->
                match x is
                    n if let Some {val = a} = getA {x = n} && let Some {val = b} = getB {x = a} -> a + b
                    _ -> 0

            let result = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `three if let bindings with && chain`() {
        val code = """
            let f1: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 1}
            let f2: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 2}
            let f3: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x + 3}

            let test = {x: Int} ->
                match x is
                    n if let Some {val = a} = f1 {x = n} && let Some {val = b} = f2 {x = a} && let Some {val = c} = f3 {x = b} -> a + b + c
                    _ -> 0

            let result = test {x = 1}
        """.trimIndent()

        typeCheck(code)
    }

    @Test
    fun `multiple if let bindings share scope`() {
        // Bindings from earlier if-lets should be visible in later if-lets
        val code = """
            let getA: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x * 2}
            let getB: {x: Int, y: Int} -> | Some {val: Int}, None | = {x, y} -> Some {val = x + y}

            let test = {n: Int} ->
                match n is
                    x if let Some {val = a} = getA {x = x} && let Some {val = b} = getB {x = a, y = x} -> b
                    _ -> 0

            let result = test {n = 5}
        """.trimIndent()

        typeCheck(code)
    }

    // ========== Exhaustiveness with If-Let ==========
    // Note: Exhaustiveness checking only applies to closed variant types.
    // Matching on Int/String/etc. doesn't require exhaustiveness.

    @Test
    fun `variant with if let is not exhaustive even if pattern covers all constructors`() {
        val code = """
            type Color = | Red, Blue |

            let check: {c: Color} -> | Some {val: Int}, None | = {c} ->
                match c is
                    Red -> Some {val = 1}
                    Blue -> None

            let test = {c: Color} ->
                match c is
                    Red if let Some {val = v} = check {c = Red} -> v
                    Blue if let Some {val = v} = check {c = Blue} -> v
        """.trimIndent()

        // Should fail - even though we cover Red and Blue, both have if-let which may not match
        assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
    }

    @Test
    fun `variant with if let plus unconditional fallback for same constructor is exhaustive`() {
        val code = """
            type Color = | Red, Blue |

            let check: {c: Color} -> | Some {val: Int}, None | = {c} ->
                match c is
                    Red -> Some {val = 1}
                    Blue -> None

            let test = {c: Color} ->
                match c is
                    Red if let Some {val = v} = check {c = Red} -> v
                    Red -> 0
                    Blue -> 0

            let result = test {c = Red}
        """.trimIndent()

        // Should pass - Red has if-let plus unconditional fallback, Blue is unconditional
        typeCheck(code)
    }

    @Test
    fun `variant with if let needs wildcard for exhaustiveness`() {
        val code = """
            type Status = | Active, Inactive, Pending |

            let check: {s: Status} -> | Some {val: Int}, None | = {s} ->
                match s is
                    Active -> Some {val = 1}
                    _ -> None

            let test = {s: Status} ->
                match s is
                    Active if let Some {val = v} = check {s = Active} -> v
                    Inactive -> 0
                    Pending -> 0
        """.trimIndent()

        // Should fail - Active is only covered conditionally (with if-let), no unconditional fallback
        assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
    }

    @Test
    fun `variant with wildcard after if let cases is exhaustive`() {
        val code = """
            type Status = | Active, Inactive, Pending |

            let check: {s: Status} -> | Some {val: Int}, None | = {s} ->
                match s is
                    Active -> Some {val = 1}
                    _ -> None

            let test = {s: Status} ->
                match s is
                    Active if let Some {val = v} = check {s = Active} -> v
                    _ -> 0

            let result = test {s = Active}
        """.trimIndent()

        // Should pass - wildcard provides fallback
        typeCheck(code)
    }

    @Test
    fun `variant with multiple if let bindings requires wildcard`() {
        val code = """
            type Wrapper = | Value { n: Int } |

            let f1: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x}
            let f2: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x}

            let test = {w: Wrapper} ->
                match w is
                    Value { n } if let Some {val = a} = f1 {x = n} && let Some {val = b} = f2 {x = a} -> a + b
        """.trimIndent()

        // Should fail - if-let bindings may not match
        assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
    }

    @Test
    fun `variant with multiple if let bindings and wildcard is exhaustive`() {
        val code = """
            type Wrapper = | Value { n: Int } |

            let f1: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x}
            let f2: {x: Int} -> | Some {val: Int}, None | = {x} -> Some {val = x}

            let test = {w: Wrapper} ->
                match w is
                    Value { n } if let Some {val = a} = f1 {x = n} && let Some {val = b} = f2 {x = a} -> a + b
                    _ -> 0

            let result = test {w = Value {n = 5}}
        """.trimIndent()

        // Should pass - wildcard provides fallback
        typeCheck(code)
    }

    // ========== Error Messages with Source Locations ==========

    @Test
    fun `if let type mismatch error has source location`() {
        val code = """
            let getValue: {x: Int} -> Int = {x} -> x * 2

            let test = {x: Int} ->
                match x is
                    _ if let Some {val = v} = getValue {x = 5} -> v
                    _ -> 0
        """.trimIndent()

        // Should fail because getValue returns Int, not Option
        val error = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
        assertNotNull(error.sourceLocation, "Error should have source location")
    }

    @Test
    fun `if let binding variable type error has source location`() {
        val code = """
            let lookup: {key: Int} -> | Some {val: String}, None | = {key} ->
                match key is
                    1 -> Some {val = "one"}
                    _ -> None

            let test = {key: Int} ->
                match key is
                    k if let Some {val = v} = lookup {key = k} -> v + 1
                    _ -> 0
        """.trimIndent()

        // Should fail because v is String and we're adding Int
        val error = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
        assertNotNull(error.sourceLocation, "Error should have source location")
    }

    @Test
    fun `exhaustiveness error for if let has source location`() {
        val code = """
            type Status = | Active, Inactive |

            let check: {s: Status} -> | Some {val: Int}, None | = {s} -> Some {val = 1}

            let test = {s: Status} ->
                match s is
                    Active if let Some {val = v} = check {s = Active} -> v
                    Inactive if let Some {val = v} = check {s = Inactive} -> v
        """.trimIndent()

        // Should fail - both patterns have if-let, so match is non-exhaustive
        val error = assertFailsWith<TypeCheckError> {
            typeCheck(code)
        }
        assertNotNull(error.sourceLocation, "Error should have source location")
        assertTrue(error.message?.contains("doesn't handle all cases") == true ||
                   error.message?.contains("exhaustive") == true,
            "Error should mention exhaustiveness. Actual: ${error.message}")
    }
}
