package com.mezze.language.typechecker.context

import com.mezze.language.ast.*

/**
 * Immutable typing context for type checking.
 *
 * Replaces mutable `context: MutableMap<String, MezzeType>` with immutable,
 * scoped context that is passed down and extended, never mutated.
 *
 * Design principles:
 * - Immutable: all fields are `val`, no mutation
 * - Scoped: supports nested scopes via parent chain
 * - Type-safe: lookup returns sealed type, not nullable
 * - Fast: backed by persistent map for efficient extension
 * - O(1) ability lookup: secondary index by ability name
 */
data class TypingContext(
    private val bindings: Map<String, Binding>,
    private val abilityImpls: Map<String, AbilityImplInfo> = emptyMap(),  // Keyed by content hash
    private val abilityImplsByName: Map<String, List<String>> = emptyMap(),  // Secondary index: abilityName -> [contentHash]
    val parent: TypingContext? = null
) {
    /**
     * Lookup result - sealed type, no nulls!
     */
    sealed class LookupResult {
        data class Found(val binding: Binding) : LookupResult()
        data class NotFound(val name: String) : LookupResult()
    }

    /**
     * A binding in the context - variable name mapped to type scheme
     */
    sealed class Binding {
        /**
         * Monomorphic binding - single type, no quantifiers
         */
        data class Monomorphic(
            val type: MezzeType,
            val sourceModule: String? = null
        ) : Binding()

        /**
         * Polymorphic binding - type scheme with quantified variables
         */
        data class Polymorphic(
            val scheme: TypeScheme,
            val sourceModule: String? = null
        ) : Binding()
    }

    /**
     * Information about an ability implementation.
     *
     * Stored with content hash as the key (e.g., "a1b2c3d4e5f6...").
     * Content hashes are computed in TypeChecker Phase 0.5 before context building.
     */
    data class AbilityImplInfo(
        val abilityName: String,
        val targetTypes: List<String>,  // Type names, e.g., ["String"], ["Int", "String"]
        val sourceModule: String? = null,
        val contentHash: String? = null  // Content hash (same as the map key)
    )

    /**
     * Look up a variable in this context or parent contexts.
     * Returns sealed type - no nulls, exhaustive when required!
     */
    fun lookup(name: String): LookupResult {
        // Check local bindings first
        val localBinding = bindings[name]
        return when {
            localBinding != null -> LookupResult.Found(localBinding)
            parent != null -> parent.lookup(name)
            else -> LookupResult.NotFound(name)
        }
    }

    /**
     * Extend context with a new binding.
     * Returns NEW context, does not mutate!
     */
    fun extend(name: String, binding: Binding): TypingContext {
        return TypingContext(
            bindings = bindings + (name to binding),
            abilityImpls = abilityImpls,  // Preserve ability impls
            abilityImplsByName = abilityImplsByName,  // Preserve secondary index
            parent = parent
        )
    }

    /**
     * Extend context with a monomorphic binding (common case)
     */
    fun extendMono(name: String, type: MezzeType, sourceModule: String? = null): TypingContext {
        return extend(name, Binding.Monomorphic(type, sourceModule))
    }

    /**
     * Extend context with a polymorphic binding
     */
    fun extendPoly(name: String, scheme: TypeScheme, sourceModule: String? = null): TypingContext {
        return extend(name, Binding.Polymorphic(scheme, sourceModule))
    }

    /**
     * Extend context with multiple bindings at once
     */
    fun extendAll(newBindings: Map<String, Binding>): TypingContext {
        return TypingContext(
            bindings = bindings + newBindings,
            abilityImpls = abilityImpls,  // Preserve ability impls
            abilityImplsByName = abilityImplsByName,  // Preserve secondary index
            parent = parent
        )
    }

    /**
     * Extend context with an ability implementation.
     *
     * Key is the content hash of the ImplAbilityDeclaration.
     * Updates both primary (by hash) and secondary (by name) indexes.
     */
    fun extendWithImpl(key: String, implInfo: AbilityImplInfo): TypingContext {
        // Update secondary index: abilityName -> [contentHash]
        val existingHashes = abilityImplsByName[implInfo.abilityName] ?: emptyList()
        val newImplsByName = abilityImplsByName + (implInfo.abilityName to (existingHashes + key))

        return TypingContext(
            bindings = bindings,
            abilityImpls = abilityImpls + (key to implInfo),
            abilityImplsByName = newImplsByName,
            parent = parent
        )
    }

    /**
     * Check if an ability implementation exists by content hash.
     *
     * @param contentHash The content hash of the implementation
     * @return True if implementation exists
     *
     * Checks both local and parent contexts.
     */
    fun hasAbilityImpl(contentHash: String): Boolean {
        return abilityImpls.containsKey(contentHash) || (parent?.hasAbilityImpl(contentHash) ?: false)
    }

    /**
     * Get ability implementation info by content hash.
     *
     * @param contentHash The content hash of the implementation
     * @return The implementation info, or null if not found
     *
     * Checks both local and parent contexts.
     */
    fun getAbilityImpl(contentHash: String): AbilityImplInfo? {
        return abilityImpls[contentHash] ?: parent?.getAbilityImpl(contentHash)
    }

    /**
     * Get all implementations for a given ability name.
     *
     * This is used for multi-parameter ability resolution where we need to find
     * implementations that match a subset of type parameters.
     *
     * Example: For ability "Convert", returns all implementations like:
     * - Convert_Int_String
     * - Convert_String_Int
     * - Convert_Float_String
     *
     * Uses secondary index for O(1) lookup instead of O(N) filter.
     * Checks both local and parent contexts.
     */
    fun getAbilityImpls(abilityName: String): List<AbilityImplInfo> {
        // O(1) lookup via secondary index instead of O(N) filter
        val localHashes = abilityImplsByName[abilityName] ?: emptyList()
        val localImpls = localHashes.mapNotNull { abilityImpls[it] }
        val parentImpls = parent?.getAbilityImpls(abilityName) ?: emptyList()
        return localImpls + parentImpls
    }

    /**
     * Get all ability implementations (for debugging/introspection).
     *
     * Checks both local and parent contexts.
     */
    fun getAllAbilityImpls(): Map<String, AbilityImplInfo> {
        val parentImpls = parent?.getAllAbilityImpls() ?: emptyMap()
        return parentImpls + abilityImpls  // Local impls override parent impls
    }

    /**
     * Create a nested scope - new context with this as parent
     */
    fun enterScope(): TypingContext {
        return TypingContext(
            bindings = emptyMap(),
            abilityImpls = emptyMap(),  // Start with empty impls (inherit via parent)
            abilityImplsByName = emptyMap(),  // Start with empty secondary index (inherit via parent)
            parent = this
        )
    }

    /**
     * Check if a variable is bound locally (not in parent)
     */
    fun hasLocal(name: String): Boolean = bindings.containsKey(name)

    /**
     * Get all local bindings (for debugging/error messages)
     */
    fun localNames(): Set<String> = bindings.keys

    /**
     * Get all bindings including parent contexts (for debugging)
     */
    fun allNames(): Set<String> {
        val parentNames = parent?.allNames() ?: emptySet()
        return bindings.keys + parentNames
    }

    /**
     * Get all bindings including parent contexts (for error messages with suggestions)
     */
    fun getAllBindings(): Map<String, Binding> {
        val parentBindings = parent?.getAllBindings() ?: emptyMap()
        return parentBindings + bindings  // Local bindings override parent bindings
    }

    companion object {
        /**
         * Empty context - starting point
         */
        val empty = TypingContext(emptyMap(), emptyMap(), emptyMap(), null)

        /**
         * Create context from map (for testing)
         */
        fun from(bindings: Map<String, MezzeType>): TypingContext {
            return TypingContext(
                bindings = bindings.mapValues { (_, type) ->
                    Binding.Monomorphic(type)
                },
                abilityImpls = emptyMap(),
                abilityImplsByName = emptyMap()
            )
        }
    }
}
