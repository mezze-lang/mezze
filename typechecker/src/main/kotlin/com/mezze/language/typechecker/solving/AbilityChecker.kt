package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typedast.Hash

class AbilityChecker internal constructor(
    private val context: TypingContext,
    private val registry: DeclarationRegistry,
    /**
     * Impl-body validation mode (see ConstraintSolver.deferExistingAbilityImpls).
     * When true, an AbilityRequired whose impl exists somewhere but doesn't match
     * the strict check here is DEFERRED (success) rather than failed, so an
     * operator body using a type whose impl isn't import-visible at this site
     * isn't spuriously rejected. A truly-absent impl still fails.
     */
    private val deferExistingAbilityImpls: Boolean = false,
    /**
     * Optional caller-owned codec-impl registry shared with sibling solvers in
     * the same module typecheck (see the field-level note on
     * [generatedCodecImpls]). `null` uses a private per-instance map.
     */
    private val sharedGeneratedCodecImpls: MutableMap<Hash, GeneratedCodecImpl>? = null,
) {
    private val codecGenerator = CodecGenerator()

    data class GeneratedCodecImpl(
        val abilityName: String,
        val targetTypes: List<MezzeType>,
        val contentHash: Hash
    )

    private data class TrackedAbilityConstraint(
        val abilityName: String,
        val abilityHash: Hash?,
        val typeArgs: List<MezzeType>,
        val loc: Loc?,
        val fromAnnotation: Boolean = false
    )

    // Codec-impl derivation is a SIDE EFFECT of ability-constraint checking that
    // must OUTLIVE a single AbilityChecker instance. During a module typecheck the
    // constraint generator creates transient per-binding [ConstraintSolver]s (each
    // with its own AbilityChecker) so it can incrementally solve one binding at a
    // time; the impls a per-binding solver auto-derives (e.g. `Encode JsonC Bool`
    // for a bare `True.encode {…}`) would be dropped when that solver is thrown
    // away, and the outer module-level solver often can't rederive them because
    // the equality that pinned the receiver's type var lives INSIDE the discarded
    // per-binding substitution. Publishing every fresh impl to
    // [sharedGeneratedCodecImpls] — a mutable map the module typecheck owns and
    // hands to both per-binding and module-level solvers — makes the derivations
    // visible to [TypedModuleEmitter] regardless of which solver instance
    // synthesized them. Falls back to a private map when no shared map is
    // installed (single-solver callers).
    private val generatedCodecImpls: MutableMap<Hash, GeneratedCodecImpl> =
        sharedGeneratedCodecImpls ?: mutableMapOf()
    private val typeVarAbilityConstraints = mutableMapOf<TypeVar, MutableList<TrackedAbilityConstraint>>()
    private val deferredInference = mutableMapOf<TypeVar, MezzeType>()
    private val associatedTypeResolutionConstraints = mutableMapOf<TypeVar, MutableList<Constraint.AssociatedTypeResolution>>()

    fun getGeneratedCodecImpls(): Collection<GeneratedCodecImpl> = generatedCodecImpls.values
    fun getDeferredInference(): Map<TypeVar, MezzeType> = deferredInference
    fun getAssociatedTypeResolutionConstraints(): Map<TypeVar, MutableList<Constraint.AssociatedTypeResolution>> = associatedTypeResolutionConstraints

    internal fun validateAbilityConstraints(substitution: Substitution): Pair<String, Loc?>? {
        for ((typeVar, abilityConstraints) in typeVarAbilityConstraints) {
            val varType = MezzeType.VarType(typeVar)
            val boundType = substitution.apply(varType)

            if (boundType !is MezzeType.VarType) {
                for (tracked in abilityConstraints) {
                    val abilityName = tracked.abilityName
                    val typeArgs = tracked.typeArgs
                    val errorLoc = tracked.loc
                    val trackedResolvedHash = tracked.abilityHash ?: registry.resolveAbilityHash(abilityName)
                    if (WellKnownHashConstants.isInspect(trackedResolvedHash)) continue
                    val concreteTypeArgs: List<MezzeType> = typeArgs.map { typeArg ->
                        if (typeArg is MezzeType.VarType &&
                            (typeArg.variable.id == typeVar.id ||
                             typeArg.variable.name.removePrefix("'") == typeVar.name.removePrefix("'"))) {
                            boundType
                        } else {
                            substitution.apply(typeArg)
                        }
                    }

                    // Close open records/variants BEFORE the concreteness test. An open
                    // record `{a: Int, ...}` is not `isConcrete` (its rowVar is a hole),
                    // so without this it defers as "not yet concrete" and the obligation
                    // is silently dropped (HOLE C). Extra hidden fields can never SUPPLY a
                    // structural-ability impl the closed shape lacks (impls match on the
                    // written shape), so validating against the closed shape is sound: a
                    // record with `Ord`-lacking visible fields is rejected, and one whose
                    // visible fields all derive (e.g. Eq) still passes. This generalizes
                    // the codec-only closing that already existed.
                    val closedTypeArgs = concreteTypeArgs.map { closeOpenRecords(it) }

                    // Hard-reject a function operand even when a SIBLING position is
                    // still an unresolved var. A concrete FunctionType is monotonic —
                    // no later substitution turns it into something else — and no impl
                    // targets a function at a concrete slot, so its unimplementability
                    // is decidable now, independent of the other operands. Without this,
                    // `(10 + 20) * f` (whose `(10+20)` co-operand is a not-yet-resolved
                    // class-param `having 'out` var) slips through the all-or-nothing
                    // `allConcrete` gate below into the defer/absorb branch and the
                    // obligation evaporates unchecked (the class-param fn-operand hole).
                    if (functionOperandHasNoImpl(abilityName, closedTypeArgs, tracked.abilityHash)) {
                        return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs, errorLoc), errorLoc)
                    }

                    val allConcrete = closedTypeArgs.all { isConcrete(it) }

                    if (allConcrete) {
                        val hasImpl = checkAbilityImplementation(abilityName, closedTypeArgs, tracked.abilityHash, errorLoc)
                        if (!hasImpl) {
                            if (deferExistingAbilityImpls && abilityImplExistsForType(abilityName, closedTypeArgs)) {
                                continue
                            }
                            return Pair(generateAbilityErrorMessage(abilityName, closedTypeArgs, errorLoc), errorLoc)
                        }
                    } else {
                        val inferredSubst = tryBidirectionalInference(abilityName, closedTypeArgs, tracked.abilityHash)
                        if (inferredSubst != null) {
                            deferredInference.putAll(inferredSubst.mappings)
                        }
                    }
                }
            }
        }
        return null
    }

    internal fun solveAbilityRequired(constraint: Constraint.AbilityRequired): ConstraintSolver.ConstraintResult {
        val abilityName = constraint.abilityName
        val typeArgs = constraint.typeArgs

        val resolvedHash = constraint.abilityHash ?: registry.resolveAbilityHash(abilityName)
        if (WellKnownHashConstants.isInspect(resolvedHash)) {
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        // Build the full class-arg tuple `[receiver, class-args…]` that
        // `checkAbilityImplementation` matches positionally against an impl's
        // targetTypeAnnotations. The receiver (`constraint.typeVar`, the `for 'l`
        // position) usually leads and the class-args (`typeArgs`, the `'r…`
        // positions) follow — the method-call path emits `typeArgs =
        // abilityTypeVars.drop(1)`, receiver kept OUT, so `[receiver] + typeArgs`
        // is the right tuple (this is what makes the class-param same-var case
        // `NumAdd 'a 'a` from `{x}->x+x` produce `[Int, Int]` instead of a
        // name-deduped 1-arg `[Int]` that no 2-arg impl matches).
        //
        // BUT some sites (annotation-sourced `with Ord 'a` constraints) already put
        // the receiver INTO `typeArgs`. Blindly prepending there duplicates it —
        // `Ord Float` becomes `[Float, Float]`, which no single-param `Ord for
        // Float` impl matches, wrongly rejecting valid single-param ability uses.
        // Gate on the ability's declared arity: prepend only when `typeArgs` is
        // short of it. When `typeArgs` already fills the arity, the receiver is
        // present — use it as-is.
        val trackingArgs = com.mezze.language.typechecker.constraints.abilityClassArgs(
            primary = MezzeType.VarType(constraint.typeVar),
            typeArgs = typeArgs,
            arity = registry.lookupAbility(abilityName)?.typeParams?.size,
        )
        val fromAnnotation = constraint.context?.startsWith("Type annotation on ") == true
        val trackedEntry = TrackedAbilityConstraint(abilityName, constraint.abilityHash, trackingArgs, constraint.loc, fromAnnotation)
        typeVarAbilityConstraints.getOrPut(constraint.typeVar) { mutableListOf() }
            .add(trackedEntry)

        for (typeArg in typeArgs) {
            if (typeArg is MezzeType.VarType && typeArg.variable != constraint.typeVar) {
                val constraints = typeVarAbilityConstraints.getOrPut(typeArg.variable) { mutableListOf() }
                constraints.add(trackedEntry)
            }
        }

        val allConcrete = trackingArgs.all { isConcrete(it) }

        if (allConcrete) {
            val hasImpl = checkAbilityImplementation(abilityName, trackingArgs, constraint.abilityHash, constraint.loc)

            if (!hasImpl) {
                // Impl-body validation: when the impl EXISTS somewhere (just not
                // matchable/import-visible in this isolated solve), defer to the
                // use-site/top-level solve instead of rejecting. A genuinely-absent
                // impl still fails, so an operator body with no applicable impl for
                // its operand type leaves `'out` unresolved and the return check
                // rejects it (the soundness case).
                if (deferExistingAbilityImpls && abilityImplExistsForType(abilityName, trackingArgs)) {
                    return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
                }
                val errorMessage = generateAbilityErrorMessage(abilityName, trackingArgs, constraint.loc)
                return ConstraintSolver.ConstraintResult.Failure(
                    errorMessage,
                    code = ErrorCode.V0408_NO_IMPLEMENTATION,
                )
            }

            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        } else {
            // Even with a sibling still unresolved, a concrete function operand that no
            // impl accepts at its slot is already unsatisfiable — reject eagerly rather
            // than deferring into the finalize absorb path (the class-param fn-operand
            // hole). See functionOperandHasNoImpl.
            if (functionOperandHasNoImpl(abilityName, trackingArgs, constraint.abilityHash)) {
                return ConstraintSolver.ConstraintResult.Failure(
                    generateAbilityErrorMessage(abilityName, trackingArgs, constraint.loc),
                    code = ErrorCode.V0408_NO_IMPLEMENTATION,
                )
            }
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }
    }

    internal fun solveAssociatedTypeResolution(
        constraint: Constraint.AssociatedTypeResolution,
        currentSubst: Substitution
    ): ConstraintSolver.ConstraintResult {
        val abilityName = constraint.abilityName
        val primaryType = currentSubst.apply(MezzeType.VarType(constraint.primaryTypeVar))

        DebugLogger.debug(Category.ConstraintSolver) {
            "solveAssociatedTypeResolution: ability=$abilityName, primaryTypeVar=${constraint.primaryTypeVar}, resolved=$primaryType"
        }

        if (primaryType is MezzeType.VarType) {
            DebugLogger.debug(Category.ConstraintSolver) {
                "  Deferring - primary type still a variable: ${primaryType.variable}"
            }
            // Dedup the deferral: finalizeDeferredAbilities re-invokes this on the
            // already-deferred constraints, and an unresolved primary re-adds the
            // SAME constraint to the SAME list every pass — unbounded growth that
            // OOMs on effect-heavy modules (TVar/Promise/Semaphore pull in many
            // deferred `+`/ability constraints). Only enqueue a constraint once per
            // var; a constraint still unresolved at finalize simply stays
            // unresolved (harmless — the AbilityRequired check enforces soundness).
            val queue = associatedTypeResolutionConstraints.getOrPut(primaryType.variable) { mutableListOf() }
            if (constraint !in queue) queue.add(constraint)
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        // Resolve the full class-arg tuple (e.g. [ 'r, 'l ] for NumAdd) so we can
        // disambiguate impls that share a receiver type (see classArgTypeVars).
        val resolvedTupleArgs = constraint.classArgTypeVars
            .map { currentSubst.apply(MezzeType.VarType(it)) }
        val tupleFullyConcrete = resolvedTupleArgs.isNotEmpty() &&
            resolvedTupleArgs.none { it is MezzeType.VarType }

        // B1 (soundness): for a MULTI-parameter class ability, receiver-only matching
        // freezes `having 'out` off the wrong operand while the receiver is still a
        // var. In `pub let f = {x} -> x + 0` the class-arg tuple is [ 'r=Int, 'l=? ];
        // matching by the co-operand `'r=Int` alone picks `NumAdd Int for Int` and
        // bakes `out := Int` into f's generalized scheme — but `f {x=5.0}` RUNS as
        // Float. We only freeze `out` from a FULLY-concrete tuple; otherwise we DEFER
        // (keyed on the still-open tuple vars), leaving `out` a var linked to the
        // receiver so it re-resolves at each call site once the receiver is pinned
        // (ExprFunctionConstraints re-emits the AssociatedTypeResolution there).
        // Single-param abilities have no co-operand to disambiguate, so receiver-only
        // matching (`primaryType` IS the receiver) stays correct for them.
        val isMultiParam = constraint.classArgTypeVars.size > 1
        if (isMultiParam && !tupleFullyConcrete) {
            // B1 containment (case B): the tuple isn't concrete, so we normally defer.
            // But if the `out` var has been pinned to a CONCRETE type EXTERNALLY (e.g.
            // `perform ExceptionToResult in (a / b)` unifies the body's `out` with
            // `Result …`), we must verify some impl of this ability can actually
            // PRODUCE that output type. No NumDiv impl yields `Result`, so a raw
            // numeric result laundering into the Result wrapper is a real error —
            // reject it here instead of deferring it away. Only fires when `out` is
            // already concrete; a still-var `out` defers as before (genuine poly).
            for ((_, outVar) in constraint.associatedTypeVars) {
                val resolvedOut = currentSubst.apply(MezzeType.VarType(outVar))
                if (resolvedOut !is MezzeType.VarType &&
                    !abilityCanProduceOutput(abilityName, resolvedOut)
                ) {
                    return ConstraintSolver.ConstraintResult.Failure(
                        "No implementation of ability '$abilityName' produces the required result type " +
                            "'${prettyPrintType(resolvedOut)}'. The result was forced to that type externally " +
                            "(e.g. by a `perform … in` handler expecting a wrapped value), but the operation " +
                            "yields a different associated result — wrap it (e.g. with `Ok`/`Some`) or adjust the types.",
                        code = ErrorCode.V0408_NO_IMPLEMENTATION,
                    )
                }
            }
            for (arg in resolvedTupleArgs) {
                if (arg is MezzeType.VarType) {
                    val queue = associatedTypeResolutionConstraints.getOrPut(arg.variable) { mutableListOf() }
                    if (constraint !in queue) queue.add(constraint)
                }
            }
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        val matchingImplPair = if (tupleFullyConcrete) {
            findMatchingAbilityImplByArgs(abilityName, resolvedTupleArgs)
                ?: findMatchingAbilityImpl(abilityName, primaryType)
        } else {
            findMatchingAbilityImpl(abilityName, primaryType)
        }
        DebugLogger.debug(Category.ConstraintSolver) {
            "  matchingImpl: ${matchingImplPair?.first?.abilityName} with bindings ${matchingImplPair?.first?.associatedTypeBindings}, " +
                "implTypeVarBindings: ${matchingImplPair?.second}"
        }

        if (matchingImplPair == null) {
            // No impl for this type yet — nothing to resolve. Not an error here;
            // a missing impl surfaces via the AbilityRequired check instead.
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        val matchingImpl = matchingImplPair.first
        val implTypeVarBindings = matchingImplPair.second

        val associatedBindings = matchingImpl.associatedTypeBindings
        if (associatedBindings.isEmpty()) {
            // Impl declares no `having` bindings — nothing to resolve.
            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        }

        var resultSubst = Substitution.empty

        for ((assocTypeName, typeVar) in constraint.associatedTypeVars) {
            val binding = associatedBindings[assocTypeName]
                ?: associatedBindings["'$assocTypeName"]

            if (binding != null) {
                val concreteType = substituteAnnotationWithImplBindings(binding, implTypeVarBindings)

                val resolvedTypeVar = currentSubst.apply(MezzeType.VarType(typeVar))

                // L-03: thread the class registry (was defaulting to
                // DeclarationRegistry.EMPTY) so alias/type-decl expansion happens
                // here the SAME as in VariantSolver's unify calls. Without it, a
                // nominal-alias/TypeApplication concreteType fails to expand and
                // `having 'out` silently mis-resolves — the same associated-type
                // question answered differently with vs without the registry.
                val unifyResult = unify(resolvedTypeVar, concreteType, registry)
                when (unifyResult) {
                    is UnificationResult.Success -> {
                        resultSubst = unifyResult.substitution.compose(resultSubst)
                    }
                    is UnificationResult.Failure -> {
                        return ConstraintSolver.ConstraintResult.Failure(
                            "Type mismatch for associated type '$assocTypeName': expected ${prettyPrintType(concreteType)} but got ${prettyPrintType(resolvedTypeVar)}",
                            code = unifyResult.code,
                        )
                    }
                }
            }
        }

        return ConstraintSolver.ConstraintResult.Success(resultSubst)
    }

    /** Resolve an impl's `having 'out = <annotation>` to a concrete type, substituting
     *  the impl's type-var bindings at EVERY named position — including nested ones under
     *  a constructor (`having 'out = Map Str 'a` must substitute the inner `'a`). This
     *  mirrors the body-side substitution in ImplValidator; without the recursion a nested
     *  type param falls through to `typeAnnotationToMezzeType`, which turns `'a` into a
     *  bogus `TypeApplication(constructor = "'a")` rather than the binding (#269). */
    private fun substituteAnnotationWithImplBindings(
        annotation: ParsedTypeAnnotation,
        implTypeVarBindings: Map<String, MezzeType>,
    ): MezzeType {
        // Look up a bound type var by name, tolerating the leading-`'` ambiguity
        // (assoc names are stored with `'` in decls but constraint keys are bare).
        fun boundFor(name: String): MezzeType? =
            implTypeVarBindings[name]
                ?: implTypeVarBindings["'$name"]
                ?: if (name.startsWith("'")) implTypeVarBindings[name.removePrefix("'")] else null

        return when (annotation) {
            is ParsedNamedType ->
                boundFor(annotation.name) ?: (PrimitiveTypes.fromName(annotation.name)
                    // #312: an UNBOUND type-var name (`'a`) is a free type variable —
                    // model it as VarType, NOT a bogus TypeApplication(constructor="'a")
                    // whose name lives only in `constructor`. (Closes producer #1: the
                    // #269 doc above describes exactly this leak.)
                    ?: if (annotation.name.isTypeVarName()) MezzeType.VarType(TypeVar.freshWithName(annotation.name))
                       else WellKnownTypeApp.of(annotation.name, emptyList()))
            is ParsedTypeApp -> {
                // A constructor position can itself be a bound type var (higher-kinded
                // binding); otherwise recurse into the args so nested `'a` is substituted.
                val bound = boundFor(annotation.constructor)
                if (bound != null && annotation.args.isEmpty()) bound
                // #312: an unbound nullary type-var constructor is a free type variable.
                else if (annotation.args.isEmpty() && annotation.constructor.isTypeVarName())
                    MezzeType.VarType(TypeVar.freshWithName(annotation.constructor))
                else WellKnownTypeApp.of(
                    annotation.constructor,
                    annotation.args.map { substituteAnnotationWithImplBindings(it, implTypeVarBindings) },
                )
            }
            is ParsedSeqType ->
                com.mezze.language.ast.BuiltinTypes.seqApp(substituteAnnotationWithImplBindings(annotation.elementType, implTypeVarBindings))
            is ParsedRecordType ->
                MezzeType.RecordType(fields = annotation.entries.associate {
                    it.name to substituteAnnotationWithImplBindings(it.type, implTypeVarBindings)
                })
            is ParsedFunctionType ->
                MezzeType.FunctionType(
                    paramType = substituteAnnotationWithImplBindings(annotation.paramType, implTypeVarBindings),
                    returnType = substituteAnnotationWithImplBindings(annotation.returnType, implTypeVarBindings),
                )
            is ParsedVariantType ->
                MezzeType.VariantType(constructors = annotation.constructors.associate {
                    it.name to it.dataType?.let { dt -> substituteAnnotationWithImplBindings(dt, implTypeVarBindings) }
                })
            is ParsedQualifiedType ->
                WellKnownTypeApp.of(
                    (annotation.modulePath + annotation.typeName).joinToString("::"),
                    annotation.args.map { substituteAnnotationWithImplBindings(it, implTypeVarBindings) },
                )
        }
    }

    // B1 containment (case B): can ANY impl of [abilityName] produce a `having 'out`
    // whose type could equal [required]? An impl whose declared output is a type
    // PARAMETER (e.g. `having 'out = 'r`) can produce any type, so it's permissive;
    // an impl with a concrete output (`having 'out = Int`) only matches that exact
    // head. If no impl can produce [required], the result was forced to a type this
    // ability never yields (e.g. a numeric op forced to `Result`), which is an error.
    private fun abilityCanProduceOutput(abilityName: String, required: MezzeType): Boolean {
        val impls = abilityImpls(abilityName)
        if (impls.isEmpty()) return true  // unknown ability surface — don't over-reject
        val requiredHead = typeHeadName(required)
        for (impl in impls) {
            for ((_, outAnnotation) in impl.associatedTypeBindings) {
                // A type-parameter output (name maps to one of the impl's type vars, or
                // starts with `'`) can produce anything.
                if (outAnnotation is ParsedNamedType) {
                    val n = outAnnotation.name
                    if (n.startsWith("'") || n.removePrefix("'") in impl.typeVars) return true
                }
                val outType = typeAnnotationToMezzeType(outAnnotation)
                if (outType is MezzeType.VarType) return true
                if (typeHeadName(outType) == requiredHead) return true
            }
            // An impl with no `having` bindings imposes no output constraint.
            if (impl.associatedTypeBindings.isEmpty()) return true
        }
        return false
    }

    // Head constructor name for a coarse "same shape" comparison (e.g. Result 'a 'b
    // vs Result Int Exception both have head "Result"). Precise arg agreement is
    // enforced later by unification; here we only rule out "no impl yields this shape".
    private fun typeHeadName(type: MezzeType): String = when (type) {
        is MezzeType.IntType -> "Int"
        is MezzeType.FloatType -> "Float"
        is MezzeType.StringType -> "Str"
        is MezzeType.NewtypeType -> type.name
        is MezzeType.BoolType -> "Bool"
        is MezzeType.TypeApplication -> type.constructor
        is MezzeType.VariantType -> type.identity.displayNameOrNull() ?: "|variant|"
        is MezzeType.RecordType -> "{record}"
        is MezzeType.FunctionType -> "{fn}"
        else -> type::class.simpleName ?: "?"
    }

    private fun findMatchingAbilityImpl(
        abilityName: String,
        primaryType: MezzeType
    ): Pair<TypingContext.AbilityImplInfo, Map<String, MezzeType>>? {
        val allImpls = abilityImpls(abilityName)

        // Which annotation is the RECEIVER? For a single-param ability it's the
        // only one; for a multi-param ability (`Scale 'by for 'a`) the impl's
        // `targetTypeAnnotations` maps positionally to the ability's type params
        // (`AbilityImplFactory` builds it as concreteArgs + targetTypes, "extra
        // params first, self last"), so the receiver sits at the ability's
        // `receiverTypeParam` index. We match `primaryType` (the receiver) against
        // that annotation only — the non-receiver args are pinned separately by
        // the ability-required constraint, and matching the receiver is what
        // disambiguates which impl supplies the `having` bindings.
        val abilityDecl = registry.lookupAbility(abilityName)
        val receiverIdx = abilityDecl?.let { decl ->
            decl.receiverTypeParam?.let { rp -> decl.typeParams.indexOf(rp) }
        }?.takeIf { it >= 0 }

        for (impl in allImpls) {
            val annotations = impl.targetTypeAnnotations ?: continue
            // Pick the receiver annotation: the ability-declared receiver index
            // when it's in range, else fall back to the last annotation (the
            // `for X` target is always last in the positional tuple).
            val receiverAnnotation = when {
                annotations.size == 1 -> annotations.first()
                receiverIdx != null && receiverIdx < annotations.size -> annotations[receiverIdx]
                else -> annotations.last()
            }
            val typeVarBindings = mutableMapOf<String, MezzeType>()
            val matched = TypeAnnotationMatcher.typeMatchesAnnotation(
                primaryType, receiverAnnotation, registry.typeDeclarations, impl.typeVars, typeVarBindings
            )
            if (matched) return impl to typeVarBindings
        }
        return null
    }

    /**
     * Find the impl whose FULL class-arg tuple matches [classArgs] (in the same
     * positional order the impl stores them: `AbilityImplFactory` builds
     * `targetTypeAnnotations` as the ability's class-args followed by the `for`
     * target). Unlike [findMatchingAbilityImpl] (receiver-only), this disambiguates
     * impls that share a receiver — e.g. `NumAdd Float for Float` vs
     * `NumAdd Int for Float`, which differ only in the non-receiver class arg —
     * so the RIGHT impl's `having` bindings are used for `'out` resolution.
     */
    private fun findMatchingAbilityImplByArgs(
        abilityName: String,
        classArgs: List<MezzeType>
    ): Pair<TypingContext.AbilityImplInfo, Map<String, MezzeType>>? {
        for (impl in abilityImpls(abilityName)) {
            val annotations = impl.targetTypeAnnotations ?: continue
            if (annotations.size != classArgs.size) continue
            val typeVarBindings = mutableMapOf<String, MezzeType>()
            val matched = classArgs.zip(annotations).all { (actualType, annotation) ->
                TypeAnnotationMatcher.typeMatchesAnnotation(
                    actualType, annotation, registry.typeDeclarations, impl.typeVars, typeVarBindings
                )
            }
            if (matched) return impl to typeVarBindings
        }
        return null
    }

    private fun typeAnnotationToMezzeType(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            // #312, same rule as [convertAnnotationToType] twelve hundred lines
            // below: an unbound type-var name IS a free type variable, not a
            // nominal type that happens to be spelled `'a`. Writing it as a
            // TypeApplication gives it a rigid identity that then refuses to
            // unify with the caller's concrete type. MEASURED at zero firings
            // across both suites, so this is the shape being made correct rather
            // than a live defect being closed — but the sibling below proves the
            // shape is reachable in general.
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name)
                ?: if (annotation.name.isTypeVarName()) MezzeType.VarType(TypeVar.freshWithName(annotation.name))
                   else WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedTypeApp -> WellKnownTypeApp.of(annotation.constructor, annotation.args.map { typeAnnotationToMezzeType(it) })
            is ParsedSeqType -> com.mezze.language.ast.BuiltinTypes.seqApp(typeAnnotationToMezzeType(annotation.elementType))
            is ParsedRecordType -> MezzeType.RecordType(fields = annotation.entries.associate { it.name to typeAnnotationToMezzeType(it.type) })
            is ParsedFunctionType -> MezzeType.FunctionType(paramType = typeAnnotationToMezzeType(annotation.paramType), returnType = typeAnnotationToMezzeType(annotation.returnType))
            is ParsedVariantType -> MezzeType.VariantType(constructors = annotation.constructors.associate { it.name to it.dataType?.let { dt -> typeAnnotationToMezzeType(dt) } })
            is ParsedQualifiedType -> WellKnownTypeApp.of((annotation.modulePath + annotation.typeName).joinToString("::"), annotation.args.map { typeAnnotationToMezzeType(it) })
        }
    }

    /**
     * Ability-impl lookup, hash-aware. Returns the UNION of the ability's
     * hash-keyed impls (resolved from [abilityName] via the registry — the same
     * pure fn the well-known table's generator uses) and its name-keyed impls,
     * deduped by content hash.
     *
     * The two populations are NOT interchangeable: USER impls carry the ability's
     * decl content hash and land in the by-hash index, while BUILT-IN impls
     * (`Ord for Int`, `Eq for Int`, …) are hashless and live only in the name
     * index. Returning just one silently drops the other — e.g. a user `Ord for
     * Str` would hide the builtin `Ord for Int`. Merging keeps the hash channel
     * (which disambiguates two same-named abilities from different modules) while
     * still surfacing the hashless builtins the name index holds.
     */
    private fun abilityImpls(abilityName: String, abilityHash: Hash? = null): List<TypingContext.AbilityImplInfo> {
        val hash = abilityHash ?: registry.resolveAbilityHash(abilityName)
        val byName = context.getAbilityImpls(abilityName)
        if (hash == null) return byName
        val byHash = context.getAbilityImplsByDeclHash(hash)
        // The name channel stays — impls whose module merely IMPORTED the ability
        // are untagged and it is the only key they have — but it may not supply an
        // impl that says it is for a DIFFERENT ability. That is the impl's own
        // testimony, so excluding it cannot lose a real answer, and admitting it is
        // how `show_b::Show`'s impl discharged a demand for `show_a::Show`.
        //
        // Narrowing this further was tried and reverted twice: dropping the name
        // channel entirely, and admitting only untagged impls while ignoring byHash
        // when non-empty. Both broke `Default.default` at Int and at Bool.
        val nameAdmissible = byName.filter { it.abilityDeclHash == null || it.abilityDeclHash == hash }
        if (byHash.isEmpty()) return nameAdmissible
        val seen = HashSet<Hash>()
        return (byHash + nameAdmissible).filter { seen.add(it.contentHash) }
    }

    /**
      * @param loc where the obligation was written, threaded purely so the
      *   auto-derivation failure below can be squiggled. An error the editor
      *   cannot place is an error the user has to go looking for.
      */
    internal fun checkAbilityImplementation(
        abilityName: String,
        typeArgs: List<MezzeType>,
        abilityHash: Hash? = null,
        loc: com.mezze.language.parsedast.Loc? = null,
    ): Boolean {
        val effectiveHash = abilityHash ?: registry.resolveAbilityHash(abilityName)
        if (typeArgs.size == 1) {
            if (canDeriveAbility(abilityName, typeArgs.first(), effectiveHash)) {
                registerDerivedShapeImpl(effectiveHash, typeArgs.first())
                return true
            }
        }

        if (isCodecAbility(abilityName, abilityHash)) {
            // New codec ability shape (`docs/codec/redesign-2026-09.md`):
            //   Encode 'config for 'a          — 2 positional slots [config, data]
            //   Decode 'config 'a for 'in      — 3 positional slots [config, data, in]
            //   Format for 'config having 'out — 1 positional slot  [config]
            // We only auto-derive the Encode / Decode pair; Format impls come
            // from format modules and never need synthesis.
            if (effectiveHash == WellKnownHashConstants.ABILITY_ENCODE && typeArgs.size == 2) {
                val formatType = typeArgs[0]
                val dataType = typeArgs[1]
                if (checkCodecAbilityImplementation(abilityName, listOf(formatType, dataType), dataType, loc)) {
                    return true
                }
            } else if (effectiveHash == WellKnownHashConstants.ABILITY_DECODE && typeArgs.size == 3) {
                val formatType = typeArgs[0]
                val dataType = typeArgs[1]
                val inType = typeArgs[2]
                if (checkCodecAbilityImplementation(abilityName, listOf(formatType, dataType, inType), dataType, loc)) {
                    return true
                }
            }
        }

        // One lookup, not a second copy of it. This was an inline duplicate of
        // `abilityImpls` that had drifted: it consulted the hash index and then fell
        // back to the NAME with no check that the impls it got back were impls of
        // THIS ability. That is the path a cross-module same-name collision took —
        // `show_b`'s impl for `B` discharged a demand for `show_a::Show`, because the
        // fix applied to `abilityImpls` never ran here.
        val impls = abilityImpls(abilityName, effectiveHash)

        val unwrappedTypeArgs = typeArgs

        val hasImpl = impls.any { implInfo ->
            val annotations = implInfo.targetTypeAnnotations ?: return@any false

            if (annotations.size != unwrappedTypeArgs.size) return@any false

            val typeVarBindings = mutableMapOf<String, MezzeType>()

            val matches = unwrappedTypeArgs.zip(annotations).all { (actualType, annotation) ->
                TypeAnnotationMatcher.typeMatchesAnnotation(actualType, annotation, registry.typeDeclarations, implInfo.typeVars, typeVarBindings)
            }

            if (!matches) return@any false

            if (typeVarBindings.isNotEmpty() && implInfo.operationConstraints.isNotEmpty()) {
                implInfo.operationConstraints.all { constraint ->
                    val resolvedTypeArgs = constraint.typeArgs.map { typeArg ->
                        when (typeArg) {
                            is ParsedNamedType -> {
                                val bound = typeVarBindings[typeArg.name]
                                if (bound != null) bound
                                else typeAnnotationToMezzeType(typeArg)
                            }
                            else -> typeAnnotationToMezzeType(typeArg)
                        }
                    }
                    // #86 sub-gap 2: when the with-clause has fewer args than the
                    // constrained ability's arity (e.g. `with Decode JsonC 'a`
                    // supplies 2 of Decode's 3 typeParams — 'config/'a but not
                    // 'in), the missing tail-slots correspond positionally to the
                    // OUTER impl's own receiver-type ('in). For an outer impl
                    // `FromJson 'a for Json`, the outer targets end with `Json`
                    // — that's the same `'in` the op body's `it` binds. Pad
                    // resolvedTypeArgs with the outer impl's trailing target
                    // types so the recursive check gets a full class-arg tuple.
                    // Without this, `Decode JsonC 'a` recursed with only
                    // [JsonC, Point] (2 args) and missed the codec-derive path
                    // which requires size 3 [config, data, in] — auto-derived
                    // decode was never triggered by `Type.from_json`.
                    val depAbility = registry.lookupAbility(constraint.abilityName)
                    val depArity = depAbility?.typeParams?.size ?: resolvedTypeArgs.size
                    val paddedTypeArgs = if (resolvedTypeArgs.size < depArity && annotations.size >= depArity - resolvedTypeArgs.size) {
                        val padCount = depArity - resolvedTypeArgs.size
                        val trailing = annotations.takeLast(padCount).map { ann ->
                            when (ann) {
                                is ParsedNamedType -> {
                                    val bound = typeVarBindings[ann.name]
                                    bound ?: typeAnnotationToMezzeType(ann)
                                }
                                else -> typeAnnotationToMezzeType(ann)
                            }
                        }
                        resolvedTypeArgs + trailing
                    } else {
                        resolvedTypeArgs
                    }
                    // A PARSED constraint: it carries a name and nothing else, so the
                    // registry is the only thing that can turn it into an identity.
                    checkAbilityImplementation(constraint.abilityName, paddedTypeArgs, registry.resolveAbilityHash(constraint.abilityName))
                }
            } else {
                true
            }
        }

        return hasImpl
    }

    private fun isCodecAbility(abilityName: String, abilityHash: Hash? = null): Boolean =
        WellKnownHashConstants.isCodecAbility(abilityHash ?: registry.resolveAbilityHash(abilityName))

    /**
     * Register an auto-derived codec impl. [allTypes] is the full positional
     * type tuple of the ability at the constraint site (Encode → [config,
     * data], Decode → [config, data, in]); [dataType] is the value shape the
     * emitter walks when it synthesizes the impl body.
     *
     * DP-10 (docs/codec/redesign-2026-09.md §Design principles): a missing
     * Tier-2 capability for the target format is a **compile-time error at
     * the offending field path**, never a runtime "field 'encode_variant'
     * not found". Before registering, walk the value shape and verify every
     * Tier-2 impl the emit would call is available for this config. If any
     * are missing, THROW with the path — the ability system's usual
     * "no impl" error surfaces the encode/decode site rather than a
     * cryptic runtime crash inside the auto-derived body.
     */
    private fun checkCodecAbilityImplementation(
        abilityName: String,
        allTypes: List<MezzeType>,
        dataType: MezzeType,
        loc: com.mezze.language.parsedast.Loc? = null,
    ): Boolean {
        if (!codecGenerator.isSerializable(dataType)) {
            return false
        }

        if (allTypes.any { containsVarType(it) }) {
            return false
        }

        val effectiveHash = registry.resolveAbilityHash(abilityName)
        val isEncode = effectiveHash == WellKnownHashConstants.ABILITY_ENCODE
        val isDecode = effectiveHash == WellKnownHashConstants.ABILITY_DECODE
        if (isEncode || isDecode) {
            val configType = allTypes[0]
            val missing = walkTier2Capabilities(dataType, configType, isEncode, path = emptyList())
            if (missing != null) {
                throw com.mezze.language.typechecker.TypeCheckError(
                    message = "Cannot auto-derive ${if (isEncode) "Encode" else "Decode"} " +
                        "${typeHeadName(configType)} for ${typeHeadName(dataType)}: " +
                        "format lacks ${missing.abilityName} " +
                        (if (missing.path.isEmpty()) "(at root)"
                         else "at field path .${missing.path.joinToString(".")}"),
                    loc = loc,
                    errorType = com.mezze.language.typechecker.ErrorType.ABILITY_ERROR,
                    code = com.mezze.language.parsedast.diagnostics.ErrorCode.V0408_NO_IMPLEMENTATION,
                )
            }
        }

        val hashBuilder = MezzeHashBuilder()
        hashBuilder.append("codec_impl")
        hashBuilder.append(abilityName)
        // #317: canonical MezzeTypeHasher, not lossy mezzeTypeToString (see the
        // single-param codec site above for why the field-name-only string collides).
        for (t in allTypes) com.mezze.language.ast.hashing.appendType(hashBuilder, t)
        val implKey = Hash(hashBuilder.build().toFullHexString())

        if (generatedCodecImpls.containsKey(implKey)) {
            return true
        }

        generatedCodecImpls[implKey] = GeneratedCodecImpl(
            abilityName = abilityName,
            targetTypes = allTypes,
            contentHash = implKey
        )
        return true
    }

    /**
     * Derived `Eq` for an ANONYMOUS record shape. Declared records / variants get
     * their derived instance generated once, in their declaring module
     * (ContextBuilder.deriveAbilityImpls); an anonymous `{ p: P }` has no declaring
     * module, so — like a codec impl — the module that compares it synthesizes the
     * instance (TypedModuleEmitter → DerivedImplGenerator). Only shapes holding a
     * DECLARED-impl leaf (a newtype somewhere inside) need one: for an all-structural
     * shape the runtime's structural equality is already the derived instance, and
     * generating an impl per record literal would only slow that path down.
     */
    private fun registerDerivedShapeImpl(abilityHash: Hash?, type: MezzeType) {
        if (!WellKnownHashConstants.isEq(abilityHash)) return
        if (type !is MezzeType.RecordType || type.identity !is TypeIdentity.Anonymous || type.isOpen) return
        if (containsVarType(type) || !hasDeclaredImplLeaf(type)) return
        val hashBuilder = MezzeHashBuilder()
        hashBuilder.append("derived_eq_shape")
        com.mezze.language.ast.hashing.appendType(hashBuilder, type)
        val implKey = Hash(hashBuilder.build().toFullHexString())
        if (generatedCodecImpls.containsKey(implKey)) return
        generatedCodecImpls[implKey] = GeneratedCodecImpl(
            abilityName = "Eq",
            targetTypes = listOf(type),
            contentHash = implKey,
        )
    }

    /** Does [type] contain, at any depth, a component whose `Eq` is a DECLARED
     *  impl rather than structural — i.e. a newtype? Declared records / variants
     *  are opened through the registry; a recursive type is cut at its back-edge. */
    private fun hasDeclaredImplLeaf(type: MezzeType, seen: MutableSet<String> = mutableSetOf()): Boolean {
        if (type is MezzeType.NewtypeType) return true
        if (type is MezzeType.TypeApplication) {
            val decl = registry.lookupType(type.constructor) ?: ModuleResolver.lookupTypeDeclaration(type.constructor)
            if (decl?.definition is ParsedNewtypeDef) return true
            if (!seen.add(type.constructor)) return false
            val expanded = expandTypeApplicationToRecord(type, registry) ?: expandTypeApplicationToVariant(type, registry)
            if (expanded != null) return hasDeclaredImplLeaf(expanded, seen)
        }
        return type.anyChild { hasDeclaredImplLeaf(it, seen) }
    }

    /** Missing-Tier-2 report used by [walkTier2Capabilities]. */
    private data class MissingTier2(val abilityName: String, val path: List<String>)

    /**
     * Walk the value shape and, for each shape that requires a Tier-2 impl
     * from the format, verify the impl exists. Returns the first missing
     * requirement (with field path) or null if every shape is covered.
     *
     * Mirrors [CodecGenerator]'s emit walk so the check is exactly what
     * gets called at runtime — no false positives (asking for an impl the
     * emitter wouldn't call) or false negatives (letting emit reach an
     * unregistered `.encode_*` method).
     */
    private fun walkTier2Capabilities(
        dataType: MezzeType,
        configType: MezzeType,
        isEncode: Boolean,
        path: List<String>,
    ): MissingTier2? {
        val prefix = if (isEncode) "Encode" else "Decode"

        fun require(abilityName: String): MissingTier2? {
            val hash = registry.resolveAbilityHash(abilityName)
            val impls = abilityImpls(abilityName, hash)
            val hit = impls.any { impl ->
                val ann = impl.targetTypeAnnotations?.firstOrNull() ?: return@any false
                val bindings = mutableMapOf<String, MezzeType>()
                TypeAnnotationMatcher.typeMatchesAnnotation(configType, ann, registry.typeDeclarations, impl.typeVars, bindings)
            }
            return if (hit) null else MissingTier2(abilityName, path)
        }

        // Bool-variant (`| True, False |`) folds into the scalar bool hook,
        // per PrimitiveTypes.isBoolVariant — treat it as scalar, not variant.
        if (com.mezze.language.ast.PrimitiveTypes.isBoolVariant(dataType)) {
            return require("${prefix}Scalar")
        }

        // An Option spelled structurally (`| Some { val }, None |`) needs the same
        // Option hook its `TypeApplication` spelling does, NOT the variant hook —
        // exactly as CodecGenerator now emits for it. The two must agree: when this
        // demanded ...Variant while the emit called encode_option, a format carrying
        // both got a value encoded as a tagged variant and read back as an option.
        if (BuiltinTypes.isOptionVariant(dataType)) {
            return require("${prefix}Option") ?: run {
                val inner = (dataType as MezzeType.VariantType).typeArgs.firstOrNull()
                    ?: (dataType.constructors[BuiltinTypes.SOME] as? MezzeType.RecordType)
                        ?.fields?.get(BuiltinTypes.VAL_FIELD)
                    ?: return@run null
                walkTier2Capabilities(inner, configType, isEncode, path + "?")
            }
        }

        return when (dataType) {
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.FracType, is MezzeType.CharType,
            is MezzeType.BytesType -> require("${prefix}Scalar")

            is MezzeType.RecordType -> require("${prefix}Record") ?: run {
                for ((fieldName, fieldType) in dataType.fields) {
                    val nested = walkTier2Capabilities(fieldType, configType, isEncode, path + fieldName)
                    if (nested != null) return nested
                }
                null
            }

            is MezzeType.VariantType -> require("${prefix}Variant") ?: run {
                for ((ctorName, payload) in dataType.constructors) {
                    if (payload != null) {
                        val nested = walkTier2Capabilities(payload, configType, isEncode, path + ctorName)
                        if (nested != null) return nested
                    }
                }
                null
            }



            is MezzeType.TypeApplication -> when (dataType.constructor) {
                BuiltinTypes.OPTION -> require("${prefix}Option") ?: run {
                    val inner = dataType.args.firstOrNull() ?: return@run null
                    walkTier2Capabilities(inner, configType, isEncode, path + "?")
                }
                // Builtin collection type-applications: some contexts produce
                // `TypeApplication("Map"|"List"|"Seq"|"Set"|…, args)` rather than
                // the structural `MezzeType.MapType`/`ListType`/… — the two are
                // interchangeable identity-wise (see BuiltinTypes.getGenericName).
                BuiltinTypes.LIST, BuiltinTypes.SEQ ->
                    require("${prefix}List") ?: dataType.args.firstOrNull()?.let {
                        walkTier2Capabilities(it, configType, isEncode, path + "[]")
                    }
                BuiltinTypes.SET, BuiltinTypes.SORTED_SET ->
                    require("${prefix}List") ?: dataType.args.firstOrNull()?.let {
                        walkTier2Capabilities(it, configType, isEncode, path + "{}")
                    }
                BuiltinTypes.MAP, BuiltinTypes.SORTED_MAP ->
                    require("${prefix}Map") ?: run {
                        val k = dataType.args.getOrNull(0)?.let {
                            walkTier2Capabilities(it, configType, isEncode, path + "key")
                        }
                        if (k != null) return k
                        dataType.args.getOrNull(1)?.let {
                            walkTier2Capabilities(it, configType, isEncode, path + "value")
                        }
                    }
                else -> null
            }

            // VarType / NewtypeType / FunctionType / EffectRow — the walker doesn't
            // emit a Tier-2 call for these directly. NewtypeType falls back to a
            // Tier-3 recursion at emit time; VarType is filtered by the
            // containsVarType gate above. Nothing to check.
            else -> null
        }
    }

    internal fun containsVarType(type: MezzeType): Boolean {
        return type is MezzeType.VarType || type.anyChild { containsVarType(it) }
    }

    /**
     * Strip open-row holes (`rowVar`) from records/variants recursively, yielding the
     * closed shape written by the programmer. Used both by codec (which serializes the
     * closed shape) and by [validateAbilityConstraints] (which must test a structural
     * ability against the closed shape rather than defer on the open row — HOLE C).
     */
    internal fun closeOpenRecords(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.RecordType -> {
                val needsClosing = type.rowVar != null || type.fields.values.any { needsRowClosing(it) }
                if (!needsClosing) {
                    return type
                }
                val closedFields = type.fields.mapValues { (_, fieldType) ->
                    closeOpenRecords(fieldType)
                }
                MezzeType.RecordType(fields = closedFields, rowVar = null, identity = type.identity)
            }
            is MezzeType.TypeApplication -> {
                if (type.args.none { needsRowClosing(it) }) {
                    return type
                }
                val closedArgs = type.args.map { closeOpenRecords(it) }
                WellKnownTypeApp.of(type.constructor, closedArgs, (type.identity as? TypeIdentity.Declared)?.hash)
            }
            is MezzeType.VariantType -> {
                val needsClosing = type.rowVar != null || type.constructors.values.any { it != null && needsRowClosing(it) }
                if (!needsClosing) {
                    return type
                }
                val closedConstructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { closeOpenRecords(it) }
                }
                MezzeType.VariantType(closedConstructors, rowVar = null, identity = type.identity)
            }
            is MezzeType.FunctionType -> {
                if (!needsRowClosing(type.paramType) && !needsRowClosing(type.returnType)) return type
                MezzeType.FunctionType(
                    paramType = closeOpenRecords(type.paramType),
                    returnType = closeOpenRecords(type.returnType),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.VarType -> type
            is MezzeType.EffectRow -> type
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.FracType,
            is MezzeType.CharType,
            is MezzeType.BytesType,
            is MezzeType.NewtypeType -> type
        }
    }

    private fun needsRowClosing(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.RecordType -> type.rowVar != null
            is MezzeType.VariantType -> type.rowVar != null
            else -> false
        } || type.anyChild { needsRowClosing(it) }
    }

    /** Structural derivability ONLY — never consults a declared impl for [type]
     *  itself (fields/constructors recurse through [canDeriveOrHasImpl]). Internal so
     *  ImplValidator can reject a hand-written impl on a type the compiler already
     *  derives the ability for (coherence: one instance per type). */
    internal fun canDeriveAbility(
        abilityName: String,
        type: MezzeType,
        abilityHash: Hash? = null,
        seen: MutableSet<Any> = mutableSetOf(),
    ): Boolean = com.mezze.language.typechecker.derive.canDeriveAbilityStructurally(
        abilityName, type, abilityHash, registry, seen,
    ) { name, componentType, hash, seenSoFar -> canDeriveOrHasImpl(name, componentType, hash, seenSoFar) }

    private fun canDeriveOrHasImpl(
        abilityName: String,
        type: MezzeType,
        abilityHash: Hash? = null,
        seen: MutableSet<Any> = mutableSetOf(),
    ): Boolean {
        if (canDeriveAbility(abilityName, type, abilityHash, seen)) return true
        // The retry WITHOUT the hash is load-bearing and must stay until the demand
        // side is fixed. Making the hash answer final was tried and reverted: it
        // broke `Default.default` at Int and at Bool (StaticAbilityOpTest), because
        // the hash a demand arrives with is not always the one its ability is
        // registered under, and the by-name retry is what currently repairs that.
        // The hash is only trustworthy once `DeclarationRegistry` stops keying
        // abilities by name — see AbilityIdentityCrossModuleTest.
        if (abilityHash != null && checkAbilityImplementation(abilityName, listOf(type), abilityHash)) return true
        return checkAbilityImplementation(abilityName, listOf(type))
    }

    /**
     * Render the `impl … where { ... }` skeleton suggested in an ability error,
     * keyed on the ability's DECLARED SHAPE (never on hard-coded names):
     *
     *  - Non-class-param ability (no params before `for`, no `having`) →
     *    `impl <Ability> for <receiver> where { ... }` (Ord, Eq, Show, …).
     *  - Class-param / having ability (e.g. `NumMul 'r for 'l having 'out`) →
     *    `impl <Ability> <class-args…> for <receiver> having <assoc> = ... where { ... }`,
     *    with `...` as the placeholder for each associated type's value. Class
     *    args are omitted from the head when there are none; the `having` clause
     *    is omitted when the ability declares no associated types.
     *
     * [receiverRendered] is the `for` target; [classArgsRendered] are the
     * ability's class-args (the params before `for`), already pretty-printed and
     * in declared order. [withBody] appends the ` where { ... }` tail (used for
     * the actionable "implement:" suggestion; the terser "Available impls" list
     * omits it).
     */
    private fun renderImplSkeleton(
        abilityName: String,
        receiverRendered: String,
        classArgsRendered: List<String>,
        withBody: Boolean = true,
    ): String {
        val decl = registry.lookupAbility(abilityName)
        // typeParams = class-args + receiver (receiver last); associatedTypes = the
        // `having` names as written (e.g. "'out"). A missing decl falls back to the
        // observed args so a class-param impl still renders its class-args + having.
        val declaredClassArgCount = decl?.let { it.typeParams.size - 1 }?.takeIf { it >= 0 }
        val assocTypes = decl?.associatedTypes ?: emptyList()

        val classArgs = if (declaredClassArgCount != null) {
            classArgsRendered.take(declaredClassArgCount)
        } else {
            classArgsRendered
        }

        val body = if (withBody) " where { ... }" else ""

        val isClassParam = classArgs.isNotEmpty() || assocTypes.isNotEmpty()
        if (!isClassParam) {
            return "impl $abilityName for $receiverRendered$body"
        }

        val head = if (classArgs.isEmpty()) {
            "impl $abilityName for $receiverRendered"
        } else {
            "impl $abilityName ${classArgs.joinToString(" ")} for $receiverRendered"
        }
        val having = if (assocTypes.isEmpty()) "" else " having " + assocTypes.joinToString(", ") { "$it = ..." }
        return "$head$having$body"
    }

    internal fun generateAbilityErrorMessage(abilityName: String, typeArgs: List<MezzeType>, loc: Loc? = null): String {
        val hasFunctionType = typeArgs.any { it is MezzeType.FunctionType }
        val typeNames = typeArgs.map { prettyPrintType(it) }.joinToString(", ")
        val primaryType = typeArgs.firstOrNull()?.let { prettyPrintType(it) } ?: typeNames

        val operator = loc?.let { SyntaxMetadata.getOperatorSyntax(it) }

        if (hasFunctionType) {
            return if (operator != null) {
                "Cannot use '$operator' with a function. Functions cannot implement $abilityName."
            } else {
                "Functions cannot implement '$abilityName'."
            }
        }

        if (operator != null) {
            val implExistsForType = abilityImplExistsForType(abilityName, typeArgs)
            return if (implExistsForType) {
                "Cannot use '$operator' with $typeNames. An `impl $abilityName for $primaryType` exists but is not " +
                    "visible at this call site — most likely the operator's stdlib helper isn't imported. " +
                    "Try adding `use std::${stdModuleForType(primaryType)}::{ * }` (or import the specific symbol used)." +
                    formatAbilityCandidates(abilityName)
            } else {
                // typeArgs here is `[receiver, class-args…]` (see solveAbilityRequired's
                // `[receiver] + typeArgs`), so the receiver leads and the class-args follow.
                val classArgsRendered = typeArgs.drop(1).map { prettyPrintType(it) }
                val skeleton = renderImplSkeleton(abilityName, primaryType, classArgsRendered)
                "Cannot use '$operator' with $typeNames. To enable this, implement: $skeleton${formatAbilityCandidates(abilityName)}"
            }
        }

        return when (abilityName) {
            // Codec Tier 3 (docs/codec/redesign-2026-09.md): `Decode 'config 'a for 'in`.
            // Positional args are [config, data, in]; data is the target type,
            // often driven by a LHS type annotation or inherent-method syntax
            // (`Type.from_json { it = raw }`).
            "Decode" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    """Cannot infer decode target type. Add a type annotation:
  let result: MyType = it.decode { config = config }
  # or use inherent-method syntax:
  MyType.from_json { it = raw }"""
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            // Codec Tier 3: `Encode 'config for 'a`. Positional args are [config, data].
            "Encode" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    "Cannot infer encode source type. Add a type annotation to the value being encoded."
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            "Tryable" -> {
                "Cannot use `<-` on $typeNames — no `Tryable` impl. `<-` works " +
                    "on `Option`, `Result`, or any type implementing `Tryable`. " +
                    "If you meant a plain binding, use `let x = ...` instead."
            }
            else -> {
                val classArgsRendered = typeArgs.drop(1).map { prettyPrintType(it) }
                val skeleton = renderImplSkeleton(abilityName, primaryType, classArgsRendered)
                "$typeNames does not implement '$abilityName'. To enable this, implement: $skeleton${formatAbilityCandidates(abilityName)}"
            }
        }
    }

    /**
     * Does an impl of [abilityName] TARGET this type — setting aside whether the
     * impl's own `with` constraints can be discharged from HERE?
     *
     * That last clause is the entire purpose. [checkAbilityImplementation] answers
     * the strict question, and for a generic impl it also recurses into the impl's
     * `operationConstraints`: `impl Eq for List 'a … with Eq 'a` binds `'a = N` and
     * then demands `Eq N`, which an ISOLATED impl-body solve may simply not have in
     * scope. Deferring THAT to the top-level solve, where the imports are, is what
     * [deferExistingAbilityImpls] exists for. A type with no impl at all still
     * fails, because no target matches it.
     *
     * It used to be answered by rendering both sides to STRINGS and comparing them,
     * with a fallback comparing only the first space-separated word. It asks the
     * real question now, with the same matcher the strict check uses: bind the
     * target's type variables and compare the arguments positionally.
     *
     * MEASURED, because the reason to change it was a rule and not a failing case,
     * and the difference between those two matters here:
     *  - the exact string compare decided NOTHING — 0 firings across both suites;
     *  - the first-word fallback decided 5, every one a generic impl target against
     *    a concrete instantiation (`List 'a` vs `List N`, `Opt 'a` vs `Opt Color`),
     *    which it got right by accident;
     *  - it could not see through brackets — `['a]` vs `[N]` reads as `"['a]"` vs
     *    `"[N]"` — so it answered NO 4 times where a generic seq impl does cover
     *    the concrete query. Those 4 are the only divergences on `:typechecker:test`
     *    and there are none on `:interpreter:test`; both suites are green under
     *    either answer.
     *
     * NOT a fix for a reachable wrong answer, and the note that claimed otherwise
     * was wrong: the first-word hack cannot confuse `Seq Int` with `Seq Str`,
     * because the surface syntax cannot express an impl for a concretely-applied
     * type at all — `impl Show for Box Int` parses as TWO target slots and is
     * rejected on arity, and the stdlib has zero such impls. The justification is
     * the standing rule instead: a resolution path does not compare rendered type
     * names (see the `prettyPrintType`-is-lossy rule), and this predicate should
     * agree with its strict sibling rather than approximate it.
     */
    private fun abilityImplExistsForType(abilityName: String, typeArgs: List<MezzeType>): Boolean {
        if (typeArgs.isEmpty()) return false
        return abilityImpls(abilityName).any { info ->
            val annotations = info.targetTypeAnnotations ?: return@any false
            if (annotations.size != typeArgs.size) return@any false
            val typeVarBindings = mutableMapOf<String, MezzeType>()
            typeArgs.zip(annotations).all { (actualType, annotation) ->
                TypeAnnotationMatcher.typeMatchesAnnotation(
                    actualType, annotation, registry.typeDeclarations, info.typeVars, typeVarBindings,
                )
            }
        }
    }

    private fun stdModuleForType(primaryType: String): String = when {
        primaryType.startsWith("Int") -> "int"
        primaryType.startsWith("Float") -> "float"
        primaryType.startsWith("Nat") -> "nat"
        primaryType.startsWith("Bool") -> "bool"
        primaryType.startsWith("Str") -> "str"
        primaryType.startsWith("Char") -> "char"
        primaryType.startsWith("Frac") -> "frac"
        primaryType.startsWith("List") -> "collections::list"
        primaryType.startsWith("Map") -> "collections::map"
        primaryType.startsWith("Set") -> "collections::set"
        primaryType.startsWith("Seq") -> "collections::seq"
        primaryType.startsWith("Bytes") -> "collections::bytes"
        primaryType.startsWith("Option") -> "option"
        primaryType.startsWith("Result") -> "result"
        else -> "<unknown>"
    }

    private fun formatAbilityCandidates(abilityName: String): String {
        val impls = abilityImpls(abilityName)
        if (impls.isEmpty()) return ""
        val rendered = impls.mapNotNull { info ->
            val targets = info.targetTypeAnnotations
            when {
                targets != null && targets.isNotEmpty() ->
                    targets.joinToString(", ") { renderAnnotation(it) }
                info.targetTypes.isNotEmpty() ->
                    info.targetTypes.joinToString(", ")
                else -> null
            }
        }.distinct()
        if (rendered.isEmpty()) return ""
        val shown = rendered.take(5)
        val suffix = if (rendered.size > shown.size) ", … (${rendered.size - shown.size} more)" else ""
        // Each entry is the impl's rendered target tuple = `[class-args…, receiver]`
        // (`allTypeArgs = abilityTypeArgs + targetTypes`, receiver LAST). Split it
        // back into class-args + receiver so the impl form renders correctly for
        // class-param abilities (`impl NumMul Int for Int having 'out = ...`) while
        // single-target abilities stay `impl Ord for Int`. Split by the ability's
        // declared class-arg count (leading N slots = class-args, remainder =
        // receiver) so a receiver that itself contains a comma stays intact.
        val classArgCount = registry.lookupAbility(abilityName)
            ?.let { it.typeParams.size - 1 }?.takeIf { it >= 0 } ?: 0
        val implList = shown.joinToString("; ") { targetTuple ->
            val parts = targetTuple.split(", ")
            val n = classArgCount.coerceAtMost((parts.size - 1).coerceAtLeast(0))
            val classArgs = parts.take(n)
            val receiver = parts.drop(n).joinToString(", ")
            renderImplSkeleton(abilityName, receiver, classArgs, withBody = false)
        }
        return "\n  Available impls: $implList$suffix"
    }

    private fun renderAnnotation(a: ParsedTypeAnnotation): String = when (a) {
        is ParsedNamedType -> a.name
        is ParsedQualifiedType -> {
            val prefix = a.modulePath.joinToString("::")
            val head = if (prefix.isEmpty()) a.typeName else "$prefix::${a.typeName}"
            if (a.args.isEmpty()) head else "$head ${a.args.joinToString(" ") { renderAnnotation(it) }}"
        }
        is ParsedTypeApp ->
            if (a.args.isEmpty()) a.constructor else "${a.constructor} ${a.args.joinToString(" ") { renderAnnotation(it) }}"
        is ParsedRecordType -> {
            val fields = a.entries.joinToString(", ") { "${it.name}: ${renderAnnotation(it.type)}" }
            if (a.isOpen) "{ $fields, ... }" else "{ $fields }"
        }
        is ParsedVariantType -> {
            val ctors = a.constructors.joinToString(", ") { c ->
                val dt = c.dataType
                if (dt == null) c.name else "${c.name} ${renderAnnotation(dt)}"
            }
            "| $ctors |"
        }
        is ParsedFunctionType ->
            "${renderAnnotation(a.paramType)} -> ${renderAnnotation(a.returnType)}"
        is ParsedSeqType -> "[${renderAnnotation(a.elementType)}]"
        else -> a::class.simpleName?.removePrefix("Parsed")?.removeSuffix("Type") ?: "?"
    }

    internal fun isConcrete(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType, is MezzeType.EffectRow -> false
            is MezzeType.RecordType -> type.rowVar == null && !type.anyChild { !isConcrete(it) }
            is MezzeType.VariantType -> type.rowVar == null && !type.anyChild { !isConcrete(it) }
            else -> !type.anyChild { !isConcrete(it) }
        }
    }

    internal fun tryBidirectionalInference(abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): Substitution? {
        val resolvedHash = abilityHash ?: registry.resolveAbilityHash(abilityName)
        // New codec Tier 3: `Decode 'config 'a for 'in` — positional [config, data, in].
        // When only 'a is unbound and there's exactly one derived Decode impl
        // matching the concrete [config, in] pair, infer 'a from it.
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODE && typeArgs.size == 3) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]
            val inType = typeArgs[2]

            if (formatType !is MezzeType.VarType && inType !is MezzeType.VarType && dataType is MezzeType.VarType) {
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    registry.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODE &&
                    impl.targetTypes.size >= 3 &&
                    typeMatchesMezzeType(formatType, impl.targetTypes[0]) &&
                    typeMatchesMezzeType(inType, impl.targetTypes[2])
                }

                if (matchingImpls.size == 1) {
                    val impl = matchingImpls.first()
                    val inferredType = impl.targetTypes[1]
                    return Substitution.single(dataType.variable, inferredType)
                }
            }
        }

        val allImpls = abilityImpls(abilityName)

        if (allImpls.isEmpty()) {
            return null
        }

        val matchingImpls = mutableListOf<List<ParsedTypeAnnotation>>()

        for (impl in allImpls) {
            val annotations = impl.targetTypeAnnotations
            if (annotations == null || annotations.size != typeArgs.size) {
                continue
            }

            var matches = true
            for (i in typeArgs.indices) {
                val constraintType = typeArgs[i]

                if (constraintType !is MezzeType.VarType) {
                    if (!TypeAnnotationMatcher.typeMatchesAnnotation(constraintType, annotations[i], registry.typeDeclarations)) {
                        matches = false
                        break
                    }
                }
            }

            if (matches) {
                matchingImpls.add(annotations)
            }
        }

        if (matchingImpls.isEmpty()) {
            return null
        }

        if (matchingImpls.size > 1) {
            return null
        }

        val implAnnotations = matchingImpls.first()
        val substitutionMap = mutableMapOf<TypeVar, MezzeType>()

        for (i in typeArgs.indices) {
            val constraintType = typeArgs[i]
            if (constraintType is MezzeType.VarType) {
                val inferredType = convertAnnotationToType(implAnnotations[i])
                if (inferredType != null) {
                    substitutionMap[constraintType.variable] = inferredType
                }
            }
        }

        return if (substitutionMap.isNotEmpty()) {
            Substitution(substitutionMap)
        } else {
            null
        }
    }

    /**
     * True when some position of [typeArgs] holds a concrete [MezzeType.FunctionType]
     * that no impl of [abilityName] can accept at THAT index — i.e. the obligation is
     * already unsatisfiable regardless of how the other (possibly still-var) positions
     * resolve. A function value is monotonic and can never implement an ability whose
     * impls target concrete named types, so this verdict is stable.
     *
     * Positionally exact: an impl with a generic type-var slot at the function's index
     * (`impl A for 'a`) MATCHES (TypeAnnotationMatcher returns true for a var slot), so
     * this correctly does NOT fire — a function passed where a fully-generic slot is
     * declared is legal. Var positions in [typeArgs] are skipped, mirroring
     * [tryBidirectionalInference]. Returns false if there is no function operand.
     */
    private fun functionOperandHasNoImpl(abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): Boolean {
        val fnIndices = typeArgs.indices.filter { typeArgs[it] is MezzeType.FunctionType }
        if (fnIndices.isEmpty()) return false

        val impls = if (abilityHash != null) {
            val byHash = context.getAbilityImplsByDeclHash(abilityHash)
            if (byHash.isNotEmpty()) byHash else context.getAbilityImpls(abilityName)
        } else {
            context.getAbilityImpls(abilityName)
        }

        // Reject if EVERY function position fails to match EVERY arity-compatible impl at
        // its own index. (A single function operand that matches no impl at its slot is
        // enough to make the whole obligation unsatisfiable.)
        return fnIndices.any { i ->
            impls.none { implInfo ->
                val annotations = implInfo.targetTypeAnnotations ?: return@none false
                if (annotations.size != typeArgs.size) return@none false
                TypeAnnotationMatcher.typeMatchesAnnotation(
                    typeArgs[i], annotations[i], registry.typeDeclarations, implInfo.typeVars
                )
            }
        }
    }

    private fun convertAnnotationToType(annotation: ParsedTypeAnnotation): MezzeType? {
        return when (annotation) {
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name)
                // #312: an UNBOUND type-var name (`'a`) is a free type variable —
                // model it as VarType, NOT a bogus TypeApplication(constructor="'a")
                // whose name lives only in `constructor`. This is the sibling
                // producer to substituteAnnotationWithImplBindings (same #312 leak):
                // a generic impl's target slot (`impl FromJson 'a for Str`) is a
                // bare type-var, so binding a call-site var to it must yield a fresh
                // free var, not a rigid TypeApplication that then clashes with the
                // caller's concrete target type.
                ?: if (annotation.name.isTypeVarName()) MezzeType.VarType(TypeVar.freshWithName(annotation.name))
                   else WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedTypeApp ->
                // A nullary type-var constructor (`'a` with no args) is likewise a
                // free type variable, never a TypeApplication.
                if (annotation.args.isEmpty() && annotation.constructor.isTypeVarName())
                    MezzeType.VarType(TypeVar.freshWithName(annotation.constructor))
                else { val args = annotation.args.mapNotNull { convertAnnotationToType(it) }; if (args.size != annotation.args.size) null else WellKnownTypeApp.of(annotation.constructor, args) }
            is ParsedSeqType -> convertAnnotationToType(annotation.elementType)?.let { com.mezze.language.ast.BuiltinTypes.seqApp(it) }
            is ParsedRecordType -> { val fields = mutableMapOf<String, MezzeType>(); for (e in annotation.entries) { val ft = convertAnnotationToType(e.type) ?: return null; fields[e.name] = ft }; MezzeType.RecordType(fields) }
            is ParsedVariantType -> MezzeType.VariantType(annotation.constructors.associate { it.name to it.dataType?.let { dt -> convertAnnotationToType(dt) } })
            is ParsedFunctionType -> { val p = convertAnnotationToType(annotation.paramType) ?: return null; val r = convertAnnotationToType(annotation.returnType) ?: return null; MezzeType.FunctionType(p, r) }
            is ParsedQualifiedType -> null
        }
    }

    private fun typeMatchesMezzeType(expected: MezzeType, actual: MezzeType): Boolean {
        if (expected == actual) return true
        if (expected is MezzeType.TypeApplication && actual is MezzeType.TypeApplication) {
            // Hash is authoritative: when both carry a content hash and they DIFFER,
            // these are distinct declarations and do NOT match, even by name. Mirrors
            // the unifyTypeApplications hash-veto (Stage 0 proved recursion-stable).
            val eh = (expected.identity as? TypeIdentity.Declared)?.hash
            val ah = (actual.identity as? TypeIdentity.Declared)?.hash
            if (eh != null && ah != null && eh != ah) return false
            // Name match OR equal-hash match (rename-safe alias); the hash clause
            // only ADDS matches — the hash-inequality reject above handled distinctness.
            return expected.constructor == actual.constructor ||
                (eh != null && ah != null && eh == ah)
        }
        return false
    }
}
