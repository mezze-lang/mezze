package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Heterogeneous `-` (NumSub `'r for 'l having 'out`) end-to-end.
 *
 * NumSub is class-parameterized like NumAdd, so mixed-type subtraction resolves
 * to a distinct impl per ordered `(l, r)` pair. The one asymmetry vs NumAdd:
 * `Nat - Nat` can underflow, so its result widens to `Int` (via
 * `nat::subtract_to_int`) rather than staying `Nat`.
 */
class HeteroNumSubTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `same-type subtraction`() {
        assertEquals(7L, eval("10 - 3"))
        assertEquals(1.0, eval("2.5 - 1.5") as Double, 1e-9)
        assertEquals(7L, eval("10n - 3n").let { if (it is Long) it else (it as Number).toLong() })
    }

    @Test
    fun `Int minus Float widens to Float`() {
        assertEquals(7.5, eval("10 - 2.5") as Double, 1e-9)
    }

    @Test
    fun `Int minus Nat stays Int`() {
        assertEquals(7L, eval("10 - 3n"))
    }

    @Test
    fun `Float minus Int widens to Float`() {
        assertEquals(3.5, eval("5.5 - 2") as Double, 1e-9)
    }

    @Test
    fun `Float minus Nat widens to Float`() {
        assertEquals(3.5, eval("5.5 - 2n") as Double, 1e-9)
    }

    @Test
    fun `Nat minus Nat widens to Int and may go negative`() {
        // The whole point of Nat - Nat -> Int: underflow yields a negative Int,
        // not a wrapped/None Nat.
        assertEquals(-2L, eval("3n - 5n"))
        assertEquals(2L, eval("5n - 3n"))
    }

    @Test
    fun `Nat minus Int stays Int`() {
        assertEquals(7L, eval("10n - 3"))
        assertEquals(-5L, eval("3n - 8"))
    }

    @Test
    fun `Nat minus Float widens to Float`() {
        assertEquals(7.5, eval("10n - 2.5") as Double, 1e-9)
    }
}
