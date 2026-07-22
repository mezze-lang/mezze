package com.mezze.interpreter.bytecode

import org.junit.jupiter.api.Test

/**
 * Smoke test for [CpsDifferentialHarness]. Under Phase 1.2 the CPS
 * transform is identity, so the two pipelines are trivially equivalent —
 * these tests just prove the harness wiring runs both pipelines end to
 * end without either crashing.
 *
 * Phase 1.4+ adds real transform coverage: effect programs will land
 * here as they're rewritten.
 */
class CpsDifferentialHarnessTest {

    @Test
    fun `pure arithmetic runs identically under CPS`() {
        CpsDifferentialHarness.assertEquivalent("40 + 2", 42L)
    }

    @Test
    fun `let binding runs identically under CPS`() {
        CpsDifferentialHarness.assertEquivalent(
            """
            let x = 10
            x * 4 + 2
            """.trimIndent(),
            42L
        )
    }

    @Test
    fun `record + field access runs identically under CPS`() {
        CpsDifferentialHarness.assertEquivalent(
            """
            let r = { a = 40, b = 2 }
            r.a + r.b
            """.trimIndent(),
            42L
        )
    }

    @Test
    fun `if-else runs identically under CPS`() {
        CpsDifferentialHarness.assertEquivalent(
            """
            if 1 == 1 then 42 else 0
            """.trimIndent(),
            42L
        )
    }
}
