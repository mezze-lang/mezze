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

    /**
     * Asserts the program is REJECTED by the typechecker. Normally a
     * [com.mezze.language.typechecker.TypeCheckError] (e.g. T0206 undispatchable
     * inherent method). Under `-Dmezze.invariants=true` (the gate), the S0.2
     * no-unresolved-dispatch-var invariant catches the same "receiver never
     * pinned to a concrete/candidate type" class FIRST and throws
     * [InvariantChecker.InvariantViolation] — an equally-valid rejection of the
     * same unsound program, so accept either.
     */
    private fun assertTypeError(code: String) {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typeChecker = TypeChecker()
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            Exception::class.java
        ) {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        val ok = ex is com.mezze.language.typechecker.TypeCheckError ||
            ex is com.mezze.language.typechecker.debug.InvariantChecker.InvariantViolation
        org.junit.jupiter.api.Assertions.assertTrue(ok) {
            "expected TypeCheckError or InvariantViolation, got ${ex.javaClass.name}: ${ex.message}"
        }
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

    // ── #160: POLYMORPHIC callee (return type is a tyvar until solving) ─────
    //
    // The cases above use a monomorphic `double : {n:Int} -> Int` — its return
    // is concretely Int at generation time. When the callee is polymorphic
    // (`help : {a:'l,b:'r} with NumAdd 'r 'l having 'out -> 'out`, whose 'out is
    // pinned to Int only by its literal args during solving), the inherent
    // method's target type can't be chosen statically. These exercise the
    // deferred-dispatch path.

    @Test
    fun `parenthesized literal + to_str works`() {
        // `(5)` is transparent to type; the parens must not hide Int from
        // inherent-method dispatch.
        assertTypeChecks("let g = {} -> (5).to_str {}")
    }

    @Test
    fun `polymorphic call result + to_str (inline) works`() {
        assertTypeChecks(
            """
            let help = { a, b } -> a + b
            let g = {} -> help { a = 5, b = 6 }.to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `polymorphic call result + to_str (parenthesized) works`() {
        assertTypeChecks(
            """
            let help = { a, b } -> a + b
            let g = {} -> (help { a = 5, b = 6 }).to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `let-bound polymorphic call result + to_str in interpolation works`() {
        // The exact shape the user hit: `let x = help {..}` (polymorphic help)
        // then `x.to_str {}` inside `$(...)`.
        assertTypeChecks(
            """
            let help = { a, b } -> a + b
            let main = {} -> do
              let x = help { a = 5, b = 6 }
              "hi ${'$'}(x.to_str {})"
            """.trimIndent()
        )
    }

    @Test
    fun `chained abs then to_str on a let-bound Int works`() {
        assertTypeChecks(
            """
            let g = {} -> do
              let x = 5
              x.abs {}.to_str {}
            """.trimIndent()
        )
    }

    // ── #160: RECEIVER-DEPENDENT RETURN TYPE (abs: Int->Int, Float->Float, …) ──
    //
    // Unlike `to_str` (all impls return Str), `abs`'s return type VARIES by
    // receiver, so there is no single representative signature to pick at
    // generation time. These exercise the deferred InherentMethodResolve path:
    // the solver picks the impl AND its return type once the receiver is pinned.

    @Test
    fun `abs on a polymorphic call result (Int) works`() {
        // Under the class-parameter NumAdd ability, `help : {a:'l,b:'r} with
        // NumAdd 'r 'l having 'out -> 'out`. `help { a = 5, b = 6 }` binds both
        // 'l and 'r to Int, so the Int+Int impl resolves 'out to Int; `.abs {}`
        // must then dispatch to Int.abs and the call type is Int.
        assertTypeChecks(
            """
            let help = { a, b } -> a + b
            let g = {} -> (help { a = 5, b = 6 }).abs {}
            """.trimIndent()
        )
    }

    @Test
    fun `abs on a polymorphic call result (Float) works`() {
        // Same shape but the args pin 'l and 'r to Float, so the Float+Float impl
        // resolves 'out to Float and `.abs {}` dispatches to Float.abs whose return
        // is Float. Proves the return type follows the solved receiver.
        assertTypeChecks(
            """
            let help = { a, b } -> a + b
            let g = {} -> (help { a = 5.0, b = 6.0 }).abs {}
            """.trimIndent()
        )
    }

    @Test
    fun `abs then to_str chain on a polymorphic call result works`() {
        // `(help {..}).abs {}.to_str {}` — a two-link chain of deferred inherent
        // methods: to_str's receiver is abs's (deferred) result. Both resolve.
        assertTypeChecks(
            """
            let help = { a, b } -> a + b
            let g = {} -> (help { a = 5, b = 6 }).abs {}.to_str {}
            """.trimIndent()
        )
    }

    @Test
    fun `abs on a fully-unconstrained lambda param stays a hard error`() {
        // `{x} -> x.abs {}` — `x` is a genuinely-polymorphic param with no runtime
        // impl to dispatch to. It infers a structural record `{ x: { abs: … } }`
        // (no def-site error), so CALLING it with a real Int must fail (Int has no
        // `abs` field). Must NOT silently dispatch to a real abs impl (#216).
        assertTypeError(
            """
            let f = { x } -> x.abs {}
            let main = {} -> f { x = 5 }
            """.trimIndent()
        )
    }

    @Test
    fun `abs on a receiver that solves to a non-candidate type is rejected`() {
        // The receiver solves to Bool, which has no `abs` impl (only Int/Float/
        // Frac). Soundly rejected rather than silently mis-dispatched.
        assertTypeError(
            """
            let pick = { x, y } -> if x then y else y
            let main = {} -> (pick { x = True, y = True }).abs {}
            """.trimIndent()
        )
    }
}
