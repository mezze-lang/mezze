@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.funk.language.ast

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Type annotations for the parser.
 *
 * These represent type annotations as written by the user in source code.
 * They are converted to internal FunkType representation by the type checker.
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
    val name: String
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
data class ArrayTypeAnnotation(
    val elementType: TypeAnnotation
) : TypeAnnotation

@Serializable
data class EffectRowAnnotation(
    val effects: List<String>,
    val isPolymorphic: Boolean = false  // true when <...> or <Effect1, ...> is used
) : TypeAnnotation

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
 * Convert TypeAnnotation (parser output) to internal FunkType representation (type checker).
 */
fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
    return when (annotation) {
        is IdentifierTypeAnnotation -> {
            when (annotation.name) {
                "Int" -> FunkType.IntType
                "Float" -> FunkType.FloatType
                "String" -> FunkType.StringType
                "Boolean" -> FunkType.BooleanType
                "Nat" -> FunkType.NatType
                "Frac" -> FunkType.FracType
                "Ordering" -> FunkType.OrderingType
                else -> FunkType.VarType(TypeVar(annotation.name))
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

            // Convert effect annotations
            val effects = annotation.effects?.let { effectRow ->
                FunkType.EffectRow(effectRow.effects.toSet())
            }

            // Convert ability constraints
            val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                FunkType.FunctionAbilityConstraint(
                    constraint.abilityName,
                    constraint.typeArgs.map { convertTypeAnnotation(it) }
                )
            }

            FunkType.FunctionType(
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
            FunkType.RecordType(fieldTypes, rowVar)
        }
        is VariantTypeAnnotation -> {
            val constructorTypes = annotation.constructors.associate { constructor ->
                constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
            }
            FunkType.VariantType(constructorTypes)
        }
        is TypeApplicationAnnotation -> {
            // For parameterized types like Option, Result, List, and user-defined generic types
            // We create a TypeApplication that represents the applied type
            // This will be resolved later during type checking
            //
            // Note: List, Map, Set are NOT built-in types in Funk - they are user-defined
            // variant types from the standard library. We treat them like any other
            // user-defined type.
            if (annotation.args.isNotEmpty()) {
                // This is a parameterized type application (e.g., List Int, Option String, Result Int Error)
                // We'll represent it as a type application for now
                // The type checker will resolve this to the actual variant type
                FunkType.TypeApplication(
                    annotation.constructor,
                    annotation.args.map { convertTypeAnnotation(it) }
                )
            } else {
                // Simple type constructor without arguments (will be resolved as type variable)
                FunkType.VarType(TypeVar(annotation.constructor))
            }
        }
        is ArrayTypeAnnotation -> {
            FunkType.ArrayType(convertTypeAnnotation(annotation.elementType))
        }
        else -> {
            throw RuntimeException("Unsupported type annotation: ${annotation::class.simpleName}")
        }
    }
}
