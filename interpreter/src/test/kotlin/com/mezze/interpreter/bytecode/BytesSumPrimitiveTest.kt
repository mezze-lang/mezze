package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A zero-init ADD fold over `Bytes` becomes a primitive loop over the byte
 * array, with no guest callback at all.
 *
 * `BuiltinSumBytes` is the peer of `BuiltinSumLongSeq`, and it is the ONLY shape
 * a bytes source is allowed to fuse into. Routing bytes into the GENERIC fused
 * loop was built and measured first and is not worth having: a bare fold has no
 * stages to eliminate, so the generic loop only swaps `BytesNatives.fold` — a
 * tight loop on the scalar-ABI bypass — for a boxed accumulator and a
 * bounds-checked read. This path is different in kind: it does not call the
 * folder, it recognises it and deletes it.
 *
 * MEASURED on `bench/cases/fuseall` kernel 4 at n=2M, min of 5, with the
 * canonicalizer switched at BUILD time (which is where it acts — it is a
 * lowering pass, so the env var does nothing at run time): **9 590 ps/elem
 * against 257**, 37x, with the seq control steady at 383 → 395. Bytes now folds
 * FASTER than the fused seq fold, which is what a `byte[]` being 8x denser than
 * a `long[]` buys.
 *
 * THESE ASSERT THE PATH AS WELL AS THE ANSWER. Both spellings compute the same
 * number — that is the point of the optimisation — so a value-only test passes
 * with the fast path absent. `BytecodeCompiler.FUSED_EMITS` reports which fused
 * emit was chosen, the same device [ListFoldElementKindFusionTest] uses.
 */
class BytesSumPrimitiveTest {

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
        "use std::collections::bytes\nuse std::collections::seq\n\n${body.trimIndent()}",
    )

    /** 1+2+3 = 6, and it must take the primitive path. */
    @Test
    fun `a zero-init add fold over bytes takes the primitive sum`() {
        val out = run(
            """
            let main = {} -> bytes::fold {
              b = bytes::from_seq { arr = [1, 2, 3] }, init = 0, f = { acc, byte } -> acc + byte
            }
            main {}
            """,
        )
        assertEquals(6L, out)
        assertTrue(
            "BuiltinSumBytes" in emitted,
            "the bytes fold did not reach BuiltinSumBytes; emitted: $emitted",
        )
    }

    /**
     * A byte at the top of the range must arrive UNSIGNED. `bytes.get` returns a
     * signed Java byte, so the primitive loop's `& 0xFF` is what makes 255 read
     * as 255 and not -1 — and a sum is exactly the shape that would hide a sign
     * error as a merely-wrong total.
     */
    @Test
    fun `high bytes are unsigned on the primitive path`() {
        val out = run(
            """
            let main = {} -> bytes::fold {
              b = bytes::from_seq { arr = [255, 128, 1] }, init = 0, f = { acc, byte } -> acc + byte
            }
            main {}
            """,
        )
        assertEquals(384L, out)
        assertTrue("BuiltinSumBytes" in emitted, "emitted: $emitted")
    }

    /** Empty input must not touch the loop. */
    @Test
    fun `an empty bytes folds to the init`() {
        assertEquals(
            0L,
            run(
                """
                let main = {} -> bytes::fold {
                  b = bytes::from_seq { arr = [] }, init = 0, f = { acc, byte } -> acc + byte
                }
                main {}
                """,
            ),
        )
    }

    /**
     * CONTROL: a NON-zero init must not take the primitive path, which starts
     * from a hard-coded zero and would silently drop the init. Same guard its
     * seq peer carries, and the reason that guard exists.
     */
    @Test
    fun `a non-zero init does not take the primitive sum`() {
        val out = run(
            """
            let main = {} -> bytes::fold {
              b = bytes::from_seq { arr = [1, 2, 3] }, init = 10, f = { acc, byte } -> acc + byte
            }
            main {}
            """,
        )
        assertEquals(16L, out)
        assertTrue(
            "BuiltinSumBytes" !in emitted,
            "a non-zero init reached BuiltinSumBytes, which starts from 0 and would " +
                "drop it; emitted: $emitted",
        )
    }

    /**
     * CONTROL: a folder that is not a plain ADD must not take it either. `acc +
     * byte * 2` has an element transform, so the answer is 12 and the primitive
     * sum — which adds the raw bytes — would say 6.
     */
    @Test
    fun `a folder with an element transform does not take the primitive sum`() {
        val out = run(
            """
            let main = {} -> bytes::fold {
              b = bytes::from_seq { arr = [1, 2, 3] }, init = 0, f = { acc, byte } -> acc + byte * 2
            }
            main {}
            """,
        )
        assertEquals(12L, out)
        assertTrue(
            "BuiltinSumBytes" !in emitted,
            "a compound folder reached BuiltinSumBytes, which would ignore the `* 2`; " +
                "emitted: $emitted",
        )
    }
}
