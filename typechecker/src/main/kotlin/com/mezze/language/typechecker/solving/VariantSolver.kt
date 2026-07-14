package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.WellKnownTypeApp

internal class VariantSolver(
    private val registry: DeclarationRegistry,
    private val patternBasedRowVars: MutableSet<RowVar>,
    private val mustBeOpenRowVars: MutableSet<RowVar>,
) {

    private val constructorIndex: Map<String, List<Pair<ParsedTypeDecl, ParsedVariantDef>>> by lazy {
        buildConstructorIndex()
    }

    private fun buildConstructorIndex(): Map<String, List<Pair<ParsedTypeDecl, ParsedVariantDef>>> {
        val index = mutableMapOf<String, MutableList<Pair<ParsedTypeDecl, ParsedVariantDef>>>()
        for (typeDecl in registry.allTypeDecls) {
            val definition = typeDecl.definition
            if (definition is ParsedVariantDef) {
                for (constructor in definition.constructors) {
                    index.getOrPut(constructor.name) { mutableListOf() }
                        .add(Pair(typeDecl, definition))
                }
            }
        }
        return index
    }

    private sealed class VariantInferenceResult {
        data class Success(
            val variantType: MezzeType,
            val payloadConstraints: List<Constraint.Equality> = emptyList()
        ) : VariantInferenceResult()
        data class Failure(val errorMessage: String) : VariantInferenceResult()
    }

    fun inferVariantTypes(constraints: List<Constraint>): Pair<List<Constraint.Equality>, List<String>> {
        val constructorsByType = mutableMapOf<MezzeType, MutableMap<String, MezzeType?>>()
        val firstLocByType = mutableMapOf<MezzeType, Loc?>()

        for (constraint in constraints) {
            if (constraint is Constraint.HasConstructor) {
                val variantType = constraint.variantType
                if (variantType is MezzeType.VarType) {
                    constructorsByType
                        .getOrPut(variantType) { mutableMapOf() }[constraint.constructorName] = constraint.payloadType
                    if (variantType !in firstLocByType) firstLocByType[variantType] = constraint.loc
                }
            }
        }

        val inferredEqualities = mutableListOf<Constraint.Equality>()
        val errors = mutableListOf<String>()

        for ((typeVar, constructorsWithPayloads) in constructorsByType) {
            val constructorNames = constructorsWithPayloads.keys
            val result = findVariantWithConstructors(
                constructorNames,
                constructorsWithPayloads,
                loc = firstLocByType[typeVar],
            )

            when {
                result is VariantInferenceResult.Success -> {
                    inferredEqualities.add(
                        Constraint.Equality(
                            left = typeVar,
                            right = result.variantType,
                            context = "Inferred from constructors: ${constructorNames.joinToString()}",
                            loc = firstLocByType[typeVar],
                        )
                    )
                    inferredEqualities.addAll(result.payloadConstraints)
                }
                result is VariantInferenceResult.Failure -> {
                    errors.add(result.errorMessage)
                }
                result == null -> {
                    // No matching variant found - anonymous/structural variant
                }
            }
        }

        return Pair(inferredEqualities, errors)
    }

    private fun findVariantWithConstructors(
        constructors: Set<String>,
        constructorPayloads: Map<String, MezzeType?> = emptyMap(),
        loc: Loc? = null,
    ): VariantInferenceResult? {
        val rawCandidates = mutableSetOf<Pair<ParsedTypeDecl, ParsedVariantDef>>()
        for (constructorName in constructors) {
            constructorIndex[constructorName]?.let { variants ->
                rawCandidates.addAll(variants)
            }
        }

        val inScope = inScopeAliasNamesForSolverOrNull()
        val candidateVariants = if (inScope != null) {
            rawCandidates.filterTo(mutableSetOf()) { (typeDecl, _) ->
                typeDecl.name in inScope
            }
        } else rawCandidates

        if (candidateVariants.isEmpty()) {
            return null
        }

        for ((typeDecl, definition) in candidateVariants) {
            val typeName = typeDecl.name
            val declaredConstructors = definition.constructors.map { it.name }.toSet()

            val matchingConstructors = constructors.intersect(declaredConstructors)

            if (matchingConstructors.isEmpty()) {
                continue
            }

            val typeParams = typeDecl.typeParams
            val freshArgs = typeParams.map { MezzeType.VarType(TypeVar.fresh()) }
            val typeParamSubst = typeParams.zip(freshArgs).toMap()

            val payloadConstraints = mutableListOf<Constraint.Equality>()
            for (constructorDef in definition.constructors) {
                val actualPayload = constructorPayloads[constructorDef.name]
                val declaredPayload = constructorDef.dataType?.let { convertTypeAnnotation(it, typeParamSubst) }

                if (actualPayload != null && declaredPayload != null) {
                    payloadConstraints.add(
                        Constraint.Equality(
                            left = actualPayload,
                            right = declaredPayload,
                            context = "Constructor '${constructorDef.name}' payload type",
                            loc = loc,
                        )
                    )
                }
            }

            if (definition.isOpen) {
                val variantType = if (freshArgs.isNotEmpty()) {
                    WellKnownTypeApp.of(typeName, freshArgs, typeHash = typeDecl.contentHash)
                } else {
                    WellKnownTypeApp.of(typeName, emptyList(), typeHash = typeDecl.contentHash)
                }
                return VariantInferenceResult.Success(variantType, payloadConstraints)
            } else {
                val invalidConstructors = constructors - declaredConstructors

                if (invalidConstructors.isNotEmpty()) {
                    val suggestions = invalidConstructors.map { invalid ->
                        val suggestion = StringDistance.suggestDidYouMean(
                            invalid,
                            declaredConstructors.toList()
                        )
                        if (suggestion != null) "'$invalid' ($suggestion)" else "'$invalid'"
                    }
                    val errorMsg = "Constructor(s) ${suggestions.joinToString()} not found in closed variant '$typeName'. " +
                                  "Available constructors: ${declaredConstructors.joinToString()}"
                    return VariantInferenceResult.Failure(errorMsg)
                }

                val variantType = if (freshArgs.isNotEmpty()) {
                    WellKnownTypeApp.of(typeName, freshArgs, typeHash = typeDecl.contentHash)
                } else {
                    WellKnownTypeApp.of(typeName, emptyList(), typeHash = typeDecl.contentHash)
                }
                return VariantInferenceResult.Success(variantType, payloadConstraints)
            }
        }

        return null
    }

    fun solveHasConstructor(
        constraint: Constraint.HasConstructor,
        currentSubst: Substitution = Substitution.empty,
        originalConstraint: Constraint.HasConstructor? = null
    ): ConstraintSolver.ConstraintResult {
        val variantType = constraint.variantType
        val constructorName = constraint.constructorName
        val expectedPayloadType = constraint.payloadType

        if (variantType is MezzeType.SuspendedType) {
            return solveHasConstructor(
                constraint.copy(variantType = variantType.valueType),
                currentSubst,
                originalConstraint ?: constraint
            )
        }

        if (variantType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(variantType, registry)
            if (expanded != null) {
                return solveHasConstructor(
                    constraint.copy(variantType = expanded),
                    currentSubst,
                    originalConstraint ?: constraint
                )
            } else {
                return ConstraintSolver.ConstraintResult.Failure(
                    "Cannot expand type application ${variantType.constructor} to check constructor '$constructorName'"
                )
            }
        }

        if (variantType is MezzeType.VarType) {
            val isPatternContext = constraint.isPatternContext

            if (isPatternContext) {
                if (constructorName == "True" || constructorName == "False") {
                    if (expectedPayloadType != null) {
                        return ConstraintSolver.ConstraintResult.Failure(
                            "Constructor '$constructorName' does not take a payload. " +
                            "Use '$constructorName' instead of '$constructorName { ... }'."
                        )
                    }

                    val boolType = MezzeType.VariantType(
                        constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null),
                        rowVar = null,
                        typeName = "Bool"
                    )
                    return ConstraintSolver.ConstraintResult.Success(
                        Substitution.single(variantType.variable, boolType)
                    )
                }

                val declaringType = findTypeDeclarationForConstructor(constructorName)

                if (declaringType != null) {
                    return resolveConstructorAgainstDecl(declaringType, constructorName, expectedPayloadType, variantType.variable)
                }

                if (constructorName == BuiltinTypes.CONS || constructorName == BuiltinTypes.NIL) {
                    val elemTypeVar = MezzeType.VarType(TypeVar.fresh())
                    val listType = MezzeType.ListType(elemTypeVar)

                    var resultSubst = Substitution.single(variantType.variable, listType)

                    if (constructorName == BuiltinTypes.CONS && expectedPayloadType != null) {
                        val expectedConsPayload = MezzeType.RecordType(mapOf(
                            "head" to elemTypeVar,
                            "tail" to listType
                        ))
                        val unifyResult = unify(expectedPayloadType, expectedConsPayload, registry)
                        when (unifyResult) {
                            is UnificationResult.Success -> {
                                resultSubst = resultSubst.compose(unifyResult.substitution)
                            }
                            is UnificationResult.Failure -> {
                                return ConstraintSolver.ConstraintResult.Failure(
                                    "Cons pattern payload type mismatch: ${unifyResult.message}"
                                )
                            }
                        }
                    }

                    return ConstraintSolver.ConstraintResult.Success(resultSubst)
                }
            }

            if (constructorName == "True" || constructorName == "False") {
                if (expectedPayloadType != null) {
                    return ConstraintSolver.ConstraintResult.Failure(
                        "Constructor '$constructorName' does not take a payload. " +
                        "Use '$constructorName' instead of '$constructorName { ... }'."
                    )
                }

                val boolType = MezzeType.VariantType(
                    constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null),
                    rowVar = null,
                    typeName = "Bool"
                )
                return ConstraintSolver.ConstraintResult.Success(
                    Substitution.single(variantType.variable, boolType)
                )
            }

            if (constructorName == BuiltinTypes.CONS || constructorName == BuiltinTypes.NIL) {
                val declaringTypeExpr = findTypeDeclarationForConstructor(constructorName)

                if (declaringTypeExpr != null) {
                    return resolveConstructorAgainstDecl(declaringTypeExpr, constructorName, expectedPayloadType, variantType.variable)
                }

                val elemTypeVar = MezzeType.VarType(TypeVar.fresh())
                val listType = MezzeType.ListType(elemTypeVar)

                var resultSubst = Substitution.single(variantType.variable, listType)

                if (constructorName == BuiltinTypes.CONS && expectedPayloadType != null) {
                    val expectedConsPayload = MezzeType.RecordType(mapOf(
                        "head" to elemTypeVar,
                        "tail" to listType
                    ))
                    val unifyResult = unify(expectedPayloadType, expectedConsPayload, registry)
                    when (unifyResult) {
                        is UnificationResult.Success -> {
                            resultSubst = resultSubst.compose(unifyResult.substitution)
                        }
                        is UnificationResult.Failure -> {
                            return ConstraintSolver.ConstraintResult.Failure(
                                "Cons constructor payload type mismatch: ${unifyResult.message}"
                            )
                        }
                    }
                }

                return ConstraintSolver.ConstraintResult.Success(resultSubst)
            }

            if (!isPatternContext) {
                val typeForConstructor = findTypeDeclarationForConstructor(constructorName)

                if (typeForConstructor != null) {
                    val variantDef = typeForConstructor.definition as ParsedVariantDef
                    val constructorDef = variantDef.constructors.find { it.name == constructorName }

                    if (constructorDef != null) {
                        val declaredPayload = constructorDef.dataType

                        if (declaredPayload != null && expectedPayloadType == null) {
                            val fieldNames = when (declaredPayload) {
                                is ParsedRecordType -> declaredPayload.entries.map { it.name }
                                else -> listOf("...")
                            }
                            val fieldList = fieldNames.joinToString(", ")
                            return ConstraintSolver.ConstraintResult.Failure(
                                "Constructor '$constructorName' from type '${typeForConstructor.name}' requires a payload. " +
                                "Use '$constructorName { $fieldList }' instead of bare '$constructorName'.",
                                terminal = true
                            )
                        }

                        if (declaredPayload == null && expectedPayloadType != null) {
                            return ConstraintSolver.ConstraintResult.Failure(
                                "Constructor '$constructorName' from type '${typeForConstructor.name}' does not take a payload. " +
                                "Use '$constructorName' without '{ ... }'.",
                                terminal = true
                            )
                        }

                        if (declaredPayload != null && expectedPayloadType != null) {
                            val typeParamMapping = typeForConstructor.typeParams.associate { param ->
                                param to MezzeType.VarType(TypeVar.fresh())
                            }
                            val declaredPayloadType = convertTypeAnnotation(declaredPayload, typeParamMapping)

                            if (declaredPayloadType is MezzeType.RecordType && expectedPayloadType is MezzeType.RecordType) {
                                val missingFields = declaredPayloadType.fields.keys - expectedPayloadType.fields.keys
                                if (missingFields.isNotEmpty()) {
                                    return ConstraintSolver.ConstraintResult.Failure(
                                        "Constructor '$constructorName' payload is missing required fields: ${missingFields.joinToString(", ")}",
                                        terminal = true
                                    )
                                }
                            }
                        }

                        return resolveConstructorAgainstDecl(typeForConstructor, constructorName, expectedPayloadType, variantType.variable)
                    }
                }
            }

            if (isPatternContext) {
                val newRowVar = RowVar.fresh()
                val newVariant = MezzeType.VariantType(
                    constructors = mapOf(constructorName to expectedPayloadType),
                    rowVar = newRowVar
                )

                patternBasedRowVars.add(newRowVar)

                return ConstraintSolver.ConstraintResult.Success(
                    Substitution.single(variantType.variable, newVariant)
                )
            } else {
                val newVariant = MezzeType.VariantType(
                    constructors = mapOf(constructorName to expectedPayloadType),
                    rowVar = null
                )

                return ConstraintSolver.ConstraintResult.Success(
                    Substitution.single(variantType.variable, newVariant)
                )
            }
        }

        if (variantType is MezzeType.BoolType) {
            if (constructorName == "True" || constructorName == "False") {
                if (expectedPayloadType != null) {
                    return ConstraintSolver.ConstraintResult.Failure(
                        "Constructor '$constructorName' does not have a payload"
                    )
                }
                return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
            } else {
                return ConstraintSolver.ConstraintResult.Failure(
                    "Bool type only has constructors 'True' and 'False', not '$constructorName'"
                )
            }
        }

        if (variantType is MezzeType.OrderingType) {
            if (constructorName == "LT" || constructorName == "EQ" || constructorName == "GT") {
                if (expectedPayloadType != null) {
                    return ConstraintSolver.ConstraintResult.Failure(
                        "Constructor '$constructorName' does not have a payload"
                    )
                }
                return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
            } else {
                return ConstraintSolver.ConstraintResult.Failure(
                    "Ordering type only has constructors 'LT', 'EQ', and 'GT', not '$constructorName'"
                )
            }
        }

        if (variantType is MezzeType.VariantType) {
            val actualPayloadType = variantType.constructors[constructorName]

            if (!variantType.constructors.containsKey(constructorName)) {
                if (variantType.rowVar != null) {
                    val newConstructors = variantType.constructors + (constructorName to expectedPayloadType)
                    val extendedVariant = MezzeType.VariantType(
                        constructors = newConstructors,
                        rowVar = variantType.rowVar,
                        typeName = variantType.typeName
                    )

                    var directTypeVar: TypeVar? = null
                    var chainedTypeVar: TypeVar? = null

                    for ((typeVar, mappedType) in currentSubst.mappings) {
                        if (mappedType == variantType) {
                            directTypeVar = typeVar
                            break
                        }

                        val resolvedMappedType = currentSubst.apply(mappedType)
                        if (resolvedMappedType == variantType && chainedTypeVar == null) {
                            chainedTypeVar = typeVar
                        }
                    }

                    val targetTypeVar = directTypeVar ?: chainedTypeVar
                    if (targetTypeVar != null) {
                        return ConstraintSolver.ConstraintResult.Success(
                            Substitution.single(targetTypeVar, extendedVariant)
                        )
                    }
                    return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
                } else {
                    val inScope = inScopeAliasNamesForSolverOrNull()
                    val name = variantType.typeName
                    val nameInScope = variantType.nominalWall ||
                        (name != null && (inScope == null || name in inScope))
                    if (!nameInScope) {
                        val newConstructors = variantType.constructors + (constructorName to expectedPayloadType)
                        val extendedVariant = MezzeType.VariantType(
                            constructors = newConstructors,
                            rowVar = null,
                            typeName = null,
                            typeHash = null
                        )
                        var directTypeVar: TypeVar? = null
                        var chainedTypeVar: TypeVar? = null
                        for ((typeVar, mappedType) in currentSubst.mappings) {
                            if (mappedType == variantType) {
                                directTypeVar = typeVar
                                break
                            }
                            val resolvedMappedType = currentSubst.apply(mappedType)
                            if (resolvedMappedType == variantType && chainedTypeVar == null) {
                                chainedTypeVar = typeVar
                            }
                        }
                        val targetTypeVar = directTypeVar ?: chainedTypeVar
                        if (targetTypeVar != null) {
                            return ConstraintSolver.ConstraintResult.Success(Substitution.single(targetTypeVar, extendedVariant))
                        }
                        return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
                    }

                    val availableConstructors = variantType.constructors.keys.toList()
                    val suggestion = StringDistance.suggestOrList(
                        constructorName,
                        availableConstructors,
                        "constructors"
                    )
                    return ConstraintSolver.ConstraintResult.Failure(
                        "Variant type does not have constructor '$constructorName'. $suggestion"
                    )
                }
            }

            if (expectedPayloadType != null && actualPayloadType != null) {
                val payloadTypeToUnify = if (originalConstraint?.variantType is MezzeType.TypeApplication) {
                    actualPayloadType
                } else {
                    instantiateWithFreshVars(actualPayloadType)
                }

                val result = unify(payloadTypeToUnify, expectedPayloadType, registry)
                return when (result) {
                    is UnificationResult.Success -> ConstraintSolver.ConstraintResult.Success(result.substitution)
                    is UnificationResult.Failure -> ConstraintSolver.ConstraintResult.Failure(
                        "Constructor '$constructorName' payload type mismatch: ${result.message}"
                    )
                }
            } else if (expectedPayloadType == null && actualPayloadType != null) {
                return ConstraintSolver.ConstraintResult.Failure(
                    "Constructor '$constructorName' has a payload that must be destructured. " +
                    "Use '$constructorName { ... }' to match the payload."
                )
            } else if (expectedPayloadType != null && actualPayloadType == null) {
                return ConstraintSolver.ConstraintResult.Failure(
                    "Constructor '$constructorName' does not have a payload. " +
                    "Remove the pattern '{ ... }' after '$constructorName' since it's a unit variant."
                )
            } else {
                return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
            }
        }

        if (variantType is MezzeType.ListType) {
            if (constructorName == BuiltinTypes.CONS) {
                if (expectedPayloadType != null) {
                    val elemType = variantType.elementType
                    val expectedConsPayload = MezzeType.RecordType(mapOf(
                        "head" to elemType,
                        "tail" to variantType
                    ))
                    val result = unify(expectedPayloadType, expectedConsPayload, registry)
                    return when (result) {
                        is UnificationResult.Success -> ConstraintSolver.ConstraintResult.Success(result.substitution)
                        is UnificationResult.Failure -> ConstraintSolver.ConstraintResult.Failure(
                            "Constructor 'Cons' payload type mismatch: ${result.message}"
                        )
                    }
                } else {
                    return ConstraintSolver.ConstraintResult.Failure(
                        "Constructor 'Cons' has a payload that must be destructured. " +
                        "Use 'Cons { head, tail }' to match the payload."
                    )
                }
            } else if (constructorName == BuiltinTypes.NIL) {
                if (expectedPayloadType != null) {
                    return ConstraintSolver.ConstraintResult.Failure(
                        "Constructor 'Nil' does not have a payload"
                    )
                }
                return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
            } else {
                return ConstraintSolver.ConstraintResult.Failure(
                    "List type only has constructors 'Cons' and 'Nil', not '$constructorName'"
                )
            }
        }

        return ConstraintSolver.ConstraintResult.Failure(
            "Cannot use constructor '$constructorName' on non-variant type: ${prettyPrintType(variantType)}"
        )
    }

    fun closePatternBasedVariants(substitution: Substitution): Substitution {
        if (patternBasedRowVars.isEmpty()) return substitution

        val rowVarsToKeepOpen = mustBeOpenRowVars.toSet()
        val rowVarsToClose = patternBasedRowVars - rowVarsToKeepOpen

        if (rowVarsToClose.isEmpty()) return substitution

        val updatedMappings = substitution.mappings.mapValues { (_, type) ->
            closeRowVarsInType(type, rowVarsToClose)
        }

        return Substitution(updatedMappings)
    }

    private fun closeRowVarsInType(type: MezzeType, rowVarsToClose: Set<RowVar>): MezzeType {
        return when (type) {
            is MezzeType.VariantType -> {
                val shouldClose = type.rowVar != null && type.rowVar in rowVarsToClose
                val pinningInferred = shouldClose && type.typeName == null
                MezzeType.VariantType(
                    constructors = type.constructors.mapValues { (_, payloadType) ->
                        payloadType?.let { closeRowVarsInType(it, rowVarsToClose) }
                    },
                    rowVar = if (shouldClose) null else type.rowVar,
                    typeName = if (pinningInferred) "<inferred>" else type.typeName,
                    nominalWall = type.nominalWall || pinningInferred
                )
            }
            is MezzeType.RecordType -> {
                MezzeType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) ->
                        closeRowVarsInType(fieldType, rowVarsToClose)
                    },
                    rowVar = type.rowVar
                )
            }
            is MezzeType.FunctionType -> {
                MezzeType.FunctionType(
                    paramType = closeRowVarsInType(type.paramType, rowVarsToClose),
                    returnType = closeRowVarsInType(type.returnType, rowVarsToClose),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.SeqType -> {
                MezzeType.SeqType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.ListType -> {
                MezzeType.ListType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.MapType -> {
                MezzeType.MapType(
                    keyType = closeRowVarsInType(type.keyType, rowVarsToClose),
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose)
                )
            }
            is MezzeType.SetType -> {
                MezzeType.SetType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.SortedMapType -> {
                MezzeType.SortedMapType(
                    keyType = closeRowVarsInType(type.keyType, rowVarsToClose),
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose)
                )
            }
            is MezzeType.SortedSetType -> {
                MezzeType.SortedSetType(closeRowVarsInType(type.elementType, rowVarsToClose))
            }
            is MezzeType.TypeApplication -> {
                WellKnownTypeApp.of(
                    constructor = type.constructor,
                    args = type.args.map { closeRowVarsInType(it, rowVarsToClose) },
                    typeHash = type.typeHash
                )
            }
            is MezzeType.SuspendedType -> {
                MezzeType.SuspendedType(
                    valueType = closeRowVarsInType(type.valueType, rowVarsToClose),
                    effects = type.effects
                )
            }
            is MezzeType.NewtypeType -> MezzeType.NewtypeType(
                type.name,
                type.constructor,
                closeRowVarsInType(type.wrappedType, rowVarsToClose)
            )
            is MezzeType.VarType,
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType,
            is MezzeType.EffectRow -> type
        }
    }

    private fun findTypeDeclarationForConstructor(constructorName: String): ParsedTypeDecl? {
        return constructorIndex[constructorName]?.firstOrNull()?.first
    }

    private fun resolveConstructorAgainstDecl(
        typeDecl: ParsedTypeDecl,
        constructorName: String,
        expectedPayloadType: MezzeType?,
        bindTo: TypeVar?
    ): ConstraintSolver.ConstraintResult {
        val variantDef = typeDecl.definition as ParsedVariantDef

        val typeParamMapping = typeDecl.typeParams.associate { param ->
            param to MezzeType.VarType(TypeVar.fresh())
        }

        val constructors = variantDef.constructors.associate { ctor ->
            val payloadType = ctor.dataType?.let { convertTypeAnnotation(it, typeParamMapping) }
            ctor.name to payloadType
        }

        val declaredVariant = MezzeType.VariantType(
            constructors = constructors,
            rowVar = if (variantDef.isOpen) RowVar.fresh() else null,
            typeName = typeDecl.name,
            typeArgs = typeParamMapping.values.toList(),
            typeHash = typeDecl.contentHash
        )

        var resultSubst = if (bindTo != null) Substitution.single(bindTo, declaredVariant) else Substitution.empty
        val declaredPayload = constructors[constructorName]

        if (expectedPayloadType != null && declaredPayload != null) {
            val unifyResult = unify(expectedPayloadType, declaredPayload, registry)
            when (unifyResult) {
                is UnificationResult.Success -> resultSubst = resultSubst.compose(unifyResult.substitution)
                is UnificationResult.Failure -> return ConstraintSolver.ConstraintResult.Failure(
                    "Constructor '$constructorName' payload type mismatch: ${unifyResult.message}"
                )
            }
        } else if (expectedPayloadType == null && declaredPayload != null) {
            return ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' has a payload that must be destructured. " +
                "Use '$constructorName { ... }' to match the payload."
            )
        } else if (expectedPayloadType != null && declaredPayload == null) {
            return ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' has no payload, but pattern tries to destructure it. " +
                "Use '$constructorName' without '{ ... }'."
            )
        }

        return ConstraintSolver.ConstraintResult.Success(resultSubst)
    }

    private fun convertTypeAnnotation(
        annotation: ParsedTypeAnnotation,
        typeParamSubst: Map<String, MezzeType>
    ): MezzeType {
        val config = TypeAnnotationConverter.ConversionConfig(
            validateEffects = { ContextBuilder.validateHermeticEffects(it.allEffectNames) },
            honorOpenRowVars = true,
            typeParamSubst = typeParamSubst,
            resolveCollectionTypes = false,
        )
        return TypeAnnotationConverter.convertWith(annotation, config)
    }

    private fun instantiateWithFreshVars(type: MezzeType): MezzeType {
        val typeVars = collectTypeVars(type)
        val mapping = typeVars.associateWith { MezzeType.VarType(TypeVar.fresh()) as MezzeType }
        return Substitution(mapping).apply(type)
    }

    private fun collectTypeVars(type: MezzeType): Set<TypeVar> {
        val result = mutableSetOf<TypeVar>()
        collectTypeVariables(type, result)
        return result
    }

}
