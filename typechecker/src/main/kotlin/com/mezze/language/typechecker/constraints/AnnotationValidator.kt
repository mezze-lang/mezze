package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal class AnnotationValidator(
    private val registry: DeclarationRegistry,
) {
    fun validateAnnotationAgainstExpression(
        binding: ParsedLetDecl,
        annotatedType: MezzeType,
        annotation: ParsedTypeAnnotation,
        expression: ParsedExpr,
        inferredType: MezzeType
    ) {
        if (expression is ParsedRecordLit) {
            val resolvedRecordInfo = resolveToRecordType(annotatedType)
            if (resolvedRecordInfo != null) {
                val (annotatedFields, isOpen) = resolvedRecordInfo
                val literalFields = mutableSetOf<String>()

                for (entry in expression.entries) {
                    when (entry) {
                        is ParsedRecordEntry.Field -> literalFields.add(entry.name)
                        is ParsedRecordEntry.Spread -> {
                            return
                        }
                    }
                }

                if (!isOpen) {
                    val extraFields = literalFields - annotatedFields
                    if (extraFields.isNotEmpty()) {
                        throw TypeCheckError(
                            "Type annotation mismatch: record has extra field(s) ${extraFields.sorted().joinToString(", ")} " +
                            "not present in closed record type annotation { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }} }",
                            expression.loc,
                            code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                        )
                    }
                }

                val missingFields = annotatedFields - literalFields
                if (missingFields.isNotEmpty()) {
                    throw TypeCheckError(
                        "Type annotation mismatch: record is missing field(s) ${missingFields.sorted().joinToString(", ")} " +
                        "required by ${if (isOpen) "open" else "closed"} record type { ${annotatedFields.sorted().joinToString(", ") { "$it: ..." }}${if (isOpen) ", ..." else ""} }",
                        expression.loc,
                        code = ErrorCode.T0202_RECORD_MISSING_FIELDS,
                    )
                }
            }
        }

        if (annotatedType is MezzeType.FunctionType && expression is ParsedLambda) {
            val annotatedParamType = annotatedType.paramType

            val expectedParamCount = when (annotatedParamType) {
                is MezzeType.RecordType -> annotatedParamType.fields.size
                else -> 1
            }

            val actualParamCount = when (val params = expression.params) {
                is ParsedLambdaParams.Untyped -> params.names.size
                is ParsedLambdaParams.Typed -> params.fields.size
            }

            if (expectedParamCount != actualParamCount) {
                throw TypeCheckError(
                    "Type annotation mismatch: function expects $expectedParamCount parameter(s) " +
                    "but lambda has $actualParamCount parameter(s)",
                    expression.loc,
                    code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                    hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                )
            }
        }
    }

    fun validatePolymorphismPreserved(
        annotationType: MezzeType,
        substitution: Substitution,
        varName: String,
        sourceLoc: Loc? = null,
        abilityConstrainedTypeVars: Set<TypeVar> = emptySet()
    ) {
        val annotationTypeVars = collectTypeVars(annotationType)

        for (typeVar in annotationTypeVars) {
            if (abilityConstrainedTypeVars.any {
                it.name.removePrefix("'") == typeVar.name.removePrefix("'") || it.id == typeVar.id
            }) {
                continue
            }

            val substituted = substitution.apply(MezzeType.VarType(typeVar))
            if (substituted !is MezzeType.VarType) {
                val substitutedTypeVars = collectTypeVars(substituted)
                if (substitutedTypeVars.isEmpty()) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares type variable '${typeVar.name}' " +
                        "but implementation forces it to concrete type '${prettyPrintType(substituted)}'. " +
                        "The implementation must work for all types, not just specific ones.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                    )
                }
            }
        }

        // Skolem distinctness: distinct annotation variables (`'a`, `'b`) must
        // stay distinct in the body. If two differently-named annotation vars
        // resolve to the SAME variable, the body conflated them — e.g.
        // `{ a: 'a, b: 'b } -> 'a` implemented as `{ a, b } -> b` really has type
        // `-> 'b`, not `-> 'a`. Treating each quantified var as a rigid skolem,
        // unifying two skolems is illegal; we detect it here by seeing two source
        // names collapse onto one resolved var. Vars pinned by ability
        // constraints are skipped (same as above).
        val nameToResolved = mutableMapOf<String, TypeVar>()
        for (typeVar in annotationTypeVars) {
            if (abilityConstrainedTypeVars.any {
                    it.name.removePrefix("'") == typeVar.name.removePrefix("'") || it.id == typeVar.id
                }) {
                continue
            }
            val sourceName = typeVar.name.removePrefix("'")
            if (sourceName.isEmpty()) continue
            val resolved = substitution.apply(MezzeType.VarType(typeVar))
            if (resolved !is MezzeType.VarType) continue  // concrete case handled above
            val resolvedVar = resolved.variable
            for ((otherName, otherResolved) in nameToResolved) {
                if (otherName != sourceName && otherResolved.id == resolvedVar.id) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Annotation declares distinct type " +
                        "variables '$otherName' and '$sourceName', but the implementation forces them " +
                        "to be the same type. Each must remain independent.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("give the two positions the same variable in the annotation if they must match, or change the body so they stay independent"),
                    )
                }
            }
            nameToResolved[sourceName] = resolvedVar
        }
    }

    fun validateEffectsNotOverpromised(
        annotationType: MezzeType,
        inferredType: MezzeType,
        varName: String,
        sourceLoc: Loc? = null
    ) {
        if (annotationType is MezzeType.FunctionType && inferredType is MezzeType.FunctionType) {
            val annotatedEffects = annotationType.effects?.effects ?: emptySet()
            val inferredEffects = inferredType.effects?.effects ?: emptySet()

            val isPolymorphic = annotationType.effects?.isOpen == true
            val overpromisedEffects = annotatedEffects - inferredEffects
            if (overpromisedEffects.isNotEmpty() && !isPolymorphic) {
                throw TypeCheckError(
                    "Type annotation mismatch for '$varName': Annotation declares effects ${overpromisedEffects.joinToString(", ")} " +
                    "which are not used in the implementation.",
                    sourceLoc,
                    code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                    hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                )
            }
        }
    }

    fun validateAbilityOnCorrectTypeVar(
        annotationType: MezzeType,
        inferredAbilityConstraints: List<Constraint.AbilityRequired>,
        varName: String,
        substitution: Substitution,
        sourceLoc: Loc? = null
    ) {
        if (annotationType !is MezzeType.FunctionType) return

        val annotatedAbilities = annotationType.abilityConstraints
        val annotationTypeVars = collectTypeVars(annotationType)

        val actualUsageConstraints = inferredAbilityConstraints.filter { constraint ->
            constraint.context == null || !constraint.context.startsWith("Type annotation on")
        }

        fun resolveToSameType(tv1: TypeVar, tv2: TypeVar): Boolean {
            if (tv1.id == tv2.id) return true
            val resolved1 = substitution.apply(MezzeType.VarType(tv1))
            val resolved2 = substitution.apply(MezzeType.VarType(tv2))
            if (resolved1 == resolved2 ||
                (resolved1 is MezzeType.VarType && resolved1.variable.id == tv2.id) ||
                (resolved2 is MezzeType.VarType && resolved2.variable.id == tv1.id)) return true
            if (resolved1 is MezzeType.VarType && resolved2 is MezzeType.VarType &&
                resolved1.variable.name == resolved2.variable.name) return true
            return false
        }

        for (usage in actualUsageConstraints) {
            if (WellKnownHashConstants.isInspect(usage.abilityHash)) continue

            val matchingAnnotatedList = annotatedAbilities.filter { it.abilityName == usage.abilityName }

            if (matchingAnnotatedList.isNotEmpty()) {
                val resolvedUsageTypeVar = substitution.apply(MezzeType.VarType(usage.typeVar))
                val hasMatchingTypeVar = matchingAnnotatedList.any { matchingAnnotated ->
                    matchingAnnotated.typeArgs.any { annotatedTypeArg ->
                        when {
                            annotatedTypeArg is MezzeType.VarType ->
                                resolveToSameType(usage.typeVar, annotatedTypeArg.variable)
                            else -> resolvedUsageTypeVar == annotatedTypeArg
                        }
                    }
                }

                if (!hasMatchingTypeVar) {
                    val declaredVars = matchingAnnotatedList.flatMap { it.typeArgs }
                        .filterIsInstance<MezzeType.VarType>()
                        .map { it.variable.name }
                        .distinct()
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Ability '${usage.abilityName}' is applied to wrong type variable. " +
                        "Annotation declares constraint on ${declaredVars.joinToString()} but implementation uses it on '${usage.typeVar.name}'.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                    )
                }
            } else {
                val isOnAnnotationTypeVar = annotationTypeVars.any { annotationVar ->
                    resolveToSameType(usage.typeVar, annotationVar)
                }
                if (isOnAnnotationTypeVar) {
                    throw TypeCheckError(
                        "Type annotation mismatch for '$varName': Implementation requires '${usage.abilityName}' on type variable '${usage.typeVar.name}', " +
                        "but the annotation does not declare this constraint.",
                        sourceLoc,
                        code = ErrorCode.T0210_ANNOTATION_VS_BODY_MISMATCH,
                        hints = listOf("either change the annotation to match the body, or change the body to produce the annotated type"),
                    )
                }
            }
        }
    }

    private fun collectTypeVars(type: MezzeType): Set<TypeVar> {
        val result = mutableSetOf<TypeVar>()
        collectTypeVariables(type, result)
        return result
    }

    private fun containsConcreteTypes(type: MezzeType): Boolean = when (type) {
        is MezzeType.VarType -> false
        is MezzeType.VariantType -> type.constructors.isNotEmpty() || type.anyChild { containsConcreteTypes(it) }
        else -> type.anyChild { containsConcreteTypes(it) } || !type.anyChild { true }
    }

    private fun resolveToRecordType(type: MezzeType): Pair<Set<String>, Boolean>? {
        when (type) {
            is MezzeType.RecordType -> {
                return Pair(type.fields.keys, type.rowVar != null)
            }
            is MezzeType.TypeApplication -> {
                val typeDecl = registry.typeDeclarations[type.constructor] ?: return null
                val definition = typeDecl.definition
                if (definition is ParsedRecordDef) {
                    val fieldNames = definition.fields.map { it.name }.toSet()
                    return Pair(fieldNames, definition.isOpen)
                }
                return null
            }
            else -> return null
        }
    }
}
