package com.mezze.language.typechecker.dependencies

import com.mezze.language.ast.hashing.MezzeHash

/**
 * Tracks dependencies during type checking.
 *
 * This class collects dependency information as the type checker processes
 * declarations. After type checking completes and hashes are computed,
 * the unresolved (name-based) dependencies are resolved to hashes.
 *
 * ## Usage Pattern:
 * ```kotlin
 * val tracker = DependencyTracker()
 *
 * // During constraint generation for "foo":
 * tracker.startTracking("foo")
 * // ... when we see a reference to "bar":
 * tracker.recordReference("bar", modulePath = null)
 * // ... when we see a reference to "std::list::map":
 * tracker.recordReference("map", modulePath = "std::list")
 * tracker.finishTracking()
 *
 * // After hashes are computed:
 * val graph = tracker.resolve(nameToHash = { name, module -> ... })
 * ```
 */
class DependencyTracker {
    // Current binding being tracked (if any)
    private var currentBinding: String? = null

    // References collected for the current binding
    private val currentReferences = mutableSetOf<DefinitionRef.ByName>()

    // All unresolved dependencies collected so far
    private val allReferences = mutableMapOf<String, MutableSet<DefinitionRef.ByName>>()

    // Resolved dependency graph (populated after resolve() is called)
    private var resolvedGraph: DependencyGraph? = null

    /**
     * Start tracking dependencies for a binding.
     *
     * @param bindingName The name of the binding being processed
     */
    fun startTracking(bindingName: String) {
        require(currentBinding == null) {
            "Already tracking binding '$currentBinding', cannot start tracking '$bindingName'"
        }
        currentBinding = bindingName
        currentReferences.clear()
    }

    /**
     * Record a reference to another definition.
     *
     * @param name The name of the referenced definition
     * @param modulePath The module path, or null for current module
     */
    fun recordReference(name: String, modulePath: String? = null) {
        currentReferences.add(DefinitionRef.ByName(modulePath, name))
    }

    /**
     * Record a reference by qualified name (e.g., "std::list::map").
     */
    fun recordQualifiedReference(qualifiedName: String) {
        val parts = qualifiedName.split("::")
        if (parts.size == 1) {
            // Simple name, no module path
            recordReference(parts[0], null)
        } else {
            // Module path + name
            val modulePath = parts.dropLast(1).joinToString("::")
            val name = parts.last()
            recordReference(name, modulePath)
        }
    }

    /**
     * Finish tracking the current binding.
     */
    fun finishTracking() {
        val binding = currentBinding ?: return

        // Store the references for this binding
        allReferences.getOrPut(binding) { mutableSetOf() }.addAll(currentReferences)

        // Reset state
        currentBinding = null
        currentReferences.clear()
    }

    /**
     * Get the unresolved dependencies collected so far.
     */
    fun getUnresolvedDependencies(): UnresolvedDependencies {
        return UnresolvedDependencies(
            references = allReferences.mapValues { it.value.toSet() }
        )
    }

    /**
     * Resolve name-based dependencies to hash-based dependencies.
     *
     * @param nameToHash Function that resolves a (name, modulePath) pair to a hash.
     *                   Returns null if the definition is not found or has no hash.
     * @param bindingToHash Function that resolves a binding name to its hash.
     *                      Returns null if the binding has no hash.
     * @return The resolved DependencyGraph
     */
    fun resolve(
        nameToHash: (name: String, modulePath: String?) -> MezzeHash?,
        bindingToHash: (bindingName: String) -> MezzeHash?
    ): DependencyGraph {
        val builder = DependencyGraphBuilder()

        for ((bindingName, refs) in allReferences) {
            val fromHash = bindingToHash(bindingName) ?: continue

            for (ref in refs) {
                val toHash = nameToHash(ref.name, ref.modulePath)
                if (toHash != null) {
                    builder.addDependency(fromHash, toHash)
                }
                // If toHash is null, the reference is to something without a hash
                // (e.g., a local variable, a builtin, or an unresolved name)
            }
        }

        resolvedGraph = builder.build()
        return resolvedGraph!!
    }

    /**
     * Get the resolved dependency graph.
     * Throws if resolve() hasn't been called yet.
     */
    fun getResolvedGraph(): DependencyGraph {
        return resolvedGraph
            ?: throw IllegalStateException("Dependencies not resolved yet. Call resolve() first.")
    }

    /**
     * Check if dependencies have been resolved.
     */
    fun isResolved(): Boolean = resolvedGraph != null

    /**
     * Clear all tracked data.
     */
    fun clear() {
        currentBinding = null
        currentReferences.clear()
        allReferences.clear()
        resolvedGraph = null
    }

    /**
     * Merge another tracker's data into this one.
     */
    fun merge(other: DependencyTracker) {
        for ((binding, refs) in other.allReferences) {
            allReferences.getOrPut(binding) { mutableSetOf() }.addAll(refs)
        }
    }
}
