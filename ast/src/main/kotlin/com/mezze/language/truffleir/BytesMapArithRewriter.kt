package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * `bytes::map { b, f = { x } -> x / 2 }` becomes `__native_bytes_map_div { b, k = 2 }`.
 *
 * A peephole, and the sibling of [FoldToSumRewriter]: that one asks "does the
 * collection already know how to do this without calling back into guest code",
 * and for a one-operation mapper over bytes the answer is yes once the native
 * exists. `BytesNatives.map` calls the guest mapper once per byte; these five
 * natives do the arithmetic in the loop.
 *
 * ## What it is worth
 *
 * SELF-A/B on `bench/cases/bytesurface`: two builds from identical source, the
 * pass hook the only difference, 1024 bytes, min of 9 after 4 warmups, every
 * row at two n that agree.
 *
 * | row | before | after | |
 * |---|---|---|---|
 * | `map` with `{ x } -> x * 2` | 5 540 077 ps | 259 413 | **21.4x** |
 * | `map` with `{ x } -> x / 2` | 5 390 947 | 1 479 640 | **3.64x** |
 * | `bytes::fold` (control) | 156 234 | 155 127 | unmoved |
 * | `bytes::reverse` (control) | 305 049 | 304 112 | unmoved |
 *
 * ## TWO NUMBERS, AND THE SECOND ONE IS A DIVIDE
 *
 * `bytes::reverse` was taken as the ceiling before building this, because it is
 * the same shape without a callback — a boundary native reading and writing
 * every byte of a fresh array — and it predicted 17.2x. The measurement is more
 * specific than that prediction in both directions.
 *
 * At 252 ps/byte the multiplying map is **FASTER than `reverse`'s 298**, so the
 * rewrite does not fall short of the byte-copy ceiling, it passes it: this loop
 * indexes `src[i]` directly where `reverse` calls `bytes.length()` twice per
 * iteration and `bytes.get(i)`.
 *
 * And so row 9's entire residual is ONE INSTRUCTION. DIV is 1 452 ps/byte
 * against MUL's 252 — 1 200 ps/byte of division, 5.73x — because the literal
 * arrives as a runtime `long` and cannot be strength-reduced to a shift. A
 * `@ConstantOperand` would fix that and is NOT done here: it needs a Bytecode
 * DSL operation rather than a native, and `BytesSumPrimitiveTest` records that
 * routing bytes through the generic fused loop was built, measured and found
 * not worth having. So `reverse` was the right ceiling for a byte copy and the
 * wrong one for a dividing map, which is the distinction the row-15 measurement
 * exists to keep.
 *
 * A ROUTE THAT LOOKS RIGHT AND IS NOT, measured BEFORE building this: going
 * through a seq — `bytes::to_seq` → `seq::map` → `bytes::from_seq` — so that the
 * seq fuser inlines the transform costs 8 431 ps/byte, **1.63x slower than the
 * unfused `bytes::map` it was meant to bound**, because the boxed `[Int]`
 * intermediate costs more than the callback it removes. `bytesurface` row 14
 * keeps that measurement so nobody re-derives it.
 *
 * ## The shape, and why each part of the guard is there
 *
 * Exactly `{ x } -> x <op> <literal>`: one parameter, read once, on the LEFT of
 * one arithmetic operation whose right side is an Int literal. No captures, no
 * locals, nothing else.
 *
 * - **The element must be on the left.** `255 - x` is a different function from
 *   `x - 255` and there is no native for it. (`ClassifiedStage.MapInline` makes
 *   the same choice, carrying an `rhsLit`.)
 * - **The parameter must be read exactly once.** `x * x` is not `x * k`.
 * - **ADD/SUB/MUL/DIV/MOD only.** The comparison and boolean [InlineOp]s do not
 *   return an Int, and `map`'s callback is typed `{ x: Int } -> Int`.
 * - **A zero divisor is REFUSED.** The guest `/` raises on division by zero;
 *   `mapDiv` would throw a Java `ArithmeticException` instead. Declining leaves
 *   the callback path, which already raises correctly.
 * - **Literals whose arithmetic could overflow a long are REFUSED.** A Mezze
 *   `Int` is a JVM long and overflow RAISES, where the native's `(byte)` cast
 *   would silently wrap. The element is 0..255, so the bound is checked against
 *   255 — the largest input — and not against the array.
 *
 * Everything refused keeps the ordinary per-byte callback, which is correct
 * already. There is no fallback inside the natives.
 *
 * ## Why the call is visible here at all
 *
 * `bytes::map` is `{ b, f } -> __native_bytes_map { b = b, f = f }`, which is a
 * record-ABI trivial wrapper, so [TrivialWrapperInliner] has already replaced
 * the call with the native at the call site by the time this runs. What this
 * pass sees in a user function body is the native itself with a [MakeLambdaIR]
 * in `f`.
 */
object BytesMapArithRewriter {

    /** `x <op> k` → the native that does that op over a byte array. */
    private val OP_TO_NATIVE: Map<InlineOp, String> = mapOf(
        InlineOp.ADD to NativeHofNames.native(NativeHofNames.BYTES, "map_add"),
        InlineOp.SUB to NativeHofNames.native(NativeHofNames.BYTES, "map_sub"),
        InlineOp.MUL to NativeHofNames.native(NativeHofNames.BYTES, "map_mul"),
        InlineOp.DIV to NativeHofNames.native(NativeHofNames.BYTES, "map_div"),
        InlineOp.MOD to NativeHofNames.native(NativeHofNames.BYTES, "map_mod"),
    )

    /** The literal field the five natives read. */
    private const val K = "k"

    /** The largest value a byte element can present to the mapper. */
    private const val MAX_ELEM = 255L

    fun rewrite(node: NodeIR, allFunctions: Map<Hash, FunctionIR>): NodeIR {
        val descended = node.transformChildren { child -> rewrite(child, allFunctions) }
        return tryRewrite(descended, allFunctions) ?: descended
    }

    private fun tryRewrite(node: NodeIR, allFunctions: Map<Hash, FunctionIR>): NodeIR? {
        if (node !is CallNativeIR || node.isScalarABI) return null
        // The carried decisions, not the spelling — see CollectionKind/NativeOp.
        if (node.ownerKind != CollectionKind.BYTES || node.opKind != NativeOp.MAP) return null

        val mr = node.arg as? MakeRecordIR ?: return null
        var src: NodeIR? = null
        var f: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            NativeHofNames.B -> src = fld.value
            "f" -> f = fld.value
            // An unexpected field means this is not the shape that was priced.
            else -> return null
        }
        val srcExpr = src ?: return null

        val (op, k) = classifyArithMapper(f, allFunctions) ?: return null
        val nativeName = OP_TO_NATIVE[op] ?: return null

        // Carries its decisions for the reason `FoldToSumRewriter` now does:
        // a constructed native without them is one every kind-based predicate
        // declines, silently.
        return CallNativeIR(
            name = nativeName,
            arg = MakeRecordIR(
                listOf(FieldIR(NativeHofNames.B, srcExpr), FieldIR(K, LongLitIR(k, mr.src))),
                mr.src,
            ),
            src = node.src,
            producesKind = node.producesKind,
            ownerKind = node.ownerKind,
            opKind = NativeHofNames.opOf(nativeName),
        )
    }

    /**
     * `{ x } -> x <op> <literal>` as (op, literal), or null for anything else.
     */
    private fun classifyArithMapper(
        f: NodeIR?,
        allFunctions: Map<Hash, FunctionIR>,
    ): Pair<InlineOp, Long>? {
        val lambda = f as? MakeLambdaIR ?: return null
        if (lambda.captures.isNotEmpty()) return null
        val fn = allFunctions[lambda.functionHash] ?: return null
        if (fn.captureSlots.isNotEmpty() || fn.localSlots.isNotEmpty()) return null
        if (fn.paramSlots.size != 1) return null
        // The element arrives as the Int in 0..255, so the slot is a long. A
        // mapper typed otherwise is not this shape.
        if (fn.paramSlots[0] != SlotKind.LONG) return null

        val body = fn.body as? InlineBinaryOpIR ?: return null
        if (body.op !in OP_TO_NATIVE) return null
        // The element on the LEFT, the literal on the right: `255 - x` is a
        // different function and has no native here.
        val left = body.left as? ReadLocalIR ?: return null
        if (left.slot != 0) return null
        val k = (body.right as? LongLitIR)?.value ?: return null

        if (!isSafeLiteral(body.op, k)) return null
        return body.op to k
    }

    /**
     * True when `elem <op> k` behaves identically in the native and in the
     * guest callback for every element in 0..255.
     *
     * The guest raises on a zero divisor and on Int overflow; the native would
     * throw a Java exception for the first and silently wrap for the second.
     * Rather than reproduce either, the shapes that could differ are refused.
     */
    private fun isSafeLiteral(op: InlineOp, k: Long): Boolean = when (op) {
        // `intDivide`/`intModulo` are plain `l / r` and `l % r` behind a
        // zero-divisor guard, so every other literal — negative included —
        // already means in the native exactly what it means in the guest.
        InlineOp.DIV, InlineOp.MOD -> k != 0L
        // The largest result is `255 + k`; the smallest is `k`, which is always
        // representable. So only the upper end can overflow.
        InlineOp.ADD -> k <= Long.MAX_VALUE - MAX_ELEM
        // SUB IS NOT ADD'S MIRROR, and sharing one bound with it was wrong. The
        // result is `elem - k`, so the dangerous end is a very NEGATIVE k:
        // `255 - k` grows as k falls. The bound is therefore on the low side,
        // and `Long.MIN_VALUE + 255` — which the shared guard allowed — is
        // already past it, because `255 - (Long.MIN_VALUE + 255)` is
        // `-Long.MIN_VALUE` and that does not fit.
        InlineOp.SUB -> k >= MAX_ELEM - Long.MAX_VALUE
        // `255 * k` either way. Bounding |k| by MAX_VALUE/255 is slightly
        // conservative for negative k, which is the safe direction.
        InlineOp.MUL -> k != Long.MIN_VALUE && Math.abs(k) <= Long.MAX_VALUE / MAX_ELEM
        else -> false
    }
}
