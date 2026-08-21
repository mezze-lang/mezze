package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Heterogeneous `%` (NumMod `'r for 'l having 'out`) end-to-end.
 *
 * Class-parameterized like the other numeric ops. Rule (same as NumDiv): any
 * `Float` operand promotes to a float remainder (`-> Float`); otherwise an
 * integer remainder, and `Nat % Nat` stays `Nat`. There is no `Frac` impl —
 * modulo on rationals is undefined.
 */
class HeteroNumModTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `same-type modulo`() {
        assertEquals(1L, eval("7 % 3"))                // Int
        assertEquals(1.5, eval("7.5 % 2.0") as Double, 1e-9)
        assertEquals(1L, eval("7n % 3n").let { (it as Number).toLong() })  // Nat
    }

    @Test
    fun `Int mod Float widens to Float`() {
        assertEquals(1.0, eval("7 % 2.0") as Double, 1e-9)
    }

    @Test
    fun `Int mod Nat stays Int`() {
        assertEquals(1L, eval("7 % 3n"))
    }

    @Test
    fun `Float mod Int widens to Float`() {
        assertEquals(1.5, eval("7.5 % 2") as Double, 1e-9)
    }

    @Test
    fun `Float mod Nat widens to Float`() {
        assertEquals(1.5, eval("7.5 % 2n") as Double, 1e-9)
    }

    @Test
    fun `Nat mod Nat stays Nat`() {
        assertEquals(1L, eval("7n % 3n").let { (it as Number).toLong() })
    }

    @Test
    fun `Nat mod Int stays Int`() {
        assertEquals(1L, eval("7n % 2"))
    }

    @Test
    fun `Nat mod Float widens to Float`() {
        assertEquals(1.0, eval("7n % 2.0") as Double, 1e-9)
    }
}
