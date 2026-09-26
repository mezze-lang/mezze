package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Every value shape `Embeddable` admits, folded and then read back.
 *
 * These are round-trips through the real pipeline: the thunk runs while
 * compiling, its value is rebuilt as IR against the static shape, and the
 * program then observes the rebuilt value. A shape that reifies wrongly shows up
 * as a wrong answer here rather than as a crash somewhere later.
 */
class ComptimeShapeCoverageTest {

    private val use =
        "use std::comptime::{ Comptime, ComptimeEval }\nuse std::effects::noop::{ noop }"

    private fun run(src: String): Any = MezzeTestHelper.run("$use\n${src.trimIndent()}")

    // ── scalars ───────────────────────────────────────────────────────────

    @Test @Timeout(120)
    fun `Int`() = assertEquals(42L, run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                42
            })"""))

    @Test @Timeout(120)
    fun `Float`() = assertEquals(2.5, run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                2.5
            })"""))

    @Test @Timeout(120)
    fun `Str`() = assertEquals("hi", run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                "hi"
            })"""))

    @Test @Timeout(120)
    fun `Bool`() = assertEquals(true, run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                1 < 2
            })"""))

    /**
     * The case that proves type-guided reification is doing real work: `Nat` and
     * `Int` are both a `Long` at run time, so only the static shape can tell the
     * reifier which it is rebuilding.
     */
    @Test @Timeout(120)
    fun `Nat is not silently an Int`() {
        assertEquals(7L, run("""
            use std::nat
            match nat::from_int { n = (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                7 })
            } is
                Some { val } -> nat::to_int { n = val }
                None -> 0 - 1
        """))
    }

    @Test @Timeout(120)
    fun `Frac`() {
        assertEquals(1L, run("""
            use std::frac
            frac::numerator {
                n = (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                frac::make { numerator = 1, denominator = 3 }
            })
            }
        """))
    }

    // ── composites ────────────────────────────────────────────────────────

    @Test @Timeout(120)
    fun `nested record`() =
        assertEquals(5L, run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                { a = { b = { c = 5 } } }
            }).a.b.c"""))

    @Test @Timeout(120)
    fun `variant without payload`() {
        assertEquals("green", run("""
            type Colour = | Red, Green |
            # The comptime sits in a function, not in the trailing bare
            # expression: a variant constructor there is typed only by context,
            # and the top-level-expression path this harness allows -- real mez
            # rejects it with V0710 -- never solves it.
            let pick = {} -> Comptime.comptime { eval = {} -> do
                noop {}
                Green
            }
            match (perform ComptimeEval in pick {}) is
                Red -> "red"
                Green -> "green"
        """))
    }

    @Test @Timeout(120)
    fun `variant with payload`() {
        assertEquals(3L, run("""
            type Boxed = | Full { v: Int }, Empty |
            # The comptime sits in a function, not in the trailing bare
            # expression: a variant constructor there is typed only by context,
            # and the top-level-expression path this harness allows -- real mez
            # rejects it with V0710 -- never solves it.
            let boxed = {} -> Comptime.comptime { eval = {} -> do
                noop {}
                Full { v = 3 }
            }
            match (perform ComptimeEval in boxed {}) is
                Full { v } -> v
                Empty -> 0
        """))
    }

    @Test @Timeout(120)
    fun `Result Err is embedded as data`() {
        assertEquals("bad", run("""
            # The comptime sits in a function, not in the trailing bare
            # expression: a variant constructor there is typed only by context,
            # and the top-level-expression path this harness allows -- real mez
            # rejects it with V0710 -- never solves it.
            let outcome = {} -> Comptime.comptime { eval = {} -> do
                noop {}
                Err { error = "bad" }
            }
            match (perform ComptimeEval in outcome {}) is
                Ok { val } -> "ok"
                Err { error } -> error
        """))
    }

    @Test @Timeout(120)
    fun `seq of records`() {
        assertEquals(3L, run("""
            use std::collections::seq
            seq::fold {
                xs = (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                [{ n = 1
            }, { n = 2 }] }),
                init = 0,
                f = { acc, x } -> acc + x.n,
            }
        """))
    }

    /** Erased at run time, so the shape is the only thing that knows the value
     *  was ever wrapped. Requires the author's `Embeddable` opt-in. */
    @Test @Timeout(120)
    fun `newtype over data`() {
        assertEquals(11L, run("""
            use std::abilities::embed::{ Embeddable }
            newtype Meters = Int
            impl Embeddable for Meters where { }
            match (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                Meters.(11)
            }) is
                Meters.(v) -> v
        """))
    }

    /** No byte-literal IR node, so rebuilt from a seq of ints through the same
     *  native `bytes::from_seq` calls. */
    @Test @Timeout(120)
    fun `Bytes`() {
        assertEquals(3L, run("""
            use std::collections::bytes
            use std::collections::seq
            bytes::size { b = (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                bytes::from_seq { arr = [1, 2, 3] }
            }) }
        """))
    }

    /** Sorted containers reify by relying on the element's NATURAL order — their
     *  natives take a comparator, and a comparator is a function, which is the one
     *  thing reification cannot carry. An absent `cmp` now means natural order. */
    @Test @Timeout(120)
    fun `SortedSet`() {
        assertEquals(true, run("""
            use std::collections::sorted_set
            use std::collections::seq
            sorted_set::contains {
                s = (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                    noop {}
                    sorted_set::from_seq { xs = [3, 1, 2] }
                }),
                x = 2,
            }
        """))
    }

    // ── placement ─────────────────────────────────────────────────────────

    @Test @Timeout(120)
    fun `inside a function body, capturing nothing`() {
        assertEquals(52L, run("""
            let add_ten = { n } -> n + (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                42
            })
            add_ten { n = 10 }
        """))
    }

    @Test @Timeout(120)
    fun `a top-level value is reachable from the thunk`() {
        assertEquals(30L, run("""
            let base = 10
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                base * 3
            })
        """))
    }
}
