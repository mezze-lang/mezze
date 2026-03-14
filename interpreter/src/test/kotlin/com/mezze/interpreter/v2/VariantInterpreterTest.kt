package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Tests for variant (sum type) construction and pattern matching.
 */
class VariantInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Simple Variant Construction
    // ========================================

    @Test
    fun `test simple variant construction - Success with value`() {
        val code = """
            type MyResult = | MySuccess { value: Int }, MyFailure { error: Str }, MyLoading |
            let success: MyResult = MySuccess { value = 42 }
            match success is
                MySuccess { value } -> value
                MyFailure { error } -> -1
                MyLoading -> -2
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test simple variant construction - Failure with error`() {
        val code = """
            type MyResult = | MySuccess { value: Int }, MyFailure { error: Str }, MyLoading |
            let failure: MyResult = MyFailure { error = "Something went wrong" }
            match failure is
                MySuccess { value } -> "ok"
                MyFailure { error } -> error
                MyLoading -> "loading"
        """
        val result = eval(code)
        assertEquals("Something went wrong", result)
    }

    @Test
    fun `test simple variant construction - Loading unit variant`() {
        val code = """
            type MyResult = | MySuccess { value: Int }, MyFailure { error: Str }, MyLoading |
            let loading: MyResult = MyLoading
            match loading is
                MySuccess { value } -> 1
                MyFailure { error } -> 2
                MyLoading -> 3
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // ========================================
    // Variant Pattern Matching
    // ========================================

    @Test
    fun `test variant pattern matching with function`() {
        val code = """
            type Status = | Success { value: Int }, Failure { error: Str }, Loading |

            let handleStatus = { status: Status } -> match status is
                Success { value } -> "Got value"
                Failure { error } -> "Error: $(error)"
                Loading -> "Still loading..."

            let success = Success { value = 100 }
            handleStatus { status = success }
        """
        val result = eval(code)
        assertEquals("Got value", result)
    }

    @Test
    fun `test variant pattern matching returns correct branch for failure`() {
        val code = """
            type Status = | Success { value: Int }, Failure { error: Str }, Loading |

            let handleStatus = { status: Status } -> match status is
                Success { value } -> "Got value"
                Failure { error } -> "Error: $(error)"
                Loading -> "Still loading..."

            let failure = Failure { error = "Network error" }
            handleStatus { status = failure }
        """
        val result = eval(code)
        assertEquals("Error: Network error", result)
    }

    @Test
    fun `test variant pattern matching returns correct branch for loading`() {
        val code = """
            type Status = | Success { value: Int }, Failure { error: Str }, Loading |

            let handleStatus = { status: Status } -> match status is
                Success { value } -> "Got value"
                Failure { error } -> "Error: $(error)"
                Loading -> "Still loading..."

            let loading = Loading
            handleStatus { status = loading }
        """
        val result = eval(code)
        assertEquals("Still loading...", result)
    }

    // ========================================
    // Variant with Numeric Data
    // ========================================

    @Test
    fun `test variant with numeric data - Zero`() {
        val code = """
            type Number = | Zero, Positive { value: Int }, Negative { value: Int } |

            let processNumber = { num: Number } -> match num is
                Zero -> 0
                Positive { value } -> value
                Negative { value } -> 0 - value

            let zero = Zero
            processNumber { num = zero }
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test variant with numeric data - Positive`() {
        val code = """
            type Number = | Zero, Positive { value: Int }, Negative { value: Int } |

            let processNumber = { num: Number } -> match num is
                Zero -> 0
                Positive { value } -> value
                Negative { value } -> 0 - value

            let pos = Positive { value = 15 }
            processNumber { num = pos }
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test variant with numeric data - Negative`() {
        val code = """
            type Number = | Zero, Positive { value: Int }, Negative { value: Int } |

            let processNumber = { num: Number } -> match num is
                Zero -> 0
                Positive { value } -> value
                Negative { value } -> 0 - value

            let neg = Negative { value = 8 }
            processNumber { num = neg }
        """
        val result = eval(code)
        assertEquals(-8L, result)
    }

    // ========================================
    // Variant with Boolean Logic
    // ========================================

    @Test
    fun `test variant with boolean logic - Always`() {
        val code = """
            type Condition = | Always, Never, Sometimes { probability: Int } |

            let evaluateCondition = { condition: Condition } -> match condition is
                Always -> True
                Never -> False
                Sometimes { probability } -> probability > 50

            let always = Always
            evaluateCondition { condition = always }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test variant with boolean logic - Never`() {
        val code = """
            type Condition = | Always, Never, Sometimes { probability: Int } |

            let evaluateCondition = { condition: Condition } -> match condition is
                Always -> True
                Never -> False
                Sometimes { probability } -> probability > 50

            let never = Never
            evaluateCondition { condition = never }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test variant with boolean logic - Sometimes likely`() {
        val code = """
            type Condition = | Always, Never, Sometimes { probability: Int } |

            let evaluateCondition = { condition: Condition } -> match condition is
                Always -> True
                Never -> False
                Sometimes { probability } -> probability > 50

            let likely = Sometimes { probability = 75 }
            evaluateCondition { condition = likely }
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test variant with boolean logic - Sometimes unlikely`() {
        val code = """
            type Condition = | Always, Never, Sometimes { probability: Int } |

            let evaluateCondition = { condition: Condition } -> match condition is
                Always -> True
                Never -> False
                Sometimes { probability } -> probability > 50

            let unlikely = Sometimes { probability = 25 }
            evaluateCondition { condition = unlikely }
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // ========================================
    // Variant Construction in Functions
    // ========================================

    @Test
    fun `test variant construction in function - success case`() {
        val code = """
            type MyResult = | Success { value: Int }, Failure { error: Str } |

            let createResult = { success: Bool, value: Int, error: Str } ->
                if success
                then Success { value = value }
                else Failure { error = error }

            let result = createResult { success = True, value = 42, error = "" }
            match result is
                Success { value } -> value
                Failure { error } -> -1
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test variant construction in function - failure case`() {
        val code = """
            type MyResult = | Success { value: Int }, Failure { error: Str } |

            let createResult = { success: Bool, value: Int, error: Str } ->
                if success
                then Success { value = value }
                else Failure { error = error }

            let result = createResult { success = False, value = 0, error = "Failed" }
            match result is
                Success { value } -> "ok"
                Failure { error } -> error
        """
        val result = eval(code)
        assertEquals("Failed", result)
    }

    @Test
    fun `test Option-like variant construction - Some`() {
        val code = """
            type MyOption = | MySome { value: Int }, MyNone |

            let wrapValue = { x: Int } ->
                if x >= 0
                then MySome { value = x }
                else MyNone

            let option = wrapValue { x = 10 }
            match option is
                MySome { value } -> value
                MyNone -> -1
        """
        val result = eval(code)
        assertEquals(10L, result)
    }

    @Test
    fun `test Option-like variant construction - None`() {
        val code = """
            type MyOption = | MySome { value: Int }, MyNone |

            let wrapValue = { x: Int } ->
                if x >= 0
                then MySome { value = x }
                else MyNone

            let option = wrapValue { x = -5 }
            match option is
                MySome { value } -> value
                MyNone -> -1
        """
        val result = eval(code)
        assertEquals(-1L, result)
    }

    // ========================================
    // Built-in Option Type Tests
    // ========================================

    @Test
    fun `test built-in Option Some construction`() {
        val code = """
            let opt: (Option Int) = Some { val = 42 }
            match opt is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test built-in Option None construction`() {
        val code = """
            let opt: (Option Int) = None
            match opt is
                Some { val } -> val
                None -> -1
        """
        val result = eval(code)
        assertEquals(-1L, result)
    }

    // ========================================
    // Built-in Result Type Tests
    // ========================================

    @Test
    fun `test built-in Result Ok construction`() {
        val code = """
            let res: (Result Int Str) = Ok { val = 42 }
            match res is
                Ok { val } -> val
                Err { error } -> -1
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test built-in Result Err construction`() {
        val code = """
            let res: (Result Int Str) = Err { error = "failed" }
            match res is
                Ok { val } -> "ok"
                Err { error } -> error
        """
        val result = eval(code)
        assertEquals("failed", result)
    }
}
