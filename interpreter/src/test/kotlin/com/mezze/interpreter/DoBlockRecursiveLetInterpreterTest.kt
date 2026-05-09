package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/** Interpreter tests for recursive let-bound lambdas inside do blocks. */
class DoBlockRecursiveLetInterpreterTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `recursive countdown returns 0`() {
        val code = """
            let outer = {n: Int} -> do
              let go = {i: Int} -> if i == 0 then 0 else go {i = i - 1}
              go {i = n}

            outer {n = 100}
        """.trimIndent()
        assertEquals(0L, eval(code))
    }

    @Test
    fun `recursive sum 0 to n`() {
        val code = """
            let sum_to = {n: Int} -> do
              let go = {acc: Int, i: Int} -> if i == n
                then acc
                else go {acc = acc + i, i = i + 1}
              go {acc = 0, i = 0}

            sum_to {n = 10}
        """.trimIndent()
        assertEquals(45L, eval(code))
    }

    @Test
    fun `factorial of 5`() {
        val code = """
            let factorial = {n: Int} -> do
              let go = {i: Int, acc: Int} -> if i == 0
                then acc
                else go {i = i - 1, acc = acc * i}
              go {i = n, acc = 1}

            factorial {n = 5}
        """.trimIndent()
        assertEquals(120L, eval(code))
    }

    @Test
    fun `fibonacci 10`() {
        val code = """
            let fib = {n: Int} -> do
              let go = {i: Int, a: Int, b: Int} -> if i == 0
                then a
                else go {i = i - 1, a = b, b = a + b}
              go {i = n, a = 0, b = 1}

            fib {n = 10}
        """.trimIndent()
        assertEquals(55L, eval(code))
    }

    @Test
    fun `non-recursive lambda still works`() {
        val code = """
            let outer = {n: Int} -> do
              let double = {x: Int} -> x * 2
              double {x = n}

            outer {n = 21}
        """.trimIndent()
        assertEquals(42L, eval(code))
    }
}
