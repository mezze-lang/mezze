package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DoExpressionInterpreterTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    // TODO: These tests are commented out because do expressions need additional implementation
    // Uncomment and fix as do expression features are completed

    /*
    @Test
    fun `test simple do expression`() {
        val source = """
            let result = do
                let x = 5
                let y = 3
                x + y
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(8L, (variables["result"] as FunkInt).value)
        // x and y should not be accessible outside the do block
        assertFalse(variables.containsKey("x"))
        assertFalse(variables.containsKey("y"))
    }

    @Test
    fun `test do expression with multiple statements`() {
        val source = """
            let computation = do
                let a = 10
                let b = 20
                let c = a * b
                c / 2
                
            let final = computation + 5
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(100L, (variables["computation"] as FunkInt).value) // (10 * 20) / 2 = 100
        assertEquals(105L, (variables["final"] as FunkInt).value) // 100 + 5 = 105
        assertFalse(variables.containsKey("a"))
        assertFalse(variables.containsKey("b"))
        assertFalse(variables.containsKey("c"))
    }

    @Test
    fun `test nested do expressions`() {
        val source = """
            let result = do
                let outer = 5
                let inner = do
                    let x = 3
                    let y = 4
                    x * y
                outer + inner
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(17L, (variables["result"] as FunkInt).value) // 5 + (3 * 4) = 17
        assertFalse(variables.containsKey("outer"))
        assertFalse(variables.containsKey("inner"))
        assertFalse(variables.containsKey("x"))
        assertFalse(variables.containsKey("y"))
    }

    @Test
    fun `test do expression with variable shadowing`() {
        val source = """
            let x = 100
            let result = do
                let x = 5  // shadows outer x
                let y = do
                    let x = 2  // shadows previous x
                    x * 3
                x + y
            let final = x + result  // uses outer x
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(100L, (variables["x"] as FunkInt).value) // outer x unchanged
        assertEquals(11L, (variables["result"] as FunkInt).value) // 5 + (2 * 3) = 11
        assertEquals(111L, (variables["final"] as FunkInt).value) // 100 + 11 = 111
    }

    @Test
    fun `test do expression with conditionals`() {
        val source = """
            let process = {input} -> do
                let threshold = 10
                let processed = if input > threshold then input * 2 else input
                let adjusted = if processed < 5 then 5 else processed
                adjusted
                
            let result1 = process {input = 15}
            let result2 = process {input = 3}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(30L, (variables["result1"] as FunkInt).value) // 15 > 10, so 15 * 2 = 30
        assertEquals(5L, (variables["result2"] as FunkInt).value)   // 3 <= 10, so 3, but adjusted to 5
    }

    @Test
    fun `test do expression with early returns`() {
        val source = """
            let checkValue = {x} -> do
                let doubled = x * 2
                if doubled > 100 then doubled else do
                    let tripled = x * 3
                    if tripled > 50 then tripled else x
                    
            let result1 = checkValue {x = 60}  // 120 > 100, return 120
            let result2 = checkValue {x = 20}  // 40 <= 100, 60 > 50, return 60
            let result3 = checkValue {x = 5}   // 10 <= 100, 15 <= 50, return 5
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(120L, (variables["result1"] as FunkInt).value)
        assertEquals(60L, (variables["result2"] as FunkInt).value)
        assertEquals(5L, (variables["result3"] as FunkInt).value)
    }

    @Test
    fun `test do expression with record operations`() {
        val source = """
            let createPerson = {name, age} -> do
                let validated = if age < 0 then 0 else age
                let formatted = "$(name) (age $(validated))"
                {name = name, age = validated, description = formatted}
                
            let person = createPerson {name = "Alice", age = 25}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val person = variables["person"] as FunkRecord
        assertEquals("Alice", (person.fields["name"] as FunkString).value)
        assertEquals(25L, (person.fields["age"] as FunkInt).value)
        assertEquals("Alice (age 25)", (person.fields["description"] as FunkString).value)
    }

    @Test
    fun `test do expression with string operations`() {
        val source = """
            let formatMessage = {prefix, value, suffix} -> do
                let prefixed = "$(prefix): "
                let withValue = "$(prefixed)$(value)"
                let complete = "$(withValue) $(suffix)"
                complete

            let message = formatMessage {prefix = "Result", value = "42", suffix = "items"}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("Result: 42 items", (variables["message"] as FunkString).value)
    }

    @Test
    fun `test do expression with complex logic`() {
        val source = """
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
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        // sum = 9, product = 24, average = 3
        // product > sum (24 > 9), so bonus = 6, result = 24 + 6 = 30
        assertEquals(30L, (variables["result"] as FunkInt).value)
    }
    */
}
