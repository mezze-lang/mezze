package com.mezze.language.typechecker.comptime

import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.forEachChild

/**
 * Which handlers answer at COMPILE time (introspection §9.2).
 *
 * A handler is compile-time when one of its operations performs `Comptime`, or
 * installs a handler that is. Of those, some LEAVE `Comptime` for whoever handles
 * it outside (`EvalCodebase`), and some answer it themselves (`ComptimeEvalCodebase`,
 * which installs `ComptimeEval` around `EvalCodebase`). The difference decides
 * whether a lone `perform` of one can be answered where it stands.
 *
 * `ComptimeEval` IMPLEMENTS `Comptime` and performs nothing, so it is neither —
 * which is what keeps the consent line at `main` from folding a whole program.
 *
 * Decided from typed bodies, by hash: a handler of some other effect that happens
 * to be spelled `Comptime` is not `Comptime`.
 */
object CompileTimePerformers {

    data class Found(
        /** Every compile-time performer. */
        val compileTime: Set<Hash>,
        /** Those whose `Comptime` is left to an outer handler. */
        val leavesComptime: Set<Hash>,
        /**
         * A handler of `Comptime` the module's bodies install (`ComptimeEval`), by
         * identity. A question that leaves `Comptime` is folded where it stands,
         * and its evaluation needs something to answer that `Comptime` with.
         */
        val answerer: com.mezze.language.typedast.TypedHandlerRef? = null,
    )

    /**
     * The compile-time performers of [module], given those its imports provide.
     * A fixpoint, since one performer may install another declared beside it.
     */
    fun of(module: TypedModule, known: Found = Found(emptySet(), emptySet())): Found =
        ofPerformers(module.performerDecls, known)

    /**
     * Across every module a check has loaded, in one fixpoint: a handler in one
     * module may install a compile-time handler from another (a library wrapping
     * `EvalCodebase`), which a per-module pass cannot see.
     */
    fun ofModules(modules: Collection<TypedModule>): Found =
        ofPerformers(modules.flatMap { it.performerDecls }, Found(emptySet(), emptySet()))

    fun ofPerformers(
        performers: List<com.mezze.language.typedast.TypedPerformerDecl>,
        known: Found,
    ): Found {
        val compileTime = HashSet(known.compileTime)
        val leaves = HashSet(known.leavesComptime)
        var changed = true
        while (changed) {
            changed = false
            for (performer in performers) {
                val bodies = performer.operations.values
                if (performer.hash !in compileTime && bodies.any { raises(it, compileTime) }) {
                    compileTime += performer.hash
                    changed = true
                }
                if (performer.hash !in leaves && bodies.any { leaves(it, leaves, answered = false) }) {
                    leaves += performer.hash
                    changed = true
                }
            }
        }
        var answerer: com.mezze.language.typedast.TypedHandlerRef? = known.answerer
        fun find(e: TypedExpr) {
            if (answerer != null) return
            if (e is TypedPerform) {
                e.handlers.firstOrNull { it.effectHash == WellKnownHashConstants.EFFECT_COMPTIME && it.params == null }
                    ?.let { answerer = it; return }
            }
            e.forEachChild { find(it) }
        }
        performers.forEach { p -> p.operations.values.forEach { find(it) } }
        return Found(compileTime - known.compileTime, leaves - known.leavesComptime, answerer)
    }

    /** Whether [expr] performs `Comptime`, or installs a performer in [compileTime]. */
    fun raises(expr: TypedExpr, compileTime: Set<Hash>): Boolean {
        if (expr is TypedPerformOp && expr.effectHash == WellKnownHashConstants.EFFECT_COMPTIME) return true
        if (expr is TypedPerform && expr.handlers.any { it.performerHash in compileTime }) return true
        var hit = false
        expr.forEachChild { if (!hit && raises(it, compileTime)) hit = true }
        return hit
    }

    /**
     * Whether [expr] performs `Comptime` that nothing inside it handles: directly,
     * or by installing a performer in [leavesComptime] with no `Comptime` handler
     * around it.
     */
    private fun leaves(expr: TypedExpr, leavesComptime: Set<Hash>, answered: Boolean): Boolean {
        if (answered) return false
        if (expr is TypedPerformOp && expr.effectHash == WellKnownHashConstants.EFFECT_COMPTIME) return true
        if (expr is TypedPerform) {
            val handles = expr.handlers.any { it.effectHash == WellKnownHashConstants.EFFECT_COMPTIME }
            if (!handles && expr.handlers.any { it.performerHash in leavesComptime }) return true
            if (handles) return false
        }
        var hit = false
        expr.forEachChild { if (!hit && leaves(it, leavesComptime, answered)) hit = true }
        return hit
    }
}
