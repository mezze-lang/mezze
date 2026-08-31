package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.mono.unwrapSuspended
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.typechecker.util.StringDistance
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.ast.Keywords
import com.mezze.language.parsedast.diagnostics.ErrorCode

class ExpressionConstraintGenerator {

    internal val typeVarMappingCache = mutableMapOf<Pair<Int, Int>, Map<TypeVar, TypeVar>>()
    internal val maxCacheSize = 1000
    val nestedBindingTypes = mutableMapOf<String, MezzeType>()

    // S2.1 (#356): candidate-bucketing recording sink for Stage 2's single
    // post-solve dispatch. Constraint generation appends one MethodResolveBucket
    // per method/ability/builtin call site here (see tryGenerateMethodCall) — it
    // RECORDS the candidate set + receiver and SELECTS NOTHING. Nothing consumes
    // it yet: the S2.2 pass will resolve each bucket to a Resolution, and the old
    // gen-time ladder stays the live dispatch decider until S2.3 deletes it.
    // Instance-level (like nestedBindingTypes) rather than threaded through
    // ConstraintResult so the foundation is purely additive — no solver worklist
    // entry, no per-call-site plumbing, byte-for-byte-unchanged behaviour.
    val resolutionBuckets = mutableListOf<MethodResolveBucket>()

    // Set by ConstraintGenerator.create so do-block let bindings can solve their
    // own constraints inline and generalize the result (#76 let-polymorphism).
    // Null in bare/test construction — generalization is then skipped and bindings
    // stay monomorphic (safe fallback).
    var registry: DeclarationRegistry? = null

    // S1.3: the do-block `let` path routes through the SAME bindLet primitive the
    // top-level path uses (ConstraintGenerator.bindLet). bindLet lives on
    // ConstraintGenerator (its annotation-validation needs that class's private
    // state), and ConstraintGenerator owns THIS generator, so the reachable
    // direction is CG -> ECG; we inject a bound reference here at create() — the
    // same injection pattern as `registry` above. Null in bare/test construction:
    // the do-block let path then falls back to monomorphic bindings (safe).
    var bindLetFn: (BindLetArgs) -> ConstraintGenerator.LetBindingResult? = { null }

    /** Everything ConstraintGenerator.bindLet needs from the do-block call site,
     *  bundled so the injected callback stays a single-arg function. */
    class BindLetArgs(
        val item: ParsedLetDecl,
        val result: ConstraintResult,
        val patternResult: PatternConstraintResult,
        val preConvertedAnnotationType: MezzeType?,
        val solveResult: ConstraintSolver.SolveResult.Success,
        val substitution: com.mezze.language.ast.solving.Substitution,
        val currentContext: TypingContext,
        val outerContext: TypingContext,
        val solver: ConstraintSolver,
        val suppressGeneralization: Boolean = false,
        val harvestEffectsIntoBinding: Boolean = true,
    )

    // #109: `__native_*` intrinsic references are stdlib-only. User code that
    // writes `__native_str_eq {...}` gets treated as undefined (bypassing the
    // fresh-type-var fast path in ExprLiteralConstraints). Set by
    // ConstraintGenerator.create from the module path. Default false = safe
    // (user code can't touch natives in bare/test construction either).
    var isStdlibModule: Boolean = false

    fun generate(expr: ParsedExpr, context: ConstraintContext): ConstraintResult {
        val raw = generateImpl(expr, context)
        return when (val t = raw.type) {
            is MezzeType.SuspendType -> raw.copy(
                type = t.valueType,
                effects = raw.effects + t.effects.effects,
                effectInstances = raw.effectInstances + t.effects.effectInstances,
                performed = raw.performed || t.effects.performed
            )
            else -> raw
        }
    }

    private fun generateImpl(expr: ParsedExpr, context: ConstraintContext): ConstraintResult = when (expr) {
        is ParsedIntLit -> generateIntLiteral(expr, context)
        is ParsedNatLit -> generateNatLiteral(expr, context)
        is ParsedFloatLit -> generateFloatLiteral(expr, context)
        is ParsedFracLit -> generateFractionLiteral(expr, context)
        is ParsedStringLit -> generateStringLiteral(expr, context)
        is ParsedSeqLit -> generateSeqLiteral(expr, context)
        is ParsedCharLit -> ConstraintResult.simple(MezzeType.CharType)
        is ParsedIdentifier -> generateIdentifier(expr, context)
        is ParsedPipePlaceholder -> generatePipePlaceholder(expr, context)
        is ParsedPipe -> generatePipeExpression(expr, context)
        is ParsedParens -> generate(expr.expr, context)
        is ParsedRecordLit -> generateRecordLiteral(expr, context)
        is ParsedMemberAccess -> generateMemberAccess(expr, context)
        is ParsedVariantConstruct -> generateVariantConstruction(expr, context)
        is ParsedNewtypeConstruct -> generateNewtypeConstruction(expr, context)
        is ParsedLambda -> generateLambda(expr, context)
        is ParsedFunctionCall -> generateFunctionCall(expr, context)
        is ParsedIf -> generateMatch(expr.toMatchExpr(), context)
        is ParsedMatch -> generateMatch(expr, context)
        is ParsedDo -> generateDo(expr, context)
        is ParsedBinaryOp -> generateBinaryOp(expr, context)
        is ParsedPerform -> generatePerform(expr, context)
        is ParsedEffectCall -> generateEffectCall(expr, context)
    }

    internal inner class ConstraintCollector {
        val constraints = mutableListOf<Constraint>()
        val undefinedVars = mutableListOf<ConstraintResult.UndefinedVariable>()
        val effects = mutableSetOf<String>()
        val effectInstances = mutableSetOf<MezzeType.EffectInstance>()
        // #256/#268: row vars that flow OUT of this expression's effect row —
        // e.g. calling a callback param `f: {..} -> _ <r>` contributes `r` so the
        // enclosing lambda carries it in its OWN row. OR-merged like `performed`.
        val effectRowVars = mutableSetOf<RowVar>()
        var performed = false
        val abilityConstraints = mutableListOf<Constraint.AbilityRequired>()
        val associatedTypeConstraints = mutableListOf<Constraint.AssociatedTypeResolution>()
        val usedVars = mutableSetOf<String>()
        val referencedBindings = mutableSetOf<DefinitionRef.ByName>()
        val methodCallResolutions = mutableMapOf<Pair<Loc, String>, ConstraintResult.MethodCallInfo>()
        val callReturnTypes = mutableMapOf<Loc, MezzeType>()
        // BUG D (#332): scrutinee-loc → inference-time scrutinee type, propagated
        // up so exhaustiveness can see every match scrutinee (not just params).
        val matchScrutineeTypes = mutableMapOf<Loc, MezzeType>()
        // BUG C (#331): deferred callback-effect harvests for `<...>` HOF calls,
        // re-run post-solve so a polymorphic-intermediary callback isn't laundered.
        val pendingClosureArgEffects = mutableListOf<ConstraintResult.PendingClosureHarvest>()
        val duplicateBindings = mutableListOf<ConstraintResult.DuplicateBinding>()
        val tailCalls = mutableSetOf<Loc>()
        // #84 linter: unused bindings found in nested sub-expressions (match
        // cases, do-block lets, lambda params). merge() propagates them so they
        // survive up to the top-level per-decl ConstraintResult, where
        // collectConstraintWarnings turns them into W0101 warnings. Without this,
        // an unused binding inside a do-block was silently dropped.
        val unusedVars = mutableListOf<ConstraintResult.UnusedVariable>()
        // Do-block intermediate `let` bindings + the effects their value surfaced,
        // forwarded to an enclosing `perform` (which pins the discharged-effect
        // associated type). Deliberately NOT merged in merge() — set only by
        // generateDo for its OWN direct bindings so it never crosses a block boundary.
        val doBindingEffects = mutableListOf<ConstraintResult.DoBindingEffect>()

        fun merge(result: ConstraintResult) {
            constraints.addAll(result.constraints)
            undefinedVars.addAll(result.undefinedVariables)
            effects.addAll(result.effects)
            effectInstances.addAll(result.effectInstances)
            effectRowVars.addAll(result.effectRowVars)
            performed = performed || result.performed
            abilityConstraints.addAll(result.abilityConstraints)
            associatedTypeConstraints.addAll(result.associatedTypeConstraints)
            usedVars.addAll(result.usedVariables)
            referencedBindings.addAll(result.referencedBindings)
            methodCallResolutions.putAll(result.methodCallResolutions)
            callReturnTypes.putAll(result.callReturnTypes)
            matchScrutineeTypes.putAll(result.matchScrutineeTypes)
            pendingClosureArgEffects.addAll(result.pendingClosureArgEffects)
            duplicateBindings.addAll(result.duplicateBindings)
            tailCalls.addAll(result.tailCalls)
            unusedVars.addAll(result.unusedVariables)
        }

        fun toResult(type: MezzeType): ConstraintResult = ConstraintResult(
            type = type,
            constraints = constraints,
            effects = effects,
            effectInstances = effectInstances,
            effectRowVars = effectRowVars,
            performed = performed,
            abilityConstraints = abilityConstraints,
            associatedTypeConstraints = associatedTypeConstraints,
            undefinedVariables = undefinedVars,
            usedVariables = usedVars,
            referencedBindings = referencedBindings,
            methodCallResolutions = methodCallResolutions,
            callReturnTypes = callReturnTypes,
            matchScrutineeTypes = matchScrutineeTypes,
            pendingClosureArgEffects = pendingClosureArgEffects,
            duplicateBindings = duplicateBindings,
            tailCalls = tailCalls,
            unusedVariables = unusedVars,
            doBindingEffects = doBindingEffects,
        )
    }

    private fun ParsedIf.toMatchExpr(): ParsedMatch = ParsedMatch(
        scrutinee = condition,
        cases = listOf(
            ParsedMatchCase(
                patterns = listOf(ParsedConstructorPattern("True", null, loc)),
                body = thenBranch,
                loc = loc
            ),
            ParsedMatchCase(
                patterns = listOf(ParsedConstructorPattern("False", null, loc)),
                body = elseBranch,
                loc = loc
            )
        ),
        loc = loc
    )

    internal fun instantiateTypeWithFreshVars(type: MezzeType): MezzeType {
        val freeVars = getFreeTypeVariables(type)
        val hasRowVars = anyRowVarIn(type)
        if (freeVars.isEmpty() && !hasRowVars) return type

        // #183: Use freshWithName for associated type vars (starting with ') so each
        // instantiation gets a unique id. Plain TypeVar(name) uses id=0 and data class
        // equality collapses every `'it` across imported impls onto the SAME instance.
        val substitution = freeVars.associateWith { oldVar ->
            if (oldVar.name.startsWith("'")) TypeVar.freshWithName(oldVar.name)
            else TypeVar.fresh()
        }
        val afterTypeVars = applyTypeVarSubstitution(type, substitution)
        // Also freshen row vars: each instantiation of an effect op or
        // polymorphic fn should get its OWN row var. Sharing the same row
        // var across call sites means binding one locks the other. This
        // walk gives every RowVar in `type` a fresh copy per instantiation.
        return refreshRowVarsIn(afterTypeVars, mutableMapOf())
    }

    private fun anyRowVarIn(type: MezzeType): Boolean = when (type) {
        is MezzeType.FunctionType -> (type.effects?.rowVar != null)
            || anyRowVarIn(type.paramType) || anyRowVarIn(type.returnType)
        is MezzeType.RecordType -> type.rowVar != null || type.fields.values.any { anyRowVarIn(it) }
        is MezzeType.VariantType -> type.rowVar != null || type.constructors.values.any { it != null && anyRowVarIn(it) }
        is MezzeType.TypeApplication -> type.args.any { anyRowVarIn(it) }
        is MezzeType.SeqType -> anyRowVarIn(type.elementType)
        is MezzeType.ListType -> anyRowVarIn(type.elementType)
        is MezzeType.SetType -> anyRowVarIn(type.elementType)
        is MezzeType.SortedSetType -> anyRowVarIn(type.elementType)
        is MezzeType.MapType -> anyRowVarIn(type.keyType) || anyRowVarIn(type.valueType)
        is MezzeType.SortedMapType -> anyRowVarIn(type.keyType) || anyRowVarIn(type.valueType)
        is MezzeType.NewtypeType -> anyRowVarIn(type.wrappedType)
        is MezzeType.SuspendType -> type.effects.rowVar != null || anyRowVarIn(type.valueType)
        else -> false
    }

    private fun refreshRowVarsIn(type: MezzeType, mapping: MutableMap<RowVar, RowVar>): MezzeType {
        fun freshFor(rv: RowVar?): RowVar? {
            if (rv == null) return null
            return mapping.getOrPut(rv) { if (rv.isUserSpecified()) RowVar.userOpen() else RowVar.fresh() }
        }
        return when (type) {
            is MezzeType.FunctionType -> {
                val newEff = type.effects?.let { r -> r.copy(rowVar = freshFor(r.rowVar)) }
                MezzeType.FunctionType(
                    paramType = refreshRowVarsIn(type.paramType, mapping),
                    returnType = refreshRowVarsIn(type.returnType, mapping),
                    effects = newEff,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.RecordType -> type.copy(
                fields = type.fields.mapValues { (_, v) -> refreshRowVarsIn(v, mapping) },
                rowVar = freshFor(type.rowVar)
            )
            is MezzeType.VariantType -> type.copy(
                constructors = type.constructors.mapValues { (_, v) -> v?.let { refreshRowVarsIn(it, mapping) } },
                rowVar = freshFor(type.rowVar)
            )
            is MezzeType.TypeApplication -> type.copy(args = type.args.map { refreshRowVarsIn(it, mapping) })
            is MezzeType.SeqType -> type.copy(elementType = refreshRowVarsIn(type.elementType, mapping))
            is MezzeType.ListType -> type.copy(elementType = refreshRowVarsIn(type.elementType, mapping))
            is MezzeType.SetType -> type.copy(elementType = refreshRowVarsIn(type.elementType, mapping))
            is MezzeType.SortedSetType -> type.copy(elementType = refreshRowVarsIn(type.elementType, mapping))
            is MezzeType.MapType -> type.copy(
                keyType = refreshRowVarsIn(type.keyType, mapping),
                valueType = refreshRowVarsIn(type.valueType, mapping)
            )
            is MezzeType.SortedMapType -> type.copy(
                keyType = refreshRowVarsIn(type.keyType, mapping),
                valueType = refreshRowVarsIn(type.valueType, mapping)
            )
            is MezzeType.NewtypeType -> type.copy(wrappedType = refreshRowVarsIn(type.wrappedType, mapping))
            is MezzeType.SuspendType -> type.copy(effects = type.effects.copy(rowVar = freshFor(type.effects.rowVar)))
            else -> type
        }
    }

    internal fun instantiateTypeWithFreshVarsPreservingAssociatedWithMapping(
        type: MezzeType,
        associatedTypeNames: List<String>
    ): Pair<MezzeType, Map<String, TypeVar>> {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type to emptyMap()
        }

        val normalizedAssociatedNames = associatedTypeNames.map { it.removePrefix("'") }.toSet()

        val substitution = freeVars.associateWith { oldVar ->
            val normalizedName = oldVar.name.removePrefix("'")
            if (normalizedName in normalizedAssociatedNames) {
                TypeVar.freshWithName(oldVar.name)
            } else {
                TypeVar.fresh()
            }
        }

        val nameMapping = substitution.entries.associate { (oldVar, newVar) ->
            oldVar.name.removePrefix("'") to newVar
        }

        return applyTypeVarSubstitution(type, substitution) to nameMapping
    }

    internal fun instantiateTypeWithFreshVarsPreservingPerformerBindings(
        type: MezzeType,
        performerTypeVarNames: List<String>
    ): MezzeType {
        val freeVars = getFreeTypeVariables(type)
        if (freeVars.isEmpty()) {
            return type
        }

        val namesToPreserve = performerTypeVarNames.flatMap { name ->
            listOf(name, name.removePrefix("'"), "'$name")
        }.toSet()

        val substitution = freeVars.associateWith { oldVar ->
            if (oldVar.name in namesToPreserve || oldVar.name.removePrefix("'") in namesToPreserve) {
                oldVar
            } else {
                TypeVar.fresh()
            }
        }
        return applyTypeVarSubstitution(type, substitution)
    }

    internal fun getFreeTypeVariables(type: MezzeType): Set<TypeVar> {
        return when (type) {
            is MezzeType.VarType -> setOf(type.variable)
            is MezzeType.FunctionType -> {
                // Include vars that appear ONLY in the ability constraints — both the
                // class-arg vars and the associated-type binding vars (`having 'out =
                // 'c`). Otherwise instantiation fails to freshen a stored `'out`, so
                // two uses of a polymorphic `+`-fn share the same `'out` var and its
                // resolution leaks across call sites. Collect the constraint vars
                // DIRECTLY (they are always plain VarTypes) rather than recursing:
                // an associated-type binding's value can be the function's own return
                // var, which re-enters this FunctionType and — with no visited-set —
                // recurses forever (OOM). The paramType/returnType recursion already
                // covers any deeper structure.
                val constraintVars = type.abilityConstraints.flatMap { ac ->
                    (ac.typeArgs + ac.associatedTypeBindings.values).mapNotNull {
                        (it as? MezzeType.VarType)?.variable
                    }
                }
                getFreeTypeVariables(type.paramType) + getFreeTypeVariables(type.returnType) +
                    constraintVars
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { getFreeTypeVariables(it) }.toSet() +
                type.typeArgs.flatMap { getFreeTypeVariables(it) }.toSet()
            }
            is MezzeType.SeqType -> getFreeTypeVariables(type.elementType)
            is MezzeType.ListType -> getFreeTypeVariables(type.elementType)
            is MezzeType.SetType -> getFreeTypeVariables(type.elementType)
            is MezzeType.SortedMapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is MezzeType.SortedSetType -> getFreeTypeVariables(type.elementType)
            is MezzeType.MapType -> getFreeTypeVariables(type.keyType) + getFreeTypeVariables(type.valueType)
            is MezzeType.SuspendType -> getFreeTypeVariables(type.valueType)
            is MezzeType.TypeApplication -> type.args.flatMap { getFreeTypeVariables(it) }.toSet()
            is MezzeType.NewtypeType -> getFreeTypeVariables(type.wrappedType)
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType, is MezzeType.EffectRow -> emptySet()
        }
    }

    internal fun applyTypeVarSubstitution(type: MezzeType, substitution: Map<TypeVar, TypeVar>): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                val newVar = substitution[type.variable]
                if (newVar != null) MezzeType.VarType(newVar) else type
            }
            is MezzeType.FunctionType -> MezzeType.FunctionType(
                paramType = applyTypeVarSubstitution(type.paramType, substitution),
                returnType = applyTypeVarSubstitution(type.returnType, substitution),
                effects = type.effects,
                abilityConstraints = type.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { applyTypeVarSubstitution(it, substitution) },
                        // Freshen the associated-type binding vars too (e.g. `'out = 'c`),
                        // and never drop them: instantiation must carry the `having`
                        // link so the call site can resolve `'out` against the freshened
                        // return var. Dropping it here leaves a called `+`-fn's result
                        // unresolved (`ans : 'a`).
                        associatedTypeBindings = constraint.associatedTypeBindings.mapValues { (_, v) ->
                            applyTypeVarSubstitution(v, substitution)
                        },
                        abilityHash = constraint.abilityHash
                    )
                }
            )
            is MezzeType.RecordType -> MezzeType.RecordType(
                fields = type.fields.mapValues { (_, fieldType) ->
                    applyTypeVarSubstitution(fieldType, substitution)
                },
                rowVar = type.rowVar,
                identity = type.identity
            )
            is MezzeType.VariantType -> MezzeType.VariantType(
                constructors = type.constructors.mapValues { (_, dataType) ->
                    dataType?.let { applyTypeVarSubstitution(it, substitution) }
                },
                rowVar = type.rowVar,
                typeArgs = type.typeArgs.map { applyTypeVarSubstitution(it, substitution) },
                identity = type.identity,
                ascriptionWall = type.ascriptionWall
            )
            is MezzeType.SeqType -> MezzeType.SeqType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.ListType -> MezzeType.ListType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SetType -> MezzeType.SetType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = applyTypeVarSubstitution(type.keyType, substitution),
                valueType = applyTypeVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(
                elementType = applyTypeVarSubstitution(type.elementType, substitution)
            )
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = applyTypeVarSubstitution(type.keyType, substitution),
                valueType = applyTypeVarSubstitution(type.valueType, substitution)
            )
            is MezzeType.SuspendType -> MezzeType.SuspendType(
                valueType = applyTypeVarSubstitution(type.valueType, substitution),
                effects = type.effects
            )
            is MezzeType.TypeApplication -> WellKnownTypeApp.of(
                constructor = type.constructor,
                args = type.args.map { applyTypeVarSubstitution(it, substitution) },
                typeHash = (type.identity as? TypeIdentity.Declared)?.hash
            )
            is MezzeType.NewtypeType -> type.copy(
                wrappedType = applyTypeVarSubstitution(type.wrappedType, substitution)
            )
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType, is MezzeType.EffectRow -> type
        }
    }

    internal fun extractTypeVariables(type: MezzeType): List<TypeVar> {
        return when (type) {
            is MezzeType.VarType -> listOf(type.variable)
            is MezzeType.FunctionType -> {
                extractTypeVariables(type.paramType) + extractTypeVariables(type.returnType)
            }
            is MezzeType.RecordType -> {
                type.fields.values.flatMap { extractTypeVariables(it) }
            }
            is MezzeType.VariantType -> {
                type.constructors.values.filterNotNull().flatMap { extractTypeVariables(it) } +
                type.typeArgs.flatMap { extractTypeVariables(it) }
            }
            is MezzeType.SeqType -> extractTypeVariables(type.elementType)
            is MezzeType.ListType -> extractTypeVariables(type.elementType)
            is MezzeType.SetType -> extractTypeVariables(type.elementType)
            is MezzeType.SortedMapType -> extractTypeVariables(type.keyType) + extractTypeVariables(type.valueType)
            is MezzeType.SortedSetType -> extractTypeVariables(type.elementType)
            is MezzeType.MapType -> extractTypeVariables(type.keyType) + extractTypeVariables(type.valueType)
            is MezzeType.SuspendType -> extractTypeVariables(type.valueType)
            is MezzeType.TypeApplication -> type.args.flatMap { extractTypeVariables(it) }
            else -> emptyList()
        }
    }

    internal fun wrapCallResultWithEffects(
        valueType: MezzeType,
        effectRow: MezzeType.EffectRow?,
    ): Triple<MezzeType, Set<String>, Set<MezzeType.EffectInstance>> {
        if (effectRow == null) return Triple(valueType, emptySet(), emptySet())
        val hasUnhandled = effectRow.effects.isNotEmpty() ||
            effectRow.effectInstances.isNotEmpty() ||
            effectRow.rowVar != null
        val callType: MezzeType = if (hasUnhandled || effectRow.performed) {
            MezzeType.SuspendType(valueType = valueType, effects = effectRow)
        } else {
            valueType
        }
        return Triple(callType, effectRow.effects, effectRow.effectInstances)
    }

    internal fun removeEffectsFromType(
        type: MezzeType,
        effectsToRemove: Set<String>,
    ): MezzeType {
        return when (type) {
            is MezzeType.FunctionType -> {
                val effects = type.effects
                if (effects == null) {
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(emptySet(), null, performed = true),
                        abilityConstraints = type.abilityConstraints
                    )
                } else {
                    val remainingEffects = effects.effects - effectsToRemove
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(remainingEffects, effects.rowVar, performed = true),
                        abilityConstraints = type.abilityConstraints
                    )
                }
            }
            is MezzeType.SuspendType -> {
                val remainingEffects = type.effects.effects - effectsToRemove
                val remainingInstances = type.effects.effectInstances.filter { it.name !in effectsToRemove }.toSet()
                if (remainingEffects.isEmpty() && remainingInstances.isEmpty()) {
                    type.valueType
                } else {
                    MezzeType.SuspendType(
                        valueType = type.valueType,
                        effects = MezzeType.EffectRow(
                            effects = remainingEffects,
                            effectInstances = remainingInstances,
                            rowVar = type.effects.rowVar,
                            performed = true
                        )
                    )
                }
            }
            else -> type
        }
    }

    internal fun convertParsedFieldType(annotation: ParsedTypeAnnotation): MezzeType =
        convertParsedTypeAnnotation(annotation)

    /**
     * Peel a [MezzeType.SuspendType] wrapper (which carries effects) off an
     * expected type so bidirectional helpers see the underlying value type. An
     * effectful function type is represented as `SuspendType(FunctionType, …)`;
     * without this, an `as? FunctionType` / record-field lookup on the expected
     * type would silently miss and drop the push (falling back to post-hoc
     * unification). Effects on an *expected* type don't affect what we push down,
     * so discarding the wrapper here is safe.
     */
    internal fun unwrapExpected(type: MezzeType?): MezzeType? =
        type?.let(::unwrapSuspended)

    /**
     * Bidirectional helper: resolve an expected type to its record field→type
     * map, or null if it isn't (or doesn't name) a record. Handles a direct
     * [MezzeType.RecordType] and a [MezzeType.TypeApplication] that names a
     * record type declaration, seeing through a [MezzeType.SuspendType]
     * wrapper. Shared by every site that wants to push expected field types down
     * into subexpressions (record literals, call arguments), so the "what fields
     * does this expected type have" logic lives in one place.
     */
    internal fun expectedRecordFields(
        expectedType: MezzeType?,
        context: ConstraintContext
    ): Map<String, MezzeType>? = when (val t = unwrapExpected(expectedType)) {
        is MezzeType.RecordType -> t.fields
        is MezzeType.TypeApplication -> {
            val typeDecl = context.typeDeclarations[t.constructor]
            val def = typeDecl?.definition
            if (def is ParsedRecordDef) {
                def.fields.associate { it.name to convertParsedFieldType(it.type) }
            } else null
        }
        else -> null
    }

    /**
     * Bidirectional helper: generate [expr] with [expectedFieldType] pushed down
     * as its expected type when non-null, otherwise generate it with the
     * expected type cleared (pure inference). Centralises the "push if known,
     * else clear" decision so all call sites behave identically.
     */
    internal fun generateWithExpected(
        expr: ParsedExpr,
        expectedFieldType: MezzeType?,
        context: ConstraintContext
    ): ConstraintResult {
        val ctx = if (expectedFieldType != null) {
            context.withExpectedType(expectedFieldType)
        } else {
            context.clearExpectedType()
        }
        return generate(expr, ctx)
    }

    /** True if this argument record literal has at least one lambda field —
     *  the only case where re-generating with a pushed param type actually
     *  changes anything (a lambda's params/return can be inferred top-down;
     *  every other field value infers the same either way). */
    private fun recordLiteralHasLambdaField(expr: ParsedExpr): Boolean =
        expr is ParsedRecordLit && expr.entries.any {
            it is ParsedRecordEntry.Field && it.value is ParsedLambda
        }

    /**
     * Bidirectional helper for calls whose callee type is only known AFTER the
     * arguments have been generated once (method/ability dispatch resolves on the
     * argument's field names). Given the already-inferred [inferredArgResult] and
     * the resolved parameter type [resolvedParamType], re-generate the argument
     * record with each param field type pushed down — but ONLY when it can change
     * the outcome (the arg is a record literal with a lambda field and the param
     * type is a concrete record). Otherwise returns [inferredArgResult] unchanged,
     * so the common case pays nothing. The caller uses the returned result for its
     * final constraints; the post-hoc paramType/argType Equality stays the
     * backstop either way.
     */
    internal fun pushParamTypeIntoArgs(
        argExpr: ParsedExpr,
        inferredArgResult: ConstraintResult,
        resolvedParamType: MezzeType,
        context: ConstraintContext
    ): ConstraintResult {
        if (resolvedParamType !is MezzeType.RecordType) return inferredArgResult
        if (!recordLiteralHasLambdaField(argExpr)) return inferredArgResult
        return generateWithExpected(argExpr, resolvedParamType, context.notInTailPosition())
    }

    internal fun isRecordLiteralAccess(expr: ParsedExpr, context: ConstraintContext): Boolean {
        return when (expr) {
            is ParsedIdentifier -> {
                val key = BindingKey(qualifier = expr.qualifier?.segments?.lastOrNull(), localName = expr.localName).toKeyString()
                key in context.recordLiteralBindings
            }
            is ParsedMemberAccess -> isRecordLiteralAccess(expr.receiver, context)
            else -> false
        }
    }

    internal fun findClosestMatch(name: String, candidates: Set<String>, threshold: Int = 3): String? {
        if (candidates.isEmpty()) return null
        val similar = StringDistance.findSimilar(name, candidates.toList(), threshold, maxResults = 1)
        return similar.firstOrNull()
    }

        internal fun collectClosureArgEffects(argType: MezzeType): Set<String> {
        if (argType !is MezzeType.RecordType) return emptySet()
        val effects = mutableSetOf<String>()
        for ((_, fieldType) in argType.fields) {
            val fnType = fieldType as? MezzeType.FunctionType ?: continue
            val effRow = fnType.effects ?: continue
            effects.addAll(effRow.effects)
            for (instance in effRow.effectInstances) effects.add(instance.name)
        }
        return effects
    }

    /**
     * Walk a MezzeType and collect the union of `caughtByAncestor` effect
     * names on every reachable EffectRow. Used to know which effects an
     * enclosing function catches, so they can be filtered from what flows
     * out to the caller (row subtraction on callback rows).
     */
    internal fun collectCaughtByAncestor(type: MezzeType): Set<String> {
        val out = mutableSetOf<String>()
        collectCaughtByAncestorInto(type, out)
        return out
    }

    private fun collectCaughtByAncestorInto(type: MezzeType, into: MutableSet<String>) {
        when (type) {
            is MezzeType.FunctionType -> {
                type.effects?.let { into.addAll(it.caughtByAncestor) }
                collectCaughtByAncestorInto(type.paramType, into)
                collectCaughtByAncestorInto(type.returnType, into)
            }
            is MezzeType.RecordType -> type.fields.values.forEach { collectCaughtByAncestorInto(it, into) }
            is MezzeType.VariantType -> type.constructors.values.forEach { it?.let { collectCaughtByAncestorInto(it, into) } }
            is MezzeType.TypeApplication -> type.args.forEach { collectCaughtByAncestorInto(it, into) }
            is MezzeType.SeqType -> collectCaughtByAncestorInto(type.elementType, into)
            is MezzeType.ListType -> collectCaughtByAncestorInto(type.elementType, into)
            is MezzeType.SetType -> collectCaughtByAncestorInto(type.elementType, into)
            is MezzeType.SortedSetType -> collectCaughtByAncestorInto(type.elementType, into)
            is MezzeType.MapType -> { collectCaughtByAncestorInto(type.keyType, into); collectCaughtByAncestorInto(type.valueType, into) }
            is MezzeType.SortedMapType -> { collectCaughtByAncestorInto(type.keyType, into); collectCaughtByAncestorInto(type.valueType, into) }
            is MezzeType.NewtypeType -> collectCaughtByAncestorInto(type.wrappedType, into)
            is MezzeType.SuspendType -> collectCaughtByAncestorInto(type.valueType, into)
            is MezzeType.EffectRow -> into.addAll(type.caughtByAncestor)
            is MezzeType.VarType, is MezzeType.IntType, is MezzeType.FloatType,
            is MezzeType.StringType, is MezzeType.BoolType, is MezzeType.NatType,
            is MezzeType.FracType, is MezzeType.BytesType,
            is MezzeType.CharType -> {}
        }
    }

    internal fun extractArgumentName(expr: ParsedExpr): String? {
        return when (expr) {
            is ParsedRecordLit -> {
                val fieldNames = expr.entries.mapNotNull { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> entry.name
                        else -> null
                    }
                }
                when {
                    fieldNames.isEmpty() -> null
                    fieldNames.size == 1 -> fieldNames.first()
                    fieldNames.size <= 3 -> "{${fieldNames.joinToString(", ")}}"
                    else -> "{${fieldNames.take(3).joinToString(", ")}, ...}"
                }
            }
            is ParsedIdentifier -> expr.localName
            else -> null
        }
    }

    private val exprConversionConfig = TypeAnnotationConverter.ConversionConfig(
        // Resolve a declared type's content hash so converted TypeApplications carry
        // real nominal identity (Declared), never Named. Hash is always computable.
        resolveTypeHash = { name -> registry?.lookupType(name)?.let { d -> d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d) } },
        resolveAbilityHash = { registry?.resolveAbilityHash(it) },
        validateEffects = { ContextBuilder.validateHermeticEffects(it.allEffectNames) },
        resolveCollectionTypes = true,
        honorOpenRowVars = true,
        inlineVariantNaming = true,
        resolveTypeAlias = { name ->
            registry?.lookupType(name)?.let { decl ->
                ContextBuilder.aliasedAnnotationForTypeDecl(decl)?.let { ann ->
                    ann to decl.typeParams
                }
            }
        },
    )

    internal fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return TypeAnnotationConverter.convertWith(annotation, exprConversionConfig)
    }
}
