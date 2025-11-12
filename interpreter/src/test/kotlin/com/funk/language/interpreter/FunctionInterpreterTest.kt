package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class FunctionInterpreterTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    private val interpreter = FunkInterpreter()

    private fun parseTypeCheckAndRun(source: String): Map<String, FunkValue> {
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val (_, context) = interpreter.executeWithContext(typedProgram)
        return context
    }

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
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        val updated2 = variables["updated2"] as FunkRecord
        assertEquals("Bob", (updated2.fields["name"] as FunkString).value)
        assertEquals(25L, (updated2.fields["age"] as FunkInt).value)
    }

    @Test
    fun `test recursive function`() {
        val source = """
            let factorial = {n} ->
                if n <= 1
                then 1
                else n * factorial {n = n - 1}
                
            let result1 = factorial {n = 5}
            let result2 = factorial {n = 0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(120L, (variables["result1"] as FunkInt).value) // 5! = 120
        assertEquals(1L, (variables["result2"] as FunkInt).value)   // 0! = 1
    }

    @Test
    fun `test function composition`() {
        val source = """
            let double = {x} -> x * 2
            let addTen = {x} -> x + 10
            let square = {x} -> x * x
            
            let compose = {f, g} -> {x} -> f {y = g {x = x}}
            let doubleAndAdd = compose {f = addTen, g = double}
            
            let result = doubleAndAdd {x = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(20L, (variables["result"] as FunkInt).value) // (5 * 2) + 10 = 20
    }

    @Test
    fun `test function with conditional logic`() {
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
                
            let result1 = classify {x = -5}
            let result2 = classify {x = 0}
            let result3 = classify {x = 5}
            let result4 = classify {x = 50}
            let result5 = classify {x = 150}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals("negative", (variables["result1"] as FunkString).value)
        assertEquals("zero", (variables["result2"] as FunkString).value)
        assertEquals("small", (variables["result3"] as FunkString).value)
        assertEquals("medium", (variables["result4"] as FunkString).value)
        assertEquals("large", (variables["result5"] as FunkString).value)
    }

    @Test
    fun `test function with boolean logic`() {
        val source = """
            let isInRange = {x, min, max} ->
                x >= min && x <= max
                
            let isValid = {value} ->
                isInRange {x = value, min = 0, max = 100} && value != 50
                
            let result1 = isValid {value = 25}
            let result2 = isValid {value = 50}
            let result3 = isValid {value = 150}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)

        assertEquals(true, (variables["result1"] as FunkBool).value)
        assertEquals(false, (variables["result2"] as FunkBool).value)
        assertEquals(false, (variables["result3"] as FunkBool).value)
    }
    */
}
