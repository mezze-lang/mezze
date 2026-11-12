package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * `coll::fold { init = 0, f = { acc, x } -> acc + x }` becomes `coll::sum { coll }`.
 *
 * A peephole, not a fusion. The fuser's question is "can these stages become one
 * loop"; this one is "does the collection already know how to do this without
 * calling back into guest code", and for a plain summing fold the answer is yes
 * — `__native_{set,sorted_set,deque}_sum` have always existed.
 *
 * WHY IT IS WORTH A PASS. `fuseall` at n=2M, min of 3, measures the callback by
 * comparing each `fold` against the `sum` that computes the same number over the
 * same collection with the same walk:
 *
 * | | fold | sum | the callback |
 * |---|---|---|---|
 * | deque | 9 638 ps/elem | 1 664 | **83%** |
 * | set | 17 376 | 7 270 | 58% |
 *
 * So a guest callback costs about 8 ns per element, which is the same ~9 ns
 * `bytes::fold` was paying before `BuiltinSumBytes` recognised its folder
 * instead of calling it. This is that idea reused, and it needs no new bytecode
 * operation, no `SourceIR` variant and no emitter change — the destination is
 * already in the stdlib.
 *
 * ## The guard, and why it is the folder's SLOT KINDS
 *
 * `sum`'s boxed path reads `Long`/`Integer` and SILENTLY DROPS anything else, so
 * rewriting a fold over Floats would return 0 rather than the total. The element
 * type is not on the IR node, but each lambda parameter carries the slot kind of
 * its solved type — so a folder over Ints has two LONG parameters and one over
 * Floats does not. That is the same evidence `ListFoldElementKindFusionTest`
 * relies on to keep a Float fold off the long pipeline, and the same reason:
 * a SOURCE says nothing about what is in it, a STAGE or a slot kind does. See
 * `project_source_kind_is_not_element_kind`.
 *
 * Deliberately narrow beyond that: exactly two parameters both read once, no
 * captures, no locals, `init` the long literal 0, and the body a bare ADD of the
 * two parameters. A compound folder (`acc + x * x`) has an element transform
 * that `sum` would ignore, and a non-zero init is a total `sum` cannot express.
 */
object FoldToSumRewriter {

    /** fold native → sum native, for the collections that have both. */
    private val FOLD_TO_SUM: Map<String, String> = mapOf(
        NativeHofNames.native(NativeHofNames.SET, NativeHofNames.OP_FOLD)
            to NativeHofNames.native(NativeHofNames.SET, NativeHofNames.OP_SUM),
        NativeHofNames.native(NativeHofNames.SORTED_SET, NativeHofNames.OP_FOLD)
            to NativeHofNames.native(NativeHofNames.SORTED_SET, NativeHofNames.OP_SUM),
        NativeHofNames.native(NativeHofNames.DEQUE, NativeHofNames.OP_FOLD)
            to NativeHofNames.native(NativeHofNames.DEQUE, NativeHofNames.OP_SUM),
        // `nonemptylist` spells its natives `nel` and its fold `fold_left`. Its
        // `sum` did not exist until this rewrite gave it a reason to.
        NativeHofNames.native(NativeHofNames.NEL, NativeHofNames.OP_FOLD_LEFT)
            to NativeHofNames.native(NativeHofNames.NEL, NativeHofNames.OP_SUM),
    )

    /**
     * A map folds ENTRIES, so its folder takes three parameters and its sum has
     * to say which of the two it sums.
     */
    private val MAP_FOLD =
        NativeHofNames.native(NativeHofNames.MAP, NativeHofNames.OP_FOLD)
    private val MAP_SUM_VALUES =
        NativeHofNames.native(NativeHofNames.MAP, NativeHofNames.OP_SUM_VALUES)

    /**
     * A SortedMap folds entries the same way, so the same rule applies to it
     * once there is a `sum_values` to rewrite to. There was not one, which is
     * why `sorted_map::fold` kept its per-element guest call and measured
     * 13 202 ps/elem against `sorted_set::fold`'s 446 — the same sorted array,
     * the only difference being that a sorted SET had a `sum` for this pass to
     * find. Walking the values with no callback is 633.
     */
    private val SORTED_MAP_FOLD =
        NativeHofNames.native(NativeHofNames.SORTED_MAP, NativeHofNames.OP_FOLD)
    private val SORTED_MAP_SUM_VALUES =
        NativeHofNames.native(NativeHofNames.SORTED_MAP, NativeHofNames.OP_SUM_VALUES)

    fun rewrite(node: NodeIR, allFunctions: Map<Hash, FunctionIR>): NodeIR {
        val descended = node.transformChildren { child -> rewrite(child, allFunctions) }
        return tryRewrite(descended, allFunctions) ?: descended
    }

    private fun tryRewrite(node: NodeIR, allFunctions: Map<Hash, FunctionIR>): NodeIR? {
        if (node !is CallNativeIR || node.isScalarABI) return null
        if (node.name == MAP_FOLD)
            return tryRewriteMapValueSum(node, allFunctions, MAP_SUM_VALUES)
        if (node.name == SORTED_MAP_FOLD)
            return tryRewriteMapValueSum(node, allFunctions, SORTED_MAP_SUM_VALUES)
        val sumName = FOLD_TO_SUM[node.name] ?: return null
        val mr = node.arg as? MakeRecordIR ?: return null

        val srcField = NativeHofNames.srcFieldFor(node.ownerKind)
        var src: NodeIR? = null
        var init: NodeIR? = null
        var f: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            srcField -> src = fld.value
            "init" -> init = fld.value
            "f" -> f = fld.value
            // An unexpected field means this is not the shape we priced.
            else -> return null
        }
        val srcExpr = src ?: return null

        // `sum` starts from zero and cannot carry an init.
        val initLit = init as? LongLitIR ?: return null
        if (initLit.value != 0L) return null

        if (!isPlainLongAddFolder(f, allFunctions)) return null

        return CallNativeIR(
            name = sumName,
            arg = MakeRecordIR(listOf(FieldIR(srcField, srcExpr)), mr.src),
            src = node.src,
        )
    }

    /**
     * True for `{ acc, x } -> acc + x` over two LONG parameters, and nothing
     * else.
     */
    private fun isPlainLongAddFolder(f: NodeIR?, allFunctions: Map<Hash, FunctionIR>): Boolean {
        val lambda = f as? MakeLambdaIR ?: return false
        if (lambda.captures.isNotEmpty()) return false
        val fn = allFunctions[lambda.functionHash] ?: return false
        if (fn.captureSlots.isNotEmpty() || fn.localSlots.isNotEmpty()) return false
        if (fn.paramSlots.size != 2) return false
        // THE ELEMENT-KIND GUARD. `sum`'s boxed path drops anything that is not
        // a Long or an Integer, so a Float fold must not come here.
        if (fn.paramSlots.any { it != SlotKind.LONG }) return false

        val body = fn.body as? InlineBinaryOpIR ?: return false
        if (body.op != InlineOp.ADD) return false
        val l = body.left as? ReadLocalIR ?: return false
        val r = body.right as? ReadLocalIR ?: return false
        // Both parameters, each exactly once — `acc + acc` is not a sum.
        return setOf(l.slot, r.slot) == setOf(0, 1)
    }

    /**
     * `map::fold { init = 0, f = { acc, k, v } -> acc + v }` → `map::sum_values`.
     *
     * The key parameter must be UNUSED. `acc + k` is a key sum, which
     * `sum_values` would answer wrongly, and `acc + k + v` is neither.
     */
    private fun tryRewriteMapValueSum(
        node: CallNativeIR,
        allFunctions: Map<Hash, FunctionIR>,
        sumName: String,
    ): NodeIR? {
        val mr = node.arg as? MakeRecordIR ?: return null
        var src: NodeIR? = null
        var init: NodeIR? = null
        var f: NodeIR? = null
        for (fld in mr.fields) when (fld.name) {
            NativeHofNames.M -> src = fld.value
            "init" -> init = fld.value
            "f" -> f = fld.value
            else -> return null
        }
        val srcExpr = src ?: return null
        val initLit = init as? LongLitIR ?: return null
        if (initLit.value != 0L) return null

        val lambda = f as? MakeLambdaIR ?: return null
        if (lambda.captures.isNotEmpty()) return null
        val fn = allFunctions[lambda.functionHash] ?: return null
        if (fn.captureSlots.isNotEmpty() || fn.localSlots.isNotEmpty()) return null
        // Slots are (acc, k, v) in that order.
        if (fn.paramSlots.size != 3) return null
        // The accumulator and the VALUE must be longs; the key's kind is
        // irrelevant because the body must not read it.
        if (fn.paramSlots[0] != SlotKind.LONG || fn.paramSlots[2] != SlotKind.LONG) return null

        val body = fn.body as? InlineBinaryOpIR ?: return null
        if (body.op != InlineOp.ADD) return null
        val l = body.left as? ReadLocalIR ?: return null
        val r = body.right as? ReadLocalIR ?: return null
        // Exactly the accumulator and the value, each once. Slot 1 is the key
        // and reading it means this is not a value sum.
        if (setOf(l.slot, r.slot) != setOf(0, 2)) return null

        return CallNativeIR(
            name = sumName,
            arg = MakeRecordIR(listOf(FieldIR(NativeHofNames.M, srcExpr)), mr.src),
            src = node.src,
        )
    }
}
