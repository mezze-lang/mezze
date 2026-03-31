package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for Phase 3: Truffle Optimization Basics
 *
 * These tests verify that basic Truffle-optimized execution works correctly:
 * - Function calls
 * - Closures
 * - Record access
 * - Pattern matching
 * - Effect handlers
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class TruffleBasicsTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Function Call Tests
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
    fun testHigherOrderFunction() {
        val code = """
            let apply = {f, x} -> f {val = x}
            let double = {val} -> val * 2
            apply {f = double, x = 21}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Closure Tests
    // =============================================================================

    @Test
    fun testSimpleClosure() {
        val code = """
            let x = 5
            let addX = {y} -> x + y
            addX {y = 10}
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
                let inner = {y} -> x + y
                inner {y = 5}

            outer {x = 10}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    // =============================================================================
    // Record Access Tests
    // =============================================================================

    @Test
    fun testRecordFieldAccess() {
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
    fun testRecordUpdate() {
        val code = """
            let point = {x = 5, y = 10}
            let updated = {...point, x = 20}
            updated.x + updated.y
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // Pattern Matching Tests
    // =============================================================================

    @Test
    fun testSimpleVariantMatch() {
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
    fun testVariantWithValueMatch() {
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
        val code = """
            let getValue = {x} ->
                match x is
                    0 -> Red
                    1 -> Blue {val = 100}

            let result = getValue {x = 1}
            match result is
                Red -> 0
                Blue {val} -> val
        """
        val result = eval(code)
        assertEquals(100L, result)
    }

    // =============================================================================
    // Effect Handler Tests
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
                compute: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                compute: {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            perform Calculator in Math.compute {x = 10, y = 32}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testNestedEffectCalls() {
        val code = """
            effect Math where {
                compute: {x: Int, y: Int} -> Int <Math>,
                mul: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                compute: {x: Int, y: Int} -> Int = {x, y} -> x + y,
                mul: {x: Int, y: Int} -> Int = {x, y} -> x * y
            }

            perform Calculator in do
                let sum = Math.compute {x = 2, y = 3}
                Math.mul {x = sum, y = 4}
        """
        val result = eval(code)
        assertEquals(20L, result)
    }

    // =============================================================================
    // Performance Verification Tests
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
    fun testComplexClosureExecution() {
        val code = """
            let makeMultiplier = {factor} -> {
                apply = {x} -> x * factor,
                applyTwice = {x} -> (x * factor) * factor,
                getFactor = {} -> factor
            }

            let multiplier = makeMultiplier {factor = 3}
            let r1 = multiplier.apply {x = 5}
            let r2 = multiplier.applyTwice {x = 2}
            let r3 = multiplier.getFactor {}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(36L, result)  // 15 + 18 + 3 = 36
    }

    @Test
    fun testComplexPatternMatching() {
        val code = """
            let processValue = {v} ->
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
}
