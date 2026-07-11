package com.mezze.language.typechecker.dependencies

import com.mezze.language.ast.hashing.MezzeHash

/**
 * A reference to a definition, either by hash (resolved) or by name (unresolved).
 *
 * During type checking, we track references by name + module.
 * After hashes are computed, we resolve these to DefinitionRef.ByHash.
 */
sealed class DefinitionRef {
    /**
     * Reference by content hash - fully resolved.
     */
    data class ByHash(val hash: MezzeHash) : DefinitionRef()

    /**
     * Reference by qualified name - needs resolution.
     *
     * @param modulePath Module path (e.g., "std::list") or null for current module
     * @param name Definition name (e.g., "map")
     */
    data class ByName(
        val modulePath: String?,
        val name: String
    ) : DefinitionRef() {
        val qualifiedName: String
            get() = if (modulePath != null) "$modulePath::$name" else name
    }
}

/**
 * Tracks dependencies between definitions.
 *
 * A definition D depends on another definition E if:
 * - D calls E (function call)
 * - D references E's type (type annotation)
 * - D uses E's ability (ability method call)
 * - D performs E's effect (perform expression)
 *
 * The graph is bidirectional:
 * - dependencies: what does D need? (D -> [E1, E2, ...])
 * - dependents: what needs D? (D -> [F1, F2, ...])
 */
data class DependencyGraph(
    /**
     * Forward edges: definition hash -> set of hashes it depends on.
     * "What does this definition need to work?"
     */
    val dependencies: Map<MezzeHash, Set<MezzeHash>> = emptyMap(),

    /**
     * Reverse edges: definition hash -> set of hashes that depend on it.
     * "What breaks if this definition changes?"
     */
    val dependents: Map<MezzeHash, Set<MezzeHash>> = emptyMap()
) {
    /**
     * Get all direct dependencies of a definition.
     */
    fun getDependencies(hash: MezzeHash): Set<MezzeHash> =
        dependencies[hash] ?: emptySet()

    /**
     * Get all direct dependents of a definition.
     */
    fun getDependents(hash: MezzeHash): Set<MezzeHash> =
        dependents[hash] ?: emptySet()

    /**
     * Get all transitive dependencies (recursive).
     */
    fun getTransitiveDependencies(hash: MezzeHash): Set<MezzeHash> {
        val visited = mutableSetOf<MezzeHash>()
        val queue = ArrayDeque(getDependencies(hash))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current !in visited) {
                visited.add(current)
                queue.addAll(getDependencies(current))
            }
        }

        return visited
    }

    /**
     * Get all transitive dependents (recursive).
     */
    fun getTransitiveDependents(hash: MezzeHash): Set<MezzeHash> {
        val visited = mutableSetOf<MezzeHash>()
        val queue = ArrayDeque(getDependents(hash))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current !in visited) {
                visited.add(current)
                queue.addAll(getDependents(current))
            }
        }

        return visited
    }

    /**
     * Check if there's a dependency cycle involving this hash.
     */
    fun hasCycle(hash: MezzeHash): Boolean {
        return hash in getTransitiveDependencies(hash)
    }

    /**
     * Merge another dependency graph into this one.
     */
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

