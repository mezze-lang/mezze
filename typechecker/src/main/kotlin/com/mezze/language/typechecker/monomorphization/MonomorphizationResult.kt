package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.*

/**
 * Result of monomorphization.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class MonomorphizationResult {
    /**
     * Monomorphization succeeded.
     */
    data class Success(
        val program: Program,                           // Program with updated call sites
        val monomorphicInstances: Map<String, LetBinding>, // Generated specialized functions
        val warnings: List<String> = emptyList()
    ) : MonomorphizationResult()

    /**
     * Monomorphization failed.
     */
    data class Failure(
        val errors: List<MonomorphizationError>
    ) : MonomorphizationResult()
}

/**
 * Monomorphization error.
 */
data class MonomorphizationError(
    val message: String,
    val functionName: String?,
    val typeArgs: Map<String, MezzeType>,
    val sourceLocation: SourceLocation? = null
)

/**
 * Tracked monomorphic instance request.
 *
 * During type checking, we track which polymorphic functions
 * need specialized instances and with what type arguments.
 */
data class InstanceRequest(
    val originalName: String,          // e.g., "map"
    val typeArgs: Map<String, MezzeType>,  // e.g., {"'a" -> IntType}
    val sourceLocation: SourceLocation? = null
) {
    /**
     * Generate the monomorphic name for this instance.
     *
     * Format: functionName$Type1$Type2
     * Example: map$Int or add$Int$Float
     */
    fun generateMonomorphicName(): String {
        if (typeArgs.isEmpty()) return originalName

        val typeArgString = typeArgs.entries
            .sortedBy { it.key }
            .joinToString("$") { (_, type) -> formatTypeForName(type) }

        return "${originalName}\$$typeArgString"
    }

    /**
     * Format a type for use in a monomorphic name.
     *
     * Keeps names readable: List Int becomes ListInt, not List_Int
     */
    private fun formatTypeForName(type: MezzeType): String {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "String"
            is MezzeType.BooleanType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.ListType -> "List${formatTypeForName(type.elementType)}"
            is MezzeType.ArrayType -> "Array${formatTypeForName(type.elementType)}"
            is MezzeType.SetType -> "Set${formatTypeForName(type.elementType)}"
            is MezzeType.SortedMapType -> "SortedMap${formatTypeForName(type.keyType)}${formatTypeForName(type.valueType)}"
            is MezzeType.SortedSetType -> "SortedSet${formatTypeForName(type.elementType)}"
            is MezzeType.MapType -> "Map${formatTypeForName(type.keyType)}${formatTypeForName(type.valueType)}"
            is MezzeType.TypeApplication -> "${type.constructor}${type.args.joinToString("") { formatTypeForName(it) }}"
            is MezzeType.RecordType -> "Record" // Simplified for now
            is MezzeType.VariantType -> type.constructors.keys.first() // Use first constructor name
            is MezzeType.VarType -> type.variable.name.replace("'", "") // Remove quote from 'a
            else -> "Unknown"
        }
    }
}

/**
 * Generated monomorphic instance.
 *
 * Represents a specialized version of a polymorphic function.
 */
data class MonomorphicInstance(
    val originalName: String,              // Original polymorphic function name
    val monomorphicName: String,           // Generated name (e.g., "map$Int")
    val typeArguments: Map<String, MezzeType>, // Type arguments used for specialization
    val specializedBinding: LetBinding     // The specialized function binding
)
