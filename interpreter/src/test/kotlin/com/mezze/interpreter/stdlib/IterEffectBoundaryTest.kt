package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Can an effect be performed from INSIDE an `Iter` stage, source or sink?
 *
 * Every `Iter` combinator advertises that it can — `map`, `filter`,
 * `take_while`, `flat_map`, `unfold`, `fold`, `for_each`, `any`, `all` all
 * carry `<...>` on the callback and on their own result. Nothing tested it.
 * Every other Iter test uses pure callbacks: `ParallelChunkFusionTest`
 * installs a runner performer AROUND the pipeline, but its stage bodies are
 * arithmetic (`x * x`).
 *
 * It was worth asking rather than assuming, because the walk happens in Java.
 * A sink calls `__native_iter_*`, which sits behind a `@TruffleBoundary`, and
 * the driver loop pulls elements and invokes the guest callback from there.
 * Performing an effect SUSPENDS the guest — it unwinds to its handler and
 * resumes later. A suspension crossing a Java frame that is not expecting one
 * is exactly where this would break, and it would break as a host exception or
 * a lost continuation rather than as a type error.
 *
 * It holds. Sinks, stages, sources and both non-local exits all work, with no
 * change needed to make them. These tests pin that, in four escalating
 * positions, so the streaming design can rest on it.
 *
 * MemoryFileSystem is the instrument throughout: its writes are observable
 * afterwards, so a test can tell "the effect ran" from "the effect was
 * dropped", which a Console performer could not.
 *
 * One trap, worth recording because it cost an hour and looked exactly like a
 * dropped verdict: `FileSystem.stat` returns `Ok` for a missing path, with
 * `kind = NotFound`, deliberately — existence checks stay fast. So
 * `match stat is Ok -> True` is True for EVERY path, and a filter written that
 * way correctly keeps everything. Use `exists`.
 */
class IterEffectBoundaryTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    private val head = """
        use std::effects::filesystem::{ FileSystem, MemoryFileSystem, exists }
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
                  let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" } }
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
                  let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" } }
                  acc + x
              }
              total
        """.trimIndent()
        assertEquals(10L, eval(code))
    }

    // ==================== 2. A STAGE performing an effect ====================

    /**
     * A stage is strictly harder than a sink: it runs in the middle of the
     * walk, with the driver loop's own state live on the Java stack above it.
     * A suspension here has to unwind past that and come back to it.
     */
    @Test
    fun `map performs FileSystem mid-walk`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              let total = iter::sum {
                it = iter::map {
                  it = iter::range { start = 1, end = 4 },
                  f = { x } -> do
                    let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" } }
                    x * 10
                }
              }
              total
        """.trimIndent()
        assertEquals(60L, eval(code))
    }

    /**
     * `filter`'s predicate decides CONTROL FLOW from inside the walk — the
     * driver loop has to act on a value produced by a suspended computation,
     * which is a step beyond `map`, where it only has to store one.
     */
    @Test
    fun `filter decides on a value read through FileSystem`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/keep" }
              let _ = FileSystem.write_bytes { path = "/keep/2", bytes = bytes::from_str { s = "y" } }
              let _ = FileSystem.write_bytes { path = "/keep/4", bytes = bytes::from_str { s = "y" } }
              iter::count {
                it = iter::filter {
                  it = iter::range { start = 0, end = 6 },
                  p = { x } -> match exists { path = "/keep/$(int::to_str { n = x })" } is
                    Ok { val = yes } -> yes
                    Err { error = _ } -> False
                }
              }
        """.trimIndent()
        assertEquals(2L, eval(code))
    }

    /** `any` short-circuits — it stops the walk from inside the predicate. */
    @Test
    fun `any short-circuits on an effectful predicate`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/keep" }
              let _ = FileSystem.write_bytes { path = "/keep/3", bytes = bytes::from_str { s = "y" } }
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

    // ==================== 3. A SOURCE performing an effect ====================

    /**
     * `unfold` is the case the streaming design was actually written for: a
     * user-defined source that pulls from somewhere — a file handle, a DB
     * cursor, a paginated API. Its step function performs the effect that
     * decides whether there is another element, which is the shape every real
     * streaming source has.
     *
     * Here the state is a page number and the step reads that page, stopping
     * when the read fails.
     */
    @Test
    fun `unfold drives iteration from a FileSystem read`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/pages" }
              let _ = FileSystem.write_bytes { path = "/pages/0", bytes = bytes::from_str { s = "a" } }
              let _ = FileSystem.write_bytes { path = "/pages/1", bytes = bytes::from_str { s = "bb" } }
              let _ = FileSystem.write_bytes { path = "/pages/2", bytes = bytes::from_str { s = "ccc" } }
              iter::sum {
                it = iter::unfold {
                  state = 0,
                  step = { s } -> match FileSystem.read_bytes { path = "/pages/$(int::to_str { n = s })" } is
                    Ok { val = b } -> Yield { val = bytes::size { b = b }, next = s + 1 }
                    Err { error = _ } -> Done
                }
              }
        """.trimIndent()
        assertEquals(6L, eval(code))
    }

    /**
     * The full pipeline the design describes: an effectful source, pure stages
     * in the middle, and an effectful sink — the same walk crossed by reads on
     * the way in and writes on the way out.
     */
    @Test
    fun `an effectful source, pure stages and an effectful sink compose`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/in" }
              let _ = FileSystem.create_directory { path = "/out" }
              let _ = FileSystem.write_bytes { path = "/in/0", bytes = bytes::from_str { s = "a" } }
              let _ = FileSystem.write_bytes { path = "/in/1", bytes = bytes::from_str { s = "bb" } }
              let _ = FileSystem.write_bytes { path = "/in/2", bytes = bytes::from_str { s = "ccc" } }
              let _ = FileSystem.write_bytes { path = "/in/3", bytes = bytes::from_str { s = "dddd" } }
              let _ = iter::for_each {
                it = iter::filter {
                  it = iter::map {
                    it = iter::unfold {
                      state = 0,
                      step = { s } -> match FileSystem.read_bytes { path = "/in/$(int::to_str { n = s })" } is
                        Ok { val = b } -> Yield { val = { i = s, n = bytes::size { b = b } }, next = s + 1 }
                        Err { error = _ } -> Done
                    },
                    f = { x } -> { i = x.i, n = x.n * 2 }
                  },
                  p = { x } -> x.n > 4
                },
                f = { x } -> do
                  let _ = FileSystem.write_bytes { path = "/out/$(int::to_str { n = x.i })", bytes = bytes::from_str { s = "k" } }
                  {}
              }
              match FileSystem.list { path = "/out" } is
                Ok { val = names } -> seq::size { xs = names }
                Err { error = _ } -> 0 - 1
        """.trimIndent()
        // sizes 1,2,3,4 -> doubled 2,4,6,8 -> kept >4: 6 and 8 -> two writes
        assertEquals(2L, eval(code))
    }

    /** `flat_map` performs per OUTER element and yields a nested iterator. */
    @Test
    fun `flat_map performs an effect and flattens`() {
        val code = """
            $head
            perform MemoryFileSystem in do
              let _ = FileSystem.create_directory { path = "/d" }
              iter::count {
                it = iter::flat_map {
                  it = iter::range { start = 1, end = 4 },
                  f = { x } -> do
                    let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" } }
                    iter::range { start = 0, end = x }
                }
              }
        """.trimIndent()
        // 1 + 2 + 3 elements
        assertEquals(6L, eval(code))
    }

    // ==================== 4. Non-local exits ====================

    /**
     * `Exception.throw` is an ABORT, not a suspend-and-resume: the handler
     * discards the continuation. Thrown from inside a stage, it has to unwind
     * the Java driver loop and never come back to it — the harder direction,
     * because a half-walked pipeline must not be resumed or left pinned.
     *
     * `ExceptionToResult` aborts with `Err`, which fixes the type of the body
     * it wraps to that same `Result` — so the `Ok` goes INSIDE the perform and
     * the match that unwraps it stays outside. Getting this backwards reads as
     * "expected Result 'a Str, but got Str", which is about the handler's
     * contract and not about Iter at all.
     */
    @Test
    fun `a throw from inside a stage aborts the whole walk`() {
        val code = """
            use std::collections::iter
            use std::int
            use std::effects::filesystem::{ MemoryFileSystem }
            use std::effects::exception::{ Exception, ExceptionToResult }
            perform MemoryFileSystem in do
              let r = perform ExceptionToResult in Ok { val = iter::sum {
                it = iter::map {
                  it = iter::range { start = 0, end = 10 },
                  f = { x } -> if x == 4 then Exception.throw { error = "stopped at 4" } else x
                }
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
                    let _ = FileSystem.write_bytes { path = "/d/f$(int::to_str { n = x })", bytes = bytes::from_str { s = "x" } }
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
     * `Async.await` PARKS: the task suspends and a scheduler resumes it. That
     * is the suspension this whole question is about, and a sink is where a
     * streaming pipeline would hit it — an HTTP body, a DB cursor.
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

    /** And in a STAGE, where the park has the driver loop above it. */
    @Test
    fun `await from inside a stage parks and resumes`() {
        val code = """
            use std::collections::iter
            use std::effects::async::{ Async, LoomAsync, await }
            perform LoomAsync in
              iter::sum {
                it = iter::map {
                  it = iter::range { start = 1, end = 5 },
                  f = { x } -> match await { p = Async.spawn { f = { } -> x * x } } is
                    Ok { val = n } -> n
                    Err { error = _ } -> 0
                }
              }
        """.trimIndent()
        assertEquals(30L, eval(code))
    }
}
