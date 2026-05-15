package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

/**
 * Regression tests for #194 — calling an ability method (e.g. `.to_str {}`,
 * `.abs {}`) on a `let`-bound value rejects with "Cannot access field
 * 'X' on non-record type" when the let-bound value came from a function
 * call. The inline form (skipping the let-binding) typechecks fine.
 *
 * Reproduces:
 *
 *     let result = double { n = 21 }
 *     result.to_str {}            # ERROR: 'to_str' on non-record Int
 *
 * Works (today):
 *
 *     double { n = 21 }.to_str {} # OK
 *
 * The bug presumably lives in constraint generation / ability resolution
 * around let-bound variables whose type is bound by a function call. The
 * `result` symbol's type info loses its ability/binding context somehow.
 *
 * These tests assert what *should* typecheck. They will FAIL until the
 * bug is fixed — that's intentional; they document the contract.
 */
class LetBoundAbilityCallTest {
    private val parser = MezzeParser()

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
    }

    private fun assertTypeChecks(code: String) {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typeChecker = TypeChecker()
        typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ── Inline forms (baseline — these already work) ───────────────────────

    @Test
    fun `inline call result + to_str works`() {
        // Sanity check: this is the form that already passes.
        assertTypeChecks(
            """
            let double = { n: Int } -> n + n
            let main = {} -> do
              double { n = 21 }.to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `let-bound literal Int + to_str works`() {
        // Sanity check: binding a literal Int and calling .to_str on it
        // is fine. The bug is specific to function-call results.
        assertTypeChecks(
            """
            let main = {} -> do
              let x = 42
              x.to_str {}
            """.trimIndent()
        )
    }

    // ── The bug ────────────────────────────────────────────────────────────

    @Test
    fun `let-bound function-call result + to_str should work`() {
        assertTypeChecks(
            """
            let double = { n: Int } -> n + n
            let main = {} -> do
              let result = double { n = 21 }
              result.to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `let-bound function-call result + abs should work`() {
        // Same shape with a different ability method (`abs` from std::int).
        assertTypeChecks(
            """
            let double = { n: Int } -> n + n
            let main = {} -> do
              let result = double { n = 21 }
              result.abs {}
            """.trimIndent()
        )
    }

    @Test
    fun `let-bound recursive call result + to_str should work`() {
        // The original repro that surfaced the bug — recursive fib.
        assertTypeChecks(
            """
            let fib = { n: Int } -> do
              if n < 2 then n
              else fib { n = n - 1 } + fib { n = n - 2 }

            let main = {} -> do
              let result = fib { n = 35 }
              result.to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `let-bound function-call result + chained abs to_str should work`() {
        // The original mezze_demo form: `x.abs {}.to_str {}` but with a
        // function-call result bound first.
        assertTypeChecks(
            """
            let double = { n: Int } -> n + n
            let main = {} -> do
              let result = double { n = 8 }
              result.abs {}.to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `let-bound function-call result inside string interpolation should work`() {
        // Wrapping the call in $(...) is how the original `mez build`
        // smoke tests hit this. Worth covering separately because
        // interpolation drives parsing through a different path.
        assertTypeChecks(
            """
            let double = { n: Int } -> n + n
            let main = {} -> do
              let result = double { n = 21 }
              "value = ${'$'}(result.to_str {})"
            """.trimIndent()
        )
    }
}
