package com.mezze.language.typechecker.context

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedPerform
import com.mezze.language.typedast.TypedPerformOp
import com.mezze.language.typedast.forEachChild

/**
 * The effects an IMPORTED performer's operations add to every `perform` of it.
 *
 * `perform H in body` raises what the body raises, minus what `H` handles, plus
 * what `H`'s own operations raise. For a performer declared in the module being
 * checked, that last part comes from validating its bodies. For an imported one
 * it was read only from annotations on its operations — so a handler whose
 * operations perform an effect lost it at the module boundary. `EvalCodebase`
 * raises `Comptime` from every operation, and a function performing it imported
 * type-checked with no `ComptimeEval` anywhere, then failed at run time.
 *
 * Here it is read off the performers' typed bodies, over every loaded module in
 * one fixpoint: a performer may install another, from another module.
 *
 * Keyed by performer name, as the constraint generator's table is.
 */
object ImportedPerformerEffects {

    fun ofModules(modules: Collection<TypedModule>): Map<String, Set<String>> {
        val effectName: Map<Hash, String> = modules.flatMap { it.effectDecls }.associate { it.hash to it.name }
        val performers = modules.flatMap { it.performerDecls }
        val added = HashMap<Hash, Set<String>>()
        var changed = true
        while (changed) {
            changed = false
            for (p in performers) {
                val own = effectName[p.effectHash]
                // An operation is written as a lambda; its body is what runs when
                // the operation is performed.
                val raised = p.operations.values.flatMapTo(HashSet()) { op ->
                    escaping((op as? TypedLambda)?.body ?: op, added, effectName)
                } - setOfNotNull(own)
                if (raised != (added[p.hash] ?: emptySet<String>())) {
                    added[p.hash] = raised
                    changed = true
                }
            }
        }
        return performers.mapNotNull { p -> added[p.hash]?.takeIf { it.isNotEmpty() }?.let { p.name to it } }.toMap()
    }

    /**
     * The effects [e] raises that nothing inside it handles. A lambda's body is
     * not counted where the lambda is written: its effects are in its type, and
     * are raised by whatever calls it.
     */
    private fun escaping(e: TypedExpr, added: Map<Hash, Set<String>>, effectName: Map<Hash, String>): Set<String> {
        val out = HashSet<String>()
        fun walk(x: TypedExpr) {
            when (x) {
                is TypedLambda -> return
                is TypedPerformOp -> effectName[x.effectHash]?.let { out += it }
                is TypedCall -> (x.function.type as? MezzeType.FunctionType)?.effects?.allEffectNames?.let { out += it }
                is TypedPerform -> {
                    val handled = x.handlers.mapNotNullTo(HashSet()) { effectName[it.effectHash] }
                    x.handlers.forEach { h -> h.params?.let { walk(it) } }
                    val inner = escaping(x.body, added, effectName) +
                        x.handlers.flatMap { added[it.performerHash] ?: emptySet() }
                    out += inner - handled
                    return
                }
                else -> Unit
            }
            x.forEachChild { walk(it) }
        }
        walk(e)
        return out
    }
}
