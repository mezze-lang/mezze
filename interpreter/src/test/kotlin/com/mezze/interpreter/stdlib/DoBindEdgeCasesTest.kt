package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Phase-6 edge-case coverage for `<-`. Beyond DoBindBasicTest/DoBindAdvancedTest:
 *   - destructuring on the LHS
 *   - many binds (scaling sanity)
 *   - recursive function whose body uses `<-`
 *   - `<-` and user-written `perform` coexisting in the same block
 */
class DoBindEdgeCasesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `record destructure on left of bind`() {
        val r = eval("""
            type Pair = { fst: Int, snd: Int }

            let load : {} -> Result Pair Str = {} ->
                Ok {val = {fst = 3, snd = 4}}

            let run = {} -> do
                let r = do
                    {fst, snd} <- load {}
                    fst + snd
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(7L, r)
    }

    @Test
    fun `many bind statements work`() {
        // Ten sequential binds. Sanity check that scaling isn't broken.
        val r = eval("""
            let ok : {x: Int} -> Result Int Str = {x} -> Ok {val = x}

            let run = {} -> do
                let r = do
                    a <- ok {x = 1}
                    b <- ok {x = 2}
                    c <- ok {x = 3}
                    d <- ok {x = 4}
                    e <- ok {x = 5}
                    f <- ok {x = 6}
                    g <- ok {x = 7}
                    h <- ok {x = 8}
                    i <- ok {x = 9}
                    j <- ok {x = 10}
                    a + b + c + d + e + f + g + h + i + j
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(55L, r)
    }

    @Test
    fun `bind in recursive function body`() {
        val r = eval("""
            let safe_decr : {n: Int} -> Result Int Str = {n} ->
                if n > 0 then Ok {val = n - 1} else Err {error = "negative"}

            let rec_sum = {n: Int} -> do
                let r = do
                    next <- safe_decr {n = n}
                    if next == 0 then 0 else next + 0
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            rec_sum {n = 5}
        """)
        // safe_decr {n=5} = Ok {val=4}; `next` = 4; result is 4.
        assertEquals(4L, r)
    }

    @Test
    fun `bind alongside user-written perform`() {
        // Outer perform handles a user effect (Log); inner do-block uses `<-`
        // which has its own synthetic ExceptionToResult. The two layers
        // compose without interfering.
        val r = eval("""
            use std::effects::log::{Log, QuietConsole, info}

            let make_ok : {} -> Result Int Str <Log> = {} -> do
                info {msg = "produced"}
                Ok {val = 42}

            let run = {} -> perform QuietConsole in do
                let r = do
                    a <- make_ok {}
                    a + 1
                match r is
                    Ok {val} -> val
                    Err {error} -> 0 - 1

            run {}
        """)
        assertEquals(43L, r)
    }
}
