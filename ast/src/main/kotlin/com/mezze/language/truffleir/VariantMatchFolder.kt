package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash

/**
 * A variant constructed as the scrutinee of the match that destructures it is
 * not built: the match folds to the one case whose tag it carries, and the
 * payload's fields are bound straight to the slots that case was going to bind.
 *
 * THE SHAPE, which is what every `Option`-returning call followed by a `match`
 * produces once the two meet in one expression:
 *
 *     match Some { val = i } is
 *       Some { val } -> … val …
 *       None -> …
 *
 * MEASURED on `bench/cases/coresurface` at n=2e7: 1 892 ps/iter with 17 young
 * collections, against 222 ps and 2 for the same loop carrying the value
 * directly. Per iteration that allocates a payload record, a box for the Int
 * inside it, and a wrapper carrying the tag — none of which outlives the
 * expression. A three-constructor user variant measures the same (1 940), so
 * the cost is the CONSTRUCTION, not the number of arms to dispatch over.
 *
 * Both tags are known at compile time, on both sides, so nothing about this
 * needs to happen at run time.
 *
 * AND IT COMPOSES. The fold leaves `BlockIR([Let(slot, value)], <body>)`, which
 * in a self-tail-loop branch is exactly what [LetCopyPropagation] substitutes
 * and collapses — after which the branch is a bare self tail call and the loop
 * qualifies for the fused tier.
 *
 * MATCH IS FIRST-MATCH-WINS, which is where the care goes. Folding to the case
 * that carries the tag is only correct if no EARLIER case could have matched
 * first, so the scan stops at the first case it cannot rule out:
 *
 *  - a `Variant` pattern with a different tag cannot match, and is skipped;
 *  - a `Variant` pattern with THE tag is the answer;
 *  - anything else — a wildcard, a record, a literal, a sequence — refuses,
 *    because proving it cannot match a variant is not something this pass
 *    attempts. A wildcard in particular WOULD match, and may bind the whole
 *    scrutinee, which is the one thing the fold removes.
 *
 * A GUARD REFUSES TOO. A guard that fails falls through to a later case, so the
 * chosen case is not yet chosen; the same goes for an if-let binding.
 *
 * ORDER IS PRESERVED: the bindings are emitted in the order the PAYLOAD's
 * fields were written, not the order the pattern names them, so an effectful
 * field value still runs when it did. A payload field the pattern does not bind
 * is dropped, so its value must be side-effect-free — otherwise the fold would
 * skip something that used to run.
 */
object VariantMatchFolder {

    private val ENABLED: Boolean = System.getenv("MEZZE_FOLD_VARIANT_MATCH") != "0"

    fun fold(functions: Map<Hash, FunctionIR>): Map<Hash, FunctionIR> {
        if (!ENABLED) return functions
        var changed = false
        val out = HashMap<Hash, FunctionIR>(functions.size)
        for ((h, fn) in functions) {
            val body = rewrite(fn.body)
            if (body === fn.body) {
                out[h] = fn
            } else {
                changed = true
                out[h] = fn.copy(body = body)
            }
        }
        return if (changed) out else functions
    }

    private fun rewrite(node: NodeIR): NodeIR {
        // Inner first, so a match inside a folded body is folded too.
        val descended = node.transformChildren { rewrite(it) }
        if (descended !is MatchIR) return descended
        return foldMatch(descended) ?: descended
    }

    private fun foldMatch(match: MatchIR): NodeIR? {
        val made = match.scrutinee as? MakeVariantIR ?: return null
        val payload = made.payload
        if (payload != null && payload !is MakeRecordIR) return null

        val case = chooseCase(match.cases, made.tag) ?: return null
        val pattern = case.pattern as? PatternIR.Variant ?: return null
        if (pattern.fieldNames.size != pattern.fieldSlots.size) return null

        val fields = (payload as? MakeRecordIR)?.fields ?: emptyList()
        if (fields.any { it.name == com.mezze.language.ast.MagicParams.SPREAD_FIELD }) return null
        if (fields.map { it.name }.toSet().size != fields.size) return null
        // Every name the pattern binds has to come from the payload.
        val byName = fields.associate { it.name to it.value }
        if (pattern.fieldNames.any { it !in byName }) return null

        val kindOf = case.bindings.associate { it.slot to it.kind }
        val slotOf = pattern.fieldNames.zip(pattern.fieldSlots).toMap()

        val stmts = ArrayList<StmtIR>(pattern.fieldNames.size)
        for (f in fields) {
            val slot = slotOf[f.name]
            if (slot == null) {
                // Not bound by the pattern: its value is about to be dropped.
                if (!isSideEffectFree(f.value)) return null
                continue
            }
            stmts.add(
                StmtIR.Let(slot, kindOf[slot] ?: SlotKind.OBJECT, f.name, Hash.ZERO, f.value, match.src)
            )
        }
        return if (stmts.isEmpty()) case.body else BlockIR(stmts, case.body, match.src)
    }

    /**
     * The case a scrutinee with [tag] reaches, or null when an earlier case
     * cannot be ruled out. See the class note on first-match-wins.
     */
    private fun chooseCase(cases: List<CaseIR>, tag: Int): CaseIR? {
        for (case in cases) {
            val p = case.pattern
            if (p !is PatternIR.Variant) return null
            if (p.tag != tag) continue
            if (case.guard != null) return null
            if (case.ifLetBindings.isNotEmpty()) return null
            return case
        }
        return null
    }

    /** Safe to drop: nothing observable happens if it never runs. */
    private fun isSideEffectFree(node: NodeIR): Boolean = when (node) {
        is LongLitIR, is DoubleLitIR, is BoolLitIR, is StringLitIR,
        is UnitLitIR, is CharLitIR, is FracLitIR -> true
        is ReadLocalIR, is ReadCaptureIR, is ReadArgIR -> true
        is ReadFieldIR -> isSideEffectFree(node.record)
        is InlineBinaryOpIR -> isSideEffectFree(node.left) && isSideEffectFree(node.right)
        else -> false
    }
}
