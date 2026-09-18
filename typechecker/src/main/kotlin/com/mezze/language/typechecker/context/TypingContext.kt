package com.mezze.language.typechecker.context

import com.mezze.language.ast.BindingKey
import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.ParsedFnAbilityConstraint
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedTypeAnnotation
import com.mezze.language.typedast.Hash

data class TypingContext(
    private val bindings: Map<BindingKey, Binding>,
    private val abilityImpls: Map<Hash, AbilityImplInfo> = emptyMap(),
    private val abilityImplsByName: Map<String?, List<Hash>> = emptyMap(),
    private val abilityImplsByAbilityHash: Map<Hash, List<Hash>> = emptyMap(),
    private val abilityOperationTypes: Map<String, MezzeType> = emptyMap(),  // abilityName -> RecordType of operations
    private val abilityOperationTypesByHash: Map<Hash, MezzeType> = emptyMap(),  // abilityDeclHash -> RecordType of operations
    private val effectOperationTypes: Map<String, MezzeType> = emptyMap(),   // effectName -> RecordType of operations
    private val effectOperationTypesByHash: Map<Hash, MezzeType> = emptyMap(),   // effectDeclHash -> RecordType of operations
    private val performerAssociatedTypes: Map<Hash, PerformerTypeInfo> = emptyMap(),  // contentHash -> associated types
    private val performerAssociatedTypesByName: Map<String, List<Hash>> = emptyMap(),  // performerName -> [contentHash] index
    private val anonymousImplMethods: Map<String, InherentMethodInfo> = emptyMap(),
    private val anonymousImplMethodsByType: Map<String, List<String>> = emptyMap(),
    private val anonymousImplMethodsByHash: Map<Hash, InherentMethodInfo> = emptyMap(),
    val parent: TypingContext? = null
) {
    sealed class LookupResult {
        data class Found(val binding: Binding) : LookupResult()
        data class NotFound(val key: BindingKey) : LookupResult() {
            val displayName: String get() = if (key.qualifier != null) "${key.qualifier}.${key.localName}" else key.localName
        }
    }

    sealed class Binding {
        data class Monomorphic(
            val type: MezzeType,
            val sourceModule: String? = null,
            val contentHash: Hash? = null,
        ) : Binding()

        data class Polymorphic(
            val scheme: TypeScheme,
            val sourceModule: String? = null,
            val contentHash: Hash? = null
        ) : Binding()
    }

    data class AbilityImplInfo(
        val abilityName: String?,  // null for inherent impls — for error messages and fallback
        val abilityDeclHash: Hash? = null,
        val targetTypes: List<String>,
        val targetTypeAnnotations: List<ParsedTypeAnnotation>? = null,
        val associatedTypeBindings: Map<String, ParsedTypeAnnotation> = emptyMap(),
        val sourceModule: String? = null,
        val contentHash: Hash,
        val typeVars: Set<String> = emptySet(),
        val operationConstraints: List<ParsedFnAbilityConstraint> = emptyList(),
        /**
         * True when the COMPILER wrote this instance, false when the author did.
         *
         * Carried because downstream has to answer "whose instance is this" and
         * cannot re-derive it: a hash container's structural bucketing already IS
         * the derived equality, so the element's own `hash`/`eq` need threading to
         * it only when the author wrote them.
         */
        val derived: Boolean = false
    )

    data class PerformerTypeInfo(
        val performerName: String,
        val effectName: String,
        val associatedTypes: MezzeType,
        val performerFields: MezzeType? = null,
        val suspensionType: MezzeType? = null,
        val contentHash: Hash? = null
    )

    data class InherentMethodInfo(
        val targetTypeName: String,     // e.g., "Int" — for error messages and fallback
        val methodName: String,         // e.g., "to_float"
        val methodType: MezzeType,
        val implContentHash: Hash,
        val sourceModule: String? = null,
        val targetTypeHash: Hash? = null,
        val implStartLine: Int = 0,
        val implStartCol: Int = 0,
        val implSourceFile: String = "",
        val isImported: Boolean = false
    )

    fun lookup(key: BindingKey): LookupResult {
        val localBinding = bindings[key]
        return when {
            localBinding != null -> LookupResult.Found(localBinding)
            parent != null -> parent.lookup(key)
            else -> LookupResult.NotFound(key)
        }
    }

    fun lookup(name: String): LookupResult = lookup(BindingKey.unqualified(name))

    fun extend(key: BindingKey, binding: Binding): TypingContext {
        return copy(bindings = bindings + (key to binding))
    }

    fun extend(name: String, binding: Binding): TypingContext {
        return extend(BindingKey.unqualified(name), binding)
    }

    fun extendMono(key: BindingKey, type: MezzeType, sourceModule: String? = null, contentHash: Hash? = null): TypingContext {
        return extend(key, Binding.Monomorphic(type, sourceModule, contentHash))
    }

    fun extendMono(name: String, type: MezzeType, sourceModule: String? = null, contentHash: Hash? = null): TypingContext {
        return extendMono(BindingKey.unqualified(name), type, sourceModule, contentHash)
    }

    fun extendPoly(key: BindingKey, scheme: TypeScheme, sourceModule: String? = null, contentHash: Hash? = null): TypingContext {
        return extend(key, Binding.Polymorphic(scheme, sourceModule, contentHash))
    }

    fun extendPoly(name: String, scheme: TypeScheme, sourceModule: String? = null, contentHash: Hash? = null): TypingContext {
        return extendPoly(BindingKey.unqualified(name), scheme, sourceModule, contentHash)
    }

    fun extendAll(newBindings: Map<BindingKey, Binding>): TypingContext {
        return copy(bindings = bindings + newBindings)
    }

    /**
     * #224/asymmetry: union extra operation constraints into an already-registered
     * ability impl, keyed by its content hash. Used to fold body-inferred
     * preconditions (`impl Ord 'a for Wrap 'a where { lt = {…} -> it.v < r.v }`
     * infers `with Ord 'a`) into the impl AFTER the context was frozen in
     * ContextBuilder — an impl operation is just a function, so its body's
     * residual ability obligations on the impl's type params must become
     * preconditions of the impl, exactly as a top-level fn records them in its
     * signature. Only the `AbilityImplInfo` value is replaced; the name/hash
     * indices are untouched (the key already exists), so this can't duplicate
     * the impl. A no-op when `extra` is empty or the impl isn't local.
     */
    fun withUpdatedImplConstraints(key: Hash, extra: List<ParsedFnAbilityConstraint>): TypingContext {
        if (extra.isEmpty()) return this
        val existing = abilityImpls[key] ?: return this
        val merged = mergeOperationConstraints(existing.operationConstraints, extra)
        if (merged.size == existing.operationConstraints.size) return this
        return copy(abilityImpls = abilityImpls + (key to existing.copy(operationConstraints = merged)))
    }

    private fun mergeOperationConstraints(
        explicit: List<ParsedFnAbilityConstraint>,
        inferred: List<ParsedFnAbilityConstraint>
    ): List<ParsedFnAbilityConstraint> {
        // Key by (abilityName, tick-normalized type-arg names). Inference only ADDS
        // requirements; an explicit `with` is never dropped, and an inferred one
        // equal to an explicit one is deduped (the recursive check is idempotent
        // anyway, so a missed dedup is at worst redundant, never unsound).
        fun key(c: ParsedFnAbilityConstraint): String {
            val args = c.typeArgs.joinToString(",") { arg ->
                (arg as? ParsedNamedType)?.name?.removePrefix("'") ?: arg.toString()
            }
            return "${c.abilityName}(${args})"
        }
        val seen = explicit.map { key(it) }.toMutableSet()
        val result = explicit.toMutableList()
        for (c in inferred) {
            if (seen.add(key(c))) result.add(c)
        }
        return result
    }

    fun extendWithImpl(key: Hash, implInfo: AbilityImplInfo): TypingContext {
        val existingHashes = abilityImplsByName[implInfo.abilityName] ?: emptyList()
        val newImplsByName = abilityImplsByName + (implInfo.abilityName to (existingHashes + key))

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

    fun getAbilityImpl(contentHash: Hash): AbilityImplInfo? {
        return abilityImpls[contentHash] ?: parent?.getAbilityImpl(contentHash)
    }

    fun getAbilityImpls(abilityName: String): List<AbilityImplInfo> {
        val localHashes = abilityImplsByName[abilityName] ?: emptyList()
        val localImpls = localHashes.mapNotNull { abilityImpls[it] }
        val parentImpls = parent?.getAbilityImpls(abilityName) ?: emptyList()
        return localImpls + parentImpls
    }

    fun getAbilityImplsByDeclHash(abilityDeclHash: Hash): List<AbilityImplInfo> {
        val localHashes = abilityImplsByAbilityHash[abilityDeclHash] ?: emptyList()
        val localImpls = localHashes.mapNotNull { abilityImpls[it] }
        val parentImpls = parent?.getAbilityImplsByDeclHash(abilityDeclHash) ?: emptyList()
        return localImpls + parentImpls
    }

    fun getAllAbilityImpls(): Map<Hash, AbilityImplInfo> {
        val parentImpls = parent?.getAllAbilityImpls() ?: emptyMap()
        return parentImpls + abilityImpls
    }

    fun getAbilityOperationType(abilityName: String): MezzeType? =
        abilityOperationTypes[abilityName] ?: parent?.getAbilityOperationType(abilityName)

    fun getAbilityOperationTypeByHash(abilityDeclHash: Hash): MezzeType? =
        abilityOperationTypesByHash[abilityDeclHash] ?: parent?.getAbilityOperationTypeByHash(abilityDeclHash)

    fun getEffectOperationType(effectName: String): MezzeType? =
        effectOperationTypes[effectName] ?: parent?.getEffectOperationType(effectName)

    fun extendWithAbilityOperationType(abilityName: String, operationType: MezzeType, abilityDeclHash: Hash? = null): TypingContext {
        val newHashIndex = if (abilityDeclHash != null) {
            abilityOperationTypesByHash + (abilityDeclHash to operationType)
        } else {
            abilityOperationTypesByHash
        }
        return copy(
            abilityOperationTypes = abilityOperationTypes + (abilityName to operationType),
            abilityOperationTypesByHash = newHashIndex
        )
    }

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

    fun extendWithEffectOperationType(effectName: String, operationType: MezzeType, effectDeclHash: Hash? = null): TypingContext {
        val newHashIndex = if (effectDeclHash != null) {
            effectOperationTypesByHash + (effectDeclHash to operationType)
        } else {
            effectOperationTypesByHash
        }
        return copy(
            effectOperationTypes = effectOperationTypes + (effectName to operationType),
            effectOperationTypesByHash = newHashIndex
        )
    }

    fun findAnonymousMethod(typeName: String, methodName: String): InherentMethodInfo? {
        // Direct plain-name key (builtins / hashless), then fall back to a by-name
        // scan — entries whose target type has a content hash are stored under a
        // hash-qualified key (see extendWithAnonymousMethod), so the plain key
        // misses them. The scan matches on the info's targetTypeName + methodName.
        anonymousImplMethods["$typeName::$methodName"]?.let { return it }
        anonymousImplMethods.values.firstOrNull {
            it.targetTypeName == typeName && it.methodName == methodName
        }?.let { return it }
        return parent?.findAnonymousMethod(typeName, methodName)
    }

    fun findAnonymousMethodByHash(typeHash: Hash, methodName: String): InherentMethodInfo? {
        val key = Hash.computeOpHash(typeHash, methodName)
        return anonymousImplMethodsByHash[key] ?: parent?.findAnonymousMethodByHash(typeHash, methodName)
    }

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

    fun extendWithAnonymousMethod(info: InherentMethodInfo): TypingContext {
        // Hash-qualify the registry key when the target type has a content hash, so
        // two same-named types from different modules keep DISTINCT entries instead
        // of one overwriting the other (last-writer-wins → silent mis-dispatch).
        // Builtins with no decl hash keep the plain-name key. Readers: by-name scans
        // (findAnonymousMethodsByName / findAnonymousMethod fallback) match on the
        // info's fields regardless of key. See project_classparam_runtime_dispatch_tuple.
        //
        // BUG 1: this key used to carry the mangled `targetTypeName` alongside the
        // hash, because for a TYPE-APPLICATION target the hash was the
        // CONSTRUCTOR's — identical across `impl Result PyForeign` and
        // `impl Result JsForeign`, so hash-alone collided and dropped one impl
        // (two polyglot providers in one module → a pure-Py chain lost its
        // `as_float` candidate). The target hash is now the APPLIED hash
        // (`appliedHashOf`: constructor ⊕ argument identities), which is distinct
        // per full type application on its own — so the name is gone from the key
        // and dispatch is keyed on structure end to end.
        val keyType = info.targetTypeHash?.hex ?: info.targetTypeName
        val key = "$keyType::${info.methodName}"
        val existingMethods = anonymousImplMethodsByType[info.targetTypeName] ?: emptyList()
        val newHashIndex = if (info.targetTypeHash != null) {
            val hashKey = Hash.computeOpHash(info.targetTypeHash, info.methodName)
            anonymousImplMethodsByHash + (hashKey to info)
        } else {
            anonymousImplMethodsByHash
        }
        return copy(
            anonymousImplMethods = anonymousImplMethods + (key to info),
            anonymousImplMethodsByType = anonymousImplMethodsByType + (info.targetTypeName to (existingMethods + info.methodName)),
            anonymousImplMethodsByHash = newHashIndex
        )
    }

    fun getPerformerTypeInfosByName(performerName: String): List<PerformerTypeInfo> {
        val localHashes = performerAssociatedTypesByName[performerName] ?: emptyList()
        val localInfos = localHashes.mapNotNull { performerAssociatedTypes[it] }
        val parentInfos = parent?.getPerformerTypeInfosByName(performerName) ?: emptyList()
        return localInfos + parentInfos
    }

    fun extendWithPerformerTypeInfo(contentHash: Hash, info: PerformerTypeInfo): TypingContext {
        val existingHashes = performerAssociatedTypesByName[info.performerName] ?: emptyList()
        return copy(
            performerAssociatedTypes = performerAssociatedTypes + (contentHash to info),
            performerAssociatedTypesByName = performerAssociatedTypesByName + (info.performerName to (existingHashes + contentHash))
        )
    }

    fun enterScope(): TypingContext {
        return TypingContext(
            bindings = emptyMap(),
            abilityImpls = emptyMap(),
            abilityImplsByName = emptyMap(),
            abilityImplsByAbilityHash = emptyMap(),
            abilityOperationTypes = emptyMap(),
            abilityOperationTypesByHash = emptyMap(),
            effectOperationTypes = emptyMap(),
            effectOperationTypesByHash = emptyMap(),
            performerAssociatedTypes = emptyMap(),
            performerAssociatedTypesByName = emptyMap(),
            anonymousImplMethodsByHash = emptyMap(),
            parent = this
        )
    }

    fun hasLocal(key: BindingKey): Boolean = bindings.containsKey(key)

    fun hasLocal(name: String): Boolean = hasLocal(BindingKey.unqualified(name))

    fun allKeys(): Set<BindingKey> = bindings.keys + (parent?.allKeys() ?: emptySet())

    fun getAllBindings(): Map<BindingKey, Binding> =
        (parent?.getAllBindings() ?: emptyMap()) + bindings

    fun getContentHashes(): Map<BindingKey, Hash> = buildMap {
        for ((key, binding) in getAllBindings()) {
            val hash = when (binding) {
                is Binding.Monomorphic -> binding.contentHash
                is Binding.Polymorphic -> binding.contentHash
            }
            if (hash != null) put(key, hash)
        }
    }

    companion object {
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

        fun from(bindings: Map<String, MezzeType>) = TypingContext(
            bindings = bindings.map { (name, type) ->
                BindingKey.unqualified(name) to Binding.Monomorphic(type)
            }.toMap()
        )
    }
}
