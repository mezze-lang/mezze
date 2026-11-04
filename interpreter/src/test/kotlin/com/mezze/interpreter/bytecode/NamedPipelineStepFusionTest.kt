package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A named pipeline step must still COMPUTE THE RIGHT ANSWER once its local
 * lambda has been monomorphised.
 *
 * `LocalLambdaSpecializer` substitutes concrete types into a polymorphic local
 * lambda's body and rewrites its constraint-driven method calls to direct ones
 * — `x * factor` goes from a dictionary field read to an arithmetic call against
 * a specific `NumMul` impl. Picking the WRONG impl is the failure mode that
 * costs correctness rather than speed, and it would not show up as a type error.
 * So these run the specialized code and check the number.
 *
 * WHY THERE IS NO FUSION ASSERTION HERE. `BytecodeCompiler.FUSED_EMITS`, which
 * [ListFoldElementKindFusionTest] uses to assert a chosen path, records nothing
 * for an `iter` chain — measured empty for the inline spelling too, which does
 * fuse. And a typechecker lowering test is blind for a different reason: it
 * lowers the stdlib from source where `mez build` serves
 * `PrecompiledStdlib.preloweredFunctions`, and on that path the pipeline fuses
 * with the pass disabled. The perf claim therefore rests on a measurement with
 * the pass as its own switch — `bench/cases/dotprobe` k3 at 64 672 ps/elem off
 * against 206 on, control steady, identical checksums, 314x — recorded in
 * PERF-LOG. [LocalLambdaSpecializerTest] pins the transformation itself.
 *
 * `MezzeTestHelper`'s TypeChecker is static, so a program that only compiles
 * because an earlier test warmed the checker looks like a pass; see
 * `project_static_typechecker_hides`.
 */
class NamedPipelineStepFusionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /**
     * `dotprobe` k3's shape: a named step capturing a ground outer binding.
     *
     * 3i for i in 0..<20 keeping 3i > 2 sums to 3*(1+2+…+19) = 570.
     */
    @Test
    fun `a named step that captures computes the same answer`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::iter

            let run = { n } -> do
              let factor = if n > 0 then 3 else 1
              let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
              iter::fold {
                it = iter::filter { it = scaled { xs = iter::range { start = 0, end = n } }, p = { x } -> x > 2 },
                init = 0, f = { acc, x } -> acc + x,
              }

            let main = {} -> run { n = 20 }
            main {}
            """.trimIndent(),
        )
        assertEquals(570L, out)
    }

    /** The same shape with the factor as a literal — the capture is not the trigger. */
    @Test
    fun `a named step without a capture computes the same answer`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::iter

            let run = { n } -> do
              let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * 3 }
              iter::fold {
                it = iter::filter { it = scaled { xs = iter::range { start = 0, end = n } }, p = { x } -> x > 2 },
                init = 0, f = { acc, x } -> acc + x,
              }

            let main = {} -> run { n = 20 }
            main {}
            """.trimIndent(),
        )
        assertEquals(570L, out)
    }

    /**
     * A local lambda used at TWO types must keep working at both.
     *
     * This is the correctness counterpart to the control in
     * [LocalLambdaSpecializerTest]: that one asserts the pass declines to pin
     * the binding, this one asserts the program still gives both answers. Pinned
     * to its first use, the second would run the first's impl — a wrong number,
     * not a type error, which is exactly why it is checked by running.
     */
    @Test
    fun `a local lambda used at two types still answers at both`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::seq

            let run = {} -> do
              let pair = { x } -> seq::size { xs = [x, x] }
              let a = pair { x = 1 }
              let b = pair { x = "s" }
              a + b

            let main = {} -> run {}
            main {}
            """.trimIndent(),
        )
        assertEquals(4L, out)
    }
}
