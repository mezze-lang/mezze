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
 * The answer is the same on either path, so these assert the path taken. The
 * long pipeline walks a List's cells in place; it used to copy them into a
 * long-backed Seq first, which cost more than the fold.
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
        assertTrue("FusedLongPipeline(List)" in emitted, "emitted: $emitted")
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
        assertTrue("FusedLongPipeline(List)" in emitted, "emitted: $emitted")
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
        assertTrue("FusedLongPipeline(List)" in emitted, "emitted: $emitted")
    }

    @Test
    fun `a filter then fold over an Int List walks the cells on the long pipeline`() {
        val out = run("""
            let main = {} -> do
              let l = list::from_seq { xs = [1, 2, 3, 4, 5] }
              list::fold_left { xs = list::filter { xs = l, p = {x} -> x > 2 }, init = 100, f = {acc, x} -> acc + x }
            main {}
        """)
        assertEquals(112L, out)
        assertTrue("FusedLongPipeline(List)" in emitted, "emitted: $emitted")
    }

    @Test
    fun `a map then to_seq over an Int List keeps every element in order`() {
        val out = run("""
            use std::collections::seq
            let main = {} -> do
              let l = list::from_seq { xs = [1, 2, 3] }
              list::to_seq { xs = list::map { xs = l, f = {x} -> x * 10 } }.to_dbg {}
            main {}
        """)
        assertEquals("[10, 20, 30]", out)
        assertTrue("FusedLongPipeline(List)" in emitted, "emitted: $emitted")
    }

    @Test
    fun `an Int fold over a Seq with a non-zero init takes the long pipeline`() {
        val out = run("""
            use std::collections::seq
            let total: { xs: [Int] } -> Int = { xs } -> seq::fold { xs = xs, init = 10, f = {acc, x} -> acc + x }
            let main = {} -> total { xs = [1, 2, 3] }
            main {}
        """)
        assertEquals(16L, out)
        assertTrue("FusedLongPipeline(Seq)" in emitted, "emitted: $emitted")
    }

    /** Unannotated, `tf` generalises its element over every 'a with
     *  `NumAdd 'a for Int having 'out = Int`, so the folder's element slot is not
     *  LONG. The value decides instead. */
    private val unannotated = """
        let tf = { xs } -> list::fold_left { xs = xs, init = 10, f = {acc, x} -> acc + x }
    """.trimIndent()

    @Test
    fun `an unannotated fold called at Int is specialised and takes the long pipeline`() {
        // Mono specialises `tf` at Int. Its folder reduces to `__native_int_add`
        // over the two params only once the inliner flattens wrapper bodies and
        // inlines closure-specialised wrappers; then the folder's slots are LONG.
        val out = run("""
            $unannotated
            let main = {} -> tf { xs = list::from_seq { xs = [1, 2, 3] } }
            main {}
        """)
        assertEquals(16L, out)
        assertTrue(emitted.isNotEmpty() && emitted.all { it == "FusedLongPipeline(List)" }, "emitted: $emitted")
    }

    @Test
    fun `an unannotated fold reached unspecialised checks the value and takes the long pipeline`() {
        // Passed as a value, `tf` runs its GENERIC body: the element slot is not
        // LONG there, so the list itself is tested at run time.
        val out = run("""
            $unannotated
            let apply = { f, xs } -> f { xs = xs }
            let main = {} -> apply { f = tf, xs = list::from_seq { xs = [1, 2, 3] } }
            main {}
        """)
        assertEquals(16L, out)
        assertTrue("IsLongBacked?FusedLongPipeline:FusedLoop" in emitted, "emitted: $emitted")
    }

    @Test
    fun `an unannotated fold over a Nat List takes the long pipeline too`() {
        val out = run("""
            $unannotated
            let main = {} -> tf { xs = list::from_seq { xs = [1n, 2n, 3n] } }
            main {}
        """)
        assertEquals(16L, out)
    }

    @Test
    fun `an unannotated fold over elements that are not longs takes the generic loop`() {
        // An impl in THIS module widens 'a past what the type can promise.
        val out = run("""
            use std::abilities::numeric::{ NumAdd }
            type Cents = { c: Int }
            impl NumAdd Cents for Int having 'out = Int where {
              add = { it, r } -> it + r.c
            }
            $unannotated
            let main = {} -> do
              let a: Cents = { c = 5 }
              let b: Cents = { c = 7 }
              tf { xs = list::from_seq { xs = [a, b] } }
            main {}
        """)
        assertEquals(22L, out)
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
        assertTrue("FusedLongPipeline(List)" !in emitted, "emitted: $emitted")
    }
}
