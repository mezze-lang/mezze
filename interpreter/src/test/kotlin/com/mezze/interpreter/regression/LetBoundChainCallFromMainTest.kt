package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression for #513: `main` calls a let-bound `compute` whose body is a
 * seq::range.filter.map.fold chain, inside `perform Console`. The eager
 * whole-program BDSL emit (#391) walks BFS from `__entry__` and reaches
 * `main` before `compute`; when `main`'s HandleIR body is emitted, the
 * CallDirectIR to `compute` finds no published CallTarget in the slot table
 * and no local `compiledCallTargets` entry. Pre-fix the CallDirect emit
 * threw `IllegalStateException: CallDirectIR references unknown target
 * hash ... — callee must be compiled first`. Fix mirrors the `supportsNode`
 * fallback: mint a LazyStub via `MezzeLanguage.getOrMintUserStub`.
 */
class LetBoundChainCallFromMainTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `main calls let-bound filter map fold — #513 reproducer`() {
        val src = """
            use std::collections::seq

            let compute : { n: Int } -> Int = { n } -> do
                let half = n / 2
                seq::range { start = 0, end = n }
                    .filter { p = {x: Int} -> x < half }
                    .map { f = {x: Int} -> x * 2 }
                    .fold { init = 0, f = {acc: Int, x: Int} -> acc + x }

            let main = {} -> do
              compute {n = 100}
            main {}
        """.trimIndent()
        // filter x<50 over 0..99 keeps 0..49, doubled = 0,2,4,...,98
        // sum = 2 * (0+1+...+49) = 2 * 1225 = 2450
        assertEquals(2450L, eval(src))
    }

    @Test
    fun `main under perform Console calls let-bound chain and logs — #513 canonical form`() {
        // The canonical failing shape from bench/cases/filter_map_fold:
        // HandleIR wraps the CallDirect to compute. Emit order matters —
        // main is BFS-reached before compute, so CallDirect must mint a
        // LazyStub when the slot table isn't yet published.
        val src = """
            use std::effects::log::{println, Console}
            use std::collections::seq

            let compute : { n: Int } -> Int = { n } -> do
                let half = n / 2
                seq::range { start = 0, end = n }
                    .filter { p = {x: Int} -> x < half }
                    .map { f = {x: Int} -> x * 2 }
                    .fold { init = 0, f = {acc: Int, x: Int} -> acc + x }

            let main = {} -> perform Console in do
                let a = compute {n = 100}
                println { msg = "result=${'$'}(a.to_str{})" }
                a
            main {}
        """.trimIndent()
        assertEquals(2450L, eval(src))
    }
}
