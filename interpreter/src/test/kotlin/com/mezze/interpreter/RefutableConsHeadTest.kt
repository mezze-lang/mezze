package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * B7: an open-cons seq arm with a REFUTABLE head (`[Some { val = x }, ...rest]`)
 * was counted total on the length axis alone — a `[None]` input satisfied
 * "length >= 1", matched the arm, bound `x = null` (the Some payload extraction
 * on a None), and crashed at `x + 100` ("Cannot add null and Long"). The head
 * constructor/literal must be tested at runtime, so `[None]` falls through.
 */
class RefutableConsHeadTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `open-cons arm with a refutable head does not match a non-matching head`() {
        // `[None]` must NOT match `[Some {val=x}, ...rest]` — it should fall
        // through. With only these two arms it is non-exhaustive, so we add a
        // catch-all to make it total AND observe which arm fires.
        val src = """
            let f = { xs } -> match xs is
              [Some { val = x }, ...rest] -> x + 100
              other -> 0
            f { xs = [None] }
        """
        assertEquals(0L, eval(src))  // [None] head is None, not Some → catch-all
    }

    @Test
    fun `open-cons arm with a refutable head matches a matching head`() {
        val src = """
            let f = { xs } -> match xs is
              [Some { val = x }, ...rest] -> x + 100
              other -> 0
            f { xs = [Some { val = 5 }, None] }
        """
        assertEquals(105L, eval(src))  // head is Some{val=5} → 5 + 100
    }

    @Test
    fun `open-cons refutable head with the empty-list arm before it`() {
        val src = """
            let f = { xs } -> match xs is
              [] -> 0
              [Some { val = x }, ...rest] -> x + 100
              other -> 999
            f { xs = [None] }
        """
        assertEquals(999L, eval(src))  // [None]: not [], head not Some → catch-all
    }
}
