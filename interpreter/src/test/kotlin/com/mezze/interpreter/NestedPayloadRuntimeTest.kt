package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * B4 / B6: a nested SEQ or STRING-INTERPOLATION sub-pattern inside a variant/
 * record payload field must be tested at runtime. Before the fix, buildNestedTests
 * returned null for these, so the arm matched on the variant tag / field presence
 * ALONE and fired the wrong arm (silent wrong result, no crash, no type error).
 */
class NestedPayloadRuntimeTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ===== B4: nested seq sub-pattern in a variant payload =====

    @Test
    fun `nested empty-seq arm does not match a two-element seq`() {
        val src = """
            type Outer = | Wrap { xs: [Int] } |
            let f = { o } -> match o is
              Wrap { xs = [] } -> 111
              Wrap { xs = other } -> 222
            f { o = Wrap { xs = [10, 20] } }
        """
        assertEquals(222L, eval(src))  // [10,20] is not [], so second arm
    }

    @Test
    fun `nested fixed-length seq arm matches only the right length`() {
        val src = """
            type Outer = | Wrap { xs: [Int] } |
            let f = { o } -> match o is
              Wrap { xs = [a, b] } -> a + b
              Wrap { xs = other } -> 999
            f { o = Wrap { xs = [3] } }
        """
        assertEquals(999L, eval(src))  // [3] has length 1, not 2 → fallthrough
    }

    @Test
    fun `nested two-element seq arm matches a two-element seq`() {
        val src = """
            type Outer = | Wrap { xs: [Int] } |
            let f = { o } -> match o is
              Wrap { xs = [a, b] } -> a + b
              Wrap { xs = other } -> 999
            f { o = Wrap { xs = [3, 4] } }
        """
        assertEquals(7L, eval(src))  // [3,4] → a=3 b=4 → 7
    }

    // ===== B6: nested string-interpolation sub-pattern in a variant payload =====

    @Test
    fun `nested string-prefix arm does not match a non-matching string`() {
        val src = """
            type Msg = | Wrap { s: Str } |
            let f = { o } -> match o is
              Wrap { s = "hello$(rest)" } -> 111
              Wrap { s = other } -> 222
            f { o = Wrap { s = "world" } }
        """
        assertEquals(222L, eval(src))  // "world" has no "hello" prefix → second arm
    }

    @Test
    fun `nested string-prefix arm matches a matching string`() {
        val src = """
            type Msg = | Wrap { s: Str } |
            let f = { o } -> match o is
              Wrap { s = "hello$(rest)" } -> 111
              Wrap { s = other } -> 222
            f { o = Wrap { s = "hello there" } }
        """
        assertEquals(111L, eval(src))  // "hello there" starts with "hello" → first arm
    }
}
