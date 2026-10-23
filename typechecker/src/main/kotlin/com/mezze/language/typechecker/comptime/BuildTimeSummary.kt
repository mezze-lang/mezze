package com.mezze.language.typechecker.comptime

import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import kotlinx.serialization.Serializable

/**
 * What a module, and everything it imports, contributes to deciding what runs at
 * build time: its compile-time performers, those that leave `Comptime` to an
 * outer handler, and its functions that ask a question nothing answers inside.
 *
 * Computed once, when the module is checked, on top of its imports' summaries —
 * so it is transitive — and kept with the module. An importer unions its
 * imports' summaries instead of scanning their bodies: that scan read every
 * imported body on every check, and was repeated by every importer.
 */
@Serializable
data class BuildTimeSummary(
    val compileTime: Set<Hash>,
    val leaves: Set<Hash>,
    val asking: Set<Hash>,
) {
    companion object {
        val EMPTY = BuildTimeSummary(emptySet(), emptySet(), emptySet())

        fun union(summaries: Collection<BuildTimeSummary>): BuildTimeSummary =
            if (summaries.size == 1) summaries.first()
            else BuildTimeSummary(
                summaries.flatMapTo(HashSet()) { it.compileTime },
                summaries.flatMapTo(HashSet()) { it.leaves },
                summaries.flatMapTo(HashSet()) { it.asking },
            )

        /** [module]'s summary, given its imports': theirs plus what [module] adds. */
        fun of(module: TypedModule, imports: BuildTimeSummary): BuildTimeSummary {
            val found = CompileTimePerformers.ofPerformers(
                module.performerDecls, CompileTimePerformers.Found(imports.compileTime, imports.leaves),
            )
            val compileTime = imports.compileTime + found.compileTime
            val leaves = imports.leaves + found.leavesComptime
            val asking = BuildTimeUnits.askingFunctions(
                module.decls.filterIsInstance<TypedLetDecl>(),
                BuildTimeUnits.Handlers(compileTime, leaves),
                imports.asking,
            )
            return BuildTimeSummary(compileTime, leaves, imports.asking + asking)
        }
    }
}
