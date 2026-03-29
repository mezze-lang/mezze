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
    object BoolType : MezzeType

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
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val typeName: String? = null, // Original type alias name (e.g., "Person" for `type Person = { ... }`)
        val typeHash: String? = null  // Content hash of the type declaration (for rename-safe identity)
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            typeName ?: "RecordType",
            truncatedContent = { "${fields.size} fields" },
            fullContent = { "${fields.keys.take(3)}${if (fields.size > 3) "..." else ""}" }
        )
    }

    // Variant types
    @Serializable
    data class VariantType(
        val constructors: Map<String, MezzeType?>, // null = no data, MezzeType = constructor data
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val typeName: String? = null, // Original type alias name (e.g., "Person" for `type Person = | ... |`)
        val typeHash: String? = null  // Content hash of the type declaration (for rename-safe identity)
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        override fun toString(): String = safeToString(
            typeName ?: "VariantType",
            truncatedContent = { "${constructors.size} constructors" },
            fullContent = { "${constructors.keys.take(3)}${if (constructors.size > 3) "..." else ""}" }
        )
    }

    // Newtype — compile-time wrapper, erased at runtime.
    // newtype UserId = UserId Int → NewtypeType("UserId", "UserId", IntType)
    // newtype Point = Point {x: Float, y: Float} → NewtypeType("Point", "Point", RecordType({x: Float, y: Float}))
    // At runtime, UserId 42 is just 42. Point {x=1.0, y=2.0} is just {x=1.0, y=2.0}.
    // Does NOT auto-derive Eq/Ord/Codec — requires explicit impl.
    // Constructor is private to defining module (opaque type).
    @Serializable
    data class NewtypeType(
        val name: String,            // Type name: "UserId"
        val constructor: String,     // Constructor name: "UserId"
        val wrappedType: MezzeType   // Inner type: IntType, RecordType, etc.
    ) : MezzeType {
        override fun toString(): String = safeToString(
            name,
            truncatedContent = { "wraps ${wrappedType::class.simpleName}" },
            fullContent = { name }
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
    data class TypeApplication(val constructor: String, val args: List<MezzeType>, val typeHash: String? = null) : MezzeType {
        override fun toString(): String = safeToString(
            "TypeApplication",
            truncatedContent = { "$constructor, ${args.size} args" },
            fullContent = { constructor }
        )
    }

    // Built-in ordering type for comparisons
    @Serializable
    object OrderingType : MezzeType

    // Seq types (for [] syntax)
    @Serializable
    data class SeqType(val elementType: MezzeType) : MezzeType

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

    /**
     * An effect instance with its associated type bindings.
     * Example: Exception with 'error = String
     */
    @Serializable
    data class EffectInstance(
        val name: String,
        val associatedTypes: Map<String, MezzeType> = emptyMap()
    ) {
        override fun toString(): String {
            return if (associatedTypes.isEmpty()) {
                name
            } else {
                val bindings = associatedTypes.entries.joinToString(", ") { (k, v) ->
                    "'$k = $v"
                }
                "$name having $bindings"
            }
        }

        /** Check if this effect matches another by name (ignoring associated types) */
        fun matchesName(other: String): Boolean = name == other

        /** Check if this effect matches another (same name, compatible associated types) */
        fun matches(other: EffectInstance): Boolean = name == other.name
    }

    // Effect rows
    @Serializable
    data class EffectRow(
        val effects: Set<String>,
        val effectInstances: Set<EffectInstance> = emptySet(), // Effects with associated type info
        val effectHashes: Map<String, String> = emptyMap(),  // effectName → effectDeclHash
        val rowVar: RowVar? = null, // null = closed, non-null = open
        val performed: Boolean = false // true = effects have been performed via 'perform'
    ) : MezzeType {
        val isOpen: Boolean get() = rowVar != null
        val isClosed: Boolean get() = rowVar == null

        /** True if this is truly pure - no effects, not open, nothing performed */
        val isPure: Boolean get() = effects.isEmpty() && effectInstances.isEmpty() && rowVar == null && !performed

        /** Get all effect names (from both simple effects and effect instances) */
        val allEffectNames: Set<String> get() = effects + effectInstances.map { it.name }.toSet()

        /** Get associated types for an effect by name */
        fun getAssociatedTypes(effectName: String): Map<String, MezzeType>? {
            return effectInstances.find { it.name == effectName }?.associatedTypes
        }

        /** Create a new EffectRow with an effect instance added/updated */
        fun withEffectInstance(instance: EffectInstance): EffectRow {
            // Remove from simple effects if present, add to instances
            val newEffects = effects - instance.name
            val newInstances = effectInstances.filterNot { it.name == instance.name }.toSet() + instance
            return copy(effects = newEffects, effectInstances = newInstances)
        }

        override fun toString(): String {
            // Combine simple effects and effect instances for display
            val simpleEffects = effects.take(3).toList()
            val instanceStrs = effectInstances.take(3).map { it.toString() }
            val allEffects = simpleEffects + instanceStrs
            val effectList = allEffects.take(5).joinToString(", ")
            val totalCount = effects.size + effectInstances.size
            val suffix = when {
                performed && totalCount > 0 -> ", *"
                performed -> "*"
                totalCount > 5 -> ", ..."
                else -> ""
            }
            return safeToString(
                "EffectRow",
                truncatedContent = { if (performed) "$totalCount effects, *" else "$totalCount effects" },
                fullContent = { "$effectList$suffix" }
            )
        }

        companion object {
            /** Create an EffectRow from simple effect names (backward compatible) */
            fun simple(effects: Set<String>, rowVar: RowVar? = null, performed: Boolean = false, effectHashes: Map<String, String> = emptyMap()): EffectRow {
                return EffectRow(effects = effects, effectInstances = emptySet(), effectHashes = effectHashes, rowVar = rowVar, performed = performed)
            }

            /** Create an EffectRow with a single effect instance (with associated types) */
            fun withInstance(instance: EffectInstance, rowVar: RowVar? = null, performed: Boolean = false, effectHashes: Map<String, String> = emptyMap()): EffectRow {
                return EffectRow(effects = emptySet(), effectInstances = setOf(instance), effectHashes = effectHashes, rowVar = rowVar, performed = performed)
            }
        }
    }

    // Function-level ability constraints
    @Serializable
    data class FunctionAbilityConstraint(
        val abilityName: String,
        val typeArgs: List<MezzeType> = emptyList(),  // Type arguments for the ability
        val associatedTypeBindings: Map<String, MezzeType> = emptyMap(),  // Associated type bindings (e.g., 'item = Int)
        val abilityHash: String? = null  // Content hash of the ability declaration
    ) {
        /** True if this constraint has any associated type bindings specified */
        val hasAssociatedTypes: Boolean get() = associatedTypeBindings.isNotEmpty()
    }

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
                type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applySubstitutionToType(it, substitution) }
                },
                type.rowVar,
                typeName = type.typeName,
                typeHash = type.typeHash
            )
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                applySubstitutionToType(type.wrappedType, substitution)
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                type.constructor,
                type.args.map { applySubstitutionToType(it, substitution) },
                type.typeHash
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
            is MezzeType.SeqType -> MezzeType.SeqType(
                applySubstitutionToType(type.elementType, substitution)
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                applySubstitutionToType(type.valueType, substitution),
                type.effects
            )
            // Concrete types remain unchanged
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
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
