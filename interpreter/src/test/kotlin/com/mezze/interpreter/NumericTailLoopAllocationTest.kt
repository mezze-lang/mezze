package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A numeric tail loop must allocate O(1), not O(iterations).
 *
 * READ THIS BEFORE TRUSTING IT: this test CANNOT catch the regression it was
 * originally written for, and that is recorded here so nobody takes its green
 * as coverage of it.
 *
 * The defect was that `acc + n` reached bytecode emission with its operands in
 * a `MezzeRecord` — `__native_int_add { l = .., r = .. }` — which allocated
 * ~100 bytes per iteration and made `FusedLongTailLoop` silently refuse the
 * enclosing function (12 ns/iter against 0.2). But it only arises when the
 * NativeSignatures registry is EMPTY at compile time, which is true under
 * `mez build` (the interpreter module is `compileOnly` in the CLI) and FALSE in
 * a test JVM, where the registry is loaded and the lowering-side collapse
 * succeeds. MEASURED: this test passed identically with the fix disabled.
 *
 * The real coverage for that logic is
 * `com.mezze.interpreter.bytecode.NativeRecordOperandTest`, which exercises the
 * record-operand extraction directly and does fail without the fix.
 *
 * This test is kept anyway, because the INVARIANT is worth holding on its own:
 * a numeric tail loop that starts allocating per iteration is a real
 * regression whatever its cause, and this catches the ones a test JVM can see.
 */
class NumericTailLoopAllocationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** Bytes allocated on THIS thread while [body] runs. */
    private fun allocatedBytes(body: () -> Unit): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        body()
        return bean.getThreadAllocatedBytes(id) - before
    }

    private fun sumTo(n: Int): String = """
        let sum_tr = { n, acc } ->
          if n <= 0 then acc
          else sum_tr { n = n - 1, acc = acc + n }
        sum_tr { n = $n, acc = 0 }
    """.trimIndent()

    @Test
    fun `an annotation-free numeric tail loop allocates O(1), not O(iterations)`() {
        // Correctness first: the loop must still compute the right sum.
        assertEquals(20000100000L, MezzeTestHelper.run(sumTo(200_000)))
        assertEquals(80000200000L, MezzeTestHelper.run(sumTo(400_000)))

        // Warm the shared TypeChecker and the compiled loop, so the measured
        // runs are not dominated by one-time compilation allocation.
        repeat(2) {
            MezzeTestHelper.run(sumTo(200_000))
            MezzeTestHelper.run(sumTo(400_000))
        }

        val small = allocatedBytes { MezzeTestHelper.run(sumTo(200_000)) }
        val large = allocatedBytes { MezzeTestHelper.run(sumTo(400_000)) }

        // Per-iteration allocation shows up as GROWTH between the two sizes;
        // the fixed cost of parse + typecheck + compile is common to both. A
        // record per iteration would add ~100 bytes * 200000 = ~20MB, so a 4MB
        // bound fails by a wide margin while staying robust across machines.
        val growth = large - small
        assertTrue(
            growth < 4_000_000,
            "doubling the iteration count grew allocation by $growth bytes " +
                "($small -> $large). A numeric tail loop must not allocate per " +
                "iteration.",
        )
    }
}
