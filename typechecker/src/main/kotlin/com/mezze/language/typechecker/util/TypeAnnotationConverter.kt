package com.mezze.language.typechecker.util

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*

/**
 * Shared utility for converting [ParsedTypeAnnotation] to [MezzeType].
 *
 * This consolidates the duplicate `convertParsedTypeAnnotation` logic from
 * ModuleResolver (lossy mode) and TypeValidator (faithful mode).
 *
 * Other copies that remain local due to context-specific behavior:
 * - ContextBuilder: does type declaration lookups (currentTypeDecls, currentEffectDecls, currentAbilityDecls)
 * - TypedAstBuilder: has special Unit handling and uppercase->TypeApp logic with typeVarCache
 * - ExpressionConstraints: does import resolution, ability lookup, and hermetic effect validation
 */
object TypeAnnotationConverter {

    /**
     * Convert a [ParsedTypeAnnotation] to a [MezzeType].
     *
     * @param annotation The parsed type annotation to convert.
     * @param faithful If true, preserve TypeApp/Variant/QualifiedType structure faithfully.
     *                 If false, return VarType(fresh()) for complex types (lossy mode).
     * @param includeAbilityConstraints If true, convert ability constraints on FunctionType.
     *                                  If false, omit them.
     * @param typeVarCache If provided, reuse TypeVars for repeated names (e.g., 'a appears twice).
     *                     If null, create fresh TypeVars each time a non-primitive named type is seen.
     */
    fun convert(
        annotation: ParsedTypeAnnotation,
        faithful: Boolean = true,
        includeAbilityConstraints: Boolean = true,
        typeVarCache: MutableMap<String, TypeVar>? = null
    ): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                if (faithful) {
                    // Named type var: use cache if provided, else create a named TypeVar
                    MezzeType.VarType(
                        if (typeVarCache != null) {
                            typeVarCache.getOrPut(annotation.name) { TypeVar(annotation.name) }
                        } else {
                            TypeVar(annotation.name)
                        }
                    )
                } else {
                    // Lossy: fresh TypeVar for any non-primitive named type
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedFunctionType -> {
                val effectRow = annotation.effects?.let { row ->
                    val rowVar = if (row.isPolymorphic) RowVar.fresh() else null
                    MezzeType.EffectRow.simple(row.effects.toSet(), rowVar, row.isPerformed)
                }
                val abilityConstraints = if (includeAbilityConstraints) {
                    annotation.abilityConstraints.map { c ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = c.abilityName,
                            typeArgs = c.typeArgs.map { convert(it, faithful, includeAbilityConstraints, typeVarCache) },
                            abilityHash = com.mezze.language.typechecker.WellKnownHashConstants.resolveAbilityHash(c.abilityName)
                        )
                    }
                } else {
                    emptyList()
                }
                MezzeType.FunctionType(
                    paramType = convert(annotation.paramType, faithful, includeAbilityConstraints, typeVarCache),
                    returnType = convert(annotation.returnType, faithful, includeAbilityConstraints, typeVarCache),
                    effects = effectRow,
                    abilityConstraints = abilityConstraints
                )
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(annotation.entries.associate { e ->
                    e.name to convert(e.type, faithful, includeAbilityConstraints, typeVarCache)
                })
            }
            is ParsedSeqType -> MezzeType.SeqType(convert(annotation.elementType, faithful, includeAbilityConstraints, typeVarCache))
            is ParsedVariantType -> {
                if (faithful) {
                    MezzeType.VariantType(
                        constructors = annotation.constructors.associate {
                            it.name to it.dataType?.let { dt -> convert(dt, faithful, includeAbilityConstraints, typeVarCache) }
                        },
                        rowVar = null
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedTypeApp -> {
                if (faithful) {
                    MezzeType.TypeApplication(
                        annotation.constructor,
                        annotation.args.map { convert(it, faithful, includeAbilityConstraints, typeVarCache) }
                    )
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
            is ParsedQualifiedType -> {
                if (faithful) {
                    MezzeType.VarType(TypeVar.fresh())
                } else {
                    MezzeType.VarType(TypeVar.fresh())
                }
            }
        }
    }
}
