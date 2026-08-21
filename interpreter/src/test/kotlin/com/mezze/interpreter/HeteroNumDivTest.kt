package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Heterogeneous `/` (NumDiv `'r for 'l having 'out`) end-to-end.
 *
 * Class-parameterized like NumAdd/NumSub/NumMul. Rule: any `Float` operand
 * promotes to exact division (`-> Float`); otherwise integer truncation toward
 * zero. `Int / Int`, `Int / Nat`, `Nat / Int`, `Nat / Nat` all truncate
 * (`7 / 2 == 3`); `Nat / Nat` stays `Nat` (never negative), the rest land in
 * `Int`. Any Float mix is exact.
 */
class HeteroNumDivTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `same-type division`() {
        assertEquals(3L, eval("7 / 2"))               // Int trunc
        assertEquals(3.5, eval("7.0 / 2.0") as Double, 1e-9)
        assertEquals(3L, eval("7n / 2n").let { (it as Number).toLong() })  // Nat trunc
    }

    @Test
    fun `Int over Float widens to exact Float`() {
        assertEquals(3.5, eval("7 / 2.0") as Double, 1e-9)
    }

    @Test
    fun `Int over Nat truncates to Int`() {
        assertEquals(3L, eval("7 / 2n"))
    }

    @Test
    fun `Float over Int widens to exact Float`() {
        assertEquals(3.5, eval("7.0 / 2") as Double, 1e-9)
    }

    @Test
    fun `Float over Nat widens to exact Float`() {
        assertEquals(3.5, eval("7.0 / 2n") as Double, 1e-9)
    }

    @Test
    fun `Nat over Nat truncates and stays Nat`() {
        assertEquals(3L, eval("7n / 2n").let { (it as Number).toLong() })
    }

    @Test
    fun `Nat over Int truncates to Int`() {
        assertEquals(3L, eval("7n / 2"))
    }

    @Test
    fun `Nat over Float widens to exact Float`() {
        assertEquals(3.5, eval("7n / 2.0") as Double, 1e-9)
    }
}
