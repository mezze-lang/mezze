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

    /**
     * Each `__native_*` intrinsic's Mezze type, read off the `pub let` that wraps
     * it. Populated per module by `ConstraintGenerator`.
     *
     * A native is in no binding table and has no signature of its own, so a
     * reference to one used to get a bare fresh variable — nothing for the solve to
     * work from. Where a native's result flows straight out of its wrapper that
     * costs nothing, because the wrapper's declared return pins it. Where it does
     * NOT — an inherent impl operation calling a native directly, whose parameters
     * beyond the receiver have no other source — those parameters stay variables
     * all the way into the emitted tree.
     *
     * The type is already written down: `pub let clamp: { n: Int, min: Int, max:
     * Int } -> Int = { n, min, max } -> __native_int_clamp { n = n, min = min, max
     * = max }` says exactly what `__native_int_clamp` takes and returns. This reads
     * it rather than asking anyone to declare it twice.
     */
    val nativeDeclaredTypes = mutableMapOf<String, MezzeType>()

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

    fun generate(expr: ParsedExpr, context: ConstraintContext): ConstraintResult =
        generateImpl(expr, context)

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
        val unusedHandlers = mutableListOf<ConstraintResult.UnusedHandler>()
        // Do-block intermediate `let` bindings + the effects their value surfaced,
        // forwarded to an enclosing `perform` (which pins the discharged-effect
        // associated type). Deliberately NOT merged in merge() — set only by
        // generateDo for its OWN direct bindings so it never crosses a block boundary.
        val doBindingEffects = mutableListOf<ConstraintResult.DoBindingEffect>()
        // #396: per-loc solved value type of each do-block `let` RHS. Keyed by the
        // globally-unique RHS Loc, so — unlike doBindingEffects — it IS merged up
        // (like callReturnTypes) and carries every nested block's binding types to
        // the top-level decl's ConstraintResult for emit to read.
        val doBindingSolvedTypes = mutableMapOf<Loc, MezzeType>()
        // Effect-model migration (S2): per-`perform` fully-discharged flags, merged
        // up (like doBindingSolvedTypes/callReturnTypes) so every nested perform's
        // entry reaches the top-level decl's ConstraintResult for emit to read.
        val performFullyDischarged = mutableMapOf<Loc, Boolean>()
        // Per-lambda solved param types, merged up like callReturnTypes so a
        // lambda nested anywhere reaches the top-level decl's ConstraintResult.
        val lambdaParamTypes = mutableMapOf<Loc, Map<String, MezzeType>>()

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
            unusedHandlers.addAll(result.unusedHandlers)
            doBindingSolvedTypes.putAll(result.doBindingSolvedTypes)
            performFullyDischarged.putAll(result.performFullyDischarged)
            lambdaParamTypes.putAll(result.lambdaParamTypes)
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
            lambdaParamTypes = lambdaParamTypes,
            matchScrutineeTypes = matchScrutineeTypes,
            pendingClosureArgEffects = pendingClosureArgEffects,
            duplicateBindings = duplicateBindings,
            tailCalls = tailCalls,
            unusedVariables = unusedVars,
            unusedHandlers = unusedHandlers,
            doBindingEffects = doBindingEffects,
            doBindingSolvedTypes = doBindingSolvedTypes,
            performFullyDischarged = performFullyDischarged,
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
        is MezzeType.ParametricBuiltin -> type.typeArgs.any { anyRowVarIn(it) }
        is MezzeType.NewtypeType -> anyRowVarIn(type.wrappedType)
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
            is MezzeType.ParametricBuiltin -> type.rebuild(type.typeArgs.map { refreshRowVarsIn(it, mapping) })
            is MezzeType.NewtypeType -> type.copy(wrappedType = refreshRowVarsIn(type.wrappedType, mapping))
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
            is MezzeType.ParametricBuiltin -> type.typeArgs.flatMap { getFreeTypeVariables(it) }.toSet()
            is MezzeType.TypeApplication -> type.args.flatMap { getFreeTypeVariables(it) }.toSet()
            is MezzeType.NewtypeType -> getFreeTypeVariables(type.wrappedType)
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.FracType,
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
            is MezzeType.ParametricBuiltin ->
                type.rebuild(type.typeArgs.map { applyTypeVarSubstitution(it, substitution) })
            is MezzeType.TypeApplication -> WellKnownTypeApp.of(
                constructor = type.constructor,
                args = type.args.map { applyTypeVarSubstitution(it, substitution) },
                typeHash = (type.identity as? TypeIdentity.Declared)?.hash
            )
            is MezzeType.NewtypeType -> type.copy(
                wrappedType = applyTypeVarSubstitution(type.wrappedType, substitution)
            )
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.FracType,
            is MezzeType.BytesType,
            is MezzeType.CharType, is MezzeType.EffectRow -> type
        }
    }

    // S-06/L-30: removed the dead `extractTypeVariables` walker. It was NOT
    // ability-aware (dropped abilityConstraints + EffectRow assoc-types) — the
    // latent 'out-leak hazard the audit flagged — and had ZERO external callers.
    // The live free-var walks are the ability-aware getFreeTypeVariables
    // (instantiation) and ast.solving.collectFreeTypeVars (generalization); those
    // are the single source of truth. Deleting the divergent walker removes the
    // trap of a future caller reintroducing the leak.

    internal fun wrapCallResultWithEffects(
        valueType: MezzeType,
        effectRow: MezzeType.EffectRow?,
    ): Triple<MezzeType, Set<String>, Set<MezzeType.EffectInstance>> {
        if (effectRow == null) return Triple(valueType, emptySet(), emptySet())
        // Effect-model migration (S2, docs/effect-model.md): effects ride on ARROWS,
        // not values. Return the BARE value type; the caller threads
        // effectRow.effects/effectInstances into its collector, which hoists them onto
        // the enclosing function's arrow. The value carries no effect row — that
        // value-carried effect row was the source of the whole laundering/doubled-row
        // bug class.
        return Triple(valueType, effectRow.effects, effectRow.effectInstances)
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
            else -> type
        }
    }

    internal fun convertParsedFieldType(annotation: ParsedTypeAnnotation): MezzeType =
        convertParsedTypeAnnotation(annotation)

    /**
     * Bidirectional helper: resolve an expected type to its record field→type
     * map, or null if it isn't (or doesn't name) a record. Handles a direct
     * [MezzeType.RecordType] and a [MezzeType.TypeApplication] that names a
     * record type declaration. Shared by every site that wants to push expected
     * field types down into subexpressions (record literals, call arguments), so
     * the "what fields does this expected type have" logic lives in one place.
     */
    internal fun expectedRecordFields(
        expectedType: MezzeType?,
        context: ConstraintContext
    ): Map<String, MezzeType>? = when (val t = expectedType) {
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
        // Shared with TypeAnnotationConverter (was a byte-identical private copy here).
        TypeAnnotationConverter.collectCaughtByAncestorInto(type, out)
        return out
    }

    /**
     * Move-6b (L-34 Part A): ONE callback-row effect-harvest, extracted from the
     * byte-identical copies in the ability- and inherent-method paths.
     *
     * A method whose parameter record has a field that is a FunctionType with an
     * OPEN effect row (rowVar != null) is effect-polymorphic in its callback: the
     * effects the caller's actual callback performs flow OUT to the call site.
     * Harvest them from the concrete argument record, minus whatever the method's
     * own row catches (row subtraction). If no callback field is effect-open,
     * nothing is harvested.
     *
     * `callbackHostParamType` is the method's (stripped) parameter type; `argType`
     * is the solved argument record. GEN-TIME name-set union only — deliberately
     * NOT routed through the solve-time unifyEffectRows (which binds row vars and
     * previously broke 12 interp tests; see #331/L-34 note). The aggregate-over-
     * candidates deferred path computes its own caughtByOp/hasRowVar and calls
     * [harvestCallbackEffectsWith] with them.
     */
    internal fun harvestCallbackEffects(callbackHostParamType: MezzeType?, argType: MezzeType): Set<String> {
        val hasCallbackRowVar = (callbackHostParamType as? MezzeType.RecordType)?.fields?.values?.any { field ->
            (field as? MezzeType.FunctionType)?.effects?.rowVar != null
        } == true
        val caughtByOp = if (callbackHostParamType != null) collectCaughtByAncestor(callbackHostParamType) else emptySet()
        return harvestCallbackEffectsWith(hasCallbackRowVar, caughtByOp, argType)
    }

    /** The shared tail: given the (context-computed) row-var presence and the
     *  caught-by-op set, harvest the callback's effects from the argument. */
    internal fun harvestCallbackEffectsWith(hasCallbackRowVar: Boolean, caughtByOp: Set<String>, argType: MezzeType): Set<String> =
        if (hasCallbackRowVar) collectClosureArgEffects(argType) - caughtByOp else emptySet()

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

    /** The annotations of ONE signature, in a single variable scope.
     *  See [TypeAnnotationConverter.convertSignature]. */
    internal fun convertSignature(
        entries: List<Pair<String, ParsedTypeAnnotation>>,
    ): List<Pair<String, MezzeType>> =
        TypeAnnotationConverter.convertSignature(entries, exprConversionConfig)
}
