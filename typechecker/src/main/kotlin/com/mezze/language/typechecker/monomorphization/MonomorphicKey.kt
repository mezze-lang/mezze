package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.MezzeType
import java.security.MessageDigest

/**
 * Structured key for monomorphic function instances.
 *
 * Replaces string-based keys like "map$Int$Int" with a proper data structure
 * that enables:
 * 1. Type-directed node selection in interpreter (no string parsing)
 * 2. Efficient hash-based deduplication
 * 3. Structured pattern matching on type arguments
 *
 * ## Example
 *
 * A call to `map[Int, String](fn, seq)` produces:
 * ```
 * MonomorphicKey(
 *     originalName = "map",
 *     originalContentHash = "abc123...",
 *     typeArgs = listOf(
 *         ConcreteType.MzInt,
 *         ConcreteType.MzString
 *     ),
 *     abilityImpls = emptyList()
 * )
 * ```
 */
data class MonomorphicKey(
    /** Original polymorphic function name (e.g., "map", "filter") */
    val originalName: String,

    /** Content hash of the original polymorphic function definition */
    val originalContentHash: String?,

    /** Concrete type arguments in order (e.g., [Int, String] for map[Int, String]) */
    val typeArgs: List<ConcreteType>,

    /** Content hashes of ability implementations required by this instantiation */
    val abilityImpls: List<String> = emptyList()
) {
    /**
     * Generate a deterministic content hash for this monomorphic instance.
     *
     * Combines the original function's content hash with type arguments
     * and ability implementations to produce a unique identifier.
     */
    fun generateContentHash(): String? {
        if (originalContentHash == null) return null
        if (typeArgs.isEmpty() && abilityImpls.isEmpty()) return originalContentHash

        val components = buildString {
            append(originalContentHash)
            if (typeArgs.isNotEmpty()) {
                append(":")
                append(typeArgs.joinToString("|") { it.toCanonicalString() })
            }
            if (abilityImpls.isNotEmpty()) {
                append("#")
                append(abilityImpls.sorted().joinToString(","))
            }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(components.toByteArray())
        return hashBytes.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate the legacy string-based monomorphic name.
     * Used for backward compatibility during migration.
     */
    fun toLegacyName(): String {
        if (typeArgs.isEmpty()) return originalName
        val typeArgString = typeArgs.joinToString("$") { it.toCanonicalString() }
        return "${originalName}\$$typeArgString"
    }

    companion object {
        /**
         * Create a MonomorphicKey from an InstanceRequest.
         */
        fun fromInstanceRequest(request: InstanceRequest): MonomorphicKey {
            return MonomorphicKey(
                originalName = request.originalName,
                originalContentHash = request.originalContentHash,
                typeArgs = request.typeArgs.entries
                    .sortedBy { it.key }
                    .map { ConcreteType.fromMezzeType(it.value) }
            )
        }

        /**
         * Create a MonomorphicKey from type parameters.
         */
        fun create(
            originalName: String,
            originalContentHash: String?,
            typeArgs: Map<String, MezzeType>,
            abilityImpls: List<String> = emptyList()
        ): MonomorphicKey {
            return MonomorphicKey(
                originalName = originalName,
                originalContentHash = originalContentHash,
                typeArgs = typeArgs.entries
                    .sortedBy { it.key }
                    .map { ConcreteType.fromMezzeType(it.value) },
                abilityImpls = abilityImpls
            )
        }
    }
}

/**
 * Concrete type representation for monomorphization.
 *
 * A sealed class hierarchy that represents concrete (non-polymorphic) types
 * in a structured way that's efficient for pattern matching and hashing.
 *
 * ## Design Goals
 *
 * 1. **Exhaustive matching**: Sealed class ensures all cases are handled
 * 2. **Efficient comparison**: Data classes provide equals/hashCode
 * 3. **JIT-friendly**: No string parsing at runtime
 * 4. **Canonical form**: Each type has exactly one representation
 *
 * ## Naming Convention
 *
 * Primitive types use "Mz" prefix to avoid conflicts with Kotlin stdlib types.
 * E.g., MzInt, MzString, MzBool instead of Int, String, Bool.
 */
sealed class ConcreteType {
    /** Get a canonical string representation for hashing */
    abstract fun toCanonicalString(): String

    // ========================================================================
    // PRIMITIVE TYPES
    // ========================================================================

    /** Mezze Int type */
    data object MzInt : ConcreteType() {
        override fun toCanonicalString() = "Int"
    }

    /** Mezze Nat type */
    data object MzNat : ConcreteType() {
        override fun toCanonicalString() = "Nat"
    }

    /** Mezze Float type */
    data object MzFloat : ConcreteType() {
        override fun toCanonicalString() = "Float"
    }

    /** Mezze String type */
    data object MzString : ConcreteType() {
        override fun toCanonicalString() = "String"
    }

    /** Mezze Bool type */
    data object MzBool : ConcreteType() {
        override fun toCanonicalString() = "Bool"
    }

    /** Mezze Char type */
    data object MzChar : ConcreteType() {
        override fun toCanonicalString() = "Char"
    }

    /** Mezze Bytes type */
    data object MzBytes : ConcreteType() {
        override fun toCanonicalString() = "Bytes"
    }

    /** Mezze ByteBuffer type */
    data object MzByteBuffer : ConcreteType() {
        override fun toCanonicalString() = "ByteBuffer"
    }

    /** Mezze Frac type */
    data object MzFrac : ConcreteType() {
        override fun toCanonicalString() = "Frac"
    }

    /** Mezze Ordering type */
    data object MzOrdering : ConcreteType() {
        override fun toCanonicalString() = "Ordering"
    }

    /** Mezze Unit type */
    data object MzUnit : ConcreteType() {
        override fun toCanonicalString() = "Unit"
    }

    // ========================================================================
    // COLLECTION TYPES
    // ========================================================================

    /** Seq type (default collection) */
    data class MzSeq(val elementType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "Seq<${elementType.toCanonicalString()}>"
    }

    /** List type (linked list) */
    data class MzList(val elementType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "List<${elementType.toCanonicalString()}>"
    }

    /** Set type (unordered set) */
    data class MzSet(val elementType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "Set<${elementType.toCanonicalString()}>"
    }

    /** SortedSet type (ordered set) */
    data class MzSortedSet(val elementType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "SortedSet<${elementType.toCanonicalString()}>"
    }

    /** Map type (key-value) */
    data class MzMap(val keyType: ConcreteType, val valueType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "Map<${keyType.toCanonicalString()}, ${valueType.toCanonicalString()}>"
    }

    /** SortedMap type (ordered key-value) */
    data class MzSortedMap(val keyType: ConcreteType, val valueType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "SortedMap<${keyType.toCanonicalString()}, ${valueType.toCanonicalString()}>"
    }

    /** Deque type (double-ended queue) */
    data class MzDeque(val elementType: ConcreteType) : ConcreteType() {
        override fun toCanonicalString() = "Deque<${elementType.toCanonicalString()}>"
    }

    // ========================================================================
    // COMPOUND TYPES
    // ========================================================================

    /** Function type */
    data class MzFunction(
        val paramType: ConcreteType,
        val returnType: ConcreteType
    ) : ConcreteType() {
        override fun toCanonicalString() = "(${paramType.toCanonicalString()} -> ${returnType.toCanonicalString()})"
    }

    /** Record type (structural) */
    data class MzRecord(
        val fields: Map<String, ConcreteType>
    ) : ConcreteType() {
        override fun toCanonicalString(): String {
            val fieldStr = fields.entries
                .sortedBy { it.key }
                .joinToString(", ") { "${it.key}: ${it.value.toCanonicalString()}" }
            return "{$fieldStr}"
        }
    }

    /** Variant type (sum type) */
    data class MzVariant(
        val constructors: Map<String, ConcreteType?>
    ) : ConcreteType() {
        override fun toCanonicalString(): String {
            val ctorStr = constructors.entries
                .sortedBy { it.key }
                .joinToString(" | ") { (name, payload) ->
                    if (payload != null) "$name(${payload.toCanonicalString()})" else name
                }
            return "[$ctorStr]"
        }
    }

    /** Named type (user-defined type or type application) */
    data class MzNamed(
        val name: String,
        val typeArgs: List<ConcreteType> = emptyList()
    ) : ConcreteType() {
        override fun toCanonicalString(): String {
            return if (typeArgs.isEmpty()) {
                name
            } else {
                val argsStr = typeArgs.joinToString(", ") { it.toCanonicalString() }
                "$name<$argsStr>"
            }
        }
    }

    /** Suspended computation type */
    data class MzSuspended(
        val valueType: ConcreteType,
        val effects: Set<String>
    ) : ConcreteType() {
        override fun toCanonicalString(): String {
            val effectStr = effects.sorted().joinToString(", ")
            return "Suspended<${valueType.toCanonicalString()}, {$effectStr}>"
        }
    }

    /** Type variable that couldn't be resolved (should be rare in monomorphization) */
    data class MzTypeVar(val name: String) : ConcreteType() {
        override fun toCanonicalString() = "'$name"
    }

    companion object {
        /**
         * Convert a MezzeType to a ConcreteType.
         */
        fun fromMezzeType(type: MezzeType): ConcreteType = when (type) {
            MezzeType.IntType -> MzInt
            MezzeType.NatType -> MzNat
            MezzeType.FloatType -> MzFloat
            MezzeType.StringType -> MzString
            MezzeType.BoolType -> MzBool
            MezzeType.CharType -> MzChar
            MezzeType.BytesType -> MzBytes
            MezzeType.ByteBufferType -> MzByteBuffer
            MezzeType.FracType -> MzFrac
            MezzeType.OrderingType -> MzOrdering

            is MezzeType.VarType -> MzTypeVar(type.variable.name)

            is MezzeType.FunctionType -> MzFunction(
                paramType = fromMezzeType(type.paramType),
                returnType = fromMezzeType(type.returnType)
            )

            is MezzeType.RecordType -> MzRecord(
                fields = type.fields.mapValues { fromMezzeType(it.value) }
            )

            is MezzeType.VariantType -> MzVariant(
                constructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { fromMezzeType(it) }
                }
            )

            is MezzeType.SeqType -> MzSeq(fromMezzeType(type.elementType))
            is MezzeType.ListType -> MzList(fromMezzeType(type.elementType))
            is MezzeType.SetType -> MzSet(fromMezzeType(type.elementType))
            is MezzeType.SortedSetType -> MzSortedSet(fromMezzeType(type.elementType))
            is MezzeType.MapType -> MzMap(
                fromMezzeType(type.keyType),
                fromMezzeType(type.valueType)
            )
            is MezzeType.SortedMapType -> MzSortedMap(
                fromMezzeType(type.keyType),
                fromMezzeType(type.valueType)
            )

            is MezzeType.TypeApplication -> MzNamed(
                name = type.constructor,
                typeArgs = type.args.map { fromMezzeType(it) }
            )

            is MezzeType.SuspendedType -> MzSuspended(
                valueType = fromMezzeType(type.valueType),
                effects = type.effects.effects.toSet()
            )

            is MezzeType.EffectRow -> MzNamed("EffectRow", emptyList())
        }
    }
}

/**
 * Extension to check if a ConcreteType is a numeric type.
 */
fun ConcreteType.isNumeric(): Boolean = this in listOf(
    ConcreteType.MzInt,
    ConcreteType.MzNat,
    ConcreteType.MzFloat,
    ConcreteType.MzFrac
)

/**
 * Extension to check if a ConcreteType is a primitive type.
 */
fun ConcreteType.isPrimitive(): Boolean = this in listOf(
    ConcreteType.MzInt,
    ConcreteType.MzNat,
    ConcreteType.MzFloat,
    ConcreteType.MzString,
    ConcreteType.MzBool,
    ConcreteType.MzChar,
    ConcreteType.MzBytes,
    ConcreteType.MzByteBuffer,
    ConcreteType.MzFrac,
    ConcreteType.MzOrdering,
    ConcreteType.MzUnit
)

/**
 * Extension to check if a ConcreteType is a collection type.
 */
fun ConcreteType.isCollection(): Boolean = this is ConcreteType.MzSeq ||
    this is ConcreteType.MzList ||
    this is ConcreteType.MzSet ||
    this is ConcreteType.MzSortedSet ||
    this is ConcreteType.MzMap ||
    this is ConcreteType.MzSortedMap ||
    this is ConcreteType.MzDeque
