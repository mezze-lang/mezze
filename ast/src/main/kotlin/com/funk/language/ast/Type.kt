@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.funk.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Type system representations for the Funk language.
 *
 * This file contains:
 * - FunkType: Sealed interface for all type representations
 * - TypeScheme: Polymorphic type schemes for let-polymorphism
 * - UsageContext: Bidirectional type inference support
 * - Type conversion and normalization utilities
 * - BuiltinTypes: Built-in type constructor utilities
 *
 * Part of the refactored AST module - previously in FunkAST.kt.
 */

// ============================================================================
// TYPES
// ============================================================================

@Serializable
@JsonClassDiscriminator("typeKind")
sealed interface FunkType {
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
    object IntType : FunkType

    @Serializable
    object FloatType : FunkType

    @Serializable
    object StringType : FunkType

    @Serializable
    object BooleanType : FunkType

    // Enhanced numeric types
    @Serializable
    object NatType : FunkType

    @Serializable
    object FracType : FunkType

    // Binary data type
    @Serializable
    object BytesType : FunkType

    // Mutable byte buffer for streaming/building (internal mutation, functional API)
    @Serializable
    object ByteBufferType : FunkType

    // Type variables (for inference)
    @Serializable
    data class VarType(val variable: TypeVar) : FunkType

    // Record types
    @Serializable
    data class RecordType(
        val fields: Map<String, FunkType>,
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
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
        val constructors: Map<String, FunkType?>, // null = no data, FunkType = constructor data
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
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
    data class ListType(val elementType: FunkType) : FunkType

    @Serializable
    data class MapType(val keyType: FunkType, val valueType: FunkType) : FunkType

    @Serializable
    data class SetType(val elementType: FunkType) : FunkType

    // Generic type application (e.g., Option Int, Result String Error, MyType 'a 'b)
    // This represents applying type arguments to a type constructor
    @Serializable
    data class TypeApplication(val constructor: String, val args: List<FunkType>) : FunkType {
        override fun toString(): String = safeToString(
            "TypeApplication",
            truncatedContent = { "$constructor, ${args.size} args" },
            fullContent = { constructor }
        )
    }

    // Built-in ordering type for comparisons
    @Serializable
    object OrderingType : FunkType

    // Array types (for arrays [] syntax)
    @Serializable
    data class ArrayType(val elementType: FunkType) : FunkType

    // Function types
    @Serializable
    data class FunctionType(
        val paramType: FunkType,
        val returnType: FunkType,
        val effects: EffectRow? = null,  // null = pure function, non-null = effectful function
        val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()  // Ability constraints for this function
    ) : FunkType {
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
        val valueType: FunkType,
        val effects: EffectRow
    ) : FunkType {
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
        val rowVar: RowVar? = null // null = closed, non-null = open
    ) : FunkType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String {
            val effectList = effects.take(5).joinToString(", ")
            val suffix = if (effects.size > 5) ", ..." else ""
            return safeToString(
                "EffectRow",
                truncatedContent = { "${effects.size} effects" },
                fullContent = { "$effectList$suffix" }
            )
        }
    }

    // Function-level ability constraints
    @Serializable
    data class FunctionAbilityConstraint(
        val abilityName: String,
        val typeArgs: List<FunkType> = emptyList()  // Type arguments for the ability
    )

}

/**
 * Type schemes for let-polymorphism
 * Represents ∀α₁,α₂,...,αₙ. τ (forall type variables, type τ)
 */
@Serializable
data class TypeScheme(
    val quantifiedVars: Set<TypeVar>,
    val type: FunkType,
    val constraints: List<AbilityConstraint> = emptyList()
) {
    /**
     * Check if this type scheme is monomorphic (no quantified variables)
     */
    val isMonomorphic: Boolean get() = quantifiedVars.isEmpty()

    /**
     * Instantiate this type scheme with fresh type variables
     */
    fun instantiate(): FunkType {
        if (isMonomorphic) return type

        val substitution = quantifiedVars.associateWith {
            FunkType.VarType(TypeVar.fresh())
        }
        return applySubstitutionToType(type, substitution)
    }

    companion object {
        /**
         * Generalize a type into a type scheme by quantifying over free type variables
         */
        fun generalize(type: FunkType, constraints: List<AbilityConstraint> = emptyList()): TypeScheme {
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
    private fun applySubstitutionToType(type: FunkType, substitution: Map<TypeVar, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> substitution[type.variable] ?: type
            is FunkType.FunctionType -> FunkType.FunctionType(
                applySubstitutionToType(type.paramType, substitution),
                applySubstitutionToType(type.returnType, substitution),
                type.effects,
                type.abilityConstraints
            )
            is FunkType.RecordType -> FunkType.RecordType(
                type.fields.mapValues { (_, fieldType) ->
                    applySubstitutionToType(fieldType, substitution)
                },
                type.rowVar
            )
            is FunkType.VariantType -> FunkType.VariantType(
                type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applySubstitutionToType(it, substitution) }
                },
                type.rowVar
            )
            is FunkType.TypeApplication -> FunkType.TypeApplication(
                type.constructor,
                type.args.map { applySubstitutionToType(it, substitution) }
            )
            is FunkType.ListType -> FunkType.ListType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.MapType -> FunkType.MapType(
                applySubstitutionToType(type.keyType, substitution),
                applySubstitutionToType(type.valueType, substitution)
            )
            is FunkType.SetType -> FunkType.SetType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.ArrayType -> FunkType.ArrayType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is FunkType.SuspendedType -> FunkType.SuspendedType(
                applySubstitutionToType(type.valueType, substitution),
                type.effects
            )
            // Concrete types remain unchanged
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType, is FunkType.BytesType, is FunkType.ByteBufferType -> type
            is FunkType.EffectRow -> type // Effects don't contain type variables for now
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
    data class TypeAnnotation(val annotatedType: FunkType) : UsageContext

    /**
     * Binary operator usage - the value is used with a binary operator
     * Example: x + 10 (x is left operand, operator is +, right operand type is Int)
     */
    @Serializable
    data class BinaryOp(
        val operator: String,
        val position: OperandPosition,  // LEFT or RIGHT
        val otherOperandType: FunkType
    ) : UsageContext

    /**
     * Field access usage - a specific field is accessed on the value
     * Example: user.name (implies user must be a record with a `name` field)
     */
    @Serializable
    data class FieldAccess(
        val fieldName: String,
        val expectedFieldType: FunkType
    ) : UsageContext

    /**
     * Function call usage - the value is called as a function
     * Example: f {x = 10} (implies f must be a function taking {x: Int})
     */
    @Serializable
    data class FunctionCall(
        val argumentType: FunkType,
        val expectedReturnType: FunkType? = null
    ) : UsageContext

    /**
     * Match pattern usage - the value is matched against a pattern
     * Example: match x { Some {val} -> ... } (implies x must be Option type)
     */
    @Serializable
    data class MatchPattern(
        val constructorName: String,
        val constructorDataType: FunkType?
    ) : UsageContext

    /**
     * Function return usage - the value is returned from a function
     * This propagates the expected return type into the expression
     */
    @Serializable
    data class FunctionReturn(
        val expectedReturnType: FunkType
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
    val inferredType: FunkType,
    val sourceLocation: SourceLocation? = null
)
