package com.mezze.language.typechecker.cache

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.store.MezzeStore
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typedast.Hash
import java.util.concurrent.ConcurrentHashMap

object TypeCheckCache {

    private var store: MezzeStore? = null

    fun initialize(store: MezzeStore) {
        this.store = store
        DebugLogger.debug(Category.TypeChecker) {
            "TypeCheckCache initialized with store persistence"
        }
    }

    fun initializeInMemory() {
        store = null
        DebugLogger.debug(Category.TypeChecker) {
            "TypeCheckCache initialized in-memory only"
        }
    }

    fun hasPersistence(): Boolean = store != null

    data class CachedAbilityImpl(
        val implementationHash: Hash,
        val modulePath: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val abilityImplCacheL1 = ConcurrentHashMap<Hash, CachedAbilityImpl>()

    fun getAbilityImpl(abilityName: String, targetTypes: List<MezzeType>): CachedAbilityImpl? {
        val key = computeAbilityKey(abilityName, targetTypes)
        return abilityImplCacheL1[key]
    }

    fun putAbilityImpl(abilityName: String, targetTypes: List<MezzeType>, implHash: Hash, modulePath: String?) {
        val key = computeAbilityKey(abilityName, targetTypes)
        abilityImplCacheL1[key] = CachedAbilityImpl(implHash, modulePath)
    }

    private fun computeAbilityKey(abilityName: String, targetTypes: List<MezzeType>): Hash {
        val builder = MezzeHashBuilder()
        builder.append("abilityKey")
        builder.append(abilityName)
        for (t in targetTypes) builder.append(typeToString(t))
        return builder.buildHash()
    }

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
            is MezzeType.SuspendType -> {
                val inner = typeToString(type.valueType)
                val effects = type.effects.effects.sorted().joinToString(", ")
                "Suspended<$inner, {$effects}>"
            }
            is MezzeType.EffectRow -> {
                val effects = type.effects.sorted().joinToString(", ")
                "EffectRow<{$effects}>"
            }
            is MezzeType.VarType -> type.toString()
            is MezzeType.NewtypeType -> "Newtype<${typeToString(type.wrappedType)}>"
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType -> type.toString()
        }
    }

    fun clearAll() {
        abilityImplCacheL1.clear()
    }
}
