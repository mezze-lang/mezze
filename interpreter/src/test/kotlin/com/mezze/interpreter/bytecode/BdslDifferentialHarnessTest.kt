package com.mezze.interpreter.bytecode

import org.junit.jupiter.api.Test

/**
 * Exercises [BdslDifferentialHarness] against a handful of Mezze
 * programs. Each test asserts the AST + BDSL interpreters agree on the
 * output (or the AST alone pins the value when the program falls
 * outside BDSL's current subset).
 *
 * As BDSL grows support in later phases, these tests transparently
 * upgrade from "AST-only pinned" to "full differential" without any
 * test-side changes.
 *
 * Task #280, plan doc docs/perf/bytecode-dsl-migration-2026-07.md.
 */
class BdslDifferentialHarnessTest {

    @Test
    fun `pure long literal — proven differential`() {
        // requiring-BDSL variant confirms the harness actually
        // exercises both interpreters for this program.
        BdslDifferentialHarness.assertEquivalentRequiringBdsl("42", expected = 42L)
    }

    @Test
    fun `nested long add — proven differential`() {
        BdslDifferentialHarness.assertEquivalentRequiringBdsl("1 + 2 + 3 + 4", expected = 10L)
    }

    @Test
    fun `let-bind top-level — differential across AST and BDSL`() {
        // Simple do-block with a let and an expression using the bound
        // value. Exercises BlockIR / StmtIR.Let / ReadLocal.
        BdslDifferentialHarness.assertEquivalent(
            """
            do
              let x = 10
              x + 5
            """.trimIndent(),
            expected = 15L,
        )
    }

    @Test
    fun `if expression — differential across AST and BDSL`() {
        BdslDifferentialHarness.assertEquivalent(
            """
            if 1 == 1 then 100 else 200
            """.trimIndent(),
            expected = 100L,
        )
    }

    @Test
    fun `record construct + field read — differential (Phase 2)`() {
        // MakeRecord + ReadFieldByName + AddLong, all in BDSL.
        BdslDifferentialHarness.assertEquivalentRequiringBdsl(
            """
            do
              let r = { x = 10, y = 20 }
              r.x + r.y
            """.trimIndent(),
            expected = 30L,
        )
    }

    @Test
    fun `nested record field read — differential (Phase 2)`() {
        BdslDifferentialHarness.assertEquivalentRequiringBdsl(
            """
            do
              let inner = { v = 7 }
              let outer = { inner = inner, extra = 100 }
              outer.inner.v + outer.extra
            """.trimIndent(),
            expected = 107L,
        )
    }

    @Test
    fun `program using stdlib — falls back to AST-only`() {
        // std::int::to_str isn't in BDSL's Phase 1 subset (needs the
        // native-call op). The harness silently skips the BDSL side
        // but AST still pins the value.
        BdslDifferentialHarness.assertEquivalent(
            """
            use std::int::{ to_str }
            to_str { n = 42 }
            """.trimIndent(),
            expected = "42",
        )
    }
}
