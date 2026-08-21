package com.mezze.language.typechecker.solving

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.constraints.Constraint
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.mono.unwrapSuspended
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typedast.Hash

internal class AbilityChecker(
    private val context: TypingContext,
    private val registry: DeclarationRegistry,
    /**
     * Impl-body validation mode (see ConstraintSolver.deferExistingAbilityImpls).
     * When true, an AbilityRequired whose impl exists somewhere but doesn't match
     * the strict check here is DEFERRED (success) rather than failed, so an
     * operator body using a type whose impl isn't import-visible at this site
     * isn't spuriously rejected. A truly-absent impl still fails.
     */
    private val deferExistingAbilityImpls: Boolean = false
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

    private val generatedCodecImpls = mutableMapOf<Hash, GeneratedCodecImpl>()
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
                    val trackedResolvedHash = tracked.abilityHash ?: WellKnownHashConstants.resolveHash(abilityName)
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
                        val hasImpl = checkAbilityImplementation(abilityName, closedTypeArgs, tracked.abilityHash)
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

        val resolvedHash = constraint.abilityHash ?: WellKnownHashConstants.resolveHash(abilityName)
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
        val abilityArity = registry.lookupAbility(abilityName)?.typeParams?.size
        val trackingArgs = if (abilityArity != null && typeArgs.size >= abilityArity) {
            typeArgs
        } else {
            listOf(MezzeType.VarType(constraint.typeVar)) + typeArgs
        }
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
            val hasImpl = checkAbilityImplementation(abilityName, trackingArgs, constraint.abilityHash)

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
                return ConstraintSolver.ConstraintResult.Failure(errorMessage)
            }

            return ConstraintSolver.ConstraintResult.Success(Substitution.empty)
        } else {
            // Even with a sibling still unresolved, a concrete function operand that no
            // impl accepts at its slot is already unsatisfiable — reject eagerly rather
            // than deferring into the finalize absorb path (the class-param fn-operand
            // hole). See functionOperandHasNoImpl.
            if (functionOperandHasNoImpl(abilityName, trackingArgs, constraint.abilityHash)) {
                return ConstraintSolver.ConstraintResult.Failure(
                    generateAbilityErrorMessage(abilityName, trackingArgs, constraint.loc)
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
        // Fall back to receiver-only matching when the tuple isn't available or any
        // element is still unresolved.
        val resolvedClassArgs = constraint.classArgTypeVars
            .map { currentSubst.apply(MezzeType.VarType(it)) }
            .takeIf { args -> args.isNotEmpty() && args.none { it is MezzeType.VarType } }
        val matchingImplPair = if (resolvedClassArgs != null) {
            findMatchingAbilityImplByArgs(abilityName, resolvedClassArgs)
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

                val unifyResult = unify(resolvedTypeVar, concreteType)
                when (unifyResult) {
                    is UnificationResult.Success -> {
                        resultSubst = unifyResult.substitution.compose(resultSubst)
                    }
                    is UnificationResult.Failure -> {
                        return ConstraintSolver.ConstraintResult.Failure(
                            "Type mismatch for associated type '$assocTypeName': expected ${prettyPrintType(concreteType)} but got ${prettyPrintType(resolvedTypeVar)}"
                        )
                    }
                }
            }
        }

        return ConstraintSolver.ConstraintResult.Success(resultSubst)
    }

    private fun substituteAnnotationWithImplBindings(
        annotation: ParsedTypeAnnotation,
        implTypeVarBindings: Map<String, MezzeType>,
    ): MezzeType {
        if (annotation is ParsedNamedType) {
            val name = annotation.name
            implTypeVarBindings[name]?.let { return it }
            implTypeVarBindings["'$name"]?.let { return it }
            if (name.startsWith("'")) {
                implTypeVarBindings[name.removePrefix("'")]?.let { return it }
            }
        }
        return typeAnnotationToMezzeType(annotation)
    }

    private fun findMatchingAbilityImpl(
        abilityName: String,
        primaryType: MezzeType
    ): Pair<TypingContext.AbilityImplInfo, Map<String, MezzeType>>? {
        val allImpls = context.getAbilityImpls(abilityName)

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
        for (impl in context.getAbilityImpls(abilityName)) {
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
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedTypeApp -> WellKnownTypeApp.of(annotation.constructor, annotation.args.map { typeAnnotationToMezzeType(it) })
            is ParsedSeqType -> MezzeType.SeqType(typeAnnotationToMezzeType(annotation.elementType))
            is ParsedRecordType -> MezzeType.RecordType(fields = annotation.entries.associate { it.name to typeAnnotationToMezzeType(it.type) })
            is ParsedFunctionType -> MezzeType.FunctionType(paramType = typeAnnotationToMezzeType(annotation.paramType), returnType = typeAnnotationToMezzeType(annotation.returnType))
            is ParsedVariantType -> MezzeType.VariantType(constructors = annotation.constructors.associate { it.name to it.dataType?.let { dt -> typeAnnotationToMezzeType(dt) } })
            is ParsedQualifiedType -> WellKnownTypeApp.of((annotation.modulePath + annotation.typeName).joinToString("::"), annotation.args.map { typeAnnotationToMezzeType(it) })
        }
    }

    internal fun checkAbilityImplementation(abilityName: String, typeArgs: List<MezzeType>, abilityHash: Hash? = null): Boolean {
        val effectiveHash = abilityHash ?: WellKnownHashConstants.resolveHash(abilityName)
        if (typeArgs.size == 1) {
            if (canDeriveAbility(abilityName, typeArgs.first(), effectiveHash)) {
                return true
            }
        }

        if (isCodecAbility(abilityName, abilityHash)) {
            if (typeArgs.size == 2) {
                val dataType = typeArgs[0]
                val formatType = typeArgs[1]
                if (checkCodecAbilityImplementation(abilityName, formatType, dataType)) {
                    return true
                }
            } else if (typeArgs.size == 1 && (effectiveHash == WellKnownHashConstants.ABILITY_ENCODEREPR || effectiveHash == WellKnownHashConstants.ABILITY_DECODEREPR)) {
                val dataType = typeArgs[0]
                if (codecGenerator.isSerializable(dataType) && !containsVarType(dataType)) {
                    val hashBuilder = MezzeHashBuilder()
                    hashBuilder.append("codec_impl")
                    hashBuilder.append(abilityName)
                    hashBuilder.append("_single_param_")
                    hashBuilder.append(ContentHashComputer.mezzeTypeToString(dataType))
                    val implKey = Hash(hashBuilder.build().toFullHexString())
                    if (!generatedCodecImpls.containsKey(implKey)) {
                        generatedCodecImpls[implKey] = GeneratedCodecImpl(
                            abilityName = abilityName,
                            targetTypes = listOf(dataType),
                            contentHash = implKey
                        )
                    }
                    return true
                }
            }
        }

        val impls = if (abilityHash != null) {
            val byHash = context.getAbilityImplsByDeclHash(abilityHash)
            if (byHash.isNotEmpty()) byHash else context.getAbilityImpls(abilityName)
        } else {
            context.getAbilityImpls(abilityName)
        }

        val unwrappedTypeArgs = typeArgs.map(::unwrapSuspended)

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
                    checkAbilityImplementation(constraint.abilityName, resolvedTypeArgs, WellKnownHashConstants.resolveHash(constraint.abilityName))
                }
            } else {
                true
            }
        }

        return hasImpl
    }

    private fun isCodecAbility(abilityName: String, abilityHash: Hash? = null): Boolean =
        WellKnownHashConstants.isCodecAbility(abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName))

    private fun checkCodecAbilityImplementation(
        abilityName: String,
        formatType: MezzeType,
        dataType: MezzeType
    ): Boolean {
        if (!codecGenerator.isSerializable(dataType)) {
            return false
        }

        if (containsVarType(formatType) || containsVarType(dataType)) {
            return false
        }

        val hashBuilder = MezzeHashBuilder()
        hashBuilder.append("codec_impl")
        hashBuilder.append(abilityName)
        hashBuilder.append(ContentHashComputer.mezzeTypeToString(formatType))
        hashBuilder.append(ContentHashComputer.mezzeTypeToString(dataType))
        val implKey = Hash(hashBuilder.build().toFullHexString())

        if (generatedCodecImpls.containsKey(implKey)) {
            return true
        }

        generatedCodecImpls[implKey] = GeneratedCodecImpl(
            abilityName = abilityName,
            targetTypes = listOf(formatType, dataType),
            contentHash = implKey
        )
        return true
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
                MezzeType.RecordType(fields = closedFields, rowVar = null, typeName = type.typeName, typeHash = type.typeHash)
            }
            is MezzeType.TypeApplication -> {
                if (type.args.none { needsRowClosing(it) }) {
                    return type
                }
                val closedArgs = type.args.map { closeOpenRecords(it) }
                WellKnownTypeApp.of(type.constructor, closedArgs, type.typeHash)
            }
            is MezzeType.VariantType -> {
                val needsClosing = type.rowVar != null || type.constructors.values.any { it != null && needsRowClosing(it) }
                if (!needsClosing) {
                    return type
                }
                val closedConstructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { closeOpenRecords(it) }
                }
                MezzeType.VariantType(closedConstructors, rowVar = null, typeName = type.typeName, typeHash = type.typeHash)
            }
            is MezzeType.ListType -> {
                if (!needsRowClosing(type.elementType)) return type
                MezzeType.ListType(closeOpenRecords(type.elementType))
            }
            is MezzeType.SeqType -> {
                if (!needsRowClosing(type.elementType)) return type
                MezzeType.SeqType(closeOpenRecords(type.elementType))
            }
            is MezzeType.SetType -> {
                if (!needsRowClosing(type.elementType)) return type
                MezzeType.SetType(closeOpenRecords(type.elementType))
            }
            is MezzeType.SortedSetType -> {
                if (!needsRowClosing(type.elementType)) return type
                MezzeType.SortedSetType(closeOpenRecords(type.elementType))
            }
            is MezzeType.MapType -> {
                if (!needsRowClosing(type.keyType) && !needsRowClosing(type.valueType)) return type
                MezzeType.MapType(
                    closeOpenRecords(type.keyType),
                    closeOpenRecords(type.valueType)
                )
            }
            is MezzeType.SortedMapType -> {
                if (!needsRowClosing(type.keyType) && !needsRowClosing(type.valueType)) return type
                MezzeType.SortedMapType(
                    closeOpenRecords(type.keyType),
                    closeOpenRecords(type.valueType)
                )
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
            is MezzeType.SuspendType -> {
                if (!needsRowClosing(type.valueType)) return type
                MezzeType.SuspendType(
                    valueType = closeOpenRecords(type.valueType),
                    effects = type.effects
                )
            }
            is MezzeType.VarType -> type
            is MezzeType.EffectRow -> type
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.CharType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType -> type
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

    private fun canDeriveAbility(abilityName: String, type: MezzeType, abilityHash: Hash? = null): Boolean {
        val resolvedHash = abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)
        var unwrappedType = unwrapSuspended(type)

        var isNewtype = unwrappedType is MezzeType.NewtypeType

        if (unwrappedType is MezzeType.TypeApplication) {
            val typeDecl = registry.lookupType(unwrappedType.constructor)
                ?: ModuleResolver.lookupTypeDeclaration(unwrappedType.constructor)
            if (typeDecl?.definition is ParsedNewtypeDef) {
                isNewtype = true
            }

            val expandedVariant = expandTypeApplicationToVariant(unwrappedType, registry)
            if (expandedVariant != null) {
                unwrappedType = expandedVariant
            } else {
                val expandedRecord = expandTypeApplicationToRecord(unwrappedType, registry)
                if (expandedRecord != null) {
                    unwrappedType = expandedRecord
                }
            }
        }

        if (isNewtype && !WellKnownHashConstants.isInspect(abilityHash)) {
            return false
        }

        return when {
            WellKnownHashConstants.isInspect(abilityHash) -> true

            WellKnownHashConstants.isEq(abilityHash) -> {
                unwrappedType is MezzeType.VarType || BuiltInAbilities.isBoolVariant(unwrappedType) ||
                (unwrappedType is MezzeType.RecordType && unwrappedType.fields.values.all { canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.VariantType && unwrappedType.constructors.values.all { it == null || canDeriveOrHasImpl(abilityName, it, abilityHash) }) ||
                (unwrappedType is MezzeType.ListType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash)) ||
                (unwrappedType is MezzeType.SeqType && canDeriveOrHasImpl(abilityName, unwrappedType.elementType, abilityHash))
            }

            WellKnownHashConstants.isOrd(abilityHash) -> {
                // Ord is NOT structurally derivable for composite types. Unlike Eq
                // (DirectEqualNode has an Objects.equals fallback that reaches
                // MezzeVariant/MezzeRecord), the runtime Direct{LessThan,GreaterThan,
                // Le,Ge}Node only specialize long/double/String/Frac and THROW on
                // anything else. Auto-deriving Ord for a record/variant/list/seq
                // therefore type-checks a program that crashes at runtime (`a < b`
                // on a variant). Require an explicit `impl Ord` for those (newtypes
                // already require it). A VarType stays derivable so a type var can
                // be resolved to something that HAS Ord.
                unwrappedType is MezzeType.VarType
            }

            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALAND ||
            resolvedHash == WellKnownHashConstants.ABILITY_LOGICALOR -> {
                unwrappedType is MezzeType.BoolType || BuiltInAbilities.isBoolVariant(unwrappedType) ||
                isBoolLikeVariant(unwrappedType)
            }

            else -> false
        }
    }

    private fun canDeriveOrHasImpl(abilityName: String, type: MezzeType, abilityHash: Hash? = null): Boolean {
        if (canDeriveAbility(abilityName, type, abilityHash)) return true
        if (abilityHash != null && checkAbilityImplementation(abilityName, listOf(type), abilityHash)) return true
        return checkAbilityImplementation(abilityName, listOf(type))
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
                "Cannot use '$operator' with $typeNames. To enable this, implement: impl $abilityName for $primaryType where { ... }${formatAbilityCandidates(abilityName)}"
            }
        }

        return when (abilityName) {
            "Decode", "DecodeRepr" -> {
                val dataType = typeArgs.getOrNull(1)
                if (dataType is MezzeType.VarType) {
                    """Cannot infer decode target type. Add a type annotation:
  let result: MyType = decode {val = data, fmt = fmt}"""
                } else {
                    "$typeNames does not implement '$abilityName'. Ensure the data type is serializable.${formatAbilityCandidates(abilityName)}"
                }
            }
            "Encode", "EncodeRepr" -> {
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
            else -> "$typeNames does not implement '$abilityName'. To enable this, implement: impl $abilityName for $primaryType where { ... }${formatAbilityCandidates(abilityName)}"
        }
    }

    private fun abilityImplExistsForType(abilityName: String, typeArgs: List<MezzeType>): Boolean {
        val primaryType = typeArgs.firstOrNull() ?: return false
        val primaryName = prettyPrintType(primaryType)
        val impls = context.getAbilityImpls(abilityName)
        return impls.any { info ->
            val targets = info.targetTypeAnnotations
            val rendered = when {
                targets != null && targets.isNotEmpty() -> targets.joinToString(", ") { renderAnnotation(it) }
                info.targetTypes.isNotEmpty() -> info.targetTypes.joinToString(", ")
                else -> return@any false
            }
            rendered == primaryName || rendered.split(' ').firstOrNull() == primaryName.split(' ').firstOrNull()
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
        val impls = context.getAbilityImpls(abilityName)
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
        return "\n  Available impls: " + shown.joinToString("; ") { "impl $abilityName for $it" } + suffix
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

    private fun isBoolLikeVariant(type: MezzeType): Boolean {
        if (type !is MezzeType.VariantType) return false
        val keys = type.constructors.keys
        return keys.all { it == "True" || it == "False" } &&
               keys.isNotEmpty() &&
               type.constructors.values.all { it == null }
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
        val resolvedHash = abilityHash ?: WellKnownHashConstants.resolveAbilityHash(abilityName)
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODEREPR && typeArgs.size == 1) {
            val dataType = typeArgs[0]

            if (dataType is MezzeType.VarType) {
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    WellKnownHashConstants.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODEREPR &&
                    impl.targetTypes.isNotEmpty()
                }

                if (matchingImpls.size == 1) {
                    val impl = matchingImpls.first()
                    val inferredType = impl.targetTypes[0]
                    return Substitution.single(dataType.variable, inferredType)
                }
            }
        }
        if (resolvedHash == WellKnownHashConstants.ABILITY_DECODE && typeArgs.size == 2) {
            val formatType = typeArgs[0]
            val dataType = typeArgs[1]

            if (formatType !is MezzeType.VarType && dataType is MezzeType.VarType) {
                val matchingImpls = generatedCodecImpls.values.filter { impl ->
                    WellKnownHashConstants.resolveAbilityHash(impl.abilityName) == WellKnownHashConstants.ABILITY_DECODE &&
                    impl.targetTypes.size >= 2 &&
                    typeMatchesMezzeType(formatType, impl.targetTypes[0])
                }

                if (matchingImpls.size == 1) {
                    val impl = matchingImpls.first()
                    val inferredType = impl.targetTypes[1]
                    return Substitution.single(dataType.variable, inferredType)
                }
            }
        }

        val allImpls = context.getAbilityImpls(abilityName)

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
            is ParsedNamedType -> PrimitiveTypes.fromName(annotation.name) ?: WellKnownTypeApp.of(annotation.name, emptyList())
            is ParsedTypeApp -> { val args = annotation.args.mapNotNull { convertAnnotationToType(it) }; if (args.size != annotation.args.size) null else WellKnownTypeApp.of(annotation.constructor, args) }
            is ParsedSeqType -> convertAnnotationToType(annotation.elementType)?.let { MezzeType.SeqType(it) }
            is ParsedRecordType -> { val fields = mutableMapOf<String, MezzeType>(); for (e in annotation.entries) { val ft = convertAnnotationToType(e.type) ?: return null; fields[e.name] = ft }; MezzeType.RecordType(fields) }
            is ParsedVariantType -> MezzeType.VariantType(annotation.constructors.associate { it.name to it.dataType?.let { dt -> convertAnnotationToType(dt) } })
            is ParsedFunctionType -> { val p = convertAnnotationToType(annotation.paramType) ?: return null; val r = convertAnnotationToType(annotation.returnType) ?: return null; MezzeType.FunctionType(p, r) }
            is ParsedQualifiedType -> null
        }
    }

    private fun typeMatchesMezzeType(expected: MezzeType, actual: MezzeType): Boolean {
        if (expected == actual) return true
        if (expected is MezzeType.TypeApplication && actual is MezzeType.TypeApplication) {
            return expected.constructor == actual.constructor
        }
        return false
    }
}
