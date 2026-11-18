package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BUG 69 / BUG 70: a park must propagate through EVERY ordinary program shape, not
 * only the one the scheduler tests happened to use. Both shapes here came from
 * `bench/cases/contloop`, where every CoopAsync kernel crashed with a HOST
 * exception (a Boolean or String cast to a continuation root; an Illegal frame
 * slot) while `let r = perform CoopAsync in go { … }` directly in `main` worked.
 * A host exception reaching a user is a compiler bug.
 */
class ParkPropagationShapesTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun program(vararg parts: String): String = parts.joinToString("\n") { it.trimIndent() }
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private val imports = """
        use std::effects::async::{ Async, CoopAsync, await, yield_now }
        use std::effects::time::{ SystemTime, monotonic }
        use std::result
        use std::int
    """

    private val go = """
        let go = { i, acc } -> if i <= 0 then acc else do
          yield_now {}
          go { i = i - 1, acc = acc + i }
    """

    /** The shape that works, as the control. */
    @Test
    fun `control - a yield loop directly under the perform in a do block`() = assertEquals(
        500500L,
        eval(program(imports, go, """
            perform SystemTime in do
              let r = perform CoopAsync in go { i = 1000, acc = 0 }
              r
        """)),
    )

    /** BUG 69: the park crosses an indirect call that sits in an ARGUMENT of a tail
     *  self-call (`acc = body { acc = acc }`). */
    @Test
    fun `BUG 69 - a park through a closure call in a tail self-call's argument`() = assertEquals(
        1001000L,
        eval(program(imports, go, """
            let outer = { b, acc, body } -> if b <= 0 then acc else outer { b = b - 1, acc = body { acc = acc }, body = body }
            let k = { n } -> perform CoopAsync in outer { b = n / 1000, acc = 0, body = { acc } -> go { i = 1000, acc = acc } }
            perform SystemTime in k { n = 2000 }
        """)),
    )

    /** BUG 70: a cont-capable perform as the WHOLE body of a function reached through
     *  a thunk parameter, once as a statement and once bound by `let`. */
    @Test
    fun `BUG 70 - a cont-capable perform in a function reached through a thunk`() = assertEquals(
        500500L,
        eval(program(imports, go, """
            let k = { n } -> perform CoopAsync in go { i = n, acc = 0 }
            let time_it = { f } -> perform SystemTime in do
              let t0 = monotonic {}
              let r = f {}
              { result = r }
            let bench = { n, f } -> do
              f {}
              let r = time_it { f = f }
              r.result
            perform SystemTime in bench { n = 1000, f = {} -> k { n = 1000 } }
        """)),
    )

    /** BUG 74 (contloop kernel 9): a block of spawns, then a fold whose callback
     *  awaits each promise, inside a closure handed to a loop — the fold is the
     *  closure's TAIL call, and its callback parks. */
    @Test
    fun `BUG 74 - a parking fold in tail position of a closure called from a loop`() = assertEquals(
        1001000L,
        eval(program(imports, """
            use std::collections::seq
            let xs = seq::range { start = 1, end = 1001 }
            let outer = { b, acc, body } -> if b <= 0 then acc else outer { b = b - 1, acc = body { acc = acc }, body = body }
            let k = { n } -> perform CoopAsync in outer { b = n / 1000, acc = 0,
              body = { acc } -> do
                let ps = seq::map { xs = xs, f = { x } -> Async.spawn { f = {} -> x } }
                seq::fold { xs = ps, init = acc, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } } }
            perform SystemTime in k { n = 2000 }
        """)),
    )

    /** contloop kernel 9 crashed in the bench but not in the single-pass test above: the
     *  bench runs the kernel SIX times in one process (warm-up and timed passes), so the
     *  later passes run on specialised and compiled code. Same shape, six passes. */
    @Test
    fun `BUG 74b - the fan-out shape six times in one process`() = assertEquals(
        6L * 1001000L,
        eval(program(imports, """
            use std::collections::seq
            let xs = seq::range { start = 1, end = 1001 }
            let outer = { b, acc, body } -> if b <= 0 then acc else outer { b = b - 1, acc = body { acc = acc }, body = body }
            let k = { n } -> perform CoopAsync in outer { b = n / 1000, acc = 0,
              body = { acc } -> do
                let ps = seq::map { xs = xs, f = { x } -> Async.spawn { f = {} -> x } }
                seq::fold { xs = ps, init = acc, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } } }
            let passes = { i, acc } -> if i <= 0 then acc else passes { i = i - 1, acc = acc + k { n = 2000 } }
            perform SystemTime in passes { i = 6, acc = 0 }
        """)),
    )

    /** contloop kernel 9 EXACTLY: the fan-out kernel reached through the bench's timing
     *  thunk (`bench { f = {} -> k { n } }` → `time_it { f }`), six passes. */
    @org.junit.jupiter.api.RepeatedTest(8)
    fun `BUG 74c - the fan-out kernel through the bench's timing thunk`() = assertEquals(
        3L * 1001000L,
        eval(program(imports, """
            use std::collections::seq
            use std::datetime::{ duration_to_nanos, sub_duration }
            let xs = seq::range { start = 1, end = 1001 }
            let outer = { b, acc, body } -> if b <= 0 then acc else outer { b = b - 1, acc = body { acc = acc }, body = body }
            let k = { n } -> perform CoopAsync in outer { b = n / 1000, acc = 0,
              body = { acc } -> do
                let ps = seq::map { xs = xs, f = { x } -> Async.spawn { f = {} -> x } }
                seq::fold { xs = ps, init = acc, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } } }
            let time_it = { f } -> perform SystemTime in do
              let t0 = monotonic {}
              let r = f {}
              let t1 = monotonic {}
              { ns = duration_to_nanos { d = sub_duration { a = t1, b = t0 } }, result = r }
            let bench = { n, f } -> do
              f {}
              f {}
              f {}
              let a = time_it { f = f }
              let b = time_it { f = f }
              let c = time_it { f = f }
              a.result + b.result + c.result
            perform SystemTime in bench { n = 2000, f = {} -> k { n = 2000 } }
        """)),
    )

    /** The bench's exact timing shape (r4): the perform bound by `let` inside a
     *  function that also performs Time around it. */
    @Test
    fun `BUG 70b - a perform bound by let beside Time reads in the same function`() = assertEquals(
        500500L,
        eval(program(imports, go, """
            let k = { n } -> perform SystemTime in do
              let t0 = monotonic {}
              let r = perform CoopAsync in go { i = n, acc = 0 }
              let t1 = monotonic {}
              r
            k { n = 1000 }
        """)),
    )
}
