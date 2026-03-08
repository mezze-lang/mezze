package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.interpreter.v2.values.MezzeVariant

/**
 * Comprehensive interpreter tests.
 * Migrated from V1 to V2 with unboxed values.
 */
class MezzeInterpreterComprehensiveTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testBasicArithmetic() {
        val source = """
            let result = 10 + 20 * 3 - 5
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(65L, result) // 10 + 60 - 5 = 65
    }

    @Test
    fun testFunctionDefinitionAndCall() {
        val source = """
            let add = {x, y} -> x + y
            let result = add {x = 5, y = 3}
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(8L, result)
    }

    @Test
    fun testPartialApplication() {
        val source = """
            let multiply = {x, y, z} -> x * y * z
            let partialMul = multiply {x = 2}
            let moreMul = partialMul {y = 3}
            let result = moreMul {z = 4}
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(24L, result) // 2 * 3 * 4 = 24
    }

    @Test
    fun testRecordOperations() {
        val source = """
            let person = {name = "Alice", age = 30}
            let name = person.name
            let updatedPerson = {name = person.name, age = 31}
            updatedPerson
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals("Alice", result.getField("name"))
        assertEquals(31L, result.getField("age"))
    }

    @Test
    fun testVariantAndPatternMatching() {
        // Use completely unique constructor names to avoid conflicts with built-in types
        // (Ok/Err are used by the built-in Result type)
        val source = """
            type Outcome = | Success {val: Int}, Failure {val: String} |
            let value: Outcome = Success {val = 42}
            let result = match value is
              Success {val} -> val * 2
              Failure {val} -> 0
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(84L, result) // 42 * 2
    }

    @Test
    fun testDoExpressions() {
        val source = """
            let computation = do
              let x = 10
              let y = 20
              let sum = x + y
              sum * 2
            computation
        """.trimIndent()

        val result = eval(source)
        assertEquals(60L, result) // (10 + 20) * 2 = 60
    }

    @Test
    fun testNestedFunctions() {
        val source = """
            let outer = {x} -> do
              let inner = {y} -> x + y
              inner {y = 10}
            let result = outer {x = 5}
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(15L, result) // 5 + 10
    }

    @Test
    fun testConditionalExpressions() {
        val source = """
            let x = 10
            let result = if x > 5 then "large" else "small"
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals("large", result)
    }

    @Test
    fun testComplexRecordUpdate() {
        val source = """
            let config = {server = {host = "localhost", port = 8080}, database = {url = "db://test", timeout = 5000}}
            let serverHost = config.server.host
            let updatedConfig = {server = {host = serverHost, port = 9090}, database = config.database}
            updatedConfig.server
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals("localhost", result.getField("host"))
        assertEquals(9090L, result.getField("port"))
    }

    @Test
    fun testRecursiveFunction() {
        val source = """
            let factorial = {n} -> if n <= 1 then 1 else n * factorial {n = n - 1}
            # Test recursive function call
            let result = factorial {n = 5}
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(120L, result) // 5! = 120
    }

    @Test
    fun testHigherOrderFunctions() {
        val source = """
            let applyTwice = {f, x} -> f {arg = f {arg = x}}
            let increment = {arg} -> arg + 1
            let result = applyTwice {f = increment, x = 5}
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(7L, result) // increment(increment(5)) = 7
    }

    @Test
    fun testComplexPatternMatching() {
        val source = """
            type Result = | Success {value: Int}, Error {message: String} |
            let processResult = {result} -> match result is
              Success {value} -> value + 10
              Error {message} -> 0
            let testResult = Success {value = 42}
            let output = processResult {result = testResult}
            output
        """.trimIndent()

        val result = eval(source)
        assertEquals(52L, result) // 42 + 10
    }

    @Test
    fun testStringOperations() {
        val source = """
            let greeting = "Hello"
            let name = "World"
            let message = "$(greeting), $(name)!"
            message
        """.trimIndent()

        val result = eval(source)
        assertEquals("Hello, World!", result)
    }

    @Test
    fun testBooleanLogic() {
        // Use Mezze's native boolean operators: && for AND, || for OR, ! for NOT
        val source = """
            let x = True
            let y = False
            let result1 = x && y
            let result2 = x || y
            let result3 = !x
            {and = result1, or = result2, not = result3}
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals(false, result.getField("and"))
        assertEquals(true, result.getField("or"))
        assertEquals(false, result.getField("not"))
    }

    @Test
    fun testNumericComparisons() {
        val source = """
            let x = 10
            let y = 20
            let isEqual = x == y
            let isLess = x < y
            let isGreater = x > y
            let isLessOrEqual = x <= y
            let isGreaterOrEqual = x >= y
            {eq = isEqual, lt = isLess, gt = isGreater, le = isLessOrEqual, ge = isGreaterOrEqual}
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals(false, result.getField("eq"))
        assertEquals(true, result.getField("lt"))
        assertEquals(false, result.getField("gt"))
        assertEquals(true, result.getField("le"))
        assertEquals(false, result.getField("ge"))
    }

    @Test
    fun testVariableScoping() {
        val source = """
            let x = 10
            let func = {y} -> do
              let x = 20  # Shadow outer x
              x + y
            let result = func {y = 5}  # Should use inner x (20)
            result
        """.trimIndent()

        val result = eval(source)
        assertEquals(25L, result) // 20 + 5
    }

    @Test
    fun testClosureCapture() {
        val source = """
            let makeCounter = {start} -> do
              let current = start
              {increment} -> do
                let newCurrent = current + increment
                newCurrent

            let counter = makeCounter {start = 0}
            let result1 = counter {increment = 5}
            result1
        """.trimIndent()

        val result = eval(source)
        assertEquals(5L, result) // 0 + 5
    }

    @Test
    fun testComplexDataStructures() {
        // Note: Mezze uses 'Bool' not 'Boolean' as the type name
        val source = """
            let alice = {id = 1, name = "Alice", active = True}
            let bob = {id = 2, name = "Bob", active = False}
            let charlie = {id = 3, name = "Charlie", active = True}

            let findUserById: {user: {id: Int, name: String, active: Bool}, targetId: Int} -> {id: Int, name: String, active: Bool} = {user, targetId} ->
              if user.id == targetId then user else {id = 0, name = "NotFound", active = False}

            let result = findUserById {user = bob, targetId = 2}
            result
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals(2L, result.getField("id"))
        assertEquals("Bob", result.getField("name"))
        assertEquals(false, result.getField("active"))
    }

    @Test
    fun testErrorHandlingInInterpreter() {
        val source = """
            type DivResult = | SuccessDiv { result: Int }, ErrorDiv { message: String } |
            let divide = {x, y} -> if y == 0 then ErrorDiv {message = "Division by zero"} else SuccessDiv {result = x / y}
            let result1 = divide {x = 10, y = 2}
            match result1 is
              SuccessDiv { result } -> result
              ErrorDiv { message } -> -1
        """.trimIndent()

        val result = eval(source)
        assertEquals(5L, result) // 10 / 2
    }

    @Test
    fun testNativeBuiltinFunctions() {
        // Note: stringToInt doesn't exist in the standard library
        // This test only checks int::to_string which is available
        val source = """
            use std::int

            let str = int::to_string {n = 42}
            str
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    fun testComplexDoExpressionWithMultipleStatements() {
        val source = """
            let complexComputation = {input} -> do
              let step1 = input * 2
              let step2 = step1 + 10
              let step3 = step2 / 2
              let step4 = if step3 > 15 then step3 - 5 else step3 + 5
              let final = step4 * step4
              final

            let result = complexComputation {input = 5}
            result
        """.trimIndent()

        // input=5, step1=10, step2=20, step3=10, step4=15 (10 is not > 15, so 10+5), final=225
        val result = eval(source)
        assertEquals(225L, result) // 15 * 15 = 225
    }
}
