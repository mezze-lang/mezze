package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #194 — a fold over a List with no stages keeps the long fast path when its
 * element type is Int.
 *
 * S-12 stopped a list SOURCE from being evidence the elements are numbers — it
 * says nothing about what is in the list — and required a stage instead. That was
 * right, and it cost the plain fold its fast path. The evidence a plain fold DOES
 * carry is its folder: each lambda parameter gets the slot kind of its solved
 * type, so a folder over Ints has two LONG parameters, and one over Floats, records
 * or a type variable does not.
 *
 * The answer is the same on either path, so these assert the path taken.
 */
class ListFoldElementKindFusionTest {

    private val emitted = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        BytecodeCompiler.FUSED_EMITS.set(emitted)
    }

    @AfterEach
    fun tearDown() {
        BytecodeCompiler.FUSED_EMITS.remove()
    }

    private fun run(body: String): Any = MezzeTestHelper.run(
        "use std::collections::list\n\n${body.trimIndent()}",
    )

    @Test
    fun `an Int fold with a non-zero init takes the long pipeline`() {
        val out = run("""
            let main = {} -> do
              let l = list::from_seq { xs = [1, 2, 3] }
              list::fold_left { xs = l, init = 10, f = {acc, x} -> acc + x }
            main {}
        """)
        assertEquals(16L, out)
        assertTrue("ListToLongSeq+FusedLongPipeline" in emitted, "emitted: $emitted")
    }

    @Test
    fun `an Int product fold takes the long pipeline`() {
        val out = run("""
            let main = {} -> do
              let l = list::from_seq { xs = [2, 3, 4] }
              list::fold_left { xs = l, init = 1, f = {acc, x} -> acc * x }
            main {}
        """)
        assertEquals(24L, out)
        assertTrue("ListToLongSeq+FusedLongPipeline" in emitted, "emitted: $emitted")
    }

    @Test
    fun `an empty Int list folds to its init on the long pipeline`() {
        val out = run("""
            let main = {} -> do
              let l: List Int = list::from_seq { xs = [] }
              list::fold_left { xs = l, init = 7, f = {acc, x} -> acc + x }
            main {}
        """)
        assertEquals(7L, out)
        assertTrue("ListToLongSeq+FusedLongPipeline" in emitted, "emitted: $emitted")
    }

    @Test
    fun `a Float fold stays off the long pipeline`() {
        val out = run("""
            let main = {} -> do
              let l = list::from_seq { xs = [1.5, 2.5] }
              list::fold_left { xs = l, init = 1.0, f = {acc, x} -> acc + x }
            main {}
        """)
        assertEquals(5.0, out)
        // It reaches the same decision and takes the generic loop: DOUBLE slots.
        assertEquals(listOf("FusedLoop"), emitted)
    }

    @Test
    fun `a fold whose element type is a variable stays off the long pipeline`() {
        val out = run("""
            let total: { xs: List 'a, f: { acc: Int, x: 'a } -> Int } -> Int =
              { xs, f } -> list::fold_left { xs = xs, init = 5, f = f }
            let main = {} -> do
              let l = list::from_seq { xs = ["ab", "c"] }
              total { xs = l, f = {acc, x} -> acc + x.size {} }
            main {}
        """)
        assertEquals(8L, out)
        assertTrue("ListToLongSeq+FusedLongPipeline" !in emitted, "emitted: $emitted")
    }
}
