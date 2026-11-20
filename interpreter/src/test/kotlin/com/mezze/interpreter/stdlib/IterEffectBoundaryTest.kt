package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Effects and `Iter`: a SINK callback may perform, a STAGE callback may not.
 *
 * A sink (`for_each`, `fold`, `any`, ...) runs its callback DURING the call, so
 * the callback's row sits on the arrow that performs it and the handler around
 * the call is the handler that is there. A stage (`map`, `filter`, `unfold`,
 * ...) only stores its callback for a later pull, so an effect in it would run
 * wherever the Iter is eventually pulled — possibly after the `perform` the
 * checker charged it to has ended. That is why stage callbacks are pure by
 * type (see `IterStageCallbacksArePureTest` in the typechecker), and why every
 * test here puts the effect in the sink.
 *
 * The walk happens in Java: a sink calls `__native_iter_*`, which sits behind
 * a `@TruffleBoundary`, and the driver loop invokes the guest callback from
 * there. These tests pin that performing, aborting and parking from inside
 * that loop all work.
 *
 * MemoryFileSystem is the instrument throughout: its writes are observable
 * afterwards, so a test can tell "the effect ran" from "the effect was
 * dropped", which a Console performer could not. One trap: `FileSystem.stat`
 * answers `Ok` with `kind = NotFound` for a missing path, so use `exists`.
 */
class IterEffectBoundaryTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val head = """
        use std::effects::filesystem::{ FileSystem, MemoryFileSystem, exists, WriteMode }
        use std::collections::{ bytes, iter, seq }
        use std::int
    """.trimIndent()

    // ==================== 1. A SINK performing an effect ====================

    /**
     * `for_each` is the sink whose whole purpose is to perform something. Five
     * elements, five writes, five distinct files — so the count also proves no
     * element was visited twice or skipped.
     */
    @Test
    fun `for_each performs FileSystem once per element`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = iter::for_each {
                it = iter::range { start = 0, end = 5 },
                f = { x } -> do
                  let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate }
                  {}
              }
              match FileSystem.list { path = "/d" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(5L, eval(code))
    }

    /** `fold` is the canonical sink — every other one is a variant of it. */
    @Test
    fun `fold performs FileSystem and still accumulates`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let total = iter::fold {
                it = iter::range { start = 1, end = 5 },
                init = 0,
                f = { acc, x } -> do
                  let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate }
                  acc + x
              }
              total
        """.trimIndent()
        assertEquals(10L, eval(code))
    }

    /**
     * Pure stages feed an effectful sink: the transform happens in `map` and
     * `filter`, the read happens in `fold`. This is the shape an effectful
     * stage used to be written in, moved to where the handler is.
     */
    @Test
    fun `pure stages feed a sink that reads through FileSystem`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/keep" }
              let _ = FileSystem.write_bytes { path = "/keep/4", bytes = bytes::from_str { s = "y" }, mode = CreateOrTruncate }
              let _ = FileSystem.write_bytes { path = "/keep/8", bytes = bytes::from_str { s = "y" }, mode = CreateOrTruncate }
              iter::fold {
                it = iter::filter { it = iter::map { it = iter::range { start = 0, end = 6 }, f = { x } -> x * 2 }, p = { x } -> x > 2 },
                init = 0,
                f = { acc, x } -> match exists { path = "/keep/$(int::to_str { n = x })" } is
                  Ok { val = yes } -> if yes then acc + 1 else acc
                  Err { error = _ } -> acc
              }
        """.trimIndent()
        // 0,2,4,6,8,10 -> >2: 4,6,8,10 -> exists: 4 and 8
        assertEquals(2L, eval(code))
    }

    /** `any` short-circuits — it stops the walk from inside the predicate. */
    @Test
    fun `any short-circuits on an effectful predicate`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/keep" }
              let _ = FileSystem.write_bytes { path = "/keep/3", bytes = bytes::from_str { s = "y" }, mode = CreateOrTruncate }
              iter::any {
                it = iter::range { start = 0, end = 6 },
                p = { x } -> match exists { path = "/keep/$(int::to_str { n = x })" } is
                  Ok { val = yes } -> yes
                  Err { error = _ } -> False
              }
        """.trimIndent()
        assertEquals(true, eval(code))
    }

    /** …and reports False, rather than a truthy host value, when nothing matches. */
    @Test
    fun `any with an effectful predicate is False when nothing matches`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/keep" }
              iter::any {
                it = iter::range { start = 0, end = 6 },
                p = { x } -> match exists { path = "/keep/$(int::to_str { n = x })" } is
                  Ok { val = yes } -> yes
                  Err { error = _ } -> False
              }
        """.trimIndent()
        assertEquals(false, eval(code))
    }

    // ==================== 2. Non-local exits from a sink ====================

    /**
     * `Exception.throw` is an ABORT, not a suspend-and-resume: the handler
     * discards the continuation. Thrown from inside the sink's callback, it has
     * to unwind the Java driver loop and never come back to it.
     *
     * `ExceptionToResult` aborts with `Err`, which fixes the type of the body
     * it wraps to that same `Result` — so the `Ok` goes INSIDE the perform and
     * the match that unwraps it stays outside.
     */
    @Test
    fun `a throw from inside a sink aborts the whole walk`() {
        val code = """
            use std::collections::iter
            use std::int
            use std::effects::filesystem::{ MemoryFileSystem, WriteMode }
            use std::effects::exception::{ Exception, ExceptionToResult }
            perform MemoryFileSystem in do
              let r = perform ExceptionToResult in Ok { val = iter::fold {
                it = iter::range { start = 0, end = 10 },
                init = 0,
                f = { acc, x } -> if x == 4 then Exception.throw { error = "stopped at 4" } else acc + x
              } }
              match r is
                Ok { val = n } -> int::to_str { n = n }
                Err { error = e } -> e
        """.trimIndent()
        assertEquals("stopped at 4", eval(code))
    }

    /**
     * And the abort must not have run the rest of the pipeline. The sink
     * writes a file per element; after a throw at the fifth, exactly four must
     * exist — so the walk stopped where it said it did, rather than finishing
     * and discarding the answer.
     *
     * The count is taken OUTSIDE the `perform ExceptionToOption`, because that
     * handler aborts with `None` and so fixes its body's type to an Option.
     */
    @Test
    fun `a throw stops the walk where it happened`() {
        val code = """
            $head
            use std::effects::exception::{ Exception, ExceptionToOption }
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let _ = perform ExceptionToOption in do
                let _ = iter::for_each {
                  it = iter::range { start = 0, end = 10 },
                  f = { x } -> if x == 4 then Exception.throw { error = "stop" } else do
                    let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" }, mode = CreateOrTruncate }
                    {}
                }
                Some { val = {} }
              match FileSystem.list { path = "/d" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        assertEquals(4L, eval(code))
    }

    /**
     * `Async.await` PARKS: the task suspends and a scheduler resumes it. A
     * sink is where a streaming consumer hits it — an HTTP body, a DB cursor.
     */
    @Test
    fun `await from inside a sink parks and resumes`() {
        val code = """
            use std::collections::iter
            use std::effects::async::{ Async, LoomAsync, await }
            perform LoomAsync in
              iter::fold {
                it = iter::range { start = 1, end = 5 },
                init = 0,
                f = { acc, x } -> match await { p = Async.spawn { f = { } -> x * x } } is
                  Ok { val = n } -> acc + n
                  Err { error = _ } -> acc
              }
        """.trimIndent()
        assertEquals(30L, eval(code))
    }
}
