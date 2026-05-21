package com.mezze.interpreter

import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Validates the cross-fiber Async + Exception composition fix.
 *
 * The motivating audit: in the old runtime, an Exception raised inside a
 * spawned task escaped past the outer ExceptionToResult handler because
 * the task's failure was wrapped as a generic RuntimeException at the
 * thread boundary.
 *
 * The new design (Pattern C from docs/effect-handlers/04-async-and-exceptions.md):
 * the spawned task inherits the parent's handler chain. If an op-call
 * propagates past all inherited handlers, it's stored on the task's
 * promise. `Async.await` re-issues the op-call on the parent fiber, where
 * outer handlers can dispatch it as if the raise had happened there.
 *
 * These tests document that the bug is fixed.
 */
class AsyncExceptionCompositionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `exception raised inside spawned task is caught by outer ExceptionToResult`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::async::{Async, LoomAsync}

            perform ExceptionToResult in perform LoomAsync in do
              let t = Async.spawn { f = {} -> Exception.throw { error = "boom" } }
              let v = match Async.await { p = t } is
                Ok { val } -> val
                Err { error = _ } -> -1
              Ok { val = v }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant) { "expected MezzeVariant, got ${result::class.simpleName}" }
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("boom", payload.getField("error"))
    }

    @Test
    fun `task that conditionally raises wraps via outer ExceptionToResult`() {
        // Body uses Exception (so typechecker keeps the ExceptionToResult
        // handler). The actual code path skips the raise — task returns
        // 42 directly; await returns Ok {val = 42}; perform returns Ok {val = 42}.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::async::{Async, LoomAsync}

            perform ExceptionToResult in perform LoomAsync in do
              let t = Async.spawn { f = {} ->
                if False then Exception.throw { error = "never" } else 42
              }
              let v = match Async.await { p = t } is
                Ok { val } -> val
                Err { error = _ } -> -1
              Ok { val = v }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(42L, payload.getField("val"))
    }

    @Test
    fun `raise inside Parallel map callback is caught by outer ExceptionToResult`() {
        // The parallel map runs on a virtual-thread pool. The callback at
        // index 2 raises. The outer ExceptionToResult must observe it via
        // chain-inheritance + propagateBatchFailure → fiber.suspend on the
        // parent.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform ExceptionToResult in perform VirtualParallel in do
              let r = Parallel.map { xs = [1, 2, 3, 4, 5], f = { x } ->
                if x == 3 then Exception.throw { error = "three" } else x * 10
              }
              Ok { val = r }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant) { "expected MezzeVariant, got ${result::class.simpleName}" }
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("three", payload.getField("error"))
    }

    @Test
    fun `raise inside Parallel both lands on outer handler`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform ExceptionToResult in perform VirtualParallel in do
              let _ = Parallel.both {
                l = {} -> Exception.throw { error = "left-fail" },
                r = {} -> 42,
              }
              Ok { val = 0 }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("left-fail", payload.getField("error"))
    }

    @Test
    fun `multiple spawns where one raises - await of failing promise raises`() {
        // Two spawns. Awaiting the failing one re-issues the op on the
        // parent fiber. The successful one is never awaited; that's OK,
        // its result is discarded.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::async::{Async, LoomAsync}

            perform ExceptionToResult in perform LoomAsync in do
              let good = Async.spawn { f = {} -> 100 }
              let bad = Async.spawn { f = {} -> Exception.throw { error = "bad" } }
              let _ = Async.await { p = good }
              let v = match Async.await { p = bad } is
                Ok { val } -> val
                Err { error = _ } -> -1
              Ok { val = v }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("bad", payload.getField("error"))
    }

    @Test
    fun `nested spawn raises - innermost handler wins`() {
        // outer task spawns inner. Inner raises. Inner's await is inside
        // the outer task body; outer handler is on the parent fiber.
        // The raise propagates through outer's await all the way to the
        // ExceptionToResult outside.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::async::{Async, LoomAsync}

            perform ExceptionToResult in perform LoomAsync in do
              let outer = Async.spawn { f = {} -> do
                let inner = Async.spawn { f = {} -> Exception.throw { error = "inner-bad" } }
                match Async.await { p = inner } is
                    Ok { val } -> val
                    Err { error = _ } -> -1
              }
              let v = match Async.await { p = outer } is
                Ok { val } -> val
                Err { error = _ } -> -1
              Ok { val = v }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("inner-bad", payload.getField("error"))
    }

    @Test
    fun `Parallel for_each whose callback raises surfaces on the handler`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform ExceptionToResult in perform VirtualParallel in do
              let _ = Parallel.for_each { xs = [1, 2, 3], f = { x } ->
                if x == 2 then Exception.throw { error = "two-fail" } else {}
              }
              Ok { val = 0 }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("two-fail", payload.getField("error"))
    }
}
