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
 * readable). 5.1x.
 *
 * THE SENTENCE THAT USED TO FOLLOW — "bytes is still 21x the FUSED seq fold
 * … `bytes` is not in the fuser's vocabulary" — IS NO LONGER TRUE OF `fold`.
 * `bench/cases/bytesurface` has `bytes::fold` at 155 ps a byte against
 * `seq::fold`'s 280 a element, so it is now FASTER than the seq fold, not 21x
 * it: bytes participates as a SOURCE under a fold sink, which is the one way
 * `NativeHofNames` admits it. `map` is still outside the fuser, and correctly
 * so — see the note there: `bytes::map`'s callback result truncates to 8 bits,
 * so peeling it as an ordinary element stage would make a fused chain compute
 * a different answer (`x * 2` over a byte of 200 is 144, not 400).
 *
 * WHY BOTH PATHS ARE EXERCISED HERE. The bypass is positional, so it cannot
 * notice a wrong field NAME, while the record path addresses fields by name —
 * and the two callbacks disagree: `std::collections::bytes` declares `fold`'s
 * as `{ acc, byte }` and `map`'s as `{ x }`. Writing `byte` for both is exactly
 * the mistake this catches, and it was made: VERIFIED by putting it back, at
 * which point both `map` tests fail and both `fold` tests still pass.
 *
 * THAT SPLIT MOVED, which is what the top-level-function case below is for.
 * `map` used not to be bypass-eligible at all, so ANY of its cases caught a
 * wrong field name. A stub peek in `ScalarBypass.tryResolve` then made an
 * inline lambda bypassable (4.8x on `bytes::map`), and a positional path cannot
 * see a name — at which point the wrong name passed every test here. The
 * record path is still reached by a callback that is a TOP-LEVEL FUNCTION
 * passed as a value, whose trampoline one of the inner-target checks refuses,
 * so that case carries the field-name coverage now and is the only one that
 * fails when the name is wrong. Each operation is tested plain, capturing, and
 * — for `map` — by-reference, so the next change to the bypass's reach has
 * somewhere to show up.
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

    /**
     * `map` whose callback is a TOP-LEVEL FUNCTION passed as a value — and this
     * is the one that keeps the field name honest.
     *
     * An inline lambda now takes the positional bypass (the stub peek in
     * `ScalarBypass.tryResolve` made that true, worth 4.8x on `bytes::map`), and
     * a positional path cannot notice a wrong field NAME. A top-level function
     * used as a value still reaches the record path — its trampoline is
     * rejected by one of the inner-target checks — so the name is addressed
     * and a wrong one shows.
     *
     * VERIFIED: putting `byte` back in `BytesNatives.map`'s `prepare1` call
     * fails THIS test and no other. Before the stub peek the inline-lambda
     * tests caught it; after, they do not, and without this case the mistake
     * that was once shipped would have become untestable.
     */
    @Test
    fun `a bytes map whose callback is a top-level function`() {
        val out = MezzeTestHelper.run(
            """
            use std::collections::bytes

            let plus_one = { x } -> x + 1

            let main = {} -> do
              let b = bytes::from_seq { arr = [1, 2, 3] }
              let bumped = bytes::map { b = b, f = plus_one }
              bytes::fold { b = bumped, init = 0, f = { acc, byte } -> acc + byte }
            main {}
            """.trimIndent(),
        )
        assertEquals(9L, out)
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
