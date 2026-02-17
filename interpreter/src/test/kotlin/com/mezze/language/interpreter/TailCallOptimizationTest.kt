package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests for Tail Call Optimization (TCO).
 *
 * TCO allows self-recursive functions in tail position to run
 * without consuming stack space, enabling deep recursion without
 * stack overflow.
 */
class TailCallOptimizationTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    @Test
    fun `test simple tail-recursive factorial with accumulator`() {
        val source = """
            let factorialAcc = {n, acc} ->
                if n == 0 then acc
                else factorialAcc {n = n - 1, acc = acc * n}

            let factorial = {n} -> factorialAcc {n = n, acc = 1}
            let result = factorial {n = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(120L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test tail-recursive sum with accumulator`() {
        val source = """
            let sumAcc = {n, acc} ->
                if n == 0 then acc
                else sumAcc {n = n - 1, acc = acc + n}

            let sum = {n} -> sumAcc {n = n, acc = 0}
            let result = sum {n = 10}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        // 10 + 9 + 8 + 7 + 6 + 5 + 4 + 3 + 2 + 1 = 55
        assertEquals(55L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test tail-recursive countdown`() {
        val source = """
            let countdown = {n, result} ->
                if n == 0 then result
                else countdown {n = n - 1, result = result + 1}

            let result = countdown {n = 100, result = 0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(100L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test tail-recursive fibonacci with accumulators`() {
        val source = """
            let fibAcc = {n, a, b} ->
                if n == 0 then a
                else fibAcc {n = n - 1, a = b, b = a + b}

            let fib = {n} -> fibAcc {n = n, a = 0, b = 1}
            let result = fib {n = 10}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        // fib(10) = 55 (0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55)
        assertEquals(55L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test tail-recursive gcd`() {
        val source = """
            let gcd = {a, b} ->
                if b == 0 then a
                else gcd {a = b, b = a % b}

            let result = gcd {a = 48, b = 18}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        // gcd(48, 18) = 6
        assertEquals(6L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test tail call in match expression`() {
        val source = """
            let length = {list, acc} ->
                match list is
                    Nil -> acc
                    Cons {head, tail} -> length {list = tail, acc = acc + 1}

            let myList = Cons {head = 1, tail = Cons {head = 2, tail = Cons {head = 3, tail = Nil}}}
            let result = length {list = myList, acc = 0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(3L, (variables["result"] as MezzeInt).value)
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `test deep recursion does not stack overflow`() {
        // This test verifies TCO by running a very deep recursion
        // Without TCO, this would cause a StackOverflowError
        val source = """
            let countdown = {n} ->
                if n == 0 then 0
                else countdown {n = n - 1}

            let result = countdown {n = 10000}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(0L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test non-tail-recursive function still works`() {
        // This is NOT a tail call (the recursive call is not in tail position)
        // Should still work for small inputs
        val source = """
            let factorial = {n} ->
                if n == 0 then 1
                else n * factorial {n = n - 1}

            let result = factorial {n = 5}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(120L, (variables["result"] as MezzeInt).value)
    }

    @Test
    fun `test tail call in nested if`() {
        val source = """
            let classify = {n, result} ->
                if n == 0 then result
                else if n > 0 then classify {n = n - 1, result = result + 1}
                else classify {n = n + 1, result = result - 1}

            let positiveResult = classify {n = 5, result = 0}
            let negativeResult = classify {n = 0 - 3, result = 0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        assertEquals(5L, (variables["positiveResult"] as MezzeInt).value)
        assertEquals(-3L, (variables["negativeResult"] as MezzeInt).value)
    }

    @Test
    fun `test tail call in do expression`() {
        val source = """
            let process = {n, acc} -> do
                let doubled = n * 2
                if doubled == 0 then acc
                else process {n = n - 1, acc = acc + doubled}

            let result = process {n = 3, acc = 0}
        """.trimIndent()

        val variables = parseTypeCheckAndRun(source)
        // 3*2 + 2*2 + 1*2 = 6 + 4 + 2 = 12
        assertEquals(12L, (variables["result"] as MezzeInt).value)
    }
}
