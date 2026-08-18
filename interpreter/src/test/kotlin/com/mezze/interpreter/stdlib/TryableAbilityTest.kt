package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for the `Tryable` ability declared in std::effects::exception.
 *
 * Verifies that the ability + impls work when `.try {}` is called inside a
 * `perform ExceptionToResult` block: `try` unwraps the success value or
 * throws, and the handler folds the throw back into an `Err`.
 *
 * Tests use **typed helper functions** to pin receiver types — bare variant
 * literals like `Ok {val = 42}` infer as single-constructor variants and
 * don't match the Tryable impl for `Result`. Routing through a typed
 * function forces the proper type.
 */
class TryableAbilityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Result.try {} — basic cases (Ok / Err)
    // ========================================================================

    @Test
    fun `Result_try returns inner value on Ok`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            let make_ok : {} -> Result Int Str = {} -> Ok {val = 42}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_ok {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `Result_try throws on Err and ExceptionToResult catches it`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            let make_err : {} -> Result Int Str = {} -> Err {error = "boom"}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_err {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> "unexpected"
                    Err {error} -> error

            run {}
        """)
        assertEquals("boom", result)
    }

    @Test
    fun `Result_try preserves variant error type`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            type DbError = | ConnectionLost, QueryFailed {sql: Str} |

            let make_err : {} -> Result Int DbError = {} ->
                Err {error = QueryFailed {sql = "SELECT *"}}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_err {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> match error is
                        ConnectionLost -> "lost"
                        QueryFailed {sql} -> sql

            run {}
        """)
        assertEquals("SELECT *", result)
    }

    // ========================================================================
    // Option.try {} — basic cases (Some / None)
    // ========================================================================

    @Test
    fun `Option_try returns inner value on Some`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            let make_some : {} -> Option Int = {} -> Some {val = 99}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_some {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(99L, result)
    }

    @Test
    fun `Option_try throws NoneOpt on None`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            let make_none : {} -> Option Int = {} -> None

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let v = (make_none {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> match error is
                        NoneOpt -> "none"
                        _ -> "other"

            run {}
        """)
        assertEquals("none", result)
    }

    // ========================================================================
    // Effectful value contexts — Log-using producers
    // ========================================================================

    @Test
    fun `try works on Option from a Log-using function`() {
        // Receiver call has <Log>; bind it to a `let` first so the <Log> is discharged
        // before `.try {}` is dispatched. Calling .try on a SuspendType directly
        // isn't currently supported (typechecker resolves as field access).
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}
            use std::effects::log::{Log, QuietConsole, info}

            let make_some : {x: Int} -> Option Int <Log> = {x} -> do
                info {msg = "producing"}
                Some {val = x}

            let run = {} -> perform QuietConsole in do
                let r = perform ExceptionToResult in do
                    let opt = make_some {x = 7}
                    let v = opt.try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(7L, result)
    }

    @Test
    fun `try works on Result from a Log-using function`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}
            use std::effects::log::{Log, QuietConsole, info}

            let lookup : {key: Str} -> Result Int Str <Log> = {key} -> do
                info {msg = "looking up"}
                if key == "answer" then Ok {val = 42}
                else Err {error = "missing"}

            let run = {} -> perform QuietConsole in do
                let r = perform ExceptionToResult in do
                    let res = lookup {key = "answer"}
                    let v = res.try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `try propagates failure from a Log-using function`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}
            use std::effects::log::{Log, QuietConsole, info}

            let lookup : {key: Str} -> Result Int Str <Log> = {key} -> do
                info {msg = "looking up"}
                if key == "answer" then Ok {val = 42}
                else Err {error = "missing"}

            let run = {} -> perform QuietConsole in do
                let r = perform ExceptionToResult in do
                    let res = lookup {key = "ghost"}
                    let v = res.try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("missing", result)
    }

    // ========================================================================
    // Multiple try calls — same error type
    // ========================================================================

    @Test
    fun `multiple Result_try with same primitive error type`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            let one : {} -> Result Int Str = {} -> Ok {val = 1}
            let two : {} -> Result Int Str = {} -> Ok {val = 2}
            let three : {} -> Result Int Str = {} -> Ok {val = 3}

            let run = {} -> do
                let r : Result Int Str = perform ExceptionToResult in do
                    let a = (one {}).try {}
                    let b = (two {}).try {}
                    let c = (three {}).try {}
                    Ok {val = a + b + c}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `multiple Result_try short-circuits on first Err`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            let one : {} -> Result Int Str = {} -> Ok {val = 1}
            let bad : {} -> Result Int Str = {} -> Err {error = "second failed"}
            let three : {} -> Result Int Str = {} -> Ok {val = 3}

            let run = {} -> do
                let r : Result Int Str = perform ExceptionToResult in do
                    let a = (one {}).try {}
                    let b = (bad {}).try {}
                    let c = (three {}).try {}
                    Ok {val = a + b + c}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("second failed", result)
    }

    // ========================================================================
    // Option mixed with Result — row union includes NoneOpt
    // ========================================================================

    @Test
    fun `Option and Result try together — all succeed`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            type HttpError = | NotFound, Unauthorized |

            let some_int : {} -> Option Int = {} -> Some {val = 5}
            let ok_int : {} -> Result Int HttpError = {} -> Ok {val = 6}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (some_int {}).try {}
                    let b = (ok_int {}).try {}
                    Ok {val = a + b}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(11L, result)
    }

    @Test
    fun `Option and Result try together — Option None short-circuits`() {
        // Note: HttpError is declared open (...) so the row-union with NoneOpt works.
        // Closed variants don't compose with throws from a different source.
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            type HttpError = | NotFound, Unauthorized, ... |

            let none_int : {} -> Option Int = {} -> None
            let ok_int : {} -> Result Int HttpError = {} -> Ok {val = 6}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (none_int {}).try {}
                    let b = (ok_int {}).try {}
                    Ok {val = a + b}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> match error is
                        NoneOpt -> "none"
                        NotFound -> "http-not-found"
                        Unauthorized -> "http-unauth"
                        _ -> "other"

            run {}
        """)
        assertEquals("none", result)
    }

    @Test
    fun `Option and Result try together — Result Err short-circuits`() {
        val result = eval("""
            use std::effects::exception::{ExceptionToResult}

            type HttpError = | NotFound, Unauthorized, ... |

            let some_int : {} -> Option Int = {} -> Some {val = 5}
            let err_unauth : {} -> Result Int HttpError = {} -> Err {error = Unauthorized}

            let run = {} -> do
                let r = perform ExceptionToResult in do
                    let a = (some_int {}).try {}
                    let b = (err_unauth {}).try {}
                    Ok {val = a + b}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> match error is
                        NoneOpt -> "none"
                        NotFound -> "http-not-found"
                        Unauthorized -> "http-unauth"
                        _ -> "other"

            run {}
        """)
        assertEquals("http-unauth", result)
    }

    // ========================================================================
    // Custom Tryable impl — confirms users can extend the ability
    // ========================================================================

    @Test
    fun `custom Tryable impl participates with ExceptionToResult`() {
        val result = eval("""
            use std::abilities::tryable::{Tryable}
            use std::effects::exception::{Exception, ExceptionToResult}

            type Validated 'a = | Valid {val: 'a}, Invalid {reason: Str} |

            impl Tryable for Validated 'a having 'inner = 'a where {
                try = {it} -> match it is
                    Valid {val} -> val
                    Invalid {reason} -> Exception.throw {error = reason}
            }

            let make_valid : {} -> Validated Int = {} -> Valid {val = 100}

            let run = {} -> do
                let r : Result Int Str = perform ExceptionToResult in do
                    let v = (make_valid {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `custom Tryable impl throws and is caught`() {
        val result = eval("""
            use std::abilities::tryable::{Tryable}
            use std::effects::exception::{Exception, ExceptionToResult}

            type Validated 'a = | Valid {val: 'a}, Invalid {reason: Str} |

            impl Tryable for Validated 'a having 'inner = 'a where {
                try = {it} -> match it is
                    Valid {val} -> val
                    Invalid {reason} -> Exception.throw {error = reason}
            }

            let make_invalid : {} -> Validated Int = {} -> Invalid {reason = "out of range"}

            let run = {} -> do
                let r : Result Int Str = perform ExceptionToResult in do
                    let v = (make_invalid {}).try {}
                    Ok {val = v}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> error

            run {}
        """)
        assertEquals("out of range", result)
    }
}
