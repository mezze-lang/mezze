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
        // Include transitive / auto-imported type decls too: a value's type
        // identity flows with it from the decl side, so `NoneFound` inside a
        // pattern must find `OptErr`'s decl even if the caller never wrote
        // `use opt_err::{OptErr}`. Import controls what the user can NAME in
        // annotations, not what the values' types ARE.
        for (typeDecl in registry.allTypeDeclsIncludingTransitive) {
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

        // #263: a closed candidate that only PARTIALLY covers the constructor set
        // must not abort the scan — a later candidate may fully cover it (two
        // imported variants can share a constructor name). Remember the first
        // partial-cover failure and only return it once every candidate is
        // exhausted with no full match, so acceptance no longer depends on
        // candidate/import order. See project_tc_audit_2026-08 (#12).
        var deferredFailure: VariantInferenceResult.Failure? = null

        // ...and #263's other half: when SEVERAL candidates fully cover the set,
        // returning the first is the same order-dependence one step later.
        // `{ Timeout }` is covered by both `HttpError` and `CancelReason`, and
        // `candidateVariants` is a hash set, so which one a construction inferred
        // came down to iteration order. Collect every full cover and let the
        // PAYLOADS choose between them below.
        val fullCover = mutableListOf<Pair<ParsedTypeDecl, ParsedVariantDef>>()

        for ((typeDecl, definition) in candidateVariants) {
            val typeName = typeDecl.name
            val declaredConstructors = definition.constructors.map { it.name }.toSet()

            val matchingConstructors = constructors.intersect(declaredConstructors)

            if (matchingConstructors.isEmpty()) {
                continue
            }

            if (!definition.isOpen) {
                val invalidConstructors = constructors - declaredConstructors
                if (invalidConstructors.isNotEmpty()) {
                    // Partial cover — defer the failure and try the next candidate.
                    if (deferredFailure == null) {
                        val suggestions = invalidConstructors.map { invalid ->
                            val suggestion = StringDistance.suggestDidYouMean(
                                invalid,
                                declaredConstructors.toList()
                            )
                            if (suggestion != null) "'$invalid' ($suggestion)" else "'$invalid'"
                        }
                        val errorMsg = "Constructor(s) ${suggestions.joinToString()} not found in closed variant '$typeName'. " +
                                      "Available constructors: ${declaredConstructors.joinToString()}"
                        deferredFailure = VariantInferenceResult.Failure(errorMsg)
                    }
                    continue
                }
            }

            fullCover.add(typeDecl to definition)
        }

        // No candidate fully covered the constructor set: surface the deferred
        // partial-cover failure if we saw one, else fall through (null = unresolved).
        if (fullCover.isEmpty()) return deferredFailure

        val chosen = if (fullCover.size == 1) {
            fullCover.first()
        } else {
            // The constructor NAMES no longer discriminate, so the payload field
            // names have to. Exactly one fit is an answer; anything else is not,
            // and inferring nothing is the honest outcome — the `HasConstructor`
            // constraint survives, and an annotation or the surrounding
            // unification decides it where the evidence actually is.
            val fitting = fullCover.filter { (_, definition) ->
                constructors.all { name ->
                    val ctor = definition.constructors.firstOrNull { it.name == name }
                    ctor == null || payloadFits(ctor.dataType, constructorPayloads[name])
                }
            }
            fitting.singleOrNull() ?: return null
        }

        run {
            val (typeDecl, definition) = chosen
            val typeName = typeDecl.name
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

            val variantType = if (freshArgs.isNotEmpty()) {
                WellKnownTypeApp.of(typeName, freshArgs, typeHash = typeDecl.contentHash)
            } else {
                WellKnownTypeApp.of(typeName, emptyList(), typeHash = typeDecl.contentHash)
            }
            return VariantInferenceResult.Success(variantType, payloadConstraints)
        }
    }

    fun solveHasConstructor(
        constraint: Constraint.HasConstructor,
        currentSubst: Substitution = Substitution.empty,
        originalConstraint: Constraint.HasConstructor? = null
    ): ConstraintSolver.ConstraintResult {
        val variantType = constraint.variantType
        val constructorName = constraint.constructorName
        val expectedPayloadType = constraint.payloadType

        if (variantType is MezzeType.TypeApplication) {
            val expanded = expandTypeApplicationToVariant(variantType, registry)
            if (expanded != null) {
                return solveHasConstructor(
                    constraint.copy(variantType = expanded),
                    currentSubst, originalConstraint ?: constraint
                )
            } else {
                return ConstraintSolver.ConstraintResult.Failure(
                    "Cannot resolve type '${variantType.constructor}' to check for constructor '$constructorName'"
                )
            }
        }

        return when (variantType) {
            is MezzeType.VarType -> solveConstructorOnVar(
                variantType, constructorName, expectedPayloadType,
                constraint.isPatternContext, currentSubst
            )
            is MezzeType.BoolType -> solveBoolConstructor(constructorName, expectedPayloadType)
            is MezzeType.VariantType -> solveConstructorOnVariant(
                variantType, constructorName, expectedPayloadType,
                currentSubst, originalConstraint
            )
            else -> ConstraintSolver.ConstraintResult.Failure(
                "Cannot use constructor '$constructorName' on non-variant type: ${prettyPrintType(variantType)}. " +
                    "`Ctor { … }` matches a VARIANT constructor; a record destructures as `{ field, … }` " +
                    "and a newtype opens as `Name.(inner)`."
            )
        }
    }

    private fun solveBoolConstructor(
        constructorName: String,
        expectedPayloadType: MezzeType?
    ): ConstraintSolver.ConstraintResult {
        if (constructorName != BuiltinTypes.TRUE && constructorName != BuiltinTypes.FALSE) {
            return ConstraintSolver.ConstraintResult.Failure(
                "Bool type only has constructors 'True' and 'False', not '$constructorName'"
            )
        }
        if (expectedPayloadType != null) {
            return ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' does not have a payload"
            )
        }
        return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
    }

    private fun solveBoolConstructorOnVar(
        constructorName: String,
        expectedPayloadType: MezzeType?,
        typeVar: TypeVar
    ): ConstraintSolver.ConstraintResult {
        if (expectedPayloadType != null) {
            return ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' does not take a payload. " +
                "Use '$constructorName' instead of '$constructorName { ... }'."
            )
        }
        val boolType = MezzeType.VariantType(
            constructors = mapOf(BuiltinTypes.TRUE to null, BuiltinTypes.FALSE to null),
            rowVar = null, identity = TypeIdentity.Builtin("Bool")
        )
        return ConstraintSolver.ConstraintResult.Success(
            Substitution.single(typeVar, boolType)
        )
    }

    private fun solveConsNilOnVar(
        constructorName: String,
        expectedPayloadType: MezzeType?,
        typeVar: TypeVar,
        context: String
    ): ConstraintSolver.ConstraintResult {
        val elemTypeVar = MezzeType.VarType(TypeVar.fresh())
        val listType = BuiltinTypes.listApp(elemTypeVar)
        var resultSubst = Substitution.single(typeVar, listType)

        if (constructorName == BuiltinTypes.CONS && expectedPayloadType != null) {
            val expectedConsPayload = BuiltinTypes.consPayload(elemTypeVar)
            val unifyResult = unify(expectedPayloadType, expectedConsPayload, registry)
            when (unifyResult) {
                is UnificationResult.Success -> resultSubst = resultSubst.compose(unifyResult.substitution)
                is UnificationResult.Failure -> return ConstraintSolver.ConstraintResult.Failure(
                    "Cons $context payload type mismatch: ${unifyResult.message}"
                )
            }
        }
        return ConstraintSolver.ConstraintResult.Success(resultSubst)
    }

    private fun solveConstructorOnVar(
        variantType: MezzeType.VarType,
        constructorName: String,
        expectedPayloadType: MezzeType?,
        isPatternContext: Boolean,
        currentSubst: Substitution
    ): ConstraintSolver.ConstraintResult {
        if (constructorName == BuiltinTypes.TRUE || constructorName == BuiltinTypes.FALSE) {
            return solveBoolConstructorOnVar(constructorName, expectedPayloadType, variantType.variable)
        }

        if (isPatternContext) {
            when (val found = lookupConstructorDecl(constructorName, expectedPayloadType)) {
                is CtorLookup.Resolved ->
                    return resolveConstructorAgainstDecl(found.typeDecl, constructorName, expectedPayloadType, variantType.variable)
                is CtorLookup.NoneFit ->
                    return resolveConstructorAgainstDecl(found.nearest, constructorName, expectedPayloadType, variantType.variable)
                is CtorLookup.Ambiguous -> return ambiguousConstructor(constructorName, found)
                CtorLookup.Undeclared -> {}
            }
        }

        if (constructorName == BuiltinTypes.CONS || constructorName == BuiltinTypes.NIL) {
            if (!isPatternContext) {
                // A DECLARED `Cons`/`Nil` wins over the builtin list when one is in
                // scope; when the name is ambiguous or undeclared the builtin is the
                // answer, not a deferral, so this branch never asks to wait.
                val declaringTypeExpr = lookupConstructorDecl(constructorName, expectedPayloadType) as? CtorLookup.Resolved
                if (declaringTypeExpr != null) {
                    return resolveConstructorAgainstDecl(declaringTypeExpr.typeDecl, constructorName, expectedPayloadType, variantType.variable)
                }
            }
            val context = if (isPatternContext) "pattern" else "constructor"
            return solveConsNilOnVar(constructorName, expectedPayloadType, variantType.variable, context)
        }

        if (!isPatternContext) {
            when (val found = lookupConstructorDecl(constructorName, expectedPayloadType)) {
                is CtorLookup.Resolved ->
                    return solveConstructorFromDecl(found.typeDecl, constructorName, expectedPayloadType, variantType.variable)
                is CtorLookup.NoneFit ->
                    return solveConstructorFromDecl(found.nearest, constructorName, expectedPayloadType, variantType.variable)
                is CtorLookup.Ambiguous -> return ambiguousConstructor(constructorName, found)
                CtorLookup.Undeclared -> {}
            }
        }

        if (isPatternContext) {
            // An undeclared constructor on a scrutinee that is still an unsolved var
            // gets an OPEN variant invented for it. That is row polymorphism, not a
            // hole: `OpenVariantUnificationTest` and the inferred-variant rules
            // depend on it, and the scrutinee's real type constrains it later.
            // Constructor patterns on a KNOWN scrutinee are checked against that
            // type — see the `else` arm of `solveHasConstructor` and the newtype-form
            // check in `PatternConstraints.generateConstructor`.
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

    private fun solveConstructorFromDecl(
        typeDecl: ParsedTypeDecl,
        constructorName: String,
        expectedPayloadType: MezzeType?,
        typeVar: TypeVar
    ): ConstraintSolver.ConstraintResult {
        val variantDef = typeDecl.definition as ParsedVariantDef
        val constructorDef = variantDef.constructors.find { it.name == constructorName }
            ?: return resolveConstructorAgainstDecl(typeDecl, constructorName, expectedPayloadType, typeVar)

        val declaredPayload = constructorDef.dataType

        if (declaredPayload != null && expectedPayloadType == null) {
            val fieldNames = when (declaredPayload) {
                is ParsedRecordType -> declaredPayload.entries.map { it.name }
                else -> listOf("...")
            }
            val fieldList = fieldNames.joinToString(", ")
            return ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' from type '${typeDecl.name}' requires a payload. " +
                "Use '$constructorName { $fieldList }' instead of bare '$constructorName'.",
                terminal = true
            )
        }

        if (declaredPayload == null && expectedPayloadType != null) {
            return ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' from type '${typeDecl.name}' does not take a payload. " +
                "Use '$constructorName' without '{ ... }'.",
                terminal = true
            )
        }

        if (declaredPayload != null && expectedPayloadType != null) {
            val typeParamMapping = typeDecl.typeParams.associate { param ->
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

        return resolveConstructorAgainstDecl(typeDecl, constructorName, expectedPayloadType, typeVar)
    }

    private fun findTypeVarForVariant(
        variantType: MezzeType.VariantType,
        currentSubst: Substitution
    ): TypeVar? {
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
        return directTypeVar ?: chainedTypeVar
    }

    private fun solveConstructorOnVariant(
        variantType: MezzeType.VariantType,
        constructorName: String,
        expectedPayloadType: MezzeType?,
        currentSubst: Substitution,
        originalConstraint: Constraint.HasConstructor?
    ): ConstraintSolver.ConstraintResult {
        val actualPayloadType = variantType.constructors[constructorName]

        if (!variantType.constructors.containsKey(constructorName)) {
            if (variantType.rowVar != null) {
                val extendedVariant = MezzeType.VariantType(
                    constructors = variantType.constructors + (constructorName to expectedPayloadType),
                    rowVar = variantType.rowVar, identity = variantType.identity
                )
                val targetTypeVar = findTypeVarForVariant(variantType, currentSubst)
                return if (targetTypeVar != null) {
                    ConstraintSolver.ConstraintResult.Success(Substitution.single(targetTypeVar, extendedVariant))
                } else {
                    ConstraintSolver.ConstraintResult.Success(Substitution.empty)
                }
            }

            val inScope = inScopeAliasNamesForSolverOrNull()
            val name = variantType.identity.displayNameOrNull()
            val nameInScope = variantType.ascriptionWall ||
                (name != null && (inScope == null || name in inScope))
            if (!nameInScope) {
                val extendedVariant = MezzeType.VariantType(
                    constructors = variantType.constructors + (constructorName to expectedPayloadType),
                    rowVar = null, identity = TypeIdentity.Anonymous
                )
                val targetTypeVar = findTypeVarForVariant(variantType, currentSubst)
                return if (targetTypeVar != null) {
                    ConstraintSolver.ConstraintResult.Success(Substitution.single(targetTypeVar, extendedVariant))
                } else {
                    ConstraintSolver.ConstraintResult.Success(Substitution.empty)
                }
            }

            val availableConstructors = variantType.constructors.keys.toList()
            val suggestion = StringDistance.suggestOrList(constructorName, availableConstructors, "constructors")
            return ConstraintSolver.ConstraintResult.Failure(
                "Variant type does not have constructor '$constructorName'. $suggestion"
            )
        }

        if (expectedPayloadType != null && actualPayloadType != null) {
            // The variant reaching us was resolved BEFORE the solver ran — its
            // tyvars are the ones its enclosing scope minted (a scheme instance
            // fresh-instantiated at its call site, or the impl's own forall 'a
            // for an inline transparent alias like `type Wrap 'a = |Wrap{v:'a}|`
            // used as an impl target). Do not re-mint them here: fresh vars at
            // pattern-match time would sever the payload from the tyvars every
            // downstream constraint refers to (breaks impl-body residual harvest,
            // e.g. `l < rv` residual `Ord 'a` needs to land on impl's own 'a).
            val result = unify(actualPayloadType, expectedPayloadType, registry)
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

    fun closePatternBasedVariants(substitution: Substitution): Substitution {
        if (patternBasedRowVars.isEmpty()) return substitution

        val rowVarsToKeepOpen = mustBeOpenRowVars.toSet()
        val rowVarsToClose = patternBasedRowVars - rowVarsToKeepOpen

        if (rowVarsToClose.isEmpty()) return substitution

        val updatedMappings = substitution.mappings.mapValues { (_, type) ->
            closeRowVarsInType(type, rowVarsToClose)
        }

        return Substitution(updatedMappings, substitution.rowVarMappings)
    }

    private fun closeRowVarsInType(type: MezzeType, rowVarsToClose: Set<RowVar>): MezzeType {
        return when (type) {
            is MezzeType.VariantType -> {
                val shouldClose = type.rowVar != null && type.rowVar in rowVarsToClose
                // Pinning an inferred anonymous open variant to closed shape
                // makes it act as a wall for downstream match/ascription
                // constraints — set ascriptionWall=true. Do NOT invent a synthetic
                // typeName: it's still anonymous, printed by its arm set.
                val pinningInferred = shouldClose && type.identity.displayNameOrNull() == null
                MezzeType.VariantType(
                    constructors = type.constructors.mapValues { (_, payloadType) ->
                        payloadType?.let { closeRowVarsInType(it, rowVarsToClose) }
                    },
                    rowVar = if (shouldClose) null else type.rowVar,
                    identity = type.identity,   // preserve nominal identity on row-close rebuild
                    ascriptionWall = type.ascriptionWall || pinningInferred
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
            is MezzeType.ParametricBuiltin ->
                type.rebuild(type.typeArgs.map { closeRowVarsInType(it, rowVarsToClose) })
            is MezzeType.TypeApplication -> {
                WellKnownTypeApp.of(
                    constructor = type.constructor,
                    args = type.args.map { closeRowVarsInType(it, rowVarsToClose) },
                    typeHash = (type.identity as? TypeIdentity.Declared)?.hash
                )
            }
            is MezzeType.NewtypeType -> type.copy(
                wrappedType = closeRowVarsInType(type.wrappedType, rowVarsToClose)
            )
            is MezzeType.VarType,
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType,
            is MezzeType.EffectRow -> type
        }
    }

    /**
     * Which declaration a constructor NAME refers to.
     *
     * The name alone does not decide it. `std::effects::http` declares
     * `HttpError.Timeout { url, ms }` and `std::effects::async` declares
     * `CancelReason.Timeout { deadline_ms }`, so every module that makes a
     * retrying HTTP call has two declarations under one name. The PAYLOAD decides
     * — the same way [findVariantWithConstructors] already decides the
     * multi-constructor case from the constructor set (#263). Taking the first
     * entry of the index instead made the answer a function of map order, and
     * `Timeout { url = "x", ms = 100 }` was solved against `CancelReason`.
     */
    private sealed class CtorLookup {
        /** Exactly one declaration in scope declares this name with a fitting payload. */
        data class Resolved(val typeDecl: ParsedTypeDecl) : CtorLookup()

        /** Nothing in scope declares it; the caller builds a structural variant. */
        object Undeclared : CtorLookup()

        /**
         * Something declares it, nothing FITS. Resolution has already failed; the
         * declaration carried here only decides which one the error names, so the
         * existing per-declaration diagnostics stay exact ("missing required
         * fields: ms" rather than a generic "no such constructor").
         */
        data class NoneFit(val nearest: ParsedTypeDecl) : CtorLookup()

        /**
         * Several fit. [decidable] is false while the payload still holds unsolved
         * type vars — more information is coming, so the caller waits for it; true
         * when a CONCRETE payload still does not single one out, which is a real
         * ambiguity only the program can resolve.
         */
        data class Ambiguous(val typeNames: List<String>, val decidable: Boolean) : CtorLookup()
    }

    private fun lookupConstructorDecl(
        constructorName: String,
        expectedPayloadType: MezzeType?,
    ): CtorLookup {
        val candidates = constructorIndex[constructorName].orEmpty()
        if (candidates.isEmpty()) return CtorLookup.Undeclared
        if (candidates.size == 1) return CtorLookup.Resolved(candidates.first().first)

        val fitting = candidates.filter { (_, definition) ->
            payloadFits(
                definition.constructors.first { it.name == constructorName }.dataType,
                expectedPayloadType,
            )
        }
        fitting.singleOrNull()?.let { return CtorLookup.Resolved(it.first) }

        if (fitting.isEmpty()) {
            // Name the candidate closest to what was written, tie-broken by type
            // name so the message never depends on index order.
            val nearest = candidates.minWithOrNull(
                compareBy({ payloadDistance(it.second, constructorName, expectedPayloadType) }, { it.first.name })
            )!!.first
            return CtorLookup.NoneFit(nearest)
        }

        return CtorLookup.Ambiguous(
            typeNames = fitting.map { it.first.name }.sorted(),
            decidable = expectedPayloadType?.holdsTypeVar() != true,
        )
    }

    /**
     * Could a construction or pattern carrying [expected] be THIS declared payload?
     *
     * Field NAMES are what discriminate here, and only one direction of the subset
     * holds in general: a pattern names a subset of the fields it matches, while an
     * open declaration requires a superset. Anything a record's field names cannot
     * discriminate (a bare constructor, a non-record payload, a payload not yet
     * solved to a record) is left as a fit, so the decision falls to
     * [CtorLookup.Ambiguous] rather than being made here on no evidence.
     */
    private fun payloadFits(declared: ParsedTypeAnnotation?, expected: MezzeType?): Boolean = when {
        declared == null -> expected == null
        expected == null -> false
        declared !is ParsedRecordType -> true
        expected !is MezzeType.RecordType -> true
        declared.isOpen -> declared.entries.all { it.name in expected.fields }
        else -> expected.fields.keys.all { name -> declared.entries.any { it.name == name } }
    }

    /** Field-name distance. Used only to choose which declaration an ERROR names. */
    private fun payloadDistance(
        definition: ParsedVariantDef,
        constructorName: String,
        expected: MezzeType?,
    ): Int {
        val declared = definition.constructors.first { it.name == constructorName }.dataType
        val declaredFields = (declared as? ParsedRecordType)?.entries?.map { it.name }?.toSet() ?: emptySet()
        val expectedFields = (expected as? MezzeType.RecordType)?.fields?.keys ?: emptySet()
        return (declaredFields - expectedFields).size + (expectedFields - declaredFields).size
    }

    /**
     * Two declarations fit and nothing here can choose between them. Which of the
     * two answers this is depends on whether waiting could still help.
     */
    private fun ambiguousConstructor(
        constructorName: String,
        found: CtorLookup.Ambiguous,
    ): ConstraintSolver.ConstraintResult {
        val names = found.typeNames.joinToString(" and ")
        return if (found.decidable) {
            ConstraintSolver.ConstraintResult.Failure(
                "Constructor '$constructorName' is declared by $names, and this payload fits both. " +
                    "Annotate the value with the type you mean.",
                terminal = true,
            )
        } else {
            ConstraintSolver.ConstraintResult.Deferred(
                "constructor '$constructorName' is declared by $names; waiting for the payload to be pinned"
            )
        }
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
            typeArgs = typeParamMapping.values.toList(),
            // A declared variant always has a content hash (pure fn of the decl).
            // S3.1: via THE canonical factory.
            identity = TypeIdentity.declared(
                typeDecl.name,
                typeDecl.contentHash ?: ParsedDeclHasher.hashTypeDecl(typeDecl)
            )
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
            // Resolve declared-type hashes from the registry so converted
            // TypeApplications carry real nominal identity (Declared), never Named.
            resolveTypeHash = { name -> registry.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } },
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
