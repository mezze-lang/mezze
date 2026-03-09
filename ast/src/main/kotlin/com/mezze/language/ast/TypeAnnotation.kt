@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mezze.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Type annotations for the parser.
 *
 * These represent type annotations as written by the user in source code.
 * They are converted to internal MezzeType representation by the type checker.
 *
 * Examples:
 * - Int → IdentifierTypeAnnotation("Int")
 * - Option Int → TypeApplicationAnnotation("Option", [IdentifierTypeAnnotation("Int")])
 * - {x: Int} -> String → FunctionTypeAnnotation(...)
 *
 * Part of the refactored AST module.
 */

// ============================================================================
// TYPE ANNOTATIONS (Parser Output)
// ============================================================================

@Serializable
@JsonClassDiscriminator("typeAnnotationKind")
sealed interface TypeAnnotation

@Serializable
data class IdentifierTypeAnnotation(
    val name: String,
    val sourceLocation: SourceLocation? = null
) : TypeAnnotation

@Serializable
data class QualifiedTypeAnnotation(
    val modulePath: List<String>,
    val typeName: String,
    val args: List<TypeAnnotation> = emptyList()
) : TypeAnnotation

@Serializable
data class TypeApplicationAnnotation(
    val constructor: String,
    val args: List<TypeAnnotation>
) : TypeAnnotation

@Serializable
data class RecordTypeAnnotation(
    val entries: List<RecordTypeEntry>,
    val restField: String? = null
) : TypeAnnotation

@Serializable
data class VariantTypeAnnotation(
    val constructors: List<VariantTypeConstructor>
) : TypeAnnotation

@Serializable
data class FunctionTypeAnnotation(
    val paramType: TypeAnnotation,
    val returnType: TypeAnnotation,
    val effects: EffectRowAnnotation? = null,
    val abilityConstraints: List<FunctionAbilityConstraint> = emptyList()
) : TypeAnnotation

@Serializable
data class SeqTypeAnnotation(
    val elementType: TypeAnnotation
) : TypeAnnotation

/**
 * An effect annotation with optional associated type bindings.
 *
 * Examples:
 * - Simple effect: EffectAnnotation("Exception")
 * - With bindings: EffectAnnotation("Exception", {'error -> IdentifierTypeAnnotation("String")})
 * - Multiple bindings: EffectAnnotation("Generator", {'item -> ..., 'result -> ...})
 */
@Serializable
data class EffectAnnotation(
    val name: String,
    val associatedTypes: Map<String, TypeAnnotation> = emptyMap()
) {
    /** True if this effect has any associated type bindings specified */
    val hasAssociatedTypes: Boolean get() = associatedTypes.isNotEmpty()
}

/**
 * Effect row annotation for function types.
 *
 * Supports both simple effect names (backward compatible) and effect annotations
 * with associated type bindings.
 *
 * Examples:
 * - `<Log, FileSystem>` - simple effects only
 * - `<Exception having 'error = String>` - effect with associated type
 * - `<Log, Exception having 'error = String, Generator having 'item = Int>` - mixed
 */
@Serializable
data class EffectRowAnnotation(
    val effects: List<String>,  // Simple effect names (backward compatible)
    val effectAnnotations: List<EffectAnnotation> = emptyList(),  // Effects with associated types
    val isPolymorphic: Boolean = false,  // true when <...> or <Effect1, ...> is used
    val isPerformed: Boolean = false     // true when <*> or <Effect1, *> is used
) : TypeAnnotation {
    /** Get all effect names (from both simple effects and effect annotations) */
    val allEffectNames: Set<String> get() = effects.toSet() + effectAnnotations.map { it.name }.toSet()

    /** Get associated types for an effect by name, if any were annotated */
    fun getAssociatedTypes(effectName: String): Map<String, TypeAnnotation>? {
        return effectAnnotations.find { it.name == effectName }?.associatedTypes
    }

    companion object {
        /** Create a simple effect row with just effect names (backward compatible) */
        fun simple(effects: List<String>, isPolymorphic: Boolean = false, isPerformed: Boolean = false): EffectRowAnnotation {
            return EffectRowAnnotation(effects, emptyList(), isPolymorphic, isPerformed)
        }

        /** Create an effect row with a single effect annotation */
        fun withAnnotation(annotation: EffectAnnotation, isPolymorphic: Boolean = false, isPerformed: Boolean = false): EffectRowAnnotation {
            return EffectRowAnnotation(emptyList(), listOf(annotation), isPolymorphic, isPerformed)
        }
    }
}

@Serializable
data class RecordTypeEntry(
    val name: String,
    val type: TypeAnnotation
)

@Serializable
data class VariantTypeConstructor(
    val name: String,
    val dataType: TypeAnnotation? = null
)

// ============================================================================
// TYPE ANNOTATION CONVERSION
// ============================================================================

/**
 * Convert TypeAnnotation (parser output) to internal MezzeType representation (type checker).
 */
fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
    return when (annotation) {
        is IdentifierTypeAnnotation -> {
            when (annotation.name) {
                "Int" -> MezzeType.IntType
                "Float" -> MezzeType.FloatType
                "String" -> MezzeType.StringType
                "Boolean" -> MezzeType.BoolType
                "Nat" -> MezzeType.NatType
                "Frac" -> MezzeType.FracType
                "Ordering" -> MezzeType.OrderingType
                else -> MezzeType.VarType(TypeVar(annotation.name))
            }
        }
        is FunctionTypeAnnotation -> {
            // Validate that parameter type is a RecordTypeAnnotation
            if (annotation.paramType !is RecordTypeAnnotation) {
                throw IllegalArgumentException(
                    "Function parameter type must be a record type (e.g., {x: Int} -> Int), " +
                    "but got: ${annotation.paramType::class.simpleName}"
                )
            }

            val paramType = convertTypeAnnotation(annotation.paramType)
            val returnType = convertTypeAnnotation(annotation.returnType)

            // Convert effect annotations (including polymorphic and performed flags)
            val effects = annotation.effects?.let { effectRow ->
                val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null

                // Convert effect annotations with associated types to EffectInstance objects
                val effectInstances = effectRow.effectAnnotations.map { effectAnnotation ->
                    MezzeType.EffectInstance(
                        name = effectAnnotation.name,
                        associatedTypes = effectAnnotation.associatedTypes.mapValues { (_, typeAnnotation) ->
                            convertTypeAnnotation(typeAnnotation)
                        }
                    )
                }.toSet()

                // Simple effect names go into effects set, annotated effects go into effectInstances
                MezzeType.EffectRow(
                    effects = effectRow.effects.toSet(),
                    effectInstances = effectInstances,
                    rowVar = rowVar,
                    performed = effectRow.isPerformed
                )
            }

            // Convert ability constraints (including associated type bindings)
            val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                MezzeType.FunctionAbilityConstraint(
                    constraint.abilityName,
                    constraint.typeArgs.map { convertTypeAnnotation(it) },
                    constraint.associatedTypeBindings.mapValues { (_, typeAnnotation) ->
                        convertTypeAnnotation(typeAnnotation)
                    }
                )
            }

            MezzeType.FunctionType(
                paramType,
                returnType,
                effects,
                abilityConstraints
            )
        }
        is RecordTypeAnnotation -> {
            val fieldTypes = annotation.entries.associate { entry ->
                entry.name to convertTypeAnnotation(entry.type)
            }
            // If restField is present (e.g., {x: Int, ...}), create open record with row variable
            val rowVar = if (annotation.restField != null) {
                RowVar(annotation.restField)
            } else {
                null
            }
            MezzeType.RecordType(fieldTypes, rowVar)
        }
        is VariantTypeAnnotation -> {
            val constructorTypes = annotation.constructors.associate { constructor ->
                constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
            }
            MezzeType.VariantType(constructorTypes)
        }
        is TypeApplicationAnnotation -> {
            // For parameterized types like Option, Result, List, and user-defined generic types
            // We create a TypeApplication that represents the applied type
            // This will be resolved later during type checking
            //
            // Note: List, Map, Set are NOT built-in types in Mezze - they are user-defined
            // variant types from the standard library. We treat them like any other
            // user-defined type.
            if (annotation.args.isNotEmpty()) {
                // This is a parameterized type application (e.g., List Int, Option String, Result Int Error)
                // We'll represent it as a type application for now
                // The type checker will resolve this to the actual variant type
                MezzeType.TypeApplication(
                    annotation.constructor,
                    annotation.args.map { convertTypeAnnotation(it) }
                )
            } else {
                // Simple type constructor without arguments (will be resolved as type variable)
                MezzeType.VarType(TypeVar(annotation.constructor))
            }
        }
        is SeqTypeAnnotation -> {
            MezzeType.SeqType(convertTypeAnnotation(annotation.elementType))
        }
        is QualifiedTypeAnnotation -> {
            // Qualified type like codec::Entry String Json
            // Create a TypeApplication with the fully qualified name
            val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
            if (annotation.args.isNotEmpty()) {
                MezzeType.TypeApplication(
                    qualifiedName,
                    annotation.args.map { convertTypeAnnotation(it) }
                )
            } else {
                MezzeType.VarType(TypeVar(qualifiedName))
            }
        }
        else -> {
            throw RuntimeException("Unsupported type annotation: ${annotation::class.simpleName}")
        }
    }
}
