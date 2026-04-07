package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertContains

/**
 * Comprehensive tests for destructuring with renaming in the typechecker.
 *
 * Tests cover:
 * 1. Single field renaming in let bindings
 * 2. Multiple field renaming in let bindings
 * 3. Renaming in match statement patterns
 * 4. Renaming in match guards
 * 5. Nested destructuring with renaming
 * 6. Variant pattern renaming
 * 7. Mixed renaming scenarios
 * 8. Error cases
 */
class DestructuringRenameTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun assertTypeCheckFails(code: String, expectedMessagePart: String? = null): TypeCheckError {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        if (expectedMessagePart != null) {
            assertContains(error.message ?: "", expectedMessagePart, ignoreCase = true)
        }
        return error
    }

    // ==========================================================================
    // SECTION 1: Single Field Renaming in Let Bindings
    // ==========================================================================

    @Nested
    inner class SingleFieldRenaming {

        @Test
        fun `rename single field in record destructuring`() {
            val code = """
                let point = { x = 10, y = 20 }
                let { x = myX, y } = point
                let result = myX + y
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename field to different name preserves type`() {
            val code = """
                let data = { name = "Alice", age = 30 }
                let { name = personName, age = personAge } = data
                let greeting = personName
                let isAdult = personAge > 18
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename with type annotation`() {
            val code = """
                let point: { x: Int, y: Int } = { x = 5, y = 10 }
                let { x = a, y = b } = point
                let sum: Int = a + b
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename preserves string type`() {
            val code = """
                let record = { message = "hello" }
                let { message = msg } = record
                let uppercased = msg
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename preserves boolean type`() {
            val code = """
                let config = { enabled = True, verbose = False }
                let { enabled = isEnabled, verbose = isVerbose } = config
                let bothActive = if isEnabled then isVerbose else False
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 2: Multiple Field Renaming in Let Bindings
    // ==========================================================================

    @Nested
    inner class MultipleFieldRenaming {

        @Test
        fun `rename all fields in record`() {
            val code = """
                let point = { x = 1, y = 2, z = 3 }
                let { x = a, y = b, z = c } = point
                let sum = a + b + c
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `mix renamed and non-renamed fields`() {
            val code = """
                let data = { first = 10, second = 20, third = 30 }
                let { first, second = middle, third } = data
                let result = first + middle + third
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename with mixed types`() {
            val code = """
                let person = { name = "Bob", age = 25, active = True }
                let { name = n, age = years, active = isActive } = person
                let canVote = years >= 18
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `multiple renames with partial destructuring using ellipsis`() {
            val code = """
                let data = { a = 1, b = 2, c = 3, d = 4 }
                let { a = first, b = second, ... } = data
                let sum = first + second
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 3: Renaming in Match Statement Patterns
    // ==========================================================================

    @Nested
    inner class MatchPatternRenaming {

        @Test
        fun `rename in simple match pattern`() {
            val code = """
                let process = {data} ->
                    match data is
                      { value = v } -> v * 2
                let result = process { data = { value = 21 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename multiple fields in match pattern`() {
            val code = """
                let swap = {point} ->
                    match point is
                      { x = a, y = b } -> { x = b, y = a }
                let result = swap { point = { x = 1, y = 2 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `different renames in different branches`() {
            val code = """
                let describe = {data} ->
                    match data is
                      { status = 200, body = content } -> content
                      { status = code, ... } -> "error"
                let result = describe { data = { status = 200, body = "ok" } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename in pattern with literal field`() {
            val code = """
                let handle = {response} ->
                    match response is
                      { status = 200, data = d } -> d
                      { status = code, data = d } -> 0
                let result = handle { response = { status = 200, data = 42 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename in match with multiple cases`() {
            val code = """
                let categorize = {item} ->
                    match item is
                      { category = "food", price = p } -> p * 2
                      { category = "drink", price = cost } -> cost
                      { price = amount, ... } -> amount
                let result = categorize { item = { category = "food", price = 10 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 4: Renaming in Match Guards
    // ==========================================================================

    @Nested
    inner class MatchGuardRenaming {

        @Test
        fun `use renamed variable in guard`() {
            val code = """
                let classify = {person} ->
                    match person is
                      { age = years } if years >= 18 -> "adult"
                      { age = years } -> "minor"
                let result = classify { person = { age = 25 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `use multiple renamed variables in guard`() {
            val code = """
                let isValidRect = {rect} ->
                    match rect is
                      { width = w, height = h } if w > 0 && h > 0 -> True
                      { width = w, height = h } -> False
                let result = isValidRect { rect = { width = 10, height = 20 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `guard comparing renamed fields`() {
            val code = """
                let isSquare = {rect} ->
                    match rect is
                      { width = w, height = h } if w == h -> True
                      _ -> False
                let result = isSquare { rect = { width = 5, height = 5 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `guard with arithmetic on renamed fields`() {
            val code = """
                let isPythagorean = {triple} ->
                    match triple is
                      { a = x, b = y, c = z } if x*x + y*y == z*z -> True
                      _ -> False
                let result = isPythagorean { triple = { a = 3, b = 4, c = 5 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `cascading guards with same rename`() {
            val code = """
                let classifyAge = {person} ->
                    match person is
                      { age = years } if years >= 65 -> "senior"
                      { age = years } if years >= 18 -> "adult"
                      { age = years } if years >= 13 -> "teen"
                      { age = years } -> "child"
                let result = classifyAge { person = { age = 70 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 5: Nested Destructuring with Renaming
    // ==========================================================================

    @Nested
    inner class NestedDestructuringRenaming {

        @Test
        fun `rename in nested record destructuring`() {
            val code = """
                let outer = { inner = { value = 42, extra = 10 } }
                let { inner = { value = v, extra = e } } = outer
                let sum = v + e
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `deeply nested rename`() {
            val code = """
                let data = { level1 = { level2 = { level3 = 100 } } }
                let { level1 = { level2 = { level3 = deepValue } } } = data
                let result = deepValue * 2
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `mix of renamed and non-renamed in nested`() {
            val code = """
                let data = { point = { x = 1, y = 2 }, name = "origin" }
                let { point = { x = px, y }, name } = data
                let sum = px + y
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `nested rename in match pattern`() {
            val code = """
                let process = {data} ->
                    match data is
                      { config = { port = p, host = h } } -> p
                let result = process { data = { config = { port = 3000, host = "localhost" } } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 6: Variant Pattern Renaming
    // ==========================================================================

    @Nested
    inner class VariantPatternRenaming {

        @Test
        fun `rename variant payload field`() {
            val code = """
                let unwrap = {opt} ->
                    match opt is
                      Some { val = v } -> v
                      None -> 0
                let result = unwrap { opt = Some { val = 42 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename multiple variant payload fields`() {
            val code = """
                let process = {result} ->
                    match result is
                      Ok { val = value } -> value
                      Err { error = msg } -> 0
                let result = process { result = Ok { val = 100 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `variant with record payload and renaming`() {
            val code = """
                let getX = {opt} ->
                    match opt is
                      Some { val = { x = px, y = py } } -> px
                      None -> 0
                let result = getX { opt = Some { val = { x = 10, y = 20 } } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `variant rename with guard`() {
            val code = """
                let getIfPositive = {opt} ->
                    match opt is
                      Some { val = v } if v > 0 -> v
                      Some { val = v } -> 0
                      None -> 0
                let result = getIfPositive { opt = Some { val = 42 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `variant with multiple payload fields renamed`() {
            val code = """
                let isValidRange = {range} ->
                    match range is
                      Range { min = lo, max = hi } if lo < hi -> True
                      Range { min = lo, max = hi } -> False
                      Empty -> False
                let result = isValidRange { range = Range { min = 1, max = 10 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 7: Do Block Renaming
    // ==========================================================================

    @Nested
    inner class DoBlockRenaming {

        @Test
        fun `rename in do block let binding`() {
            val code = """
                let compute = {} -> do
                    let point = { x = 5, y = 10 }
                    let { x = a, y = b } = point
                    a * b
                let result = compute {}
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `multiple renames in do block`() {
            val code = """
                let process = {} -> do
                    let p1 = { x = 1, y = 2 }
                    let p2 = { x = 10, y = 20 }
                    let { x = x1, y = y1 } = p1
                    let { x = x2, y = y2 } = p2
                    x1 + y1 + x2 + y2
                let result = process {}
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename used in subsequent expression`() {
            val code = """
                let transform = {} -> do
                    let data = { factor = 2, base = 100 }
                    let { factor = multiplier, base = start } = data
                    start * multiplier
                let result = transform {}
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 8: Complex Mixed Scenarios
    // ==========================================================================

    @Nested
    inner class ComplexScenarios {

        @Test
        fun `rename in function parameter destructuring body`() {
            val code = """
                let processPoint = {point} -> do
                    let { x = px, y = py } = point
                    px * py
                let result = processPoint { point = { x = 6, y = 7 } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `chained operations with renamed values`() {
            val code = """
                let step1 = { value = 10 }
                let { value = v1 } = step1
                let step2 = { value = v1 * 2 }
                let { value = v2 } = step2
                let result = v1 + v2
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename in conditional result`() {
            val code = """
                let getData = {flag} ->
                    if flag then { value = 100 } else { value = 0 }
                let process = {flag} -> do
                    let data = getData { flag = flag }
                    let { value = v } = data
                    v
                let result = process { flag = True }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename with record spread update`() {
            val code = """
                let original = { x = 1, y = 2, z = 3 }
                let { x = origX, y = origY, z = origZ } = original
                let updated = { ...original, x = origX * 2 }
                let { x = newX, ... } = updated
                let result = newX
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `variant match with nested record rename and guard`() {
            val code = """
                let handleResponse = {response} ->
                    match response is
                      Ok { val = { status = code, data = d } } if code == 200 -> d
                      Ok { val = { status = code, data = d } } -> 0
                      Err { error = msg } -> 0
                let result = handleResponse { response = Ok { val = { status = 200, data = 42 } } }
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }

    // ==========================================================================
    // SECTION 9: Error Cases
    // ==========================================================================

    @Nested
    inner class ErrorCases {

        @Test
        fun `error - duplicate rename targets`() {
            val code = """
                let point = { x = 10, y = 20 }
                let { x = a, y = a } = point
            """.trimIndent()

            assertTypeCheckFails(code)
        }

        @Test
        fun `error - renamed variable shadows existing`() {
            val code = """
                let existing = 5
                let point = { x = 10, y = 20 }
                let { x = existing, y } = point
            """.trimIndent()

            assertTypeCheckFails(code)
        }

        @Test
        fun `error - using original field name after rename`() {
            // After renaming x to myX, the name 'x' should not be bound
            val code = """
                let point = { x = 10, y = 20 }
                let { x = myX, y } = point
                let result = x + y
            """.trimIndent()

            assertTypeCheckFails(code)
        }

        @Test
        fun `error - renamed string used in boolean context`() {
            // String cannot be used as a boolean condition in if
            val code = """
                let data = { value = "hello" }
                let { value = v } = data
                let result = if v then 1 else 0
            """.trimIndent()

            // This SHOULD fail - String cannot unify with Bool (| True, False |)
            // If it doesn't fail, there's a bug in the typechecker
            // Note: IfExpression is unified with MatchExpression at the type level,
            // so the error message is about pattern matching on True/False
            assertTypeCheckFails(code, "True")
        }
    }

    // ==========================================================================
    // SECTION 10: Edge Cases
    // ==========================================================================

    @Nested
    inner class EdgeCases {

        @Test
        fun `rename to same name as field`() {
            // { x = x } should work - explicit binding to same name
            val code = """
                let point = { x = 10, y = 20 }
                let { x = x, y = y } = point
                let sum = x + y
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename single field record`() {
            val code = """
                let data = { only = 42 }
                let { only = theValue } = data
                let result = theValue * 2
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename with long identifier names`() {
            val code = """
                let data = { firstName = "Alice", lastName = "Smith" }
                let { firstName = thePersonsFirstName, lastName = thePersonsLastName } = data
                let fullName = thePersonsFirstName
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }

        @Test
        fun `rename in pattern with underscore fields`() {
            // Some fields can be wildcards, others renamed
            val code = """
                let data = { keep = 42, ignore = 100 }
                let { keep = k, ignore = _ } = data
                let result = k
            """.trimIndent()

            val result = parseAndTypeCheck(code)
            assertNotNull(result)
        }
    }
}
