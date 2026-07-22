package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Shape-coverage tests for `<-` (tasks #23-#27, #30):
 *   - `<-` inside lambda body inside do-block
 *   - `<-` on user-defined Tryable impl
 *   - `<-` with `with X 'a` ability constraints
 *   - multiple `<-` lines unifying error types
 *   - `<-` mixed with `<Log>` (regression: <Log> stays in surrounding row)
 *   - plain `let` with Exception-throwing RHS keeps `<Exception>` in row
 *     (i.e. `let` is NOT an implicit discharge — only `<-` is)
 */
class DoBindShapesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `bind inside lambda inside do-block`() {
        // A do-block containing a let that binds a lambda. The lambda's body
        // itself is a do-block using `<-`. Each lambda invocation runs its
        // own perform — invariants for nested binds apply.
        val r = eval("""
            let safe : {n: Int} -> Result Int Str = {n} ->
                if n > 0 then Ok {val = n * 2} else Err {error = "non-positive"}

            let run = {} -> do
                let process = {n: Int} -> do
                    let a <- safe {n = n}
                    a + 1
                let r1 = process {n = 5}
                match r1 is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        // process(5) → Ok(10) inside its own do-bind, then `Ok {val = 11}`.
        // Outer matches Ok and yields 11.
        assertEquals(11L, r)
    }

    @Test
    fun `bind on user-defined Tryable impl`() {
        val r = eval("""
            use std::abilities::tryable::{Tryable}
            use std::effects::exception::{Exception}

            type Validated 'a = | Valid {val: 'a}, Invalid {reason: Str} |

            impl Tryable for Validated 'a having 'inner = 'a where {
                try = {it} -> match it is
                    Valid {val} -> val
                    Invalid {reason} -> Exception.throw {error = reason}
            }

            let check : {x: Int} -> Validated Int = {x} ->
                if x > 0 then Valid {val = x} else Invalid {reason = "must be positive"}

            let run = {} -> do
                let r = do
                    let v <- check {x = 7}
                    v * 10
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(70L, r)
    }

    @Test
    fun `bind on user-defined Tryable impl - failure`() {
        val r = eval("""
            use std::abilities::tryable::{Tryable}
            use std::effects::exception::{Exception}

            type Validated 'a = | Valid {val: 'a}, Invalid {reason: Str} |

            impl Tryable for Validated 'a having 'inner = 'a where {
                try = {it} -> match it is
                    Valid {val} -> val
                    Invalid {reason} -> Exception.throw {error = reason}
            }

            let check : {x: Int} -> Validated Int = {x} ->
                if x > 0 then Valid {val = x} else Invalid {reason = "out of range"}

            let run = {} -> do
                let r = do
                    let v <- check {x = 0 - 1}
                    v * 10
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("out of range", r)
    }

    @Test
    fun `multiple binds with same primitive error type`() {
        // Both `<-` lines throw `Str`. The Exception row's 'error parameter
        // unifies to Str and the do-block produces Result Int Str.
        val r = eval("""
            let a : {} -> Result Int Str = {} -> Ok {val = 1}
            let b : {} -> Result Int Str = {} -> Ok {val = 2}

            let run = {} -> do
                let r = do
                    let x <- a {}
                    let y <- b {}
                    x + y
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(3L, r)
    }

    @Test
    fun `multiple binds with same variant error type`() {
        // Two `<-` lines, both throw the same variant. The Exception's 'error
        // parameter unifies trivially. (Two DIFFERENT closed variants don't
        // row-union — that's a closed-variant unification limitation,
        // unrelated to <- itself.)
        val r = eval("""
            type Err = | NotFound, Unauthorized |

            let fa : {} -> Result Int Err = {} -> Err {error = NotFound}
            let fb : {} -> Result Int Err = {} -> Ok {val = 2}

            let run = {} -> do
                let r = do
                    let a <- fa {}
                    let b <- fb {}
                    a + b
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> match error is
                        NotFound -> "notfound"
                        Unauthorized -> "unauth"

            run {}
        """)
        assertEquals("notfound", r)
    }

    @Test
    fun `bind alongside Log preserves Log in surrounding row`() {
        // The outer perform discharges <Exception> only — <Log> stays in
        // the surrounding function's effect row, requiring an outer
        // QuietConsole performer.
        val r = eval("""
            use std::effects::log::{Log, QuietConsole, info}

            let load : {id: Int} -> Result Int Str <Log> = {id} -> do
                info {msg = "loading"}
                if id > 0 then Ok {val = id} else Err {error = "bad id"}

            let run = {} -> perform QuietConsole in do
                let r = do
                    let a <- load {id = 5}
                    a + 1
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(6L, r)
    }

    @Test
    fun `bind with Numeric ability constraint`() {
        // A function whose parameter record carries `with NumAdd 'a`. The
        // `with` attaches to the parameter type, not the return type. Body
        // uses `<-` — confirms the desugar interacts cleanly with ability
        // constraints.
        val r = eval("""
            let sum_two : { a: Result 'a Str, b: Result 'a Str } with NumAdd 'a -> Result 'a Str =
                { a, b } -> do
                    let x <- a
                    let y <- b
                    x + y

            let r = sum_two { a = Ok {val = 3}, b = Ok {val = 4} }
            match r is
                Ok {val} -> val
                Err {error} -> 0 - 1
        """)
        assertEquals(7L, r)
    }

    @Test
    fun `plain let with Exception-throwing RHS keeps Exception in row`() {
        // CONTRAST WITH `<-`: a plain `let v = expr_with_Exception {}` does
        // NOT discharge `<Exception>`. The user must explicitly wrap in
        // `perform ExceptionToResult` AND wrap the body's tail in
        // `Ok {val = ...}` — `<-` is just sugar for both.
        val r = eval("""
            use std::effects::exception::{Exception, ExceptionToResult, throw}

            let raise : {} -> Int <Exception> = {} -> throw {error = "fail"}

            let run = {} -> do
                let r : Result Int Str = perform ExceptionToResult in do
                    let v = raise {}
                    Ok {val = v + 1}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        // raise {} throws — perform catches and r = Err {error = "fail"}.
        assertEquals("fail", r)
    }
}
