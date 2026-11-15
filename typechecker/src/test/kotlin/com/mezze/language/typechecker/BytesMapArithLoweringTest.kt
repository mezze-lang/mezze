package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A one-operation mapper over bytes does not need a guest call per byte.
 *
 * `BytesMapArithRewriter` turns `bytes::map { b, f = { x } -> x * 2 }` into
 * `__native_bytes_map_mul { b, k = 2 }`. SELF-A/B on `bench/cases/bytesurface`,
 * two builds from identical source with the pass hook the only difference,
 * 1024 bytes, min of 9 after 4 warmups, every row at two n that agree:
 *
 * | row | before | after | |
 * |---|---|---|---|
 * | `map` with `{ x } -> x * 2` | 5 540 077 ps | 259 413 | **21.4x** |
 * | `map` with `{ x } -> x / 2` | 5 390 947 | 1 479 640 | **3.64x** |
 * | `bytes::fold` / `bytes::reverse` (controls) | | | unmoved |
 *
 * TWO NUMBERS, AND THE SECOND IS A DIVIDE. At 252 ps/byte the multiplying map
 * BEATS `bytes::reverse`'s 298 — the same shape without a callback, which was
 * the predicted ceiling — because this loop indexes `src[i]` directly where
 * `reverse` calls `bytes.length()` twice an iteration. So the dividing row's
 * whole residual is one instruction: 1 200 ps/byte, DIV 5.73x MUL, because the
 * literal arrives as a runtime `long` and cannot become a shift.
 *
 * THE ROUTE THAT LOOKED LIKE THE ANSWER AND WAS NOT, measured BEFORE building
 * this: `bytes::to_seq` → `seq::map` → `bytes::from_seq`, so the seq fuser
 * inlines the transform, costs 8 431 ps/byte — **1.63x SLOWER than the unfused
 * `bytes::map` it was meant to bound** — because the boxed `[Int]` intermediate
 * costs more than the callback it removes. `bytesurface` row 14 keeps it.
 *
 * THESE ASSERT THE PATH, NOT THE ANSWER. Every spelling below computes the same
 * bytes — that is the point — so a value-only test passes with the rewrite
 * absent. The values are asserted separately, in `BytesMapArithValueTest`.
 *
 * A lowering test is honest here for the reason `FoldToSumLoweringTest` gives:
 * the blindness that made lowering tests useless for chain fusion came from the
 * stdlib being lowered from source here but served from `PrecompiledStdlib` by
 * `mez build`. This pass matches on the USER function's own body — the `map`
 * call and its literal mapper — so the shape here is the shape that ships.
 * Verified the usual way: with the pass unhooked, the first test fails.
 *
 * EVERY CONTROL IS A CORRECTNESS CASE. The guest `/` raises on a zero divisor
 * and a Mezze `Int` RAISES on overflow, where the native would throw a Java
 * exception for the first and silently wrap for the second. A control that
 * stopped being refused would be a wrong answer, not a slow one.
 */
class BytesMapArithLoweringTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions.filter { it.sourceFile == "p.mz" }

    private fun natives(src: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: NodeIR) {
            if (node is CallNativeIR) out.add(node.name)
            node.transformChildren { child -> walk(child); child }
        }
        for (fn in lower(src)) walk(fn.body)
        return out
    }

    private val PRELUDE = "use std::collections::bytes"

    private fun assertRewritten(src: String, expected: String) {
        val found = natives(src)
        assertTrue(
            expected in found,
            "the arithmetic mapper was not rewritten to `$expected`; natives: $found",
        )
        assertTrue(
            "__native_bytes_map" !in found,
            "the `map` call survived alongside the rewrite; natives: $found",
        )
    }

    /**
     * A refusal asserts that NO arithmetic native was emitted, and deliberately
     * says nothing about whether the plain `__native_bytes_map` is visible here.
     *
     * Whether it is depends on something unrelated to this pass: `bytes::map` is
     * a record-ABI trivial wrapper, so `TrivialWrapperInliner` usually replaces
     * the call with the native at the call site — but a mapper that CAPTURES
     * leaves the lambda ineligible, the wrapper is not inlined, and the user
     * function's body holds a call to `bytes::map` with the native still behind
     * it. The captured-multiplier case below lowers to no natives at all for
     * that reason, which is correct and was worth finding out: asserting the
     * plain call's presence failed there while the pass was behaving perfectly.
     */
    private fun assertRefused(src: String, why: String) {
        val found = natives(src)
        assertTrue(
            found.none { it.startsWith("__native_bytes_map_") },
            "$why — but an arithmetic native was emitted; natives: $found",
        )
    }

    /** THE REWRITE, and the shape the benchmark measured. */
    @Test
    fun `a divide-by-literal mapper becomes the div native`() {
        assertRewritten(
            """
            $PRELUDE
            pub let halve = { b: Bytes } -> bytes::map { b = b, f = { x } -> x / 2 }
            """,
            "__native_bytes_map_div",
        )
    }

    @Test
    fun `an add mapper becomes the add native`() {
        assertRewritten(
            """
            $PRELUDE
            pub let bump = { b: Bytes } -> bytes::map { b = b, f = { x } -> x + 1 }
            """,
            "__native_bytes_map_add",
        )
    }

    @Test
    fun `a subtract mapper becomes the sub native`() {
        assertRewritten(
            """
            $PRELUDE
            pub let down = { b: Bytes } -> bytes::map { b = b, f = { x } -> x - 1 }
            """,
            "__native_bytes_map_sub",
        )
    }

    @Test
    fun `a multiply mapper becomes the mul native`() {
        assertRewritten(
            """
            $PRELUDE
            pub let double = { b: Bytes } -> bytes::map { b = b, f = { x } -> x * 2 }
            """,
            "__native_bytes_map_mul",
        )
    }

    @Test
    fun `a modulo mapper becomes the mod native`() {
        assertRewritten(
            """
            $PRELUDE
            pub let low = { b: Bytes } -> bytes::map { b = b, f = { x } -> x % 16 }
            """,
            "__native_bytes_map_mod",
        )
    }

    /**
     * A ZERO DIVISOR IS REFUSED. `x / 0` raises in the guest; `mapDiv` would
     * throw a Java `ArithmeticException` out of a boundary instead. Declining
     * leaves the callback path, which already raises correctly.
     */
    @Test
    fun `a zero divisor is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let bad = { b: Bytes } -> bytes::map { b = b, f = { x } -> x / 0 }
            """,
            "a zero divisor must keep the guest callback, which raises",
        )
    }

    /** `x % 0` for the same reason — the remainder of a zero divisor raises too. */
    @Test
    fun `a zero modulus is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let bad = { b: Bytes } -> bytes::map { b = b, f = { x } -> x % 0 }
            """,
            "a zero modulus must keep the guest callback, which raises",
        )
    }

    /**
     * A LITERAL THAT COULD OVERFLOW IS REFUSED. A Mezze `Int` is a JVM long and
     * overflow RAISES; the native's `(byte)` cast would silently wrap. The
     * element is at most 255, so that is what the bound is checked against.
     */
    @Test
    fun `a multiplier that could overflow a long is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let bad = { b: Bytes } -> bytes::map { b = b, f = { x } -> x * 9223372036854775807 }
            """,
            "255 * Long.MAX_VALUE overflows, and the guest raises where the native would wrap",
        )
    }

    /**
     * SUB'S DANGEROUS END IS THE NEGATIVE ONE, and this case found a bug in the
     * guard rather than confirming it.
     *
     * `elem - k` grows as k FALLS, so for subtraction the bound is on the low
     * side — the opposite of addition. The first version of `isSafeLiteral`
     * shared one condition between ADD and SUB and so admitted
     * `Long.MIN_VALUE + 255`, for which `255 - k` is `-Long.MIN_VALUE` and does
     * not fit in a long: the guest raises an overflow error there, and the
     * native would have silently wrapped.
     */
    @Test
    fun `a very negative subtrahend is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let bad = { b: Bytes } -> bytes::map { b = b, f = { x } -> x - -9223372036854775553 }
            """,
            "255 - (Long.MIN_VALUE + 255) overflows; the guest raises where the native would wrap",
        )
    }

    /** An ordinary negative subtrahend is safe, and must still be rewritten. */
    @Test
    fun `an ordinary negative subtrahend is still rewritten`() {
        assertRewritten(
            """
            $PRELUDE
            pub let up = { b: Bytes } -> bytes::map { b = b, f = { x } -> x - -2 }
            """,
            "__native_bytes_map_sub",
        )
    }

    /**
     * A NEGATIVE DIVISOR IS FINE and must NOT be refused. `intDivide` is plain
     * `l / r` behind a zero guard, so Java's truncation toward zero is already
     * what the guest does and the native needs no special case.
     */
    @Test
    fun `a negative divisor is still rewritten`() {
        assertRewritten(
            """
            $PRELUDE
            pub let down = { b: Bytes } -> bytes::map { b = b, f = { x } -> x / -2 }
            """,
            "__native_bytes_map_div",
        )
    }

    /**
     * THE ELEMENT MUST BE ON THE LEFT. `255 - x` is a different function from
     * `x - 255` and there is no native for it, so rewriting it would invert the
     * operands and return wrong bytes.
     */
    @Test
    fun `a literal on the left is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let invert = { b: Bytes } -> bytes::map { b = b, f = { x } -> 255 - x }
            """,
            "`255 - x` is not `x - 255`, and only the latter has a native",
        )
    }

    /** `x * x` reads the parameter twice, so there is no literal operand. */
    @Test
    fun `squaring is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let sq = { b: Bytes } -> bytes::map { b = b, f = { x } -> x * x }
            """,
            "`x * x` has no literal operand",
        )
    }

    /**
     * A CAPTURED multiplier is not a literal. It is also what keeps the scalar
     * bypass covered: `BytesHofScalarBypassTest` prices an inline lambda
     * through `ScalarCall`, and this is the case that still reaches it.
     */
    @Test
    fun `a captured multiplier is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let scale = { b: Bytes, factor: Int } -> bytes::map { b = b, f = { x } -> x * factor }
            """,
            "a captured operand is not a literal",
        )
    }

    /** Two operations is two stages, not one native. */
    @Test
    fun `a two-operation mapper is refused`() {
        assertRefused(
            """
            $PRELUDE
            pub let both = { b: Bytes } -> bytes::map { b = b, f = { x } -> x * 2 + 1 }
            """,
            "`x * 2 + 1` is two operations",
        )
    }
}
