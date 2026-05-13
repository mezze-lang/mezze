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
              let v = Async.await { p = t }
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
        // 42 directly; await returns 42; perform returns Ok {val = 42}.
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}
            use std::effects::async::{Async, LoomAsync}

            perform ExceptionToResult in perform LoomAsync in do
              let t = Async.spawn { f = {} ->
                if False then Exception.throw { error = "never" } else 42
              }
              let v = Async.await { p = t }
              Ok { val = v }
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(42L, payload.getField("val"))
    }
}
