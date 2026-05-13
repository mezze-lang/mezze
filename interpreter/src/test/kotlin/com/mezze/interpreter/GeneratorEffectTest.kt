package com.mezze.interpreter

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled

/**
 * Tests for the userland Generator effect.
 *
 * Disabled while the Generator stdlib is being redesigned on the new
 * effect-handler dispatch (task #82). The original `Collect` handler
 * used `scope.cont.suspend_and_resume` + `take_collected` in
 * `finalize_scope`, which doesn't translate to the new design's
 * no-return-clause model. Re-enable once `Generator` is re-implemented.
 */
@Disabled("Generator stdlib under redesign — see task #82")
class GeneratorEffectTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertSeqEquals(result: Any, expected: List<Any>) {
        assertTrue(result is MezzeSeq<*>, "Expected MezzeSeq but got ${result::class.simpleName}")
        val seq = result as MezzeSeq<*>
        assertEquals(expected.size, seq.size(), "Seq size mismatch")
        for (i in expected.indices) {
            assertEquals(expected[i], seq.get(i), "Element at index $i mismatch")
        }
    }

    @Test
    fun `generator yield collects values`() {
        val code = """
            use std::effects::generator::{Generator, Collect}

            perform Collect in do
                Generator.yield {value = 1}
                Generator.yield {value = 2}
                Generator.yield {value = 3}
        """
        val result = eval(code)
        assertSeqEquals(result, listOf(1L, 2L, 3L))
    }

    // Test removed: Cannot test empty body because `{}` is pure and typechecker
    // correctly rejects `perform Collect in {}`. The Generator effect is designed
    // to be used with code that actually yields values.

    @Test
    fun `generator with single yield`() {
        val code = """
            use std::effects::generator::{Generator, Collect}

            perform Collect in Generator.yield {value = 42}
        """
        val result = eval(code)
        assertSeqEquals(result, listOf(42L))
    }

    @Test
    fun `generator with loop`() {
        val code = """
            use std::effects::generator::{Generator, Collect}
            use std::collections::seq

            perform Collect in
                seq::for_each {xs = [1, 2, 3, 4, 5], f = {x} -> Generator.yield {value = x * 2}}
        """
        val result = eval(code)
        assertSeqEquals(result, listOf(2L, 4L, 6L, 8L, 10L))
    }

    @Test
    fun `generator with string yields`() {
        val code = """
            use std::effects::generator::{Generator, Collect}

            perform Collect in do
                Generator.yield {value = "hello"}
                Generator.yield {value = "world"}
        """
        val result = eval(code)
        assertSeqEquals(result, listOf("hello", "world"))
    }

    @Test
    fun `generator with conditional yields`() {
        val code = """
            use std::effects::generator::{Generator, Collect}
            use std::collections::seq

            perform Collect in
                seq::for_each {xs = [1, 2, 3, 4, 5, 6], f = {x} ->
                    if x % 2 == 0 then Generator.yield {value = x} else {}
                }
        """
        val result = eval(code)
        assertSeqEquals(result, listOf(2L, 4L, 6L))
    }

    @Test
    fun `generator using yield helper`() {
        val code = """
            use std::effects::generator::{Generator, Collect, yield}

            perform Collect in do
                yield {value = 10}
                yield {value = 20}
                yield {value = 30}
        """
        val result = eval(code)
        assertSeqEquals(result, listOf(10L, 20L, 30L))
    }

    @Test
    fun `multiple generator performs are independent`() {
        // Test that two separate Collect handlers maintain independent state
        // by running two generators sequentially
        val code1 = """
use std::effects::generator::{Generator, Collect}

perform Collect in do
    Generator.yield {value = 1}
    Generator.yield {value = 2}
        """
        val result1 = eval(code1)
        assertSeqEquals(result1, listOf(1L, 2L))

        val code2 = """
use std::effects::generator::{Generator, Collect}

perform Collect in do
    Generator.yield {value = 10}
    Generator.yield {value = 20}
        """
        val result2 = eval(code2)
        assertSeqEquals(result2, listOf(10L, 20L))
    }
}
