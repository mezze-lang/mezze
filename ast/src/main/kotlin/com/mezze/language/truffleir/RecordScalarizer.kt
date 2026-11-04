package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * A record that never escapes its binding is not built: its fields become
 * locals and its field reads become local reads.
 *
 * THE SHAPE, which is what a helper returning a record lowers to once that
 * helper has been inlined:
 *
 *     let p = { lo = i - 1, add = i }     // Let(slot, MakeRecordIR)
 *     … p.lo … p.add …                    // every use is a field read
 *
 * becomes two lets and two local reads, and the `MezzeRecord` plus its
 * `Object[]` plus a `Long` box per numeric field are gone. A JFR profile of the
 * loop before this names exactly those three: 211 samples `java.lang.Long`, 86
 * `Object[]`, 61 `MezzeRecord` — the 1:1:2 a two-field all-object record
 * predicts.
 *
 * IT ONLY PAYS WITH TWO OTHER THINGS IN PLACE, and the order is the whole
 * story of how long this took to establish:
 *
 *  - `boxingEliminationTypes` must be on. Before it, a store into an
 *    Object-typed BDSL local boxed, so this pass traded one allocation for two
 *    and measured flat.
 *  - The helper must be INLINED, so the record's construction and its uses sit
 *    in one function. A top-level helper is inlined by `tryInlineDirectCall`; a
 *    `let`-bound one is compiled as its own function and never inlined, so the
 *    record is built inside it and returned, and this pass never sees it.
 *
 * MEASURED on `bench/cases/recordcost`, a loop whose helper returns a two-field
 * record, helper at module level so it inlines:
 *
 *     without this pass    5 121 ps/iter    32 young GCs
 *     with it                644             3
 *
 * Eight times, and the GC count is the honest part: three collections against
 * two for the same loop with no record at all, so nothing is allocated per
 * iteration.
 *
 * WHEN IT REFUSES, and each refusal is a correctness condition rather than a
 * missed opportunity:
 *
 *  - A BARE use of the binding — passed somewhere, returned, compared — means
 *    the record is a value in its own right and there is nothing to replace it
 *    with. One such use disqualifies the binding.
 *  - A LAMBDA capturing the slot is a bare use: `CaptureIR` names a frame slot,
 *    and after the rewrite that slot holds nothing.
 *  - A read of a field the record does not declare, which would have to come
 *    from somewhere this pass cannot see.
 *  - A SPREAD field, whose field set is not known here, or a duplicate field
 *    name, where one slot would shadow the other.
 *
 * ORDER IS PRESERVED. The lets are emitted in the order the fields were
 * WRITTEN, which is the order the record evaluated them, so an effectful field
 * value still runs when it did.
 */
object RecordScalarizer {

    /** The synthetic function holding a module's top-level statements. */
    private const val ENTRY_FUNCTION = "__entry__"

    fun scalarize(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        var anyChanged = false
        val out = HashMap<Hash, FunctionIR>(functions.size)
        for ((hash, fn) in functions) {
            // NEVER THE ENTRY BLOCK. A module-level `let` lives there, and its
            // binding is reachable from OTHER functions by hash rather than
            // through this block — which the use check cannot see. Rewriting one
            // removed a record that another function still read, and
            // `BasicLanguageFeaturesTest > test basic records` failed with
            // "null cannot be cast to MezzeRecord". A binding inside an ordinary
            // function is only reachable lexically, by a read or a capture, and
            // both are checked.
            if (fn.name == ENTRY_FUNCTION) {
                out[hash] = fn
                continue
            }
            val alloc = SlotAllocator(fn)
            val newBody = rewrite(fn.body, alloc)
            if (newBody === fn.body) {
                out[hash] = fn
            } else {
                anyChanged = true
                out[hash] = fn.copy(
                    body = newBody,
                    localSlots = fn.localSlots + alloc.addedKinds,
                    localNames = fn.localNames + alloc.addedNames,
                )
            }
        }
        return if (anyChanged) out else functions
    }

    /** Appends locals to the function being rewritten; the frame is laid out
     *  `[captures…, params…, locals…]`, so a new local lands after the rest. */
    private class SlotAllocator(fn: FunctionIR) {
        private val base = fn.captureSlots.size + fn.paramSlots.size + fn.localSlots.size
        val addedNames = mutableListOf<String>()
        val addedKinds = mutableListOf<SlotKind>()

        fun next(name: String): Int {
            val slot = base + addedNames.size
            addedNames.add(name)
            addedKinds.add(SlotKind.OBJECT)
            return slot
        }
    }

    private fun rewrite(node: NodeIR, alloc: SlotAllocator): NodeIR {
        // Inner blocks first, so a record inside one is scalarized before the
        // enclosing block considers its own bindings.
        val descended = node.transformChildren { rewrite(it, alloc) }
        if (descended !is BlockIR) return descended
        return scalarizeBlock(descended, alloc)
    }

    private fun scalarizeBlock(block: BlockIR, alloc: SlotAllocator): NodeIR {
        var stmts = block.stmts
        var result = block.result
        var changed = false
        var i = 0
        while (i < stmts.size) {
            val st = stmts[i]
            if (st !is StmtIR.Let) { i++; continue }
            val rec = st.value as? MakeRecordIR
            if (rec == null) { i++; continue }
            if (rec.fields.isEmpty()) { i++; continue }
            if (rec.fields.any { it.name == com.mezze.language.ast.MagicParams.SPREAD_FIELD }) { i++; continue }
            if (rec.fields.map { it.name }.toSet().size != rec.fields.size) { i++; continue }

            // Everything the binding is still visible to.
            val scope: List<NodeIR> = stmts.drop(i + 1).map { s ->
                when (s) {
                    is StmtIR.Let -> s.value
                    is StmtIR.Expr -> s.node
                }
            } + result
            val names = rec.fields.map { it.name }.toSet()
            if (!scope.all { onlyFieldReads(it, st.slot, names) }) { i++; continue }

            // One local per field, bound in the order the fields were written.
            val slotOf = HashMap<String, Int>(rec.fields.size)
            val lets = rec.fields.map { f ->
                val name = "__rec_${st.name}_${f.name}"
                val slot = alloc.next(name)
                slotOf[f.name] = slot
                StmtIR.Let(slot, SlotKind.OBJECT, name, Hash.ZERO, f.value, st.src)
            }
            val rewrittenTail = stmts.drop(i + 1).map { s ->
                when (s) {
                    is StmtIR.Let -> s.copy(value = replaceFieldReads(s.value, st.slot, slotOf))
                    is StmtIR.Expr -> s.copy(node = replaceFieldReads(s.node, st.slot, slotOf))
                }
            }
            stmts = stmts.take(i) + lets + rewrittenTail
            result = replaceFieldReads(result, st.slot, slotOf)
            changed = true
            i += lets.size
        }
        return if (changed) BlockIR(stmts, result, block.src) else block
    }

    /**
     * True when every mention of [slot] in [node] is the record of a field read
     * naming one of [fields] — so replacing the binding with per-field locals
     * leaves nothing pointing at it.
     */
    private fun onlyFieldReads(node: NodeIR, slot: Int, fields: Set<String>): Boolean {
        if (node is ReadFieldIR) {
            val r = node.record
            if (r is ReadLocalIR && r.slot == slot) {
                // A field this record does not declare cannot come from here.
                return node.fieldName in fields
            }
            return onlyFieldReads(r, slot, fields)
        }
        // A capture names a frame slot, so capturing the binding is a bare use.
        if (node is MakeLambdaIR) return node.captures.none { it.slot == slot }
        if (node is ReadLocalIR) return node.slot != slot
        var ok = true
        node.transformChildren { child ->
            if (ok && !onlyFieldReads(child, slot, fields)) ok = false
            child
        }
        return ok
    }

    /** Rewrite `ReadFieldIR(ReadLocalIR(slot), name)` to a read of the local
     *  that now holds that field. Only reached for a [slot] that
     *  [onlyFieldReads] accepted, so every mention is such a read. */
    private fun replaceFieldReads(node: NodeIR, slot: Int, slotOf: Map<String, Int>): NodeIR {
        if (node is ReadFieldIR) {
            val r = node.record
            if (r is ReadLocalIR && r.slot == slot) {
                val target = slotOf[node.fieldName]
                if (target != null) return ReadLocalIR(target, SlotKind.OBJECT, node.src)
            }
        }
        return node.transformChildren { replaceFieldReads(it, slot, slotOf) }
    }
}
