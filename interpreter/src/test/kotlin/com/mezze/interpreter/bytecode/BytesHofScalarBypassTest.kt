package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `bytes::fold` and `bytes::map` call the guest callback per element, and both
 * ways of doing that must give the same answer.
 *
 * They used to allocate FOUR objects per element — two `Object[]`, the
 * field-name `String[]`, and a `MezzeRecord` — and ran under
 * `@TruffleBoundary`, so the loop never compiled and the callback was never
 * inlined into it. `SeqNatives.fold` has neither problem: it takes a
 * scalar-ABI bypass that reuses one POSITIONAL argument array across the loop.
 *
 * MEASURED, `bench/cases/fuseall` kernel 4 at n=2M, with kernel 1 as the
 * control: 41 076 ps/elem → 8 089, control 385 → 383 (0.5%, so the table is
 * readable). 5.1x. Bytes is still 21x the FUSED seq fold, which is the separate
 * fusion-coverage gap — `bytes` is not in the fuser's vocabulary.
 *
 * WHY BOTH PATHS ARE EXERCISED HERE. The bypass is positional, so it cannot
 * notice a wrong field NAME, while the record path addresses fields by name —
 * and the two callbacks disagree: `std::collections::bytes` declares `fold`'s
 * as `{ acc, byte }` and `map`'s as `{ x }`. Writing `byte` for both is exactly
 * the mistake this catches, and it was made: VERIFIED by putting it back, at
 * which point both `map` tests fail and both `fold` tests still pass.
 *
 * So `map` is not bypass-eligible here at all — not merely when its callback
 * captures — which is why its field name is load-bearing and the fold's is not
 * yet. Each operation is tested plain and capturing so that stays true if the
 * bypass's reach changes.
 */
class BytesHofScalarBypassTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** 1+2+3 = 6, with a plain folder — the bypass-eligible shape. */
    @Test
    fun `a bytes fold with a plain folder`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq

            let main = {} -> do
              let b = bytes::from_seq { arr = [1, 2, 3] }
              bytes::fold { b = b, init = 0, f = { acc, byte } -> acc + byte }
            main {}
            """.trimIndent(),
        )
        assertEquals(6L, out)
    }

    /**
     * The same fold whose folder CAPTURES an outer binding.
     *
     * (1*10) + (2*10) + (3*10) = 60.
     */
    @Test
    fun `a bytes fold whose folder captures`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq

            let main = {} -> do
              let b = bytes::from_seq { arr = [1, 2, 3] }
              let scale = 10
              bytes::fold { b = b, init = 0, f = { acc, byte } -> acc + byte * scale }
            main {}
            """.trimIndent(),
        )
        assertEquals(60L, out)
    }

    /**
     * `map`'s callback is `{ x }`, not `{ byte }`. This is the assertion that
     * would have caught writing the wrong field name into the record path.
     *
     * Each byte doubled, then folded: (2+4+6) = 12.
     */
    @Test
    fun `a bytes map names its callback parameter x`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq

            let main = {} -> do
              let b = bytes::from_seq { arr = [1, 2, 3] }
              let doubled = bytes::map { b = b, f = { x } -> x * 2 }
              bytes::fold { b = doubled, init = 0, f = { acc, byte } -> acc + byte }
            main {}
            """.trimIndent(),
        )
        assertEquals(12L, out)
    }

    /** `map` with a capturing callback — the record-path form of the above. */
    @Test
    fun `a bytes map whose callback captures`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::bytes
            use std::collections::seq

            let main = {} -> do
              let b = bytes::from_seq { arr = [1, 2, 3] }
              let factor = 3
              let scaled = bytes::map { b = b, f = { x } -> x * factor }
              bytes::fold { b = scaled, init = 0, f = { acc, byte } -> acc + byte }
            main {}
            """.trimIndent(),
        )
        assertEquals(18L, out)
    }

    /**
     * An empty input must not touch the loop at all, and a byte at the top of
     * the range must come back unsigned — `bytes.get` returns a signed Java
     * byte and the `& 0xFF` is what makes 255 arrive as 255 rather than -1.
     */
    @Test
    fun `empty and high-byte edges`() {
        assertEquals(
            0L,
            MezzeTestHelper.run(
                """
                use std::collections::bytes
                use std::collections::seq

                let main = {} -> bytes::fold {
                  b = bytes::from_seq { arr = [] }, init = 0, f = { acc, byte } -> acc + byte
                }
                main {}
                """.trimIndent(),
            ),
        )
        assertEquals(
            255L + 128L,
            MezzeTestHelper.run(
                """
                use std::collections::bytes
                use std::collections::seq

                let main = {} -> bytes::fold {
                  b = bytes::from_seq { arr = [255, 128] }, init = 0, f = { acc, byte } -> acc + byte
                }
                main {}
                """.trimIndent(),
            ),
        )
    }
}
