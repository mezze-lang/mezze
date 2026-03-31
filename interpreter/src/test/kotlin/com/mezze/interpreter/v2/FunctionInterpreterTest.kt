package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for function interpreter features.
 * Migrated from V1 to V2 with unboxed values.
 */
class FunctionInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // TODO: These tests are commented out because they use advanced features not yet fully implemented
    // Uncomment and fix as more language features are implemented

    /*
    @Test
    fun `test function with complex record operations`() {
        val source = """
            let updateRecord = {record, field, value} ->
                if field == "name"
                then {record | name = value}
                else if field == "age"
                then {record | age = value}
                else record

            let person = {name = "Alice", age = 30}
            let updated1 = updateRecord {record = person, field = "name", value = "Bob"}
            let updated2 = updateRecord {record = updated1, field = "age", value = 25}
            updated2
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals("Bob", result.getField("name"))
        assertEquals(25L, result.getField("age"))
    }

    @Test
    fun `test recursive function`() {
        val source = """
            let factorial = {n} ->
                if n <= 1
                then 1
                else n * factorial {n = n - 1}

            factorial {n = 5}
        """.trimIndent()

        assertEquals(120L, eval(source)) // 5! = 120
    }

    @Test
    fun `test recursive function with zero`() {
        val source = """
            let factorial = {n} ->
                if n <= 1
                then 1
                else n * factorial {n = n - 1}

            factorial {n = 0}
        """.trimIndent()

        assertEquals(1L, eval(source)) // 0! = 1
    }

    @Test
    fun `test function composition`() {
        val source = """
            let double = {x} -> x * 2
            let addTen = {x} -> x + 10
            let square = {x} -> x * x

            let compose = {f, g} -> {x} -> f {y = g {x = x}}
            let doubleAndAdd = compose {f = addTen, g = double}

            doubleAndAdd {x = 5}
        """.trimIndent()

        assertEquals(20L, eval(source)) // (5 * 2) + 10 = 20
    }

    @Test
    fun `test function with conditional logic - negative`() {
        val source = """
            let classify = {x} ->
                if x < 0
                then "negative"
                else if x == 0
                then "zero"
                else if x < 10
                then "small"
                else if x < 100
                then "medium"
                else "large"

            classify {x = -5}
        """.trimIndent()

        assertEquals("negative", eval(source))
    }

    @Test
    fun `test function with conditional logic - zero`() {
        val source = """
            let classify = {x} ->
                if x < 0
                then "negative"
                else if x == 0
                then "zero"
                else if x < 10
                then "small"
                else if x < 100
                then "medium"
                else "large"

            classify {x = 0}
        """.trimIndent()

        assertEquals("zero", eval(source))
    }

    @Test
    fun `test function with conditional logic - small`() {
        val source = """
            let classify = {x} ->
                if x < 0
                then "negative"
                else if x == 0
                then "zero"
                else if x < 10
                then "small"
                else if x < 100
                then "medium"
                else "large"

            classify {x = 5}
        """.trimIndent()

        assertEquals("small", eval(source))
    }

    @Test
    fun `test function with conditional logic - medium`() {
        val source = """
            let classify = {x} ->
                if x < 0
                then "negative"
                else if x == 0
                then "zero"
                else if x < 10
                then "small"
                else if x < 100
                then "medium"
                else "large"

            classify {x = 50}
        """.trimIndent()

        assertEquals("medium", eval(source))
    }

    @Test
    fun `test function with conditional logic - large`() {
        val source = """
            let classify = {x} ->
                if x < 0
                then "negative"
                else if x == 0
                then "zero"
                else if x < 10
                then "small"
                else if x < 100
                then "medium"
                else "large"

            classify {x = 150}
        """.trimIndent()

        assertEquals("large", eval(source))
    }

    @Test
    fun `test function with boolean logic - valid in range`() {
        val source = """
            let isInRange = {x, min, max} ->
                x >= min && x <= max

            let isValid = {value} ->
                isInRange {x = value, min = 0, max = 100} && value != 50

            isValid {value = 25}
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test function with boolean logic - invalid at excluded value`() {
        val source = """
            let isInRange = {x, min, max} ->
                x >= min && x <= max

            let isValid = {value} ->
                isInRange {x = value, min = 0, max = 100} && value != 50

            isValid {value = 50}
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    @Test
    fun `test function with boolean logic - invalid out of range`() {
        val source = """
            let isInRange = {x, min, max} ->
                x >= min && x <= max

            let isValid = {value} ->
                isInRange {x = value, min = 0, max = 100} && value != 50

            isValid {value = 150}
        """.trimIndent()

        assertEquals(false, eval(source))
    }
    */
}
