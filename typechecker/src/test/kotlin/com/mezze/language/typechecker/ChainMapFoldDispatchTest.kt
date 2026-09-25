package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Repro for chain-map-fold-dispatch-picks-Bytes bug.
 * xs: [Int]. xs.map{f}.fold{init,f} should pick Seq.map / Seq.fold
 * but the current typechecker locks .fold to Bytes.fold and blows up.
 */
class ChainMapFoldDispatchTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `xs map fold chain routes to Seq not Bytes`() {
        val r = check(
            """
            pub let compute: { xs: [Int] } -> Int = { xs } ->
              xs.map { f = { x } -> x + 1 }.fold { init = 0, f = { acc, x } -> acc + x }
            """.trimIndent()
        )
        // The bug: dispatches .fold to Bytes.fold expecting {acc,byte}.
        // Correct: Seq.fold accepting {acc, x} -> Int.
        assertNotNull(r.getVariableType("compute"), "expected compute to typecheck")
    }

    // ---- #458: effects performed inside chain-callback lambdas must
    // surface on the outer call's effect row (dot-chain form).
    //
    // Before the fix: `xs.map{f = {x} -> do; perform E.op {}; x}` dropped
    // the E effect at the .map call site (inherent-method dispatch harvested
    // effects from `argumentResult.effects` but NOT from row-var-encoded
    // effects on closure fields). The enclosing `perform Handler in do {...}`
    // then reported "body performs nothing" because the row never reached it.
    //
    // Fix: ExprMethodConstraints — three sites now call collectClosureArgEffects
    // and unify the harvested set into the outer effect row, gated on rowVar != null
    // (per adversarial-check: closed rows must not over-propagate).

    @Test
    fun `chain map with perform in callback surfaces effect on outer function`() {
        val r = check("""
            effect MyLog where {
              info : {msg: Str} -> {} <MyLog>
            }
            impl MyLog for TestLogger where {
              info : {msg: Str} -> {} = { msg } -> {}
            }

            pub let compute: {} -> Int <*> = {} ->
              perform TestLogger in do
                [1, 2, 3].map { f = {x} -> do
                    MyLog.info {msg = "hi"}
                    x * 2
                  }
                  .fold { init = 0, f = {acc, x} -> acc + x }
        """.trimIndent())
        // Pre-fix: TypeCheckError "Handler TestLogger handles effect MyLog,
        // but the body performs nothing" — the .map dropped the MyLog effect.
        // Post-fix: harvest carries MyLog through .map, `perform TestLogger`
        // discharges it, compute typechecks.
        assertNotNull(r.getVariableType("compute"),
            "chain map with perform-in-callback should typecheck: effect must reach outer perform-in")
    }

    @Test
    fun `chain filter with perform in predicate surfaces effect`() {
        val r = check("""
            effect MyLog where {
              info : {msg: Str} -> {} <MyLog>
            }
            impl MyLog for TestLogger where {
              info : {msg: Str} -> {} = { msg } -> {}
            }

            pub let compute: {} -> Int <*> = {} ->
              perform TestLogger in do
                [1, 2, 3, 4].filter { p = {x} -> do
                    MyLog.info {msg = "check"}
                    x > 1
                  }
                  .fold { init = 0, f = {acc, x} -> acc + x }
        """.trimIndent())
        assertNotNull(r.getVariableType("compute"),
            "chain filter with perform-in-predicate should typecheck")
    }

    @Test
    fun `chain map+fold with two effects unions both onto outer row`() {
        val r = check("""
            effect E1 where {
              op1 : {} -> {} <E1>
            }
            effect E2 where {
              op2 : {} -> {} <E2>
            }
            impl E1 for H1 where {
              op1 : {} -> {} = {} -> {}
            }
            impl E2 for H2 where {
              op2 : {} -> {} = {} -> {}
            }

            pub let compute: {} -> Int <*> = {} ->
              perform H1 in do
                perform H2 in do
                  [1, 2, 3].map { f = {x} -> do
                      E1.op1 {}
                      x + 1
                    }
                    .fold { init = 0, f = {acc, x} -> do
                        E2.op2 {}
                        acc + x
                      }
        """.trimIndent())
        assertNotNull(r.getVariableType("compute"),
            "chain map+fold with two effects should typecheck: both must reach outer perform-in")
    }

    @Test
    fun `qualified seq map with perform in callback typechecks — regression guard`() {
        // Regression guard: qualified form was working pre-fix (via the plain-call
        // harvest at ExprFunctionConstraints.kt:757-764). Must still work.
        val r = check("""
            use std::collections::seq

            effect MyLog where {
              info : {msg: Str} -> {} <MyLog>
            }
            impl MyLog for TestLogger where {
              info : {msg: Str} -> {} = { msg } -> {}
            }

            pub let compute: {} -> Int <*> = {} ->
              perform TestLogger in do
                let ys = seq::map { xs = [1, 2, 3], f = {x} -> do
                    MyLog.info {msg = "hi"}
                    x * 2
                  }
                seq::fold { xs = ys, init = 0, f = {acc, x} -> acc + x }
        """.trimIndent())
        assertNotNull(r.getVariableType("compute"))
    }

    @Test
    fun `pure chain still typechecks — no over-propagation from empty callback rows`() {
        // Sanity guard: chain-method dispatch on pure callbacks must not
        // synthesize effects. collectClosureArgEffects returns an empty set
        // for pure lambdas, so the harvest adds nothing to a pure chain.
        val r = check("""
            pub let compute: { xs: [Int] } -> Int = { xs } ->
              xs.map { f = {x} -> x * 2 }
                .filter { p = {x} -> x > 2 }
                .fold { init = 0, f = {acc, x} -> acc + x }
        """.trimIndent())
        assertNotNull(r.getVariableType("compute"))
    }
}
