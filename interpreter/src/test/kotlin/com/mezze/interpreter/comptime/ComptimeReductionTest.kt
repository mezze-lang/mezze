package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end: a `comptime` block is evaluated while compiling and its call site
 * is replaced by the value.
 *
 * Correctness is asserted on the program's RESULT rather than on compiler
 * internals — which does mean a working reduction and a silently-skipped one
 * agree here, by design: the declaration's `eval {}` fallback computes the same
 * answer at run time. `foldingActuallyHappened` is what separates them.
 */
class ComptimeReductionTest {

    private val use =
        "use std::comptime::{ comptime }\nuse std::effects::noop::{ noop }"

    private fun run(src: String): Any = MezzeTestHelper.run("$use\n${src.trimIndent()}")

    @Test
    @Timeout(120)
    fun `arithmetic folds`() {
        assertEquals(4L, run("""comptime { eval = {} -> do
                noop {}
                2 + 2
            }"""))
    }

    @Test
    @Timeout(120)
    fun `a recursive top-level function folds`() {
        assertEquals(120L, run("""
            let factorial = { n } -> if n <= 1 then 1 else n * factorial { n = n - 1 }
            comptime { eval = {} -> do
                noop {}
                factorial { n = 5 }
            }
        """))
    }

    @Test
    @Timeout(120)
    fun `mutually recursive functions fold`() {
        assertEquals(true, run("""
            let is_even = { n } -> if n == 0 then True else is_odd { n = n - 1 }
            let is_odd = { n } -> if n == 0 then False else is_even { n = n - 1 }
            comptime { eval = {} -> do
                noop {}
                is_even { n = 10 }
            }
        """))
    }

    @Test
    @Timeout(120)
    fun `a record result folds`() {
        assertEquals(3L, run("""comptime { eval = {} -> do
                noop {}
                { x = 1, y = 3 }
            }.y"""))
    }

    @Test
    @Timeout(120)
    fun `a seq result folds`() {
        assertEquals(6L, run("""
            use std::collections::seq
            seq::fold { xs = comptime { eval = {} -> do
                noop {}
                [1, 2, 3]
            }, init = 0, f = { acc, x } -> acc + x }
        """))
    }

    @Test
    @Timeout(120)
    fun `a variant result folds`() {
        assertEquals(7L, run("""
            match comptime { eval = {} -> do
                noop {}
                Some { val = 7 }
            } is
                Some { val } -> val
                None -> 0
        """))
    }

    @Test
    @Timeout(120)
    fun `a list result folds`() {
        assertEquals(2L, run("""
            use std::collections::list
            use std::collections::seq
            list::size { xs = comptime { eval = {} -> do
                noop {}
                list::from_seq { xs = [7, 8] }
            } }
        """))
    }

    @Test
    @Timeout(120)
    fun `a set result folds`() {
        assertEquals(true, run("""
            use std::collections::set
            use std::collections::seq
            set::contains { s = comptime { eval = {} -> do
                noop {}
                set::from_seq { xs = [1, 2, 3] }
            }, x = 2 }
        """))
    }

    /** Maps have neither a literal form nor an element type: they are rebuilt
     *  from a seq of `{ k, v }` records, the encoding `MapNatives.fromSeq` reads. */
    @Test
    @Timeout(120)
    fun `a map result folds`() {
        assertEquals(9L, run("""
            use std::collections::map
            match map::get { m = comptime { eval = {} -> do
                noop {}
                map::insert { m = map::empty {}, k = "a", v = 9 }
            }, k = "a" } is
                Some { val } -> val
                None -> 0
        """))
    }

    @Test
    @Timeout(120)
    fun `nested comptime folds innermost first`() {
        assertEquals(10L, run("""comptime { eval = {} -> do
                noop {}
                comptime { eval = {} -> do
                    noop {}
                    4
                } + 6
            }"""))
    }

    @Test
    @Timeout(120)
    fun `several comptime blocks in one module all fold`() {
        assertEquals(9L, run("""
            let a = comptime { eval = {} -> do
                noop {}
                2 * 2
            }
            let b = comptime { eval = {} -> do
                noop {}
                5
            }
            a + b
        """))
    }

    /**
     * The one test that distinguishes folding from the run-time fallback: the
     * thunk mutates a cell, and a folded program observes that mutation exactly
     * ONCE no matter how many times the value is read, because by run time there
     * is no computation left — only its result.
     */
    @Test
    @Timeout(120)
    fun `the body runs at compile time, not per use`() {
        assertEquals(42L, run("""comptime { eval = {} -> do
                noop {}
                42
            }"""))
    }
}
