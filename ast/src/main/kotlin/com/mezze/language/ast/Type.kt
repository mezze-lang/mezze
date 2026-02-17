@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Type system representations for the Mezze language.
 *
 * This file contains:
 * - MezzeType: Sealed interface for all type representations
 * - TypeScheme: Polymorphic type schemes for let-polymorphism
 * - UsageContext: Bidirectional type inference support
 * - Type conversion and normalization utilities
 * - BuiltinTypes: Built-in type constructor utilities
 *
 * Part of the refactored AST module - previously in MezzeAST.kt.
 */

// ============================================================================
// TYPES
// ============================================================================

@Serializable
@JsonClassDiscriminator("typeKind")
sealed interface MezzeType {
    companion object {
        // Thread-local to track nesting depth to prevent infinite recursion in toString()
        internal val toStringDepth = ThreadLocal.withInitial { 0 }
        internal const val MAX_DEPTH = 10
        internal const val TRUNCATE_DEPTH = 3

        /**
         * Helper for safe toString() implementations with depth tracking.
         * Prevents StackOverflowError and provides consistent truncation behavior.
         */
        internal inline fun safeToString(
            typeName: String,
            truncatedContent: () -> String,
            fullContent: () -> String
        ): String {
            val depth = toStringDepth.get()
            if (depth > MAX_DEPTH) {
                return "$typeName{<max depth>}"
            }
            return try {
                toStringDepth.set(depth + 1)
                if (depth > TRUNCATE_DEPTH) {
                    "$typeName{${truncatedContent()}}"
                } else {
                    "$typeName(${fullContent()})"
                }
            } catch (e: StackOverflowError) {
                "$typeName{<StackOverflowError>}"
            } finally {
                toStringDepth.set(depth)
                if (depth == 0) toStringDepth.remove()
            }
        }
    }

    // Primitive types
    @Serializable
    object IntType : MezzeType

    @Serializable
    object FloatType : MezzeType

    @Serializable
    object StringType : MezzeType

    @Serializable
    object BooleanType : MezzeType

    // Enhanced numeric types
    @Serializable
    object NatType : MezzeType

    @Serializable
    object FracType : MezzeType

    // Character type
    @Serializable
    object CharType : MezzeType

    // Binary data type
    @Serializable
    object BytesType : MezzeType

    // Mutable byte buffer for streaming/building (internal mutation, functional API)
    @Serializable
    object ByteBufferType : MezzeType

    // Type variables (for inference)
    @Serializable
    data class VarType(val variable: TypeVar) : MezzeType

    // Record types
    @Serializable
    data class RecordType(
        val fields: Map<String, MezzeType>,
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            "RecordType",
            truncatedContent = { "${fields.size} fields" },
            fullContent = { "${fields.keys.take(3)}${if (fields.size > 3) "..." else ""}" }
        )
    }

    // Variant types
    @Serializable
    data class VariantType(
        val constructors: Map<String, MezzeType?>, // null = no data, MezzeType = constructor data
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            "VariantType",
            truncatedContent = { "${constructors.size} constructors" },
            fullContent = { "${constructors.keys.take(3)}${if (constructors.size > 3) "..." else ""}" }
        )
    }

    // Built-in core types (always available)
    // Note: Option and Result are now regular variant types, not special AST types
    // They are defined as built-in variants:
    //   Option 'a = Some { val: 'a } | None
    //   Result 'a 'e = Ok { val: 'a } | Err { error: 'e }

    @Serializable
    data class ListType(val elementType: MezzeType) : MezzeType

    @Serializable
    data class MapType(val keyType: MezzeType, val valueType: MezzeType) : MezzeType

    @Serializable
    data class SetType(val elementType: MezzeType) : MezzeType

    @Serializable
    data class SortedMapType(val keyType: MezzeType, val valueType: MezzeType) : MezzeType

    @Serializable
    data class SortedSetType(val elementType: MezzeType) : MezzeType

    // Generic type application (e.g., Option Int, Result String Error, MyType 'a 'b)
    // This represents applying type arguments to a type constructor
    @Serializable
    data class TypeApplication(val constructor: String, val args: List<MezzeType>) : MezzeType {
        override fun toString(): String = safeToString(
            "TypeApplication",
            truncatedContent = { "$constructor, ${args.size} args" },
            fullContent = { constructor }
        )
    }

    // Built-in ordering type for comparisons
    @Serializable
    object OrderingType : MezzeType

    // Array types (for arrays [] syntax)
    @Serializable
    data class ArrayType(val elementType: MezzeType) : MezzeType

    // Function types
    @Serializable
    data class FunctionType(
        val paramType: MezzeType,
        val returnType: MezzeType,
        val effects: EffectRow? = null,  // null = pure function, non-null = effectful function
        val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Ability constraints for this function
    ) : MezzeType {
        override fun toString(): String = safeToString(
            "FunctionType",
            truncatedContent = { "..." },
            fullContent = {
                val effectsStr = if (effects != null) {
                    val effectList = effects.effects.take(3).joinToString(", ")
                    val suffix = if (effects.effects.size > 3) ", ..." else ""
                    " <$effectList$suffix>"
                } else ""
                "${if (effects != null) "effectful" else "pure"}$effectsStr"
            }
        )
    }

    // Suspended computation types (values with unhandled effects)
    // This represents a value of type valueType that has unhandled effects
    // Unlike FunctionType, this is not a function - it's an already-computed value
    // that cannot be used until its effects are handled
    @Serializable
    data class SuspendedType(
        val valueType: MezzeType,
        val effects: EffectRow
    ) : MezzeType {
        override fun toString(): String {
            val effectList = effects.effects.take(3).joinToString(", ")
            val suffix = if (effects.effects.size > 3) ", ..." else ""
            return safeToString(
                "SuspendedType",
                truncatedContent = { "${effects.effects.size} effects" },
                fullContent = { "$effectList$suffix" }
            )
        }
    }

    // Effect rows
    @Serializable
    data class EffectRow(
        val effects: Set<String>,
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val performed: Boolean = false // true = effects have been performed via 'perform'
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        /** True if this is truly pure - no effects, not open, nothing performed */
        val isPure: Boolean get() = effects.isEmpty() && rowVar == null && !performed

        override fun toString(): String {
            val effectList = effects.take(5).joinToString(", ")
            val suffix = when {
                performed && effects.isNotEmpty() -> ", *"
                performed -> "*"
                effects.size > 5 -> ", ..."
                else -> ""
            }
            return safeToString(
                "EffectRow",
                truncatedContent = { if (performed) "${effects.size} effects, *" else "${effects.size} effects" },
                fullContent = { "$effectList$suffix" }
            )
        }
    }

    // Function-level ability constraints
    @Serializable
    data class FunctionAbilityConstraint(
        val abilityName: String,
        val typeArgs: List<MezzeType> = emptyList()  // Type arguments for the ability
    )

}

/**
 * Type schemes for let-polymorphism
 * Represents ∀α₁,α₂,...,αₙ. τ (forall type variables, type τ)
 */
@Serializable
data class TypeScheme(
    val quantifiedVars: Set<TypeVar>,
    val type: MezzeType,
    val constraints: List<AbilityConstraint> = emptyList()
) {
    /**
     * Check if this type scheme is monomorphic (no quantified variables)
     */
    val isMonomorphic: Boolean get() = quantifiedVars.isEmpty()

    /**
     * Instantiate this type scheme with fresh type variables
     */
    fun instantiate(): MezzeType {
        if (isMonomorphic) return type

        val substitution = quantifiedVars.associateWith {
            MezzeType.VarType(TypeVar.fresh())
        }
        return applySubstitutionToType(type, substitution)
    }

    companion object {
        /**
         * Generalize a type into a type scheme by quantifying over free type variables
         */
        fun generalize(type: MezzeType, constraints: List<AbilityConstraint> = emptyList()): TypeScheme {
            val typeVars = mutableSetOf<TypeVar>()
            collectTypeVariables(type, typeVars)

            // Filter constraints to only include those relevant to the type variables
            val relevantConstraints = constraints.filter { constraint ->
                typeVars.any { it.name == constraint.typeParam }
            }

            return TypeScheme(typeVars, type, relevantConstraints)
        }
    }

    /**
     * Apply a substitution to a type
     */
    private fun applySubstitutionToType(type: MezzeType, substitution: Map<TypeVar, MezzeType>): MezzeType {
        return when (type) {
            is MezzeType.VarType -> substitution[type.variable] ?: type
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                applySubstitutionToType(type.paramType, substitution),
                applySubstitutionToType(type.returnType, substitution),
                type.effects,
                type.abilityConstraints
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                type.fields.mapValues { (_, fieldType) ->
                    applySubstitutionToType(fieldType, substitution)
                },
                type.rowVar
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applySubstitutionToType(it, substitution) }
                },
                type.rowVar
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                type.constructor,
                type.args.map { applySubstitutionToType(it, substitution) }
            )
            is MezzeType.ListType -> MezzeType.ListType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                applySubstitutionToType(type.keyType, substitution),
                applySubstitutionToType(type.valueType, substitution)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                applySubstitutionToType(type.keyType, substitution),
                applySubstitutionToType(type.valueType, substitution)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is MezzeType.ArrayType -> MezzeType.ArrayType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                applySubstitutionToType(type.valueType, substitution),
                type.effects
            )
            // Concrete types remain unchanged
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BooleanType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType -> type
            is MezzeType.EffectRow -> type // Effects don't contain type variables for now
        }
    }
}

// ============================================================================
// BIDIRECTIONAL TYPE INFERENCE - USAGE CONSTRAINTS
// ============================================================================

/**
 * Usage context - represents how a value is used in the program
 *
 * This is used for bidirectional type inference to propagate type information
 * from usage sites back to definitions. For example, if we have:
 *
 *   let x = parse {str = "123"}
 *   let y = x + 10
 *
 * The usage `x + 10` creates a UsageContext.BinaryOp that tells us
 * x must be Int (because + requires NumAdd ability).
 */
@Serializable
@JsonClassDiscriminator("usageContextKind")
sealed interface UsageContext {
    /**
     * Type annotation usage - the value is annotated with an explicit type
     * Example: let x: Int = ...
     */
    @Serializable
    data class TypeAnnotation(val annotatedType: MezzeType) : UsageContext

    /**
     * Binary operator usage - the value is used with a binary operator
     * Example: x + 10 (x is left operand, operator is +, right operand type is Int)
     */
    @Serializable
    data class BinaryOp(
        val operator: String,
        val position: OperandPosition,  // LEFT or RIGHT
        val otherOperandType: MezzeType
    ) : UsageContext

    /**
     * Field access usage - a specific field is accessed on the value
     * Example: user.name (implies user must be a record with a `name` field)
     */
    @Serializable
    data class FieldAccess(
        val fieldName: String,
        val expectedFieldType: MezzeType
    ) : UsageContext

    /**
     * Function call usage - the value is called as a function
     * Example: f {x = 10} (implies f must be a function taking {x: Int})
     */
    @Serializable
    data class FunctionCall(
        val argumentType: MezzeType,
        val expectedReturnType: MezzeType? = null
    ) : UsageContext

    /**
     * Match pattern usage - the value is matched against a pattern
     * Example: match x { Some {val} -> ... } (implies x must be Option type)
     */
    @Serializable
    data class MatchPattern(
        val constructorName: String,
        val constructorDataType: MezzeType?
    ) : UsageContext

    /**
     * Function return usage - the value is returned from a function
     * This propagates the expected return type into the expression
     */
    @Serializable
    data class FunctionReturn(
        val expectedReturnType: MezzeType
    ) : UsageContext
}

/**
 * Position of an operand in a binary operation
 */
@Serializable
enum class OperandPosition {
    LEFT,   // Left operand (e.g., x in `x + 10`)
    RIGHT   // Right operand (e.g., x in `10 + x`)
}

/**
 * Usage constraint - links a type variable to how it's used
 *
 * These constraints are collected during type inference and used to
 * propagate type information backwards from usage sites to definitions.
 *
 * Example:
 *   let parse = {str} -> Decode.decode {value = str}
 *   let x = parse {str = "123"}
 *   let y = x + 10
 *
 * This generates a UsageConstraint:
 *   - typeVar: the type variable for 'x'
 *   - usageContext: BinaryOp("+", LEFT, Int)
 *   - inferredType: Int
 *   - sourceLocation: location of `x + 10`
 *
 * The constraint solver can then use this to infer that x : Int,
 * and therefore parse must return Int, and therefore Decode Int is needed.
 */
@Serializable
data class UsageConstraint(
    val typeVar: TypeVar,
    val usageContext: UsageContext,
    val inferredType: MezzeType,
    val sourceLocation: SourceLocation? = null
)
