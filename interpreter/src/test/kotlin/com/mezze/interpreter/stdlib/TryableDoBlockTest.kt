package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * End-to-end proof that everything the removed `<-` do-bind sugar did is still
 * expressible with an explicit `.try {}` call inside a `perform
 * ExceptionToResult in do` block — and evaluates to the SAME values.
 *
 * Each test here is a former `DoBindBasicTest` case, mechanically rewritten
 * from the sugar to what the desugar produced:
 *
 *   do                              perform ExceptionToResult in do
 *     let a <- make_ok {}     ⇒       let a = (make_ok {}).try {}
 *     a + 1                           Ok { val = a + 1 }
 *
 * Same short-circuit-on-Err/None semantics, same unwrap-on-Ok/Some, same
 * `Ok {val=...}` wrapping. `Tryable` (`.try`) comes from the prelude;
 * `ExceptionToResult` is imported explicitly (the sugar used to inject it).
 */
class TryableDoBlockTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `try on Result Ok unwraps and returns Ok of value`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (make_ok {}).try {}
                    Ok {val = a + 1}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(43L, result)
    }

    @Test
    fun `try on Result Err short-circuits`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let make_err : {} -> Result Int Str = {} -> Err {error = "fail"}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (make_err {}).try {}
                    Ok {val = a + 1}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("fail", result)
    }

    @Test
    fun `try on Option Some unwraps`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let make_some : {} -> Option Int = {} -> Some {val = 7}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (make_some {}).try {}
                    Ok {val = a * 10}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(70L, result)
    }

    @Test
    fun `try on Option None short-circuits with NoneOpt`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let make_none : {} -> Option Int = {} -> None

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (make_none {}).try {}
                    Ok {val = a * 10}
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
    fun `multiple try calls chain when all succeed`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let a : {} -> Result Int Str = {} -> Ok {val = 1}
            let b : {} -> Result Int Str = {} -> Ok {val = 2}
            let c : {} -> Result Int Str = {} -> Ok {val = 3}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let x = (a {}).try {}
                    let y = (b {}).try {}
                    let z = (c {}).try {}
                    Ok {val = x + y + z}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `multiple try calls short-circuit on middle failure`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let a : {} -> Result Int Str = {} -> Ok {val = 1}
            let b : {} -> Result Int Str = {} -> Err {error = "middle failed"}
            let c : {} -> Result Int Str = {} -> Ok {val = 3}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let x = (a {}).try {}
                    let y = (b {}).try {}
                    let z = (c {}).try {}
                    Ok {val = x + y + z}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("middle failed", result)
    }

    @Test
    fun `try mixed with plain let bindings`() {
        val result = eval("""
            use std::effects::exception::{ ExceptionToResult }

            let a : {} -> Result Int Str = {} -> Ok {val = 10}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let x = (a {}).try {}
                    let y = 5
                    Ok {val = x + y}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(15L, result)
    }
}
