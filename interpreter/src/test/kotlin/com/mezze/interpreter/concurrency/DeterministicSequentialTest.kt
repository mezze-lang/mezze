package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * #63 — DeterministicSequential: a Parallel impl that runs l then r
 * sequentially on the current thread. Same signature as LoomParallel
 * and LoomParallel, but zero scheduler nondeterminism.
 *
 * <p>Useful in tests that want to verify combinator correctness
 * (associativity of reduce, ordering of map results, etc.) without
 * having to reason about scheduler interleavings.
 */
@Execution(ExecutionMode.SAME_THREAD)
class DeterministicSequentialTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test DeterministicSequential join returns both values`() {
        val code = """
            use std::effects::parallel::{ Parallel, DeterministicSequential }

            perform DeterministicSequential in do
                Parallel.join { l = {} -> 1, r = {} -> 2 }
        """
        val result = eval(code) as MezzeRecord
        assertEquals(1L, result.getField("l"))
        assertEquals(2L, result.getField("r"))
    }

    @Test
    fun `test DeterministicSequential runs l before r`() {
        // Encode order in the return values: l reports the accumulator
        // before r runs, r reports the accumulator after l ran. In a
        // deterministic sequential impl, l always finishes before r
        // starts, so l sees 0 and r sees 1.
        val code = """
            use std::effects::parallel::{ Parallel, DeterministicSequential }
            use std::effects::mutation::{ Mut, LocalMut, MutInt, int_to_mut, read_int, write_int }

            perform LocalMut in perform DeterministicSequential in do
                let counter = int_to_mut { n = 0 }
                let both = Parallel.join {
                    l = {} -> do
                        let before = read_int { n = counter }
                        write_int { n = counter, v = before + 1 }
                        before
                    ,
                    r = {} -> read_int { n = counter }
                }
                both
        """
        val result = eval(code) as MezzeRecord
        assertEquals(0L, result.getField("l"), "l should see counter = 0 before it increments")
        assertEquals(1L, result.getField("r"), "r should see counter = 1 after l ran")
    }

    @Test
    fun `test DeterministicSequential with derived map combinator`() {
        val code = """
            use std::effects::parallel::{ Parallel, DeterministicSequential, map }

            perform DeterministicSequential in do
                map { xs = [1, 2, 3, 4, 5], f = { x } -> x * 2 }
        """
        val result = eval(code) as MezzeSeq<*>
        assertEquals(5, result.size())
        assertEquals(2L, result.get(0))
        assertEquals(4L, result.get(1))
        assertEquals(6L, result.get(2))
        assertEquals(8L, result.get(3))
        assertEquals(10L, result.get(4))
    }

    @Test
    fun `test DeterministicSequential with fold combinator`() {
        val code = """
            use std::effects::parallel::{ Parallel, DeterministicSequential, fold }

            perform DeterministicSequential in do
                fold {
                    xs = [1, 2, 3, 4, 5],
                    zero = 0,
                    map_fn = { x } -> x,
                    combine = { l, r } -> l + r
                }
        """
        assertEquals(15L, eval(code))
    }
}
