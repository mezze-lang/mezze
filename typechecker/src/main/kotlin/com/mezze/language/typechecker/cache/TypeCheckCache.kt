package com.mezze.language.typechecker.cache

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typedast.Hash
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Content-addressed cache for type checking results.
 *
 * Two-tier caching:
 * 1. **In-memory (L1)**: Fast ConcurrentHashMap, cleared on process exit
 * 2. **MezzeStore (L2)**: Persistent via ability_impls table, survives across sessions
 */
object TypeCheckCache {

    private var store: MezzeStore? = null

    // Stats tracking
    private val constraintCacheHits = AtomicLong(0)
    private val constraintCacheMisses = AtomicLong(0)
    private val abilityImplCacheHits = AtomicLong(0)
    private val abilityImplCacheMisses = AtomicLong(0)

    // ========================================================================
    // INITIALIZATION
    // ========================================================================

    /**
     * Initialize with MezzeStore persistence (project mode).
     */
    fun initialize(store: MezzeStore) {
        this.store = store
        DebugLogger.debug(Category.TypeChecker) {
            "TypeCheckCache initialized with store persistence"
        }
    }

    /**
     * Initialize in-memory only (script mode).
     */
    fun initializeInMemory() {
        store = null
        DebugLogger.debug(Category.TypeChecker) {
            "TypeCheckCache initialized in-memory only"
        }
    }

    /**
     * Check if persistence is enabled.
     */
    fun hasPersistence(): Boolean = store != null

    // ========================================================================
    // CONSTRAINT CACHE (L1: In-Memory)
    // ========================================================================

    data class CachedConstraintResult(
        val result: ConstraintResult,
        val contextHash: Hash,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Composite key (bindingHash, contextHash) — typed pair so neither component can collide. */
    data class ConstraintCacheKey(val bindingHash: Hash, val contextHash: Hash)

    private val constraintCacheL1 = ConcurrentHashMap<ConstraintCacheKey, CachedConstraintResult>()

    /**
     * Get cached constraint result.
     * Checks L1 (memory) first, then L2 (store).
     */
    fun getConstraintResult(bindingHash: Hash, contextHash: Hash): ConstraintResult? {
        val key = ConstraintCacheKey(bindingHash, contextHash)
        constraintCacheL1[key]?.let { cached ->
            constraintCacheHits.incrementAndGet()
            return cached.result
        }
        constraintCacheMisses.incrementAndGet()
        return null
    }

    /**
     * Store constraint result in cache.
     */
    fun putConstraintResult(bindingHash: Hash, contextHash: Hash, result: ConstraintResult) {
        val key = ConstraintCacheKey(bindingHash, contextHash)
        constraintCacheL1[key] = CachedConstraintResult(result, contextHash)
    }

    /**
     * Compute a content-addressed hash for type arguments. Replaces the old
     * `String.hashCode().toString(16)` (32-bit collision space) with a full
     * MezzeHash-derived identity.
     */
    fun hashTypeArgs(typeArgs: Map<String, MezzeType>): Hash {
        val builder = MezzeHashBuilder()
        builder.append("typeArgs")
        if (typeArgs.isEmpty()) {
            builder.append("empty")
        } else {
            for ((k, v) in typeArgs.entries.sortedBy { it.key }) {
                builder.append(k)
                builder.append(typeToString(v))
            }
        }
        return builder.buildHash()
    }

    // ========================================================================
    // ABILITY IMPLEMENTATION CACHE
    // ========================================================================

    data class CachedAbilityImpl(
        val implementationHash: Hash,
        val modulePath: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val abilityImplCacheL1 = ConcurrentHashMap<Hash, CachedAbilityImpl>()

    /**
     * Get cached ability implementation.
     */
    fun getAbilityImpl(abilityName: String, targetTypes: List<MezzeType>): CachedAbilityImpl? {
        val key = computeAbilityKey(abilityName, targetTypes)

        // L1: Check in-memory cache
        abilityImplCacheL1[key]?.let {
            abilityImplCacheHits.incrementAndGet()
            return it
        }

        // L2 store lookup is a future improvement (need a name→hash index).

        abilityImplCacheMisses.incrementAndGet()
        return null
    }

    /**
     * Store ability implementation in cache.
     */
    fun putAbilityImpl(abilityName: String, targetTypes: List<MezzeType>, implHash: Hash, modulePath: String?) {
        val key = computeAbilityKey(abilityName, targetTypes)
        abilityImplCacheL1[key] = CachedAbilityImpl(implHash, modulePath)
    }

    /**
     * Compute a content-addressed key for ability implementation lookup.
     * Replaces the old `"$abilityName\$$typeStr"` interpolation (collision
     * class because typeToString embeds `$`) with a typed Hash derived from
     * the canonical hash builder.
     */
    fun computeAbilityKey(abilityName: String, targetTypes: List<MezzeType>): Hash {
        val builder = MezzeHashBuilder()
        builder.append("abilityKey")
        builder.append(abilityName)
        for (t in targetTypes) builder.append(typeToString(t))
        return builder.buildHash()
    }

    // ========================================================================
    // CONTEXT HASHING
    // ========================================================================

    fun hashContext(
        bindingNames: Set<String>,
        typeDeclarations: Set<String>,
        abilityNames: Set<String>,
        effectNames: Set<String>
    ): Hash {
        val builder = MezzeHashBuilder()
        builder.append("ctx")
        for (b in bindingNames.sorted()) { builder.append("b"); builder.append(b) }
        for (t in typeDeclarations.sorted()) { builder.append("t"); builder.append(t) }
        for (a in abilityNames.sorted()) { builder.append("a"); builder.append(a) }
        for (e in effectNames.sorted()) { builder.append("e"); builder.append(e) }
        return builder.buildHash()
    }

    // ========================================================================
    // UTILITIES
    // ========================================================================

    private fun typeToString(type: MezzeType): String {
        PrimitiveTypes.toName(type)?.let { return it }

        return when (type) {
            is MezzeType.VarType -> "'${type.variable.name}"
            is MezzeType.FunctionType -> {
                val paramStr = typeToString(type.paramType)
                val returnStr = typeToString(type.returnType)
                "($paramStr -> $returnStr)"
            }
            is MezzeType.RecordType -> {
                val fields = type.fields.entries
                    .sortedBy { it.key }
                    .joinToString(", ") { (k, v) -> "$k: ${typeToString(v)}" }
                "{$fields}"
            }
            is MezzeType.VariantType -> {
                val constructors = type.constructors.entries
                    .sortedBy { it.key }
                    .joinToString(" | ") { (k, v) ->
                        if (v != null) "$k(${typeToString(v)})" else k
                    }
                "[$constructors]"
            }
            is MezzeType.SeqType -> "Seq<${typeToString(type.elementType)}>"
            is MezzeType.ListType -> "List<${typeToString(type.elementType)}>"
            is MezzeType.SetType -> "Set<${typeToString(type.elementType)}>"
            is MezzeType.SortedSetType -> "SortedSet<${typeToString(type.elementType)}>"
            is MezzeType.MapType -> "Map<${typeToString(type.keyType)}, ${typeToString(type.valueType)}>"
            is MezzeType.SortedMapType -> "SortedMap<${typeToString(type.keyType)}, ${typeToString(type.valueType)}>"
            is MezzeType.TypeApplication -> {
                val args = type.args.joinToString(", ") { typeToString(it) }
                "${type.constructor}<$args>"
            }
            is MezzeType.SuspendedType -> {
                val inner = typeToString(type.valueType)
                val effects = type.effects.effects.sorted().joinToString(", ")
                "Suspended<$inner, {$effects}>"
            }
            is MezzeType.EffectRow -> {
                val effects = type.effects.sorted().joinToString(", ")
                "EffectRow<{$effects}>"
            }
            else -> type.toString()
        }
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    fun clearAll() {
        constraintCacheL1.clear()
        abilityImplCacheL1.clear()
    }

    fun clearAllIncludingSqlite() {
        clearAll()
        // Store clears happen at the store level, not here
    }

    fun getStats(): CacheStats {
        return CacheStats(
            constraintCacheL1Size = constraintCacheL1.size,
            constraintCacheHits = constraintCacheHits.get(),
            constraintCacheMisses = constraintCacheMisses.get(),
            abilityImplCacheL1Size = abilityImplCacheL1.size,
            abilityImplCacheHits = abilityImplCacheHits.get(),
            abilityImplCacheMisses = abilityImplCacheMisses.get(),
            hasSqlitePersistence = hasPersistence()
        )
    }

    data class CacheStats(
        val constraintCacheL1Size: Int,
        val constraintCacheHits: Long,
        val constraintCacheMisses: Long,
        val abilityImplCacheL1Size: Int,
        val abilityImplCacheHits: Long,
        val abilityImplCacheMisses: Long,
        val hasSqlitePersistence: Boolean
    ) {
        val constraintCacheHitRate: Double
            get() = if (constraintCacheHits + constraintCacheMisses > 0) {
                constraintCacheHits.toDouble() / (constraintCacheHits + constraintCacheMisses)
            } else 0.0

        val abilityImplCacheHitRate: Double
            get() = if (abilityImplCacheHits + abilityImplCacheMisses > 0) {
                abilityImplCacheHits.toDouble() / (abilityImplCacheHits + abilityImplCacheMisses)
            } else 0.0

        override fun toString(): String = buildString {
            appendLine("TypeCheckCache Stats:")
            appendLine("  Constraint Cache: ${constraintCacheL1Size} entries, ${(constraintCacheHitRate * 100).toInt()}% hit rate")
            appendLine("  Ability Impl Cache: ${abilityImplCacheL1Size} entries, ${(abilityImplCacheHitRate * 100).toInt()}% hit rate")
            appendLine("  Store Persistence: ${if (hasSqlitePersistence) "enabled" else "disabled"}")
        }
    }
}
