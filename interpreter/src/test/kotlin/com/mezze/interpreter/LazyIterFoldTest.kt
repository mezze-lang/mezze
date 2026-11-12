package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An `iter` fold that cannot fuse must still STREAM.
 *
 * `IterNatives.foldViaRecursion` is the path every descriptor the fused emitter
 * refuses falls to — `zip`, `flat_map`, `chain`, `take`, `drop`, `take_while`,
 * `drop_while`, `enumerate` and `unfold`. It used to collect the whole iterator
 * into a `java.util.ArrayList` and fold that, under the note "the correctness
 * path isn't asked to be fast, only to work. Fusion is the fast path's job."
 *
 * The second half is what made it wrong. Those descriptors NEVER fuse, so for
 * all of them the correctness path is the ONLY path, and the standing rule for
 * `iter` is that a pipeline expressed with it "never materialises what it is
 * about to discard" — naming this very shape, a `zip` whose only consumer is a
 * `fold`, as the thing to use `iter` FOR. It was materialising every element.
 * At `iter_perf_compare`'s n that is a ten-million-element ArrayList, and
 * `iter::zip` measured 73 284 ps an element against `iter::map`'s 133.
 *
 * WHAT THESE TESTS PIN is the observable consequence, not the speed.
 * Materialising means every element is PRODUCED before the folder sees any of
 * them; streaming means they interleave. The tests below make the source and
 * the folder each append to an atom, so the log distinguishes the two
 * directly — `s1 f1 s2 f2` against `s1 s2 f1 f2`. That is laziness, which is
 * what `iter` is for, and it is also exactly what the FUSED path does, so this
 * makes the unfused path agree with the fused one instead of differing from it
 * in a way nothing noticed.
 */
class LazyIterFoldTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = """
        use std::collections::iter
        use std::collections::seq
        use std::effects::async::atom::{ atom, deref, swap }
        use std::effects::async::{ Async, LoomAsync }
        use std::str
        use std::int
    """

    /**
     * `flat_map`'s function is the SOURCE side and the folder is the sink side.
     * Both append to one log, so the order says whether anything was buffered.
     */
    @Test
    fun `a flat_map fold interleaves source and folder`() {
        val src = """
            $prelude
            let main = {} -> perform LoomAsync in do
              let log = atom { val = "" }
              let total = iter::fold {
                it = iter::flat_map {
                  it = iter::range { start = 1, end = 4 },
                  f = { x } -> do
                    let _ = swap { atom = log, f = { current } -> "$(current)s" }
                    iter::range { start = x, end = x + 1 }
                },
                init = 0,
                f = { acc, x } -> do
                  let _ = swap { atom = log, f = { current } -> "$(current)f" }
                  acc + x
              }
              "$(deref { atom = log })|$(int::to_str { n = total })"
            main {}
        """
        // Three elements, each produced then immediately folded, summing 1+2+3.
        assertEquals("sfsfsf|6", eval(src))
    }

    /** The same question through `zip`, the shape with the worst number. */
    @Test
    fun `a zip fold interleaves production and folding`() {
        val src = """
            $prelude
            let main = {} -> perform LoomAsync in do
              let log = atom { val = "" }
              let total = iter::fold {
                it = iter::zip {
                  a = iter::flat_map {
                    it = iter::range { start = 1, end = 4 },
                    f = { x } -> do
                      let _ = swap { atom = log, f = { current } -> "$(current)s" }
                      iter::range { start = x, end = x + 1 }
                  },
                  b = iter::range { start = 10, end = 13 },
                },
                init = 0,
                f = { acc, x } -> do
                  let _ = swap { atom = log, f = { current } -> "$(current)f" }
                  acc + x.fst
              }
              "$(deref { atom = log })|$(int::to_str { n = total })"
            main {}
        """
        assertEquals("sfsfsf|6", eval(src))
    }

    // ── The values themselves, so a streaming rewrite cannot quietly
    //    change what a fold answers ──

    @Test
    fun `zip folds the first component`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::zip {
                a = iter::range { start = 1, end = 5 },
                b = iter::range { start = 10, end = 14 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            main {}
        """
        assertEquals(10L, eval(src)) // 1+2+3+4
    }

    @Test
    fun `zip folds the second component`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::zip {
                a = iter::range { start = 1, end = 5 },
                b = iter::range { start = 10, end = 14 },
              },
              init = 0,
              f = { acc, x } -> acc + x.snd,
            }
            main {}
        """
        assertEquals(46L, eval(src)) // 10+11+12+13
    }

    /** `zip` stops at the SHORTER side, which a streaming walk must preserve. */
    @Test
    fun `zip stops at the shorter side`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::zip {
                a = iter::range { start = 1, end = 100 },
                b = iter::range { start = 10, end = 13 },
              },
              init = 0,
              f = { acc, x } -> acc + x.fst,
            }
            main {}
        """
        assertEquals(6L, eval(src)) // 1+2+3 only
    }

    @Test
    fun `flat_map folds every inner element`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::flat_map {
                it = iter::range { start = 0, end = 3 },
                f = { x } -> iter::range { start = x, end = x + 3 },
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            main {}
        """
        // 0+1+2, 1+2+3, 2+3+4
        assertEquals(18L, eval(src))
    }

    @Test
    fun `chain folds both sides in order`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::chain {
                a = iter::range { start = 1, end = 4 },
                b = iter::range { start = 10, end = 13 },
              },
              init = 0,
              f = { acc, x } -> acc * 10 + x,
            }
            main {}
        """
        // A non-commutative folder, so the ORDER is part of the answer:
        // 1, 12, 123, 1240, 12411, 124122.
        assertEquals(124122L, eval(src))
    }

    /** A folder that reads the accumulator AND the element, threaded across a
     *  streaming walk rather than a buffered one. */
    @Test
    fun `enumerate threads the accumulator`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::enumerate { it = iter::range { start = 5, end = 9 } },
              init = 0,
              f = { acc, x } -> acc + x.i * 100 + x.val,
            }
            main {}
        """
        // i = 0..3, val = 5..8
        assertEquals(626L, eval(src))
    }

    /** The accumulator is a non-number, so nothing can quietly sum past it. */
    @Test
    fun `a string accumulator keeps element order`() {
        val src = """
            $prelude
            let main = {} -> iter::fold {
              it = iter::zip {
                a = iter::range { start = 1, end = 4 },
                b = iter::range { start = 7, end = 10 },
              },
              init = "",
              f = { acc, x } -> "$(acc)$(int::to_str { n = x.fst })",
            }
            main {}
        """
        assertEquals("123", eval(src))
    }
}
