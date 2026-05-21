package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Basic end-to-end smoke tests for the `<-` do-bind feature.
 *
 * Phase 4 of the do-bind proposal. Tests that:
 *   - Single `<-` line with Result/Option works
 *   - Multiple `<-` lines short-circuit on first failure
 *   - Mixed `<-` and `let` in same block
 *   - Synthetic perform discharges <Exception> — no <*> leak
 *
 * The desugar rewrites:
 *   pat <- expr  →  let pat = Tryable.try {it = expr}
 * Wraps the result in Ok {val = ...}.
 * Wraps the whole do-block in `perform ExceptionToResult`.
 */
class DoBindBasicTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `single bind on Result Ok unwraps and returns Ok of value`() {
        // No explicit import — Tryable and ExceptionToResult come from the prelude.
        val result = eval("""
            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let run = {} -> do
                let r = do
                    a <- make_ok {}
                    a + 1
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(43L, result)
    }

    @Test
    fun `single bind on Result Err short-circuits`() {
        val result = eval("""
            let make_err : {} -> Result Int Str = {} -> Err {error = "fail"}

            let run = {} -> do
                let r = do
                    a <- make_err {}
                    a + 1
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("fail", result)
    }

    @Test
    fun `single bind on Option Some unwraps`() {
        val result = eval("""
            let make_some : {} -> Option Int = {} -> Some {val = 7}

            let run = {} -> do
                let r = do
                    a <- make_some {}
                    a * 10
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(70L, result)
    }

    @Test
    fun `single bind on Option None short-circuits with NoneOpt`() {
        val result = eval("""
            let make_none : {} -> Option Int = {} -> None

            let run = {} -> do
                let r = do
                    a <- make_none {}
                    a * 10
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> match error is
                        NoneOpt -> "none"
                        _ -> "other"

            run {}
        """)
        assertEquals("none", result)
    }

    @Test
    fun `multiple binds chain when all succeed`() {
        val result = eval("""
            let a : {} -> Result Int Str = {} -> Ok {val = 1}
            let b : {} -> Result Int Str = {} -> Ok {val = 2}
            let c : {} -> Result Int Str = {} -> Ok {val = 3}

            let run = {} -> do
                let r = do
                    x <- a {}
                    y <- b {}
                    z <- c {}
                    x + y + z
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `multiple binds short-circuit on middle failure`() {
        val result = eval("""
            let a : {} -> Result Int Str = {} -> Ok {val = 1}
            let b : {} -> Result Int Str = {} -> Err {error = "middle failed"}
            let c : {} -> Result Int Str = {} -> Ok {val = 3}

            let run = {} -> do
                let r = do
                    x <- a {}
                    y <- b {}
                    z <- c {}
                    x + y + z
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("middle failed", result)
    }

    @Test
    fun `bind mixed with let bindings`() {
        val result = eval("""
            let a : {} -> Result Int Str = {} -> Ok {val = 10}

            let run = {} -> do
                let r = do
                    x <- a {}
                    let y = 5
                    x + y
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(15L, result)
    }
}
