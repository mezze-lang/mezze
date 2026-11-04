package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * A pipeline step bound to a `let` is still a pipeline step.
 *
 * WHY THIS PASS EXISTS, MEASURED. `ChainCanonicalizer` recognises a fusable
 * chain by walking an EXPRESSION TREE: [ChainCanonicalizer.peelSource] has peels
 * for map, filter, take, drop, enumerate and from_seq, and a hop through a
 * direct call — but no case for a [ReadLocalIR]. So the moment a step is given a
 * name, the walk hits a slot read, classifies the source as Opaque, and the
 * whole pipeline runs element-at-a-time. `bench/cases/fusecross` kernel 2:
 *
 *     let src     = iter::range { start = 0, end = n }
 *     let tripled = iter::map    { it = src,     f = { x } -> x * 3 }
 *     let big     = iter::filter { it = tripled, p = { x } -> x > 2 }
 *     iter::fold  { it = big, init = 0, f = { acc, x } -> acc + x }
 *
 * lowers with `FusedChainIR=0` and residual `__native_iter_map`, `_filter` and
 * `_fold`, against kernel 1 — the identical pipeline as one expression — which
 * fuses. **Naming the steps costs the entire pipeline**, and naming steps is how
 * readable code is written.
 *
 * It also costs CROSS-FUNCTION fusion, for the same reason one layer up.
 * [ChainCanonicalizer.peekThroughMonoWrapper] refuses a callee outright when
 * `target.localSlots.isNotEmpty()`, so a helper holding ONE named intermediate
 * takes its caller's whole pipeline down with it — `fusecross` kernel 4 against
 * kernel 3, where the helpers differ only by a `let`. Substituting here empties
 * the callee's locals, so that refusal stops firing and the cross-function peek
 * works on helpers written the normal way.
 *
 * WHY A SEPARATE PASS FROM [LetCopyPropagation], which does the same shape of
 * rewrite. That pass deliberately refuses to move a CALL, and says why: nothing
 * in the IR says whether the impl behind one has effects, so moving it into a
 * branch could change whether it runs at all. A chain stage IS a call, so it is
 * outside that pass's safety argument and needs its own:
 *
 *  - **The single read IS the consumer.** A binding read exactly once moves to
 *    that one read, so the stage is built in the same place in the data flow it
 *    was built before. What changes is only its position relative to the
 *    statements in between.
 *  - **`iter` stages are LAZY.** `iter::map` builds a descriptor and runs
 *    nothing; the work happens at the sink, which is where the value is going.
 *  - **The callbacks are checked elsewhere.** `ChainCanonicalizer.isEffectSafe`
 *    refuses to fuse a chain whose lambda performs, so an effectful stage never
 *    reaches the fused path regardless of what this pass does.
 *  - **And it may not move past anything that is not itself inert**, the same
 *    rule and for the same reason as [LetCopyPropagation]: substitution reorders
 *    this value after every statement between the binding and its use.
 *
 * WHAT IT WILL MOVE. Only a native call for an operation in the fuser's own
 * vocabulary, on a collection the fuser handles — so the set of things this can
 * relocate is exactly the set `ChainCanonicalizer` can consume, and moving one
 * can only turn a refusal into a fusion.
 *
 * A CAPTURED SLOT IS NEVER TOUCHED, and NOT THE ENTRY BLOCK: a module-level
 * `let` is read from other functions by hash rather than through the block
 * scanned here. Both are [LetCopyPropagation]'s refusals, for its reasons.
 *
 * ON THE DEBUGGER. Running [LetCopyPropagation] over every block broke
 * `DapEndToEndTest`: a breakpoint on a `let` line stopped on the next line,
 * because after substitution there was no code on that line. This pass removes
 * only CHAIN-valued bindings, and a fused pipeline has no per-element frame to
 * stop in anyway — the same argument that file makes for its own loop case. The
 * kill switch below is the escape hatch if a debug build ever wants the
 * intermediates back.
 *
 * No kill switch — see the note on flags below.
 */
object ChainLetInliner {

    /** The synthetic function holding a module's top-level statements. */
    private const val ENTRY_FUNCTION = "__entry__"

    /**
     * Operations the fuser can consume as a stage or a source. Deliberately NOT
     * the sinks (`fold`, `sum`, `count`, …): a sink's value is the pipeline's
     * result, an ordinary value that happens to come from a chain, and moving it
     * buys nothing because there is no outer chain for it to join.
     */
    private val MOVABLE_OPS = setOf(
        NativeHofNames.OP_MAP,
        NativeHofNames.OP_FILTER,
        NativeHofNames.OP_TAKE,
        NativeHofNames.OP_DROP,
        NativeHofNames.OP_TAKE_WHILE,
        NativeHofNames.OP_DROP_WHILE,
        NativeHofNames.OP_ENUMERATE,
        NativeHofNames.OP_RANGE,
        NativeHofNames.OP_FROM_SEQ,
        NativeHofNames.OP_FROM_LIST,
    )

    fun inline(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        var anyChanged = false
        val out = HashMap<Hash, FunctionIR>(functions.size)
        for ((hash, fn) in functions) {
            if (fn.name == ENTRY_FUNCTION) {
                out[hash] = fn
                continue
            }
            val newBody = rewrite(fn.body, functions)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                anyChanged = true
                out[hash] = fn.copy(body = newBody)
            }
        }
        return if (anyChanged) out else functions
    }

    /** Every block in the body, innermost first so a nested one is done before
     *  the block that contains it. */
    private fun rewrite(node: NodeIR, fns: Map<Hash, FunctionIR>): NodeIR {
        val walked = node.transformChildren { rewrite(it, fns) }
        return if (walked is BlockIR) inlineBlock(walked, fns) else walked
    }

    private fun inlineBlock(block: BlockIR, fns: Map<Hash, FunctionIR>): NodeIR {
        var stmts = block.stmts
        var result = block.result
        var changed = false

        // To a fixed point: substituting `src` into `tripled` is what makes
        // `tripled` a complete chain expression, which is what lets `big`
        // absorb it in turn. The example in the class note needs three passes.
        var progress = true
        while (progress) {
            progress = false
            for (i in stmts.indices) {
                val st = stmts[i] as? StmtIR.Let ?: continue
                if (!isMovableChainExpr(st.value, fns)) continue

                val tail = stmts.drop(i + 1)
                val scope = tail.map { valueOf(it) } + result
                if (scope.any { capturesSlot(it, st.slot) }) continue
                // Exactly one read. Two would duplicate the pipeline, which for
                // an eager `seq` stage means duplicating the whole traversal.
                if (scope.sumOf { countReads(it, st.slot) } != 1) continue

                val crossesOnlyInert = tail
                    .takeWhile { s -> countReads(valueOf(s), st.slot) == 0 }
                    .all { s -> s is StmtIR.Let && isInert(s.value, fns) }
                if (!crossesOnlyInert) continue

                stmts = stmts.take(i) + tail.map { replaceRead(it, st.slot, st.value) }
                result = replaceRead(result, st.slot, st.value)
                changed = true
                progress = true
                break
            }
        }
        if (!changed) return block
        // A block with nothing left is its result — and that matters twice over:
        // it is what empties a helper's `localSlots` so the cross-function peek
        // stops refusing, and `peelSource` would not look inside a BlockIR
        // wrapper anyway.
        if (stmts.isEmpty()) return result
        return BlockIR(stmts, result, block.src)
    }

    /**
     * A native call for a stage or source the fuser handles — so relocating it
     * can only convert a refusal into a fusion.
     */
    private fun isMovableChainExpr(node: NodeIR, fns: Map<Hash, FunctionIR>): Boolean =
        when (node) {
            is CallNativeIR ->
                MOVABLE_OPS.any { op -> NativeHofNames.isAnyElementCollection(node.name, op) }
            // A DIRECT CALL TO THE STDLIB WRAPPER counts too. Whether a stage
            // appears as a native or as a call depends on whether this function
            // was monomorphised: the mono clone has `__native_iter_map` inlined,
            // the generic original still calls `iter::map`. Accepting only the
            // native made the pass work on the clone and silently skip the
            // original — so a helper mono declines to specialise would keep its
            // binding and keep costing its caller the pipeline. Moving the call
            // is as safe as moving the native: it is the same call, relocated to
            // its one use, and `iter::map` is as lazy as what it forwards to.
            is CallDirectIR -> isChainOpFunction(node.targetHash, fns, 0)
            else -> false
        }

    /** True if the function is (or forwards within two hops to) a movable
     *  chain-op native. The bound matches `peekThroughMonoWrapper`'s reasoning:
     *  one hop is a stdlib wrapper, two a user function forwarding to one. */
    private fun isChainOpFunction(hash: Hash, fns: Map<Hash, FunctionIR>, depth: Int): Boolean {
        if (depth > 2) return false
        val body = fns[hash]?.body ?: return false
        return when (body) {
            is CallNativeIR ->
                MOVABLE_OPS.any { op -> NativeHofNames.isAnyElementCollection(body.name, op) }
            is CallDirectIR -> isChainOpFunction(body.targetHash, fns, depth + 1)
            else -> false
        }
    }

    /**
     * Inert enough that this value may be reordered after it. A chain stage
     * counts, because the one being moved is going to the same place it is.
     */
    private fun isInert(node: NodeIR, fns: Map<Hash, FunctionIR>): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR -> true
        is ReadFieldIR -> isInert(node.record, fns)
        is InlineBinaryOpIR -> isInert(node.left, fns) && isInert(node.right, fns)
        else -> isMovableChainExpr(node, fns)
    }

    private fun valueOf(st: StmtIR): NodeIR = when (st) {
        is StmtIR.Let -> st.value
        is StmtIR.Expr -> st.node
    }

    private fun replaceRead(st: StmtIR, slot: Int, value: NodeIR): StmtIR = when (st) {
        is StmtIR.Let -> st.copy(value = replaceRead(st.value, slot, value))
        is StmtIR.Expr -> st.copy(node = replaceRead(st.node, slot, value))
    }

    /** A lambda closing over the slot needs it to hold a value. */
    private fun capturesSlot(node: NodeIR, slot: Int): Boolean {
        if (node is MakeLambdaIR) return node.captures.any { it.slot == slot }
        var found = false
        node.transformChildren { child ->
            if (!found && capturesSlot(child, slot)) found = true
            child
        }
        return found
    }

    private fun countReads(node: NodeIR, slot: Int): Int {
        if (node is ReadLocalIR) return if (node.slot == slot) 1 else 0
        var n = 0
        node.transformChildren { child -> n += countReads(child, slot); child }
        return n
    }

    private fun replaceRead(node: NodeIR, slot: Int, value: NodeIR): NodeIR {
        if (node is ReadLocalIR && node.slot == slot) return value
        return node.transformChildren { replaceRead(it, slot, value) }
    }
}
