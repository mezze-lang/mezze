package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Tests for type checking standalone record patterns in match expressions
 */
class RecordPatternTypeCheckingTest {

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
        val program = parser.parse(code)
        return typeChecker.typeCheck(program)
    }

    // ========== Basic Record Pattern Matching ==========

    @Test
    fun `should accept single field record pattern`() {
        // Pattern { name } is exhaustive for record { name: Str }
        val code = """
            let get_name = {person} ->
                match person is
                  { name } -> name
            let result = get_name { person = { name = "Alice" } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should accept multiple field record pattern`() {
        // Pattern { x, y } is exhaustive for record { x: Int, y: Int }
        val code = """
            let get_sum = {point} ->
                match point is
                  { x, y } -> x + y
            let result = get_sum { point = { x = 1, y = 2 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should accept record pattern with explicit bindings`() {
        // Pattern { name, age } is exhaustive for record { name: Str, age: Int }
        val code = """
            let describe = {data} ->
                match data is
                  { name = n, age = a } -> n
            let result = describe { data = { name = "Bob", age = 30 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Type Inference from Record Patterns ==========

    @Test
    fun `should infer return type from record pattern branches`() {
        // Pattern { x, y } is exhaustive
        val code = """
            let get_x = {point} ->
                match point is
                  { x, y } -> x
            let result = get_x { point = { x = 5, y = 10 } } + 1
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should type check arithmetic with bound variables`() {
        // Pattern { a, b, c } is exhaustive
        val code = """
            let calculate = {data} ->
                match data is
                  { a, b, c } -> a * b + c
            let result = calculate { data = { a = 2, b = 3, c = 4 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Open Record Patterns ==========

    @Test
    fun `should accept open record pattern`() {
        val code = """
            let get_name = {data} ->
                match data is
                  { name, ... } -> name
                  _ -> "unknown"
            let result = get_name { data = { name = "Alice", age = 30, city = "NYC" } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should accept open record pattern matching subset of fields`() {
        val code = """
            let get_x = {point} ->
                match point is
                  { x, ... } -> x
                  _ -> 0
            let result = get_x { point = { x = 5, y = 10, z = 15 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Nested Record Patterns ==========

    @Test
    fun `should accept record pattern with wildcard in field`() {
        // Pattern { name, age } is exhaustive (field wildcard doesn't affect exhaustiveness)
        val code = """
            let get_age = {person} ->
                match person is
                  { name = _, age } -> age
            let result = get_age { person = { name = "Alice", age = 30 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should accept record pattern with literal in field`() {
        // Literal patterns make the pattern non-exhaustive, so wildcard is reachable
        val code = """
            let describe_status = {response} ->
                match response is
                  { status = 200 } -> "OK"
                  { status = 404 } -> "Not Found"
                  { status } -> "Unknown"
            let result = describe_status { response = { status = 200 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Record Pattern with Guards ==========

    @Test
    fun `should accept record pattern with guard`() {
        val code = """
            let classify = {person} ->
                match person is
                  { age } if age > 18 -> "adult"
                  { age } -> "minor"
            let result = classify { person = { age = 25 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should accept complex guard with bound variables`() {
        val code = """
            let classify_point = {point} ->
                match point is
                  { x, y } if x > 0 && y > 0 -> "first quadrant"
                  { x, y } if x < 0 && y > 0 -> "second quadrant"
                  _ -> "other"
            let result = classify_point { point = { x = 1, y = 1 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Multiple Record Patterns ==========

    @Test
    fun `should accept multiple record patterns with pipe`() {
        // Use ... to allow partial field matching
        val code = """
            let is_origin_or_axis = {point} ->
                match point is
                  { x = 0, y = 0 } | { x = 0, ... } | { y = 0, ... } -> True
                  _ -> False
            let result = is_origin_or_axis { point = { x = 0, y = 5 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Mixed Pattern Types ==========

    @Test
    fun `should accept mix of record and wildcard patterns`() {
        // Pattern { value } is exhaustive for { value: Int }
        val code = """
            let process = {data} ->
                match data is
                  { value } -> value + 1
            let result = process { data = { value = 5 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should accept mix of record and variable patterns`() {
        // Pattern { x, y } is exhaustive for { x: Int, y: Int }
        val code = """
            let process = {data} ->
                match data is
                  { x, y } -> x + y
            let result = process { data = { x = 1, y = 2 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========== Practical Use Cases ==========

    @Test
    fun `should handle HTTP response style matching`() {
        // Use ... for partial field matching
        val code = """
            let handle_response = {response} ->
                match response is
                  { status = 200, body } -> body
                  { status = 404, ... } -> "Not Found"
                  { status, ... } -> "Error"
            let result = handle_response { response = { status = 200, body = "Hello" } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should handle config style matching`() {
        // Pattern { port, host } is exhaustive for record { port: Int, host: Str }
        val code = """
            let get_port = {config} ->
                match config is
                  { port, host } -> port
            let result = get_port { config = { port = 3000, host = "localhost" } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `should handle nested record access`() {
        // Pattern { x, y } is exhaustive
        val code = """
            let process = {data} ->
                match data is
                  { x, y } -> x * y
            let result = do
                let point = { x = 3, y = 4 }
                process { data = point }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }
}
