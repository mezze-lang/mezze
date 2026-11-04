package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * A parameter that is only ever FIELD-READ becomes one parameter per field, and
 * the record its callers built stops existing.
 *
 * THE SHAPE, which is the ordinary functional loop:
 *
 *     let go = { st } ->
 *       if st.i <= 0 then st.acc
 *       else go { st = { i = st.i - 1, acc = st.acc + st.i } }
 *
 * The record is built, handed to the next iteration, and destructured there BY
 * THE SAME FUNCTION. Nothing else ever sees it, and yet one is allocated per
 * iteration: MEASURED on `bench/cases/recordcost` kernel 2 at 2 769 ps/iter
 * with 25 young collections, against 233 ps and 2 collections for the same loop
 * carrying `i` and `acc` positionally. Twelve times, for writing the state as a
 * record — which is how anybody writing in this style writes it.
 *
 * [RecordScalarizer] cannot reach it: that pass replaces a `let` whose VALUE is
 * a record, and here the record arrives as a PARAMETER. This is the same idea
 * one level out — expand the parameter, rewrite every caller to pass the fields
 * positionally.
 *
 * AND THEN THE LOOP QUALIFIES. With two parameters instead of one record, the
 * branch is a self tail call over positional arithmetic, which is what
 * `FusedLongTailLoop` — the tier that runs the whole loop in Java with no
 * per-iteration bytecode dispatch — matches on. The allocation going away is
 * most of the win; reaching that tier is the rest.
 *
 * WHY THIS ONE HAS TO SEE THE WHOLE PROGRAM. Changing a function's arity is
 * sound only if EVERY caller is rewritten with it, so a reference this pass
 * cannot classify must disqualify the candidate rather than be assumed
 * harmless. Four refusals carry that:
 *
 *  - **Lambdas only.** A lambda is reachable solely through its hash from
 *    inside this map; a top-level declaration may be invoked from OUTSIDE it —
 *    an entry point, or a `pub` function the runtime calls by name — where no
 *    rewrite can follow. `name == "lambda"` is the IR's own marker for an
 *    extracted lambda, set at the single place that extracts one.
 *  - **Every mention classified.** A hash can appear as a direct call's
 *    target, a closure's function, a global read, or a partial application.
 *    Only the first two are rewritable; anything else refuses.
 *  - **A closure of it only ever `let`-bound**, to a slot whose every read is
 *    the callee of a call this pass then rewrites. One bare use — passed on,
 *    returned, compared — and the arity is visible somewhere unrewritable.
 *  - **An exact field set at every call site.** A missing field has nowhere to
 *    come from; an extra one would have its value silently dropped.
 *
 * ORDER IS PRESERVED: the fields are passed in the order they were WRITTEN at
 * the call site, which is the order the record evaluated them, so an effectful
 * field value still runs when it did.
 */
object ParamScalarizer {

    private val ENABLED: Boolean = System.getenv("MEZZE_PARAM_SCALARIZE") != "0"

    /** The name the compiler gives every extracted lambda. */
    private const val LAMBDA = "lambda"

    /** Separates a parameter's name from the field it stands for. Two
     *  underscores, because one is ordinary inside a Mezze identifier. */
    private const val FIELD_SEP = "__"

    private class Plan(val hash: Hash, val paramName: String, val fields: List<String>) {
        fun paramNames(): List<String> = fields.map { paramName + FIELD_SEP + it }
    }

    fun scalarize(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        if (!ENABLED) return functions

        val plans = HashMap<Hash, Plan>()
        for ((h, fn) in functions) {
            val plan = planFor(h, fn, functions) ?: continue
            plans[h] = plan
        }
        if (plans.isEmpty()) return functions

        val out = HashMap<Hash, FunctionIR>(functions.size)
        out.putAll(functions)

        // The callees: one parameter per field, field reads become reads of them.
        for (plan in plans.values) {
            val fn = out.getValue(plan.hash)
            val base = fn.captureSlots.size
            val slotOf = HashMap<String, Int>(plan.fields.size)
            for ((i, f) in plan.fields.withIndex()) slotOf[f] = base + i
            out[plan.hash] = fn.copy(
                paramSlots = plan.fields.map { SlotKind.OBJECT },
                paramNames = plan.paramNames(),
                body = expandParamReads(fn.body, base, slotOf),
            )
        }

        // The callers, after every callee is settled, so a function that is both
        // gets rewritten on both sides.
        for ((h, fn) in out.toList()) {
            val slotPlans = closureSlotPlans(fn.body, plans)
            val rewritten = rewriteCalls(fn.body, plans, slotPlans)
            if (rewritten !== fn.body) out[h] = fn.copy(body = rewritten)
        }
        return out
    }

    // ── candidate selection ────────────────────────────────────────────────

    private fun planFor(h: Hash, fn: FunctionIR, functions: Map<Hash, FunctionIR>): Plan? {
        if (fn.name != LAMBDA) return null
        if (fn.paramSlots.size != 1) return null
        // A local would have to be renumbered past the parameters gained; no
        // measured case needs it, so it refuses instead.
        if (fn.localSlots.isNotEmpty()) return null
        val paramName = fn.paramNames.singleOrNull() ?: return null
        val fields = fieldsReadOf(fn.body, fn.captureSlots.size) ?: return null
        // One field is a record that exists to hold one value: still a win, but
        // two is where the measured case is and where the shape is unambiguous.
        if (fields.size < 2) return null
        if (!everyMentionIsRewritable(h, fields, functions)) return null
        return Plan(h, paramName, fields)
    }

    /**
     * The field names read off [slot], in order of first appearance, or null
     * when the slot is used as anything other than the record of a field read.
     */
    private fun fieldsReadOf(body: NodeIR, slot: Int): List<String>? {
        val found = LinkedHashSet<String>()
        var ok = true
        fun walk(node: NodeIR) {
            if (!ok) return
            if (node is ReadFieldIR) {
                val r = node.record
                if (r is ReadLocalIR && r.slot == slot) {
                    found.add(node.fieldName)
                    return
                }
            }
            if (node is ReadLocalIR && node.slot == slot) { ok = false; return }
            if (node is ReadArgIR && node.index == slot) { ok = false; return }
            if (node is MakeLambdaIR && node.captures.any { it.slot == slot }) { ok = false; return }
            node.transformChildren { child -> walk(child); child }
        }
        walk(body)
        return if (ok && found.isNotEmpty()) found.toList() else null
    }

    // ── whole-program reference check ──────────────────────────────────────

    private fun everyMentionIsRewritable(
        h: Hash,
        fields: List<String>,
        functions: Map<Hash, FunctionIR>,
    ): Boolean {
        val callee = functions[h] ?: return false
        for ((_, fn) in functions) {
            if (!noStrayMention(fn.body, h)) return false
            if (!directCallsMatch(fn.body, h, fields)) return false
            val slots = letBoundClosureSlots(fn.body, h) ?: return false
            for (slot in slots) if (!slotOnlyCalled(fn.body, slot, fields, h, callee)) return false
        }
        return true
    }

    /** Slots a `let` binds to a closure of [h]; null when a closure of it
     *  appears anywhere other than as a `let`'s value. */
    private fun letBoundClosureSlots(body: NodeIR, h: Hash): List<Int>? {
        val letBound = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<NodeIR, Boolean>())
        val slots = mutableListOf<Int>()
        forEachNode(body) { node ->
            if (node is BlockIR) {
                for (st in node.stmts) {
                    if (st is StmtIR.Let) {
                        val v = st.value
                        if (v is MakeLambdaIR && v.functionHash == h) {
                            letBound.add(v)
                            slots.add(st.slot)
                        }
                    }
                }
            }
        }
        var ok = true
        forEachNode(body) { node ->
            if (node is MakeLambdaIR && node.functionHash == h && node !in letBound) ok = false
        }
        return if (ok) slots else null
    }

    /** Every read of [slot] is the callee of a call whose argument record
     *  matches [fields] exactly. */
    private fun slotOnlyCalled(
        body: NodeIR,
        slot: Int,
        fields: List<String>,
        h: Hash,
        callee: FunctionIR,
    ): Boolean {
        var ok = true
        fun walk(node: NodeIR) {
            if (!ok) return
            val calleeExpr = calleeOf(node)
            if (calleeExpr is ReadLocalIR && calleeExpr.slot == slot) {
                if (!recordMatches(argRecordOf(node), fields)) { ok = false; return }
                argRecordOf(node)?.let { walk(it) }
                return
            }
            if (node is ReadLocalIR && node.slot == slot) { ok = false; return }
            if (node is MakeLambdaIR && node.captures.any { it.slot == slot }) {
                // THE SELF-REFERENTIAL CLOSURE, which every recursive local
                // lambda has: `let go = { … } ` stores a closure into a slot and
                // that closure captures THE SAME SLOT, so the function can call
                // itself through its own value. A capture reads the slot, so in
                // general one disqualifies the slot — but this capture is DEAD
                // whenever the self-calls are direct, and it is dead by
                // construction here: the slot has not been written yet when the
                // `MakeLambdaIR` is evaluated, which is why a recursive let's
                // self-call is emitted as a direct call in the first place.
                //
                // So it is allowed exactly when it is this candidate's own
                // closure AND the body never reads the capture index it occupies.
                // Anything else — a DIFFERENT lambda capturing the slot, or this
                // one actually reading the capture — still refuses.
                if (node.functionHash != h) { ok = false; return }
                val indices = node.captures.withIndex()
                    .filter { (_, c) -> c.slot == slot }
                    .map { (i, _) -> i }
                if (indices.any { readsCapture(callee.body, it) }) { ok = false; return }
                return
            }
            node.transformChildren { child -> walk(child); child }
        }
        walk(body)
        return ok
    }

    /** Whether a function's body reads capture [index]. Captures occupy frame
     *  slots `0 until captureCount`, so a local read of that slot is a capture
     *  read too. */
    private fun readsCapture(body: NodeIR, index: Int): Boolean {
        var found = false
        forEachNode(body) { node ->
            if (node is ReadCaptureIR && node.index == index) found = true
            if (node is ReadLocalIR && node.slot == index) found = true
            if (node is ReadArgIR && node.index == index) found = true
        }
        return found
    }

    private fun directCallsMatch(body: NodeIR, h: Hash, fields: List<String>): Boolean {
        var ok = true
        forEachNode(body) { node ->
            if (node is CallDirectIR && node.targetHash == h &&
                !recordMatches(node.args.singleOrNull(), fields)
            ) ok = false
        }
        return ok
    }

    private fun noStrayMention(body: NodeIR, h: Hash): Boolean {
        var ok = true
        forEachNode(body) { node ->
            when (node) {
                is ReadGlobalByHashIR -> if (node.hash == h) ok = false
                is ReadGlobalFnByHashIR -> if (node.hash == h) ok = false
                is PartialApplyDirectIR -> if (node.targetHash == h) ok = false
                else -> Unit
            }
        }
        return ok
    }

    // ── rewriting ──────────────────────────────────────────────────────────

    /** In the callee: a field read of the parameter becomes a read of the
     *  parameter that now holds that field. */
    private fun expandParamReads(node: NodeIR, paramSlot: Int, slotOf: Map<String, Int>): NodeIR {
        if (node is ReadFieldIR) {
            val r = node.record
            if (r is ReadLocalIR && r.slot == paramSlot) {
                slotOf[node.fieldName]?.let { return ReadLocalIR(it, SlotKind.OBJECT, node.src) }
            }
        }
        return node.transformChildren { expandParamReads(it, paramSlot, slotOf) }
    }

    /** Slot → plan, for the closures this function binds.
     *
     *  A slot that binds closures of TWO planned functions is dropped rather
     *  than resolved to either: a call through it would then be rewritten for
     *  one arity while possibly reaching the other. `forceAllocLocal` mints a
     *  fresh slot per `let`, so this should not arise — which is exactly why it
     *  is worth refusing rather than assuming. */
    private fun closureSlotPlans(body: NodeIR, plans: Map<Hash, Plan>): Map<Int, Plan> {
        val found = HashMap<Int, Plan>()
        val ambiguous = HashSet<Int>()
        forEachNode(body) { node ->
            if (node is BlockIR) {
                for (st in node.stmts) {
                    if (st is StmtIR.Let) {
                        val v = st.value
                        if (v is MakeLambdaIR) {
                            val plan = plans[v.functionHash] ?: continue
                            val prior = found.put(st.slot, plan)
                            if (prior != null && prior.hash != plan.hash) ambiguous.add(st.slot)
                        }
                    }
                }
            }
        }
        for (slot in ambiguous) found.remove(slot)
        return found
    }

    /** In every caller: the record becomes positional arguments. */
    private fun rewriteCalls(node: NodeIR, plans: Map<Hash, Plan>, slotPlans: Map<Int, Plan>): NodeIR {
        val n = node.transformChildren { rewriteCalls(it, plans, slotPlans) }
        if (n is CallDirectIR) {
            val plan = plans[n.targetHash] ?: return n
            val ordered = orderedArgs(n.args.singleOrNull(), plan) ?: return n
            return n.copy(args = ordered, isScalarABI = true)
        }
        if (n is CallIndirectScalarIR) {
            val callee = n.function
            if (callee !is ReadLocalIR) return n
            val plan = slotPlans[callee.slot] ?: return n
            val ordered = orderedArgs(n.args.singleOrNull(), plan) ?: return n
            return n.copy(args = ordered, argFieldNames = plan.paramNames())
        }
        if (n is CallIndirectIR) {
            val callee = n.function
            if (callee !is ReadLocalIR) return n
            val plan = slotPlans[callee.slot] ?: return n
            val ordered = orderedArgs(n.arg, plan) ?: return n
            return CallIndirectScalarIR(n.function, ordered, plan.paramNames(), n.isTailCall, n.src)
        }
        return n
    }

    private fun orderedArgs(arg: NodeIR?, plan: Plan): List<NodeIR>? {
        val rec = arg as? MakeRecordIR ?: return null
        if (!recordMatches(rec, plan.fields)) return null
        val byName = rec.fields.associate { it.name to it.value }
        return plan.fields.map { byName[it] ?: return null }
    }

    // ── shared helpers ─────────────────────────────────────────────────────

    private fun calleeOf(node: NodeIR): NodeIR? = when (node) {
        is CallIndirectScalarIR -> node.function
        is CallIndirectIR -> node.function
        else -> null
    }

    private fun argRecordOf(node: NodeIR): NodeIR? = when (node) {
        is CallIndirectScalarIR -> node.args.singleOrNull()
        is CallIndirectIR -> node.arg
        else -> null
    }

    private fun recordMatches(node: NodeIR?, fields: List<String>): Boolean {
        val rec = node as? MakeRecordIR ?: return false
        val names = rec.fields.map { it.name }
        if (names.size != names.toSet().size) return false
        if (names.any { it == com.mezze.language.ast.MagicParams.SPREAD_FIELD }) return false
        return names.toSet() == fields.toSet()
    }

    private fun forEachNode(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> forEachNode(child, f); child }
    }
}
