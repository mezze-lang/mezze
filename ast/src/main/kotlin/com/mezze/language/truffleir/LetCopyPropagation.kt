package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * A binding read once, holding a value that can be recomputed wherever it is
 * read, is not a binding: the value moves to the read and the `let` disappears.
 *
 * WHY THIS IS WORTH A PASS, and it is not the frame slot it saves. Three tiers
 * compile a self-tail loop, and the fastest — `FusedLongTailLoop`, which runs
 * the whole loop in Java with no per-iteration bytecode dispatch — matches on
 * the branch being DIRECTLY the self tail call:
 *
 *     if (elseBr instanceof CallDirectIR ec && ec.isTailCall() …)
 *
 * A `do` block makes the branch a `BlockIR` whose RESULT is that call, so the
 * tier declines and the loop drops to the general path. MEASURED on
 * `bench/cases/recordcost`, two loops whose bodies are identical in the IR
 * except for the block:
 *
 *     go { i = i - 1, acc = acc + i }                      ~225 ps/iter  FUSED
 *     let nxt = i - 1 … go { i = nxt, acc = acc + cur }     ~700          declined
 *
 * Three times slower for writing the step as a named binding, which is a
 * performance cliff on a formatting choice. Substituting the two lets produces
 * the first body EXACTLY — same nodes, same order — so the tier accepts it and
 * the two spellings cost the same.
 *
 * WHAT IT WILL MOVE. Only values that can be recomputed at the read with no
 * observable difference wherever that read turns out to be: literals, reads of
 * a slot, a field read, and arithmetic over those. A call is NOT in that set
 * even when it looks like arithmetic — `i - 1` on a receiver whose type is
 * still a variable lowers to `i.sub { r = 1 }`, a call through a field read,
 * and nothing in the IR says whether the impl behind it has effects. Moving one
 * into a branch would change whether it runs at all.
 *
 * EXACTLY ONE READ, or none. Two reads would duplicate the work; and a value
 * duplicated into two conditional positions is worse than the binding it
 * replaced. A binding read zero times is dropped outright, since a value in
 * this set cannot have been there for its effects.
 *
 * A CAPTURED SLOT IS NEVER TOUCHED. `CaptureIR` names a frame slot, so a lambda
 * closing over the binding needs the slot to hold something — the same refusal
 * [RecordScalarizer] makes, for the same reason.
 *
 * AND NOT THE ENTRY BLOCK. A module-level `let` is read from OTHER functions by
 * hash rather than through the block scanned here, which is a bug this file
 * would otherwise repeat verbatim: see the note in [RecordScalarizer].
 *
 * The emptied slot stays declared in `localSlots`. An unused frame slot costs
 * nothing, and renumbering every later slot to reclaim it would rewrite every
 * read in the function for no gain.
 *
 * SCOPED TO THE LOOP BRANCH. See [rewriteSelfTailLoop]: applied to every block
 * in the program this removed ordinary bindings for no gain and broke the
 * debugger, which is a cost with no benefit attached.
 *
 * NOT [DeadLetEliminator], which drops a binding read ZERO times and is still
 * opt-in because its first integration broke ~94 tests. This substitutes a
 * binding read ONCE, which is a different transformation, and its value set is
 * much narrower than that pass's purity check — in particular a record is not
 * in it, and that pass's own notes suspect records used as handler config. Its
 * remaining suspect is worth naming here while the evidence is fresh: it does
 * not exempt the entry block, and a module-level binding read from another
 * function by hash looks dead to a block-local read count. That is exactly the
 * bug [RecordScalarizer] hit, with the same symptom of a value arriving absent.
 */
object LetCopyPropagation {

    /** The synthetic function holding a module's top-level statements. */
    private const val ENTRY_FUNCTION = "__entry__"

    fun propagate(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        var anyChanged = false
        val out = HashMap<Hash, FunctionIR>(functions.size)
        for ((hash, fn) in functions) {
            if (fn.name == ENTRY_FUNCTION) {
                out[hash] = fn
                continue
            }
            val newBody = rewriteSelfTailLoop(fn)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                anyChanged = true
                out[hash] = fn.copy(body = newBody)
            }
        }
        return if (anyChanged) out else functions
    }

    /**
     * ONLY the tail-call branch of a self-tail loop, which is the one place
     * this pays — and the restriction is not a precaution, it is a bug fix.
     *
     * Run over every block in the program, this broke the DEBUGGER, correctly:
     * `DapEndToEndTest` sets a breakpoint on a `let` line and stops on the next
     * line instead ("expected: <2> but was: <5>"), because after substitution
     * there is no code on that line, and a conditional breakpoint reading a
     * local finds no local to read. Those bindings were being removed for no
     * gain at all — outside a self-tail loop there is no tier to qualify for.
     *
     * Inside one there is nothing to lose: the tier compiles the whole loop to
     * Java with no per-iteration bytecode, so a breakpoint in its body could
     * never have stopped there anyway.
     */
    private fun rewriteSelfTailLoop(fn: FunctionIR): NodeIR {
        val body = fn.body as? IfIR ?: return fn.body
        val thenIsLoop = isSelfTailBlock(body.thenBr, fn.hash)
        val elseIsLoop = isSelfTailBlock(body.elseBr, fn.hash)
        if (!thenIsLoop && !elseIsLoop) return fn.body
        val newThen = if (thenIsLoop) propagateBlock(body.thenBr as BlockIR) else body.thenBr
        val newElse = if (elseIsLoop) propagateBlock(body.elseBr as BlockIR) else body.elseBr
        if (newThen === body.thenBr && newElse === body.elseBr) return fn.body
        return IfIR(body.cond, newThen, newElse, body.src)
    }

    /** A block whose RESULT is this function's own scalar tail call — exactly
     *  what the tier wants to see without the block. */
    private fun isSelfTailBlock(node: NodeIR, selfHash: Hash): Boolean {
        if (node !is BlockIR) return false
        val call = node.result as? CallDirectIR ?: return false
        return call.isTailCall && call.targetHash == selfHash
    }

    private fun propagateBlock(block: BlockIR): NodeIR {
        var stmts = block.stmts
        var result = block.result
        var changed = false

        // To a fixed point, because substituting one binding can make another
        // single-use: `let a = i - 1; let b = a; … b …` collapses in two steps.
        var progress = true
        while (progress) {
            progress = false
            for (i in stmts.indices) {
                val st = stmts[i] as? StmtIR.Let ?: continue
                // FOLDED FIRST. `i - 1` on a receiver whose type was not known
                // when the body was compiled is a CALL in the IR, not
                // arithmetic: `i.sub { r = 1 }`. The bytecode compiler folds
                // that back at emit time, so folding it here produces nothing
                // it would not have produced anyway — but it happens early
                // enough for the binding to be substitutable, which is what
                // lets the loop reach the fused tier. Without it a record
                // helper's own arithmetic keeps its binding and the loop stays
                // 3x off the control.
                val value = InlineOpMapping.foldAbilityOp(st.value)
                if (!isRecomputable(value)) continue

                val tail = stmts.drop(i + 1)
                val scope = tail.map { valueOf(it) } + result
                if (scope.any { capturesSlot(it, st.slot) }) continue
                val reads = scope.sumOf { countReads(it, st.slot) }
                if (reads > 1) continue

                // AND IT MAY NOT MOVE PAST ANYTHING THAT IS NOT ITSELF
                // RECOMPUTABLE. Substituting reorders this value after every
                // statement between the binding and its use, which is
                // unobservable only when those statements are as inert as the
                // value is. `InlineBinaryOpIR` is in the recomputable set and
                // CAN reach a user numeric impl — an ability's `add` is an
                // ordinary function — so moving one past a call would be a
                // reorder of two things that may both do something. Every
                // binding in a loop body of arithmetic passes this; the
                // measured kernels are unaffected.
                val crossesOnlyInert = tail
                    .takeWhile { s -> countReads(valueOf(s), st.slot) == 0 }
                    .all { s -> s is StmtIR.Let && isRecomputable(InlineOpMapping.foldAbilityOp(s.value)) }
                if (!crossesOnlyInert) continue

                stmts = if (reads == 0) {
                    stmts.take(i) + tail
                } else {
                    stmts.take(i) + tail.map { replaceRead(it, st.slot, value) }
                }
                if (reads == 1) result = replaceRead(result, st.slot, value)
                changed = true
                progress = true
                break
            }
        }
        // AND A BLOCK WITH NOTHING LEFT IN IT COLLAPSES TO ITS RESULT, which is
        // the entire point. `FusedLongTailLoop` matches on the branch BEING the
        // self tail call; an empty `BlockIR` wrapped around that call is still a
        // BlockIR and still declines. Substituting the bindings and leaving the
        // wrapper behind would have measured as nothing.
        if (stmts.isEmpty()) return result
        return if (changed) BlockIR(stmts, result, block.src) else block
    }

    private fun valueOf(st: StmtIR): NodeIR = when (st) {
        is StmtIR.Let -> st.value
        is StmtIR.Expr -> st.node
    }

    private fun replaceRead(st: StmtIR, slot: Int, value: NodeIR): StmtIR = when (st) {
        is StmtIR.Let -> st.copy(value = replaceRead(st.value, slot, value))
        is StmtIR.Expr -> st.copy(node = replaceRead(st.node, slot, value))
    }

    /**
     * A value that can be evaluated at the read instead of at the binding with
     * no observable difference: nothing allocated, nothing called, nothing that
     * depends on when it runs. Deliberately a short list — see the class note
     * on why a call that looks like arithmetic is not on it.
     */
    private fun isRecomputable(node: NodeIR): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR -> true
        is ReadFieldIR -> isRecomputable(node.record)
        is InlineBinaryOpIR -> isRecomputable(node.left) && isRecomputable(node.right)
        else -> false
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
