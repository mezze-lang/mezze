package com.mezze.language.typechecker.dependencies

import com.mezze.language.ast.hashing.MezzeHash

sealed class DefinitionRef {
        data class ByHash(val hash: MezzeHash) : DefinitionRef()

        data class ByName(
        val modulePath: String?,
        val name: String
    ) : DefinitionRef() {
        val qualifiedName: String
            get() = if (modulePath != null) "$modulePath::$name" else name
    }
}

data class DependencyGraph(
        val dependencies: Map<MezzeHash, Set<MezzeHash>> = emptyMap(),

        val dependents: Map<MezzeHash, Set<MezzeHash>> = emptyMap()
) {
        fun getDependents(hash: MezzeHash): Set<MezzeHash> =
        dependents[hash] ?: emptySet()

        fun merge(other: DependencyGraph): DependencyGraph {
        val mergedDependencies = dependencies.toMutableMap()
        val mergedDependents = dependents.toMutableMap()

        for ((hash, deps) in other.dependencies) {
            mergedDependencies[hash] = (mergedDependencies[hash] ?: emptySet()) + deps
        }

        for ((hash, deps) in other.dependents) {
            mergedDependents[hash] = (mergedDependents[hash] ?: emptySet()) + deps
        }

        return DependencyGraph(mergedDependencies, mergedDependents)
    }

    companion object {
        val EMPTY = DependencyGraph()
    }
}

