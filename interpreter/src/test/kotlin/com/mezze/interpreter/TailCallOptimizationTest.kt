package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Tests for Tail Call Optimization (TCO).
 *
 * TCO allows self-recursive functions in tail position to run
 * without consuming stack space, enabling deep recursion without
 * stack overflow.
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class TailCallOptimizationTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test simple tail-recursive factorial with accumulator`() {
        val code = """
            let factorialAcc = {n, acc} ->
                if n == 0 then acc
                else factorialAcc {n = n - 1, acc = acc * n}

            let factorial = {n} -> factorialAcc {n = n, acc = 1}
            factorial {n = 5}
        """
        assertEquals(120L, eval(code))
    }

    @Test
    fun `test tail-recursive sum with accumulator`() {
        val code = """
            let sumAcc = {n, acc} ->
                if n == 0 then acc
                else sumAcc {n = n - 1, acc = acc + n}

            let sum = {n} -> sumAcc {n = n, acc = 0}
            sum {n = 10}
        """
        // 10 + 9 + 8 + 7 + 6 + 5 + 4 + 3 + 2 + 1 = 55
        assertEquals(55L, eval(code))
    }

    @Test
    fun `test tail-recursive countdown`() {
        val code = """
            let countdown = {n, result} ->
                if n == 0 then result
                else countdown {n = n - 1, result = result + 1}

            countdown {n = 100, result = 0}
        """
        assertEquals(100L, eval(code))
    }

    @Test
    fun `test tail-recursive fibonacci with accumulators`() {
        val code = """
            let fibAcc = {n, a, b} ->
                if n == 0 then a
                else fibAcc {n = n - 1, a = b, b = a + b}

            let fib = {n} -> fibAcc {n = n, a = 0, b = 1}
            fib {n = 10}
        """
        // fib(10) = 55 (0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55)
        assertEquals(55L, eval(code))
    }

    @Test
    fun `test tail-recursive gcd`() {
        val code = """
            let gcd = {a, b} ->
                if b == 0 then a
                else gcd {a = b, b = a % b}

            gcd {a = 48, b = 18}
        """
        // gcd(48, 18) = 6
        assertEquals(6L, eval(code))
    }

    @Test
    fun `test tail call in match expression`() {
        val code = """
            let length = {list, acc} ->
                match list is
                    Nil -> acc
                    Cons {head, tail} -> length {list = tail, acc = acc + 1}

            let myList = Cons {head = 1, tail = Cons {head = 2, tail = Cons {head = 3, tail = Nil}}}
            length {list = myList, acc = 0}
        """
        assertEquals(3L, eval(code))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `test deep recursion does not stack overflow`() {
        // This test verifies TCO by running a very deep recursion
        // Without TCO, this would cause a StackOverflowError
        val code = """
            let countdown = {n} ->
                if n == 0 then 0
                else countdown {n = n - 1}

            countdown {n = 10000}
        """
        assertEquals(0L, eval(code))
    }

    @Test
    fun `test non-tail-recursive function still works`() {
        // This is NOT a tail call (the recursive call is not in tail position)
        // Should still work for small inputs
        val code = """
            let factorial = {n} ->
                if n == 0 then 1
                else n * factorial {n = n - 1}

            factorial {n = 5}
        """
        assertEquals(120L, eval(code))
    }

    @Test
    fun `test tail call in nested if - positive`() {
        val code = """
            let classify = {n, result} ->
                if n == 0 then result
                else if n > 0 then classify {n = n - 1, result = result + 1}
                else classify {n = n + 1, result = result - 1}

            classify {n = 5, result = 0}
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `test tail call in nested if - negative`() {
        val code = """
            let classify = {n, result} ->
                if n == 0 then result
                else if n > 0 then classify {n = n - 1, result = result + 1}
                else classify {n = n + 1, result = result - 1}

            classify {n = 0 - 3, result = 0}
        """
        assertEquals(-3L, eval(code))
    }

    @Test
    fun `test tail call in do expression`() {
        val code = """
            let process = {n, acc} -> do
                let doubled = n * 2
                if doubled == 0 then acc
                else process {n = n - 1, acc = acc + doubled}

            process {n = 3, acc = 0}
        """
        // 3*2 + 2*2 + 1*2 = 6 + 4 + 2 = 12
        assertEquals(12L, eval(code))
    }

    // ---- The negative side of TCO: a call in an OPERAND position is not a tail
    // call, and compiling it as one silently returns the wrong value.
    //
    // Tail position is syntactic. A recursive call inside a constructor payload,
    // a field receiver or an argument is followed by the construction, the read
    // or the call, so the self-tail-loop must not fire. It did once: converging
    // the constraint-result plumbing onto `copy` let the payload's `tailCalls`
    // escape, and a two-element `map` returned `Empty` — no error, just the wrong
    // answer. `ConstraintResult.operandOf` is the rule; these are the cases.

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `a recursive call inside a constructor payload is NOT a tail call`() {
        val code = """
            type IntList = | Empty, Node { value: Int, next: IntList } |

            let map = {fn, list: IntList} -> match list is
                Empty -> Empty
                Node {value, next} -> Node {value = fn {x = value}, next = map {fn = fn, list = next}}

            let doubled = map {
                fn = {x} -> x * 2,
                list = Node {value = 1, next = Node {value = 2, next = Empty}}
            }
            match doubled is
                Node {value, next} -> value
                Empty -> 0 - 1
        """
        // The whole point is that `map` must REBUILD the list on the way out. A
        // self-tail-loop drops every pending construction and falls out at the
        // base case, so the wrong answer here is `Empty` — which is why the
        // else-arm returns a value that could not arise from a correct run.
        assertEquals(2L, eval(code), "map must rebuild the list; -1 means the payload call was tail-looped")
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `a recursive call under an arithmetic operand is NOT a tail call`() {
        // The same rule without any variant involved: the `+` has to happen after
        // the call returns, so every frame must survive it.
        val code = """
            let sum_to = {n} ->
                if n == 0 then 0
                else n + sum_to {n = n - 1}

            sum_to {n = 10}
        """
        assertEquals(55L, eval(code))
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun `a genuine tail call still loops`() {
        // The control. Clearing `tailCalls` at operand positions must not reach
        // the position that really is one — an accumulator recursion still has to
        // run in constant stack.
        val code = """
            let count = {n, acc} ->
                if n == 0 then acc
                else count {n = n - 1, acc = acc + 1}

            count {n = 200000, acc = 0}
        """
        assertEquals(200000L, eval(code), "a real tail call must still be looped, not stack-recursed")
    }
}
