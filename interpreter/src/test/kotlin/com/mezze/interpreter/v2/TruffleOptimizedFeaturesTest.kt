package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Exhaustive tests for Phase 3: Truffle Optimization Features
 *
 * Tests cover:
 * - Optimized function calls
 * - Closure compilation and optimization
 * - Record field access optimization
 * - Pattern matching compilation
 * - Effect handler optimization
 * - Performance characteristics
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class TruffleOptimizedFeaturesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Function Call Optimization Tests
    // =============================================================================

    @Test
    fun testSimpleFunctionCall() {
        val code = """
            let add = {x, y} -> x + y
            add {x = 5, y = 3}
        """
        val result = eval(code)
        assertEquals(8L, result)
    }

    @Test
    fun testNestedFunctionCalls() {
        val code = """
            let add = {x, y} -> x + y
            let mul = {x, y} -> x * y
            mul {x = add {x = 2, y = 3}, y = 4}
        """
        val result = eval(code)
        assertEquals(20L, result)
    }

    @Test
    fun testRecursiveFunction() {
        val code = """
            let factorial = {n} ->
                if n <= 1
                then 1
                else n * factorial {n = n - 1}

            factorial {n = 5}
        """
        val result = eval(code)
        assertEquals(120L, result)
    }

    @Test
    fun testTailRecursion() {
        val code = """
            let sumTo = {n, acc} ->
                if n <= 0
                then acc
                else sumTo {n = n - 1, acc = acc + n}

            sumTo {n = 100, acc = 0}
        """
        val result = eval(code)
        assertEquals(5050L, result)
    }

    @Test
    fun testHigherOrderFunction() {
        val code = """
            let apply = {f, x} -> f {val = x}
            let double = {val} -> val * 2
            apply {f = double, x = 21}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testFunctionComposition() {
        val code = """
            let add5 = {x} -> x + 5
            let mul2 = {x} -> x * 2
            let compose = {f, g, x} -> f {x = g {x = x}}

            compose {f = mul2, g = add5, x = 10}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun testPartialApplication() {
        val code = """
            let add = {x, y} -> x + y
            let add5 = {y} -> add {x = 5, y = y}
            add5 {y = 10}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    // =============================================================================
    // Closure Optimization Tests
    // =============================================================================

    @Test
    fun testSimpleClosure() {
        val code = """
            let x = 10
            let addX = {y} -> x + y
            addX {y = 5}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun testClosureFactory() {
        val code = """
            let createAdder = {base} -> {
                add = {x} -> base + x
            }
            let adder = createAdder {base = 5}
            adder.add {x = 10}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun testClosureWithMultipleCaptures() {
        val code = """
            let a = 10
            let b = 20
            let c = 30
            let sum = {x} -> x + a + b + c
            sum {x = 5}
        """
        val result = eval(code)
        assertEquals(65L, result)
    }

    @Test
    fun testNestedClosures() {
        val code = """
            let outer = {x} -> do
                let y = x * 2
                let inner = {z} -> x + y + z
                inner {z = 5}

            outer {x = 10}
        """
        val result = eval(code)
        assertEquals(35L, result)
    }

    @Test
    fun testClosureWithComputedState() {
        val code = """
            let makeAccumulator = {base} -> {
                add = {x} -> base + x,
                multiply = {x} -> base * x
            }

            let acc = makeAccumulator {base = 10}
            acc.add {x = 5}
        """
        val result = eval(code)
        assertEquals(15L, result)  // 10 + 5 = 15
    }

    // =============================================================================
    // Record Access Optimization Tests
    // =============================================================================

    @Test
    fun testSimpleRecordAccess() {
        val code = """
            let person = {name = "Alice", age = 30}
            person.age
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun testNestedRecordAccess() {
        val code = """
            let data = {
                user = {name = "Bob", id = 123},
                active = True
            }
            data.user.id
        """
        val result = eval(code)
        assertEquals(123L, result)
    }

    @Test
    fun testRecordAccessInLoop() {
        val code = """
            let point = {x = 5, y = 10}
            let sum = {p, n} ->
                if n <= 0
                then 0
                else p.x + p.y + sum {p = p, n = n - 1}

            sum {p = point, n = 5}
        """
        val result = eval(code)
        assertEquals(75L, result)
    }

    @Test
    fun testRecordUpdate() {
        val code = """
            let point = {x = 5, y = 10}
            let updated = {...point, x = 20}
            updated.x + updated.y
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun testMultipleRecordUpdates() {
        val code = """
            let point = {x = 5, y = 10, z = 15}
            let updated1 = {...point, x = 20}
            let updated2 = {...updated1, y = 30}
            updated2.x + updated2.y + updated2.z
        """
        val result = eval(code)
        assertEquals(65L, result)
    }

    // =============================================================================
    // Pattern Matching Optimization Tests
    // =============================================================================

    @Test
    fun testSimplePatternMatch() {
        // Explicitly declare the variant type so both constructors are known
        val code = """
            type Color = | Red, Blue {val: Int} |
            let value: Color = Red
            match value is
                Red -> 1
                Blue {val} -> 2
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun testPatternMatchWithExtraction() {
        // Explicitly declare the variant type so both constructors are known
        val code = """
            type Color = | Red, Blue {val: Int} |
            let value: Color = Blue {val = 42}
            match value is
                Red -> 0
                Blue {val} -> val
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testNestedPatternMatch() {
        // Explicitly declare the variant type so both constructors are known
        val code = """
            type Color = | Red, Blue {val: Int} |
            let getValue: {x: Int} -> Color = {x} ->
                match x is
                    0 -> Red
                    1 -> Blue {val = 100}
                    _ -> Red

            let result = getValue {x = 1}
            match result is
                Red -> 0
                Blue {val} -> val
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun testComplexPatternMatching() {
        // Explicitly declare the variant type so all constructors are known
        val code = """
            type Value = | Red, Blue {val: Int}, Green {x: Int, y: Int} |
            let processValue: {v: Value} -> Int = {v} ->
                match v is
                    Red -> 1
                    Blue {val} -> val * 2
                    Green {x, y} -> x + y

            let r1 = processValue {v = Red}
            let r2 = processValue {v = Blue {val = 10}}
            let r3 = processValue {v = Green {x = 5, y = 7}}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(33L, result)
    }

    // =============================================================================
    // Effect Handler Optimization Tests
    // =============================================================================

    @Test
    fun testSimpleEffectHandler() {
        val code = """
            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
                print: {msg: Str} -> {} = {msg} -> {}
            }

            perform Console in Log.print {msg = "test"}
        """
        val result = eval(code)
        assertTrue(result is MezzeRecord && (result as MezzeRecord).getFieldCount() == 0)
    }

    @Test
    fun testEffectWithReturnValue() {
        val code = """
            effect Math where {
                add: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                add: {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            perform Calculator in Math.add {x = 10, y = 32}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testNestedEffectCalls() {
        val code = """
            effect Math where {
                add: {x: Int, y: Int} -> Int <Math>,
                mul: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                add: {x: Int, y: Int} -> Int = {x, y} -> x + y,
                mul: {x: Int, y: Int} -> Int = {x, y} -> x * y
            }

            perform Calculator in do
                let sum = Math.add {x = 2, y = 3}
                Math.mul {x = sum, y = 4}
        """
        val result = eval(code)
        assertEquals(20L, result)
    }

    @Test
    fun testMultipleEffectHandlers() {
        val code = """
            effect Math where {
                add: {x: Int, y: Int} -> Int <Math>
            }

            effect Log where {
                print: {msg: Str} -> {} <Log>
            }

            impl Math for Calculator where {
                add: {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            impl Log for Console where {
                print: {msg: Str} -> {} = {msg} -> {}
            }

            perform Calculator, Console in do
                Log.print {msg = "Adding"}
                Math.add {x = 10, y = 32}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Performance/Stress Tests
    // =============================================================================

    @Test
    fun testManyFunctionCalls() {
        val code = """
            let add = {x, y} -> x + y
            let chain = {n} ->
                if n <= 0
                then 0
                else add {x = 1, y = chain {n = n - 1}}

            chain {n = 100}
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    @Test
    fun testDeepRecursion() {
        val code = """
            let countdown = {n} ->
                if n <= 0
                then 0
                else countdown {n = n - 1}

            countdown {n = 500}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun testManyClosures() {
        val code = """
            let makeFunctions = {n} ->
                if n <= 0
                then {}
                else do
                    let x = n
                    let f = {y} -> x + y
                    makeFunctions {n = n - 1}

            makeFunctions {n = 50}
        """
        val result = eval(code)
        assertTrue(result is MezzeRecord)
    }
}
