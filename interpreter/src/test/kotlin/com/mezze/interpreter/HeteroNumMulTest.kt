package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Heterogeneous `*` (NumMul `'r for 'l having 'out`) end-to-end.
 *
 * Class-parameterized like NumAdd/NumSub, so mixed-type multiplication resolves
 * per ordered `(l, r)` pair. Unlike NumSub (`Nat - Nat -> Int`), multiplication
 * never underflows, so `Nat * Nat` stays `Nat` — matching NumAdd.
 */
class HeteroNumMulTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `same-type multiplication`() {
        assertEquals(30L, eval("6 * 5"))
        assertEquals(6.25, eval("2.5 * 2.5") as Double, 1e-9)
        assertEquals(12L, eval("3n * 4n").let { (it as Number).toLong() })
    }

    @Test
    fun `Int times Float widens to Float`() {
        assertEquals(25.0, eval("10 * 2.5") as Double, 1e-9)
    }

    @Test
    fun `Int times Nat stays Int`() {
        assertEquals(30L, eval("10 * 3n"))
    }

    @Test
    fun `Float times Int widens to Float`() {
        assertEquals(11.0, eval("5.5 * 2") as Double, 1e-9)
    }

    @Test
    fun `Float times Nat widens to Float`() {
        assertEquals(11.0, eval("5.5 * 2n") as Double, 1e-9)
    }

    @Test
    fun `Nat times Nat stays Nat`() {
        // Multiplication never underflows, so unlike Nat - Nat this stays Nat.
        assertEquals(20L, eval("4n * 5n").let { (it as Number).toLong() })
    }

    @Test
    fun `Nat times Int stays Int`() {
        assertEquals(30L, eval("10n * 3"))
    }

    @Test
    fun `Nat times Float widens to Float`() {
        assertEquals(25.0, eval("10n * 2.5") as Double, 1e-9)
    }
}
