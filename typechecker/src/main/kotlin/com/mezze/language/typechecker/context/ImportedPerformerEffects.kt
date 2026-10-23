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
import kotlinx.serialization.Serializable

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

    /**
     * What a module's performers, and those of everything it imports, add.
     *
     * Computed once, when the module is checked, on top of its imports' — so it
     * is transitive — and kept with the module. An importer unions its imports'
     * instead of reading their bodies: that read every imported body on every
     * check, which a module loaded from the checked-module cache would otherwise
     * not need.
     */
    @Serializable
    data class Summary(
        /** Every effect declared, by hash: what a `perform` of one names. */
        val effectNames: Map<Hash, String>,
        /** Every performer whose operations raise something, by hash. */
        val added: Map<Hash, Set<String>>,
        /** [added], by performer name: the constraint generator's table. */
        val byName: Map<String, Set<String>>,
    ) {
        companion object {
            val EMPTY = Summary(emptyMap(), emptyMap(), emptyMap())

            fun union(summaries: Collection<Summary>): Summary =
                if (summaries.size == 1) summaries.first()
                else Summary(
                    summaries.fold(HashMap()) { m, s -> m.apply { putAll(s.effectNames) } },
                    summaries.fold(HashMap()) { m, s -> m.apply { putAll(s.added) } },
                    summaries.fold(HashMap()) { m, s -> m.apply { putAll(s.byName) } },
                )

            /** [module]'s summary, given its imports': theirs plus what [module] adds. */
            fun of(module: TypedModule, imports: Summary): Summary = of(listOf(module), imports)

            /**
             * The summary of [modules] together, given what they import: one
             * fixpoint over all of them, for modules that carry no summary (the
             * stdlib) and whose performers may install each other's.
             */
            fun of(modules: Collection<TypedModule>, imports: Summary): Summary {
                if (modules.isEmpty()) return imports
                val effectNames = imports.effectNames + modules.flatMap { it.effectDecls }.associate { it.hash to it.name }
                val performers = modules.flatMap { it.performerDecls }
                val own = addedBy(performers, effectNames, imports.added)
                return Summary(
                    effectNames,
                    imports.added + own,
                    imports.byName + performers.mapNotNull { p -> own[p.hash]?.let { p.name to it } },
                )
            }
        }
    }

    fun ofModules(modules: Collection<TypedModule>): Map<String, Set<String>> {
        val effectName: Map<Hash, String> = modules.flatMap { it.effectDecls }.associate { it.hash to it.name }
        val performers = modules.flatMap { it.performerDecls }
        val added = addedBy(performers, effectName, emptyMap())
        return performers.mapNotNull { p -> added[p.hash]?.let { p.name to it } }.toMap()
    }

    /**
     * What each of [performers] adds, given what already-summarised performers
     * add ([known]): one fixpoint, since a performer may install another. Only
     * the performers that add something are in the answer.
     */
    private fun addedBy(
        performers: List<com.mezze.language.typedast.TypedPerformerDecl>,
        effectName: Map<Hash, String>,
        known: Map<Hash, Set<String>>,
    ): Map<Hash, Set<String>> {
        val added = HashMap(known)
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
        return performers.mapNotNull { p -> added[p.hash]?.takeIf { it.isNotEmpty() }?.let { p.hash to it } }.toMap()
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
