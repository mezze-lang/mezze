package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `Bytes` and `NonEmptyList` can now enter an `Iter` pipeline.
 *
 * Seven collection types implemented `Iterable` and these two did not, so they
 * were the only ones that could not be chained: reaching a pipeline meant
 * spelling `iter::from_seq { xs = bytes::to_seq { b = it } }` by hand. Both are
 * routed through `to_seq`, exactly as Set, SortedSet, Deque, Map and SortedMap
 * are, so `iter::from_seq` is the source and the pipeline gets the same
 * treatment as one starting from a sequence.
 *
 * THE BYTES CASE HAS A TRAP WORTH PINNING: a byte is UNSIGNED when read, so
 * 255 must arrive as 255 and not as -1, and 0 must arrive at all rather than
 * being taken for absence. Both are checked, because `bytes::get` returning
 * `Option` has been the site of exactly that confusion before.
 */
class BytesNelIterableTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    @Test
    fun `bytes can start an iter pipeline`() {
        assertEquals(6L, run(
            """
            use std::collections::bytes
            use std::collections::iter
            use std::abilities::iter::{ Iterable }
            let b = bytes::from_seq { arr = [1, 2, 3] }
            iter::fold { it = b.to_iter {}, init = 0, f = { acc, x } -> acc + x }
            """
        ))
    }

    /** 0 must be a value and 255 must be unsigned: 0 + 128 + 255 = 383. */
    @Test
    fun `bytes arrive unsigned through the pipeline`() {
        assertEquals(383L, run(
            """
            use std::collections::bytes
            use std::collections::iter
            use std::abilities::iter::{ Iterable }
            let b = bytes::from_seq { arr = [0, 128, 255] }
            iter::fold { it = b.to_iter {}, init = 0, f = { acc, x } -> acc + x }
            """
        ))
    }

    /** Stages, so the pipeline is a pipeline and not just a source. */
    @Test
    fun `a bytes pipeline takes stages`() {
        // Evens of 1,2,3,4 are 2 and 4.
        assertEquals(6L, run(
            """
            use std::collections::bytes
            use std::collections::iter
            use std::abilities::iter::{ Iterable }
            let b = bytes::from_seq { arr = [1, 2, 3, 4] }
            iter::fold {
              it = iter::map {
                it = iter::filter { it = b.to_iter {}, p = { x } -> x % 2 == 0 },
                f = { x } -> x,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            """
        ))
    }

    @Test
    fun `a nonempty list can start an iter pipeline`() {
        assertEquals(10L, run(
            """
            use std::collections::nonemptylist
            use std::collections::iter
            use std::abilities::iter::{ Iterable }
            let xs = nonemptylist::cons { x = 1, xs = nonemptylist::cons { x = 2, xs = nonemptylist::cons { x = 3, xs = nonemptylist::singleton { x = 4 } } } }
            iter::fold { it = xs.to_iter {}, init = 0, f = { acc, x } -> acc + x }
            """
        ))
    }

    /** A single-element NEL, which is the shape the type exists to guarantee. */
    @Test
    fun `a one element nonempty list iterates once`() {
        assertEquals(7L, run(
            """
            use std::collections::nonemptylist
            use std::collections::iter
            use std::abilities::iter::{ Iterable }
            let xs = nonemptylist::singleton { x = 7 }
            iter::fold { it = xs.to_iter {}, init = 0, f = { acc, x } -> acc + x }
            """
        ))
    }

    /** Collecting straight back out, using the sinks added earlier. */
    @Test
    fun `a bytes pipeline collects into a set`() {
        assertEquals(3L, run(
            """
            use std::collections::bytes
            use std::collections::iter
            use std::collections::set
            use std::abilities::iter::{ Iterable }
            let b = bytes::from_seq { arr = [1, 2, 2, 3, 3, 3] }
            set::size { s = set::from_iter { it = b.to_iter {} } }
            """
        ))
    }
}
