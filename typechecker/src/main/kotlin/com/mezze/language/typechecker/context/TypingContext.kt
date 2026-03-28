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
    private val bindings: Map<BindingKey, Binding>,
    private val abilityImpls: Map<String, AbilityImplInfo> = emptyMap(),  // Keyed by content hash
    private val abilityImplsByName: Map<String?, List<String>> = emptyMap(),  // Secondary index: abilityName -> [contentHash] (null key = inherent impls)
    private val abilityImplsByAbilityHash: Map<String, List<String>> = emptyMap(),  // Secondary index: abilityDeclHash -> [implContentHash]
    // Dedicated maps for ability/effect operation types (replaces "${name}_ability" pattern)
    private val abilityOperationTypes: Map<String, MezzeType> = emptyMap(),  // abilityName -> RecordType of operations
    private val effectOperationTypes: Map<String, MezzeType> = emptyMap(),   // effectName -> RecordType of operations
    // Dedicated map for performer associated types (replaces "${name}_performer" pattern)
    // Keyed by content hash for cross-module safety
    private val performerAssociatedTypes: Map<String, PerformerTypeInfo> = emptyMap(),  // contentHash -> associated types
    private val performerAssociatedTypesByName: Map<String, List<String>> = emptyMap(),  // performerName -> [contentHash] index
    // Anonymous impl methods: "TypeName::methodName" -> method info
    private val anonymousImplMethods: Map<String, InherentMethodInfo> = emptyMap(),
    // Secondary index for anonymous methods: "TypeName" -> [methodName]
    private val anonymousImplMethodsByType: Map<String, List<String>> = emptyMap(),
    val parent: TypingContext? = null
) {
    /**
     * Lookup result - sealed type, no nulls!
     */
    sealed class LookupResult {
        data class Found(val binding: Binding) : LookupResult()
        data class NotFound(val key: BindingKey) : LookupResult() {
            /** Display name for error messages */
            val displayName: String get() = if (key.qualifier != null) "${key.qualifier}.${key.localName}" else key.localName
        }
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
            val sourceModule: String? = null,
            val contentHash: String? = null
        ) : Binding()

        /**
         * Polymorphic binding - type scheme with quantified variables
         */
        data class Polymorphic(
            val scheme: TypeScheme,
            val sourceModule: String? = null,
            val contentHash: String? = null
        ) : Binding()
    }

    /**
     * Information about an ability implementation.
     *
     * Stored with content hash as the key (e.g., "a1b2c3d4e5f6...").
     * Content hashes are computed in TypeChecker Phase 0.5 before context building.
     */
    data class AbilityImplInfo(
        val abilityName: String?,  // null for inherent impls — for error messages and fallback
        val abilityDeclHash: String? = null,  // Content hash of the ability DECLARATION (not the impl)
        val targetTypes: List<String>,  // Type names for string-based matching, e.g., ["String"], ["Maybe_Int"]
        val targetTypeAnnotations: List<TypeAnnotation>? = null,  // Original annotations for structural matching
        val associatedTypeBindings: Map<String, TypeAnnotation> = emptyMap(),  // Associated type bindings like 'item = Int
        val sourceModule: String? = null,
        val contentHash: String,  // Content hash of the IMPL (same as the map key)
        val typeVars: Set<String> = emptySet(),  // Type variables from generic impl header, e.g., {"'a"} for `impl Eq 'a for ['a]`
        val operationConstraints: List<FunctionAbilityConstraint> = emptyList()  // Method-level constraints from impl operations
    )

    /**
     * Information about a performer's associated types.
     *
     * Stored with content hash as the key for cross-module safety.
     * Example: For `impl State for Counter having 'state = Int`:
     * - performerName = "Counter"
     * - effectName = "State"
     * - associatedTypes = RecordType({'state -> Int})
     */
    data class PerformerTypeInfo(
        val performerName: String,
        val effectName: String,
        val associatedTypes: MezzeType,  // RecordType with associated type bindings
        val performerFields: MezzeType? = null,  // RecordType with performer parameter types (e.g., {cont: Continuation 'a 'b 'c})
        val suspensionType: MezzeType? = null,  // Type passed to suspend_with in the handler (for continuation inference)
        val contentHash: String? = null
    )

    /**
     * Information about a method from an anonymous impl (`impl Type where { ... }`).
     */
    data class InherentMethodInfo(
        val targetTypeName: String,     // e.g., "Int"
        val methodName: String,         // e.g., "to_float"
        val methodType: MezzeType,      // The function type of the method (without receiver)
        val implContentHash: String,    // Content hash of the impl declaration
        val sourceModule: String? = null
    )

    /**
     * Look up a variable in this context or parent contexts using a BindingKey.
     * Returns sealed type - no nulls, exhaustive when required!
     */
    fun lookup(key: BindingKey): LookupResult {
        // Check local bindings first
        val localBinding = bindings[key]
        return when {
            localBinding != null -> LookupResult.Found(localBinding)
            parent != null -> parent.lookup(key)
            else -> LookupResult.NotFound(key)
        }
    }

    /**
     * Look up by simple name (unqualified).
     * Convenience method for local variable lookups.
     */
    fun lookup(name: String): LookupResult = lookup(BindingKey.unqualified(name))

    /**
     * Extend context with a new binding using a BindingKey.
     * Returns NEW context, does not mutate!
     */
    fun extend(key: BindingKey, binding: Binding): TypingContext {
        return copy(bindings = bindings + (key to binding))
    }

    /**
     * Extend context with a new binding by simple name (unqualified).
     * Convenience method for local variable bindings.
     */
    fun extend(name: String, binding: Binding): TypingContext {
        return extend(BindingKey.unqualified(name), binding)
    }

    /**
     * Extend context with a monomorphic binding using a BindingKey.
     */
    fun extendMono(key: BindingKey, type: MezzeType, sourceModule: String? = null, contentHash: String? = null): TypingContext {
        return extend(key, Binding.Monomorphic(type, sourceModule, contentHash))
    }

    /**
     * Extend context with a monomorphic binding by simple name (unqualified).
     * Convenience method for local variable bindings.
     */
    fun extendMono(name: String, type: MezzeType, sourceModule: String? = null, contentHash: String? = null): TypingContext {
        return extendMono(BindingKey.unqualified(name), type, sourceModule, contentHash)
    }

    /**
     * Extend context with a polymorphic binding using a BindingKey.
     */
    fun extendPoly(key: BindingKey, scheme: TypeScheme, sourceModule: String? = null, contentHash: String? = null): TypingContext {
        return extend(key, Binding.Polymorphic(scheme, sourceModule, contentHash))
    }

    /**
     * Extend context with a polymorphic binding by simple name (unqualified).
     * Convenience method for local variable bindings.
     */
    fun extendPoly(name: String, scheme: TypeScheme, sourceModule: String? = null, contentHash: String? = null): TypingContext {
        return extendPoly(BindingKey.unqualified(name), scheme, sourceModule, contentHash)
    }

    /**
     * Extend context with multiple bindings at once using BindingKeys.
     */
    fun extendAll(newBindings: Map<BindingKey, Binding>): TypingContext {
        return copy(bindings = bindings + newBindings)
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

        // Update hash-based secondary index: abilityDeclHash -> [implContentHash]
        val newImplsByAbilityHash = if (implInfo.abilityDeclHash != null) {
            val existingByHash = abilityImplsByAbilityHash[implInfo.abilityDeclHash] ?: emptyList()
            abilityImplsByAbilityHash + (implInfo.abilityDeclHash to (existingByHash + key))
        } else {
            abilityImplsByAbilityHash
        }

        return copy(
            abilityImpls = abilityImpls + (key to implInfo),
            abilityImplsByName = newImplsByName,
            abilityImplsByAbilityHash = newImplsByAbilityHash
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
     * Get all implementations for a given ability by its declaration hash.
     * Preferred over name-based lookup — enables renaming via `use X as Y`.
     */
    fun getAbilityImplsByDeclHash(abilityDeclHash: String): List<AbilityImplInfo> {
        val localHashes = abilityImplsByAbilityHash[abilityDeclHash] ?: emptyList()
        val localImpls = localHashes.mapNotNull { abilityImpls[it] }
        val parentImpls = parent?.getAbilityImplsByDeclHash(abilityDeclHash) ?: emptyList()
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

    // ========================================================================
    // ABILITY/EFFECT OPERATION TYPES
    // ========================================================================

    /**
     * Get ability operation types (the RecordType of its operations).
     * Used for qualified access like Show.display.
     */
    fun getAbilityOperationType(abilityName: String): MezzeType? {
        return abilityOperationTypes[abilityName] ?: parent?.getAbilityOperationType(abilityName)
    }

    /**
     * Get effect operation types (the RecordType of its operations).
     * Used for qualified access like Log.write.
     */
    fun getEffectOperationType(effectName: String): MezzeType? {
        return effectOperationTypes[effectName] ?: parent?.getEffectOperationType(effectName)
    }

    /**
     * Extend context with ability operation types.
     */
    fun extendWithAbilityOperationType(abilityName: String, operationType: MezzeType): TypingContext {
        return copy(abilityOperationTypes = abilityOperationTypes + (abilityName to operationType))
    }

    /**
     * Find abilities that have a method with the given name.
     * Returns list of (abilityName, methodType) pairs.
     * Used for method call resolution: `value.method { args }`.
     */
    fun findAbilitiesByMethodName(methodName: String): List<Pair<String, MezzeType>> {
        val results = mutableListOf<Pair<String, MezzeType>>()
        for ((abilityName, operationType) in abilityOperationTypes) {
            if (operationType is MezzeType.RecordType) {
                val methodType = operationType.fields[methodName]
                if (methodType != null) {
                    results.add(abilityName to methodType)
                }
            }
        }
        parent?.findAbilitiesByMethodName(methodName)?.let { results.addAll(it) }
        return results.distinctBy { it.first }
    }

    /**
     * Extend context with effect operation types.
     */
    fun extendWithEffectOperationType(effectName: String, operationType: MezzeType): TypingContext {
        return copy(effectOperationTypes = effectOperationTypes + (effectName to operationType))
    }

    // ========================================================================
    // ANONYMOUS IMPL METHODS
    // ========================================================================

    /**
     * Find an anonymous impl method for a given type and method name.
     * Used for `value.method { args }` resolution when no ability method matches.
     */
    fun findAnonymousMethod(typeName: String, methodName: String): InherentMethodInfo? {
        val key = "$typeName::$methodName"
        return anonymousImplMethods[key] ?: parent?.findAnonymousMethod(typeName, methodName)
    }

    /**
     * Find all anonymous impl method names for a given type.
     */
    fun getAnonymousMethodNames(typeName: String): List<String> {
        val local = anonymousImplMethodsByType[typeName] ?: emptyList()
        val parentMethods = parent?.getAnonymousMethodNames(typeName) ?: emptyList()
        return (local + parentMethods).distinct()
    }

    /**
     * Find anonymous methods by method name (across all types).
     * Returns list of (typeName, methodInfo) pairs.
     * Used when receiver type is not yet known — matched by unification.
     */
    fun findAnonymousMethodsByName(methodName: String): List<InherentMethodInfo> {
        val results = mutableListOf<InherentMethodInfo>()
        for ((key, info) in anonymousImplMethods) {
            if (info.methodName == methodName) {
                results.add(info)
            }
        }
        parent?.findAnonymousMethodsByName(methodName)?.let { results.addAll(it) }
        return results
    }

    /**
     * Extend context with an anonymous impl method.
     */
    fun extendWithAnonymousMethod(info: InherentMethodInfo): TypingContext {
        val key = "${info.targetTypeName}::${info.methodName}"
        val existingMethods = anonymousImplMethodsByType[info.targetTypeName] ?: emptyList()
        return copy(
            anonymousImplMethods = anonymousImplMethods + (key to info),
            anonymousImplMethodsByType = anonymousImplMethodsByType + (info.targetTypeName to (existingMethods + info.methodName))
        )
    }

    // ========================================================================
    // PERFORMER ASSOCIATED TYPES
    // ========================================================================

    /**
     * Get performer associated types by content hash.
     */
    fun getPerformerTypeInfo(contentHash: String): PerformerTypeInfo? {
        return performerAssociatedTypes[contentHash] ?: parent?.getPerformerTypeInfo(contentHash)
    }

    /**
     * Get performer associated types by performer name.
     * Returns all matching infos (for cross-module scenarios).
     */
    fun getPerformerTypeInfosByName(performerName: String): List<PerformerTypeInfo> {
        val localHashes = performerAssociatedTypesByName[performerName] ?: emptyList()
        val localInfos = localHashes.mapNotNull { performerAssociatedTypes[it] }
        val parentInfos = parent?.getPerformerTypeInfosByName(performerName) ?: emptyList()
        return localInfos + parentInfos
    }

    /**
     * Extend context with performer associated types.
     */
    fun extendWithPerformerTypeInfo(contentHash: String, info: PerformerTypeInfo): TypingContext {
        val existingHashes = performerAssociatedTypesByName[info.performerName] ?: emptyList()
        return copy(
            performerAssociatedTypes = performerAssociatedTypes + (contentHash to info),
            performerAssociatedTypesByName = performerAssociatedTypesByName + (info.performerName to (existingHashes + contentHash))
        )
    }

    /**
     * Create a nested scope - new context with this as parent
     */
    fun enterScope(): TypingContext {
        return TypingContext(
            bindings = emptyMap(),
            abilityImpls = emptyMap(),  // Start with empty impls (inherit via parent)
            abilityImplsByName = emptyMap(),
            abilityImplsByAbilityHash = emptyMap(),
            abilityOperationTypes = emptyMap(),
            effectOperationTypes = emptyMap(),
            performerAssociatedTypes = emptyMap(),
            performerAssociatedTypesByName = emptyMap(),
            parent = this
        )
    }

    /**
     * Check if a binding key is bound locally (not in parent)
     */
    fun hasLocal(key: BindingKey): Boolean = bindings.containsKey(key)

    /**
     * Check if a simple name is bound locally (unqualified lookup)
     */
    fun hasLocal(name: String): Boolean = hasLocal(BindingKey.unqualified(name))

    /**
     * Get all local binding keys (for debugging/error messages)
     */
    fun localKeys(): Set<BindingKey> = bindings.keys

    /**
     * Get all local binding display names (for debugging/error messages)
     */
    fun localNames(): Set<String> = bindings.keys.map {
        if (it.qualifier != null) "${it.qualifier}.${it.localName}" else it.localName
    }.toSet()

    /**
     * Get all binding keys including parent contexts (for debugging)
     */
    fun allKeys(): Set<BindingKey> {
        val parentKeys = parent?.allKeys() ?: emptySet()
        return bindings.keys + parentKeys
    }

    /**
     * Get all binding names including parent contexts (for debugging)
     */
    fun allNames(): Set<String> = allKeys().map {
        if (it.qualifier != null) "${it.qualifier}.${it.localName}" else it.localName
    }.toSet()

    /**
     * Get all bindings including parent contexts (for error messages with suggestions)
     */
    fun getAllBindings(): Map<BindingKey, Binding> {
        val parentBindings = parent?.getAllBindings() ?: emptyMap()
        return parentBindings + bindings  // Local bindings override parent bindings
    }

    /**
     * Get content hashes for all bindings (for identifier resolution).
     * Returns a map of binding key to content hash (only for bindings that have hashes).
     */
    fun getContentHashes(): Map<BindingKey, String> {
        val result = mutableMapOf<BindingKey, String>()
        for ((key, binding) in getAllBindings()) {
            val hash = when (binding) {
                is Binding.Monomorphic -> binding.contentHash
                is Binding.Polymorphic -> binding.contentHash
            }
            if (hash != null) {
                result[key] = hash
            }
        }
        return result
    }

    companion object {
        /**
         * Empty context - starting point
         */
        val empty = TypingContext(
            bindings = emptyMap(),
            abilityImpls = emptyMap(),
            abilityImplsByName = emptyMap(),
            abilityImplsByAbilityHash = emptyMap(),
            abilityOperationTypes = emptyMap(),
            effectOperationTypes = emptyMap(),
            performerAssociatedTypes = emptyMap(),
            performerAssociatedTypesByName = emptyMap(),
            parent = null
        )

        /**
         * Create context from map with BindingKeys.
         */
        fun fromBindingKeys(bindings: Map<BindingKey, MezzeType>): TypingContext {
            return TypingContext(
                bindings = bindings.mapValues { (_, type) ->
                    Binding.Monomorphic(type)
                },
                abilityImpls = emptyMap(),
                abilityImplsByName = emptyMap(),
                abilityImplsByAbilityHash = emptyMap()
            )
        }

        /**
         * Create context from map with simple names (for testing - unqualified bindings only).
         */
        fun from(bindings: Map<String, MezzeType>): TypingContext {
            return TypingContext(
                bindings = bindings.map { (name, type) ->
                    BindingKey.unqualified(name) to Binding.Monomorphic(type)
                }.toMap(),
                abilityImpls = emptyMap(),
                abilityImplsByName = emptyMap(),
                abilityImplsByAbilityHash = emptyMap()
            )
        }
    }
}
