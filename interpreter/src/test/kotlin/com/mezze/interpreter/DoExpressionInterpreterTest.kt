package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for do expression functionality.
 * Migrated from V1 to V2 with unboxed values.
 */
class DoExpressionInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test simple do expression`() {
        val code = """
            let result = do
                let x = 5
                let y = 3
                x + y
            result
        """
        assertEquals(8L, eval(code))
    }

    @Test
    fun `test do expression with multiple statements`() {
        val code = """
            let computation = do
                let a = 10
                let b = 20
                let c = a * b
                c / 2

            let final = computation + 5
            final
        """
        // computation = (10 * 20) / 2 = 100, final = 100 + 5 = 105
        assertEquals(105L, eval(code))
    }

    @Test
    fun `test nested do expressions`() {
        val code = """
            let result = do
                let outer = 5
                let inner = do
                    let x = 3
                    let y = 4
                    x * y
                outer + inner
            result
        """
        // 5 + (3 * 4) = 17
        assertEquals(17L, eval(code))
    }

    @Test
    fun `test do expression with variable shadowing`() {
        val code = """
            let x = 100
            let result = do
                let x = 5
                let y = do
                    let x = 2
                    x * 3
                x + y
            let final_val = x + result
            final_val
        """
        // x = 100, result = 5 + (2 * 3) = 11, final = 100 + 11 = 111
        assertEquals(111L, eval(code))
    }

    @Test
    fun `test do expression with conditionals - above threshold`() {
        val code = """
            let process = {input} -> do
                let threshold = 10
                let processed = if input > threshold then input * 2 else input
                let adjusted = if processed < 5 then 5 else processed
                adjusted

            let result = process {input = 15}
            result
        """
        // 15 > 10, so processed = 30, adjusted = 30
        assertEquals(30L, eval(code))
    }

    @Test
    fun `test do expression with conditionals - below threshold`() {
        val code = """
            let process = {input} -> do
                let threshold = 10
                let processed = if input > threshold then input * 2 else input
                let adjusted = if processed < 5 then 5 else processed
                adjusted

            let result = process {input = 3}
            result
        """
        // 3 <= 10, so processed = 3, adjusted = 5 (because 3 < 5)
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test do expression with early returns - first branch`() {
        val code = """
            let checkValue = {x} -> do
                let doubled = x * 2
                if doubled > 100 then doubled else do
                    let tripled = x * 3
                    if tripled > 50 then tripled else x

            let result = checkValue {x = 60}
            result
        """
        // 60 * 2 = 120 > 100, return 120
        assertEquals(120L, eval(code))
    }

    @Test
    fun `test do expression with early returns - second branch`() {
        val code = """
            let checkValue = {x} -> do
                let doubled = x * 2
                if doubled > 100 then doubled else do
                    let tripled = x * 3
                    if tripled > 50 then tripled else x

            let result = checkValue {x = 20}
            result
        """
        // 20 * 2 = 40 <= 100, 20 * 3 = 60 > 50, return 60
        assertEquals(60L, eval(code))
    }

    @Test
    fun `test do expression with early returns - third branch`() {
        val code = """
            let checkValue = {x} -> do
                let doubled = x * 2
                if doubled > 100 then doubled else do
                    let tripled = x * 3
                    if tripled > 50 then tripled else x

            let result = checkValue {x = 5}
            result
        """
        // 5 * 2 = 10 <= 100, 5 * 3 = 15 <= 50, return 5
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test do expression with record operations`() {
        val code = """
            let createPerson = {name, age} -> do
                let validated = if age < 0 then 0 else age
                {name = name, age = validated}

            let person = createPerson {name = "Alice", age = 25}
            person.age
        """
        assertEquals(25L, eval(code))
    }

    @Test
    fun `test do expression with string operations`() {
        val code = """
            let formatMessage = {prefix, value, suffix} -> do
                let prefixed = "$(prefix): "
                let withValue = "$(prefixed)$(value)"
                let complete = "$(withValue) $(suffix)"
                complete

            let message = formatMessage {prefix = "Result", value = "42", suffix = "items"}
            message
        """
        assertEquals("Result: 42 items", eval(code))
    }

    @Test
    fun `test do expression with complex logic`() {
        val code = """
            let complexCalculation = {a, b, c} -> do
                let sum = a + b + c
                let product = a * b * c
                let average = sum / 3
                let result = if product > sum
                           then do
                               let bonus = average * 2
                               product + bonus
                           else sum
                result

            let result = complexCalculation {a = 2, b = 3, c = 4}
            result
        """
        // sum = 9, product = 24, average = 3
        // product > sum (24 > 9), so bonus = 6, result = 24 + 6 = 30
        assertEquals(30L, eval(code))
    }
}
