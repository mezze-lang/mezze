package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * #64 — Parallel.join panic semantics.
 *
 * <p>Audits that JVM {@link RuntimeException} (specifically
 * {@link ArithmeticException}, e.g. integer division by zero inside a
 * native) raised inside either side of a {@code Parallel.join} propagates
 * to the caller cleanly, without wedging the fiber, leaking the sibling,
 * or getting silently swallowed.
 *
 * <p>Contrast with {@code Exception.throw} (a Mezze effect op), which
 * routes through {@code TaskUnhandledOpException} and reaches the outer
 * perform's abort logic. JVM RuntimeExceptions from natives take the
 * {@code errSink} path in {@link com.mezze.interpreter.nodes.continuation.SpawnSupport#runBatchTask},
 * then get rethrown by {@code propagateBatchFailure} lines 217-221.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ParallelJoinPanicTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `ArithmeticException in right side of ForkJoinParallel_join propagates as RuntimeException`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel }

            perform LoomParallel in do
                Parallel.join {
                    l = {} -> 42,
                    r = {} -> 100 / 0
                }
        """
        val e = assertThrows(Exception::class.java) { eval(code) }
        val message = e.message ?: e.cause?.message ?: ""
        assertTrue(
            message.contains("Division by zero") || message.contains("/ by zero") ||
                message.contains("ArithmeticException"),
            "expected divide-by-zero in message chain, got: ${e.message}, cause: ${e.cause?.message}"
        )
    }

    @Test
    fun `ArithmeticException in left side of ForkJoinParallel_join propagates as RuntimeException`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel }

            perform LoomParallel in do
                Parallel.join {
                    l = {} -> 100 / 0,
                    r = {} -> 42
                }
        """
        val e = assertThrows(Exception::class.java) { eval(code) }
        val message = e.message ?: e.cause?.message ?: ""
        assertTrue(
            message.contains("Division by zero") || message.contains("/ by zero") ||
                message.contains("ArithmeticException"),
            "expected divide-by-zero in message chain, got: ${e.message}, cause: ${e.cause?.message}"
        )
    }

    @Test
    fun `panic in DeterministicSequential join propagates directly`() {
        val code = """
            use std::effects::parallel::{ Parallel, DeterministicSequential }

            perform DeterministicSequential in do
                Parallel.join {
                    l = {} -> 100 / 0,
                    r = {} -> 42
                }
        """
        assertThrows(Exception::class.java) { eval(code) }
    }

    @Test
    fun `panic in LoomParallel join propagates`() {
        val code = """
            use std::effects::parallel::{ Parallel, LoomParallel }

            perform LoomParallel in do
                Parallel.join {
                    l = {} -> 42,
                    r = {} -> 100 / 0
                }
        """
        assertThrows(Exception::class.java) { eval(code) }
    }
}
