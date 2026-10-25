package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.TypeScheme
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.ast.Keywords
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.ast.solving.closeGeneratedRowVars
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.solving.ConstraintSolver
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typedast.Hash


data class ProgramConstraintResult(
    val constraintResults: Map<String, ConstraintResult>,
    val perBindingMustBeOpenRowVars: Set<RowVar>,
    val perBindingSolveErrors: List<ConstraintSolver.ConstraintError> = emptyList(),
    val perBindingSubstitution: Substitution = Substitution.empty,
    val collectedTypeCheckErrors: List<TypeCheckError> = emptyList(),
    // asymmetry fix: ability preconditions inferred from impl-op bodies, keyed by
    // impl content hash. TypeChecker folds these into the frozen context's
    // AbilityImplInfo.operationConstraints before the final use-site solve.
    val inferredImplConstraints: Map<Hash, List<ParsedFnAbilityConstraint>> = emptyMap(),
    // A.1: the impl-op body solves ImplValidator used to discard. TypeChecker
    // merges their side tables and substitutions into the module's, so emit can
    // read an impl body's types instead of rebuilding them from nothing.
    val implOpSolutions: List<ImplOpSolution> = emptyList(),
) {
}

class ConstraintGenerator(
    val expressionGenerator: ExpressionConstraintGenerator,
    private val patternGenerator: PatternConstraintGenerator,
    private val registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
    private val performerToEffect: Map<String, String> = emptyMap(),
    initialPerformerAddedEffects: Map<String, Set<String>> = emptyMap(),
    private val abilityNames: Set<String> = emptySet(),
    private val effectNames: Set<String> = emptySet(),
    // Caller-owned codec-impl registry shared with the module-level solver, so
    // codec impls the per-binding solver auto-derives survive across solver
    // instances. See ConstraintSolver.sharedGeneratedCodecImpls / the
    // AbilityChecker field-level note. Null falls back to per-solver private
    // storage (safe for unit test callers that never emit).
    private val sharedGeneratedCodecImpls: MutableMap<com.mezze.language.typedast.Hash, com.mezze.language.typechecker.solving.AbilityChecker.GeneratedCodecImpl>? = null,
) {
    private val performerAddedEffects: MutableMap<String, Set<String>> = initialPerformerAddedEffects.toMutableMap()
    private val typeVarCache = mutableMapOf<String, TypeVar>()
    private val sharedRowVar = TypeAnnotationConverter.SharedRowVar()

    private val constraintConversionConfig = TypeAnnotationConverter.ConversionConfig(
        resolveTypeHash = { registry.typeDeclarations[it]?.let { d -> d.identity } },
        resolveAbilityHash = { registry.abilityDeclarations[it]?.contentHash },
        validateEffects = { effectRow ->
            TypeAnnotationConverter.validateUnknownEffects(
                effectRow, registry.effectDeclarations.keys
            )
        },
        resolveEffectHashes = { names ->
            names.mapNotNull { name ->
                registry.effectDeclarations[name]?.contentHash?.let { name to it }
            }.toMap()
        },
        honorOpenRowVars = true,
        inlineVariantNaming = true,
        convertEffectAnnotations = true,
        resolveTypeAlias = { name ->
            registry.lookupType(name)?.let { decl ->
                com.mezze.language.typechecker.context.ContextBuilder
                    .aliasedAnnotationForTypeDecl(decl)?.let { ann -> ann to decl.typeParams }
            }
        },
        associatedTypesForEffect = { name ->
            registry.effectDeclarations[name]?.associatedTypes ?: emptyList()
        },
    )

    private val implValidator = ImplValidator(
        expressionGenerator, registry, performerToEffect, performerAddedEffects,
        abilityNames, effectNames, ::convertTypeAnnotation
    )
    private val annotationValidator = AnnotationValidator(registry)

    fun generateProgram(
        module: ParsedModule,
        context: TypingContext
    ): ProgramConstraintResult {
        DebugLogger.debugSection(Category.ConstraintGenerator, "Abilities and Effects") {
            listOf(
                "abilityNames = $abilityNames",
                "effectNames = $effectNames"
            )
        }

        val results = mutableMapOf<String, ConstraintResult>()
        var currentContext = context

        val recordLiteralBindings = mutableSetOf<String>()

        val perBindingSolveErrors = mutableListOf<ConstraintSolver.ConstraintError>()
        val collectedTypeCheckErrors = mutableListOf<TypeCheckError>()

        var perBindingSubstitution = Substitution.empty

        val solver = ConstraintSolver(context, registry, sharedGeneratedCodecImpls = sharedGeneratedCodecImpls)

        val letBindings = module.decls.filterIsInstance<ParsedLetDecl>()
        val placeholderNames = mutableSetOf<String>()
        val annotationTypes = mutableMapOf<ParsedLetDecl, MezzeType>()
        for (binding in letBindings) {
            binding.typeAnnotation?.let { annotation ->
                if (annotation is ParsedFunctionType) {
                    for (constraint in annotation.abilityConstraints) {
                        val name = constraint.abilityName
                        val isKnown = registry.abilityDeclarations.containsKey(name) ||
                            name in BuiltInAbilities.getAllAbilityNames()
                        if (!isKnown) {
                            throw TypeCheckError(
                                "Ability '$name' referenced in 'with' constraint is not defined or not imported. " +
                                "Did you forget to import it with 'use'?",
                                constraint.loc,
                                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
                            )
                        }
                    }
                }
                val convertedType = convertTypeAnnotation(annotation)
                annotationTypes[binding] = convertedType
            }

            if (binding.isSimpleBinding && binding.value is ParsedLambda) {
                val placeholderType = annotationTypes[binding] ?: MezzeType.VarType(TypeVar.fresh())

                currentContext = currentContext.extendMono(binding.variableName, placeholderType)
                placeholderNames.add(binding.variableName)
            }
        }

        // Process decls so a callee is inferred before its caller whenever
        // that ordering is unambiguous (acyclic). Without this, a call to a
        // source-order-LATER top-level `let f = { ... }` sees only f's fresh
        // monomorphic placeholder var (registered above), so the call
        // constraint `FunctionType({args}) ~ 'placeholder` binds the var whole
        // and never decomposes args against params — a polymorphic return then
        // never pins to the concrete arg type and the caller's binding leaks
        // `'a` (#126). Inferring f first registers its real Polymorphic scheme,
        // so the call instantiates the true function type (the working
        // "declared-before-use" path). Cycles (mutual recursion) keep source
        // order — placeholder behaviour, no worse than before.
        // Read each `__native_*` intrinsic's Mezze type off the `pub let` that wraps
        // it, BEFORE anything is generated, so a reference to one is typed rather
        // than met with a fresh variable. See `nativeDeclaredTypes`.
        //
        // Only a wrapper whose body is EXACTLY a call to the native, passing its own
        // parameters straight through, is read — that is the shape the stdlib uses
        // for every one, and it is the only shape where the wrapper's declared type
        // IS the native's. Two wrappers that disagree drop the entry rather than
        // pick one: an ambiguous answer is worse than none, which is what the
        // caller had before.
        collectNativeDeclaredTypes(module.decls, annotationTypes, expressionGenerator.nativeDeclaredTypes)

        val orderedDecls = dependencyOrderedDecls(module.decls)
        collectedTypeCheckErrors.addAll(implValidator.validateEqHashPairing(orderedDecls))
        for (item in orderedDecls) {
            when (item) {
                is ParsedLetDecl -> try {
                    val preConvertedAnnotationType = annotationTypes[item]
                    val result = generateLetBinding(item, currentContext, recordLiteralBindings, preConvertedAnnotationType)

                    if (item.pattern is ParsedVariablePattern && item.value is ParsedRecordLit) {
                        recordLiteralBindings.add((item.pattern as ParsedVariablePattern).name)
                    }

                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = registry.effectDeclarations, abilityDeclarations = registry.abilityDeclarations, typeDeclarations = registry.typeDeclarations)
                    val patternResult = patternGenerator.generate(
                        item.pattern,
                        result.type,
                        constraintContext,
                        item.value.loc
                    )

                    val duplicateBindings = patternResult.bindings.keys
                        .filter { name -> currentContext.hasLocal(name) && name !in placeholderNames }
                        .map { name ->
                            ConstraintResult.DuplicateBinding(
                                name = name,
                                loc = patternResult.bindingLocations[name]
                            )
                        }

                    val resultWithDuplicates = result.copy(
                        duplicateBindings = duplicateBindings
                    )


                    results[getBindingName(item)] = resultWithDuplicates

                    // A RECURSIVE binding's self-reference IS this binding. Say so.
                    //
                    // An unannotated `let f = { … }` is pre-registered as a bare
                    // monomorphic placeholder so its own body can mention it. A
                    // self-call then binds that placeholder WHOLE —
                    // `'P ~ ({ xs: [Int] } -> 'c)` — while the lambda being inferred
                    // has its own result variable, and nothing relates the two. So
                    // the self-call's result never pins:
                    //
                    //     let length = { xs: [Int] } ->
                    //       match xs is
                    //         [] -> 0
                    //         [_, ...tail] -> 1 + length { xs = tail }
                    //
                    // inferred as `{ xs: [Int] } with NumAdd 'a Int having 'out = Int
                    // -> Int` — a constraint on a variable the signature does not
                    // mention, carried on a plain recursive function and visible in
                    // its hover. With this equation `'c` unifies with the result,
                    // `NumAdd Int Int` discharges, and the signature comes out clean.
                    //
                    // This is the ordinary treatment of a recursive binding: the
                    // self-reference is monomorphic and is the binding's own type.
                    // Only for a placeholder we MINTED — an annotated binding was
                    // pre-registered with its declared type, which already relates
                    // the two, and `dependencyOrderedDecls` handles the non-recursive
                    // forward reference by inferring the callee first (#126).
                    val selfReference = if (item.isSimpleBinding && item.value is ParsedLambda) {
                        val found = currentContext.lookup(item.variableName)
                            as? TypingContext.LookupResult.Found
                        val placeholder =
                            (found?.binding as? TypingContext.Binding.Monomorphic)?.type
                        // PURE bodies only. Unifying the self-reference unifies the
                        // whole arrow, effect row included, and a row is not a set to
                        // be equated through a recursive reference: `<*>` says "the
                        // body performs effects and handles them itself", so equating
                        // it with the row the self-call site produced reports
                        // "declared `<*>` … but this body performs none"
                        // (RecursiveArbitraryDerivationTest). The leak this equation
                        // closes is an ability constraint, which an effectful
                        // recursive function can still carry; that is the narrower
                        // follow-up, and it needs the row question answered first.
                        val valueRow = (result.type as? MezzeType.FunctionType)?.effects
                        val pureBody = valueRow == null ||
                            (!valueRow.performed && valueRow.allEffectNames.isEmpty())
                        if (placeholder is MezzeType.VarType && annotationTypes[item] == null && pureBody) {
                            listOf(
                                Constraint.Equality(
                                    left = placeholder,
                                    right = result.type,
                                    context = "`${item.variableName}` refers to itself, so that reference " +
                                        "has this binding's own type",
                                    loc = item.loc,
                                )
                            )
                        } else emptyList()
                    } else emptyList()

                    val allBindingConstraints = result.constraints + result.abilityConstraints + result.associatedTypeConstraints + patternResult.constraints + selfReference
                    val solveResult = solver.solve(allBindingConstraints)
                    when (solveResult) {
                        is ConstraintSolver.SolveResult.Failure -> {
                            perBindingSolveErrors.addAll(solveResult.errors)

                            results[getBindingName(item)] = resultWithDuplicates

                            val bindingHash: Hash? = null
                            currentContext = currentContext.extendAll(
                                patternResult.bindings.map { (name, type) ->
                                    BindingKey.unqualified(name) to TypingContext.Binding.Monomorphic(type, contentHash = bindingHash)
                                }.toMap()
                            )
                            continue
                        }
                        is ConstraintSolver.SolveResult.Success -> {
                            val substitution = solveResult.substitution
                            com.mezze.language.typechecker.debug.ConstraintDumper
                                .dumpPerBindingSolve(getBindingName(item), substitution)

                            // What this binding's own solve contributes to the
                            // shared `perBindingSubstitution`. Two disjoint pieces,
                            // each with its own reason:
                            //
                            // (1) The VARIANT (HasConstructor) resolution, re-solved
                            //     on its own. It pins a bare variant field to its
                            //     declared alias — `let order = { status = Queued }`
                            //     hovering as `Status` rather than `'a`
                            //     (HoverMatchAndAliasTest). NOT HasField: that governs
                            //     record OPENNESS, and merging its per-binding answer
                            //     reopens records that must print closed, e.g. a
                            //     `from_json` decode target
                            //     (RecordInferenceOpenClosedTest).
                            //
                            // (2) The bindings for the variables the DECLARATION
                            //     NAMES. `bindLet` emits
                            //     `Equality(bodyType, annotatedType)` and this solve
                            //     honours it, but the solve was then dropped, so the
                            //     annotation's variables and the body's stayed two
                            //     unrelated ROOTS in the substitution emit reads: a
                            //     node stamped from one carried a variable the scheme,
                            //     quantified from the other, does not bind. Measured on
                            //     `Option.flatten` — the solve held `'a#239 -> 'e#247`
                            //     and the module substitution did not.
                            //
                            // Propagating the WHOLE solve instead also closes
                            // `intersperse`'s rows and MISCOMPILES: a Bool field
                            // decodes as False where it must be True
                            // (JsonDecodeExhaustiveTest). Three narrowings were
                            // measured and none accommodates the codec — see the
                            // elaboration baseline header. A variable the declaration
                            // NAMES must mean the same thing everywhere; one internal
                            // to this solve need not.
                            val variantBindingConstraints = allBindingConstraints.filter {
                                it is Constraint.HasConstructor
                            }
                            if (variantBindingConstraints.isNotEmpty()) {
                                val variantSolve = solver.solve(variantBindingConstraints)
                                if (variantSolve is ConstraintSolver.SolveResult.Success) {
                                    perBindingSubstitution =
                                        variantSolve.substitution.compose(perBindingSubstitution)
                                }
                            }



                            val letResult = bindLet(
                                item = item,
                                result = result,
                                patternResult = patternResult,
                                preConvertedAnnotationType = preConvertedAnnotationType,
                                solveResult = solveResult,
                                substitution = substitution,
                                currentContext = currentContext,
                                outerContext = context,
                                solver = solver,
                            )
                            val solvedBindingsWithEffects = letResult.solvedBindingsWithEffects

                            // (2) EVERYTHING ELSE this solve learned, with the same
                            //     row-closing `bindLet` applies to the binding's own
                            //     type. Propagated AFTER `bindLet` so it uses the
                            //     identical `mustBeOpenRowVars`.
                            //
                            //     Closing is what makes it safe. The raw solve holds
                            //     the OPEN rows field access grew while inferring —
                            //     `{ conds: [{ temp: Str, ... }], ... }` — and the
                            //     binding closes them at finalization, so propagating
                            //     the raw solve published the inference artifact and
                            //     let it win: `from_json`'s target reopened and the
                            //     codec refused to derive for a `...` record.
                            //     Row-polymorphism the author wrote, or that
                            //     `mustBeOpenRowVars` proved, is untouched — that is
                            //     the carve-out `closeGeneratedRowVars` already owns.
                            //     One EXCLUSION, and it is structural, not a list of
                            //     names: a decode target. `from_json` returns
                            //     `Result 'a DecodeError` where `'a` is inferred from
                            //     the fields the program later reads, and that variable
                            //     must stay BARE where emit reads it — codegen resolves
                            //     each call's `.decode` dictionary per call site
                            //     (DecodeTargetResolver), and a call node carrying the
                            //     record instead of the variable wires the wrong
                            //     decoder. Identified by the constraint's ABILITY HASH,
                            //     so a user codec's own decode ability is covered and a
                            //     user ability that merely has a `decode`-shaped op is
                            //     not.
                            val decodeTargetVars = result.abilityConstraints
                                .filter {
                                    val h = it.abilityHash ?: registry.resolveAbilityHash(it.abilityName)
                                    WellKnownHashConstants.isDecodeFlavoredCodec(h) ||
                                        WellKnownHashConstants.isDecodeEntryAbility(h)
                                }
                                .flatMap { c ->
                                    c.typeArgs.flatMap {
                                        com.mezze.language.ast.solving.collectFreeTypeVars(it)
                                    } + c.typeVar
                                }
                                .toSet()

                            //     A METHOD-grown receiver is held back for a
                            //     sibling reason. A method call whose family has no
                            //     representative signature and whose receiver is
                            //     genuinely polymorphic is typed by growing the
                            //     receiver into `{ to_str: {} -> 'a }`; the runtime
                            //     dispatches on the value. That shape is the call's,
                            //     not a record the program has.
                            val closeOpen = solver.getMustBeOpenRowVars()
                            perBindingSubstitution = Substitution(
                                mappings = substitution.mappings
                                    .filterKeys {
                                        it !in decodeTargetVars && it !in solver.methodGrownReceiverVars
                                    }
                                    .mapValues { closeGeneratedRowVars(it.value, closeOpen) },
                                rowVarMappings = substitution.rowVarMappings,
                                recordRowMappings = substitution.recordRowMappings,
                            ).compose(perBindingSubstitution)

                            // An obligation whose arguments came out CONCRETE and
                            // unsatisfied is an error, not something to carry on the
                            // inferred signature — see `isFullyDischarged`, which
                            // finds them. Reported here because this is the binding
                            // that would have absorbed it.
                            perBindingSolveErrors.addAll(letResult.absorbedUnsatisfiable)

                            // An ability constraint on a type variable the SIGNATURE
                            // does not mention is one no caller can ever discharge.
                            //
                            //     ability Into2 'b for 'a where { conv2: {} -> 'b }
                            //     impl Into2 Str  for Int where { … }
                            //     impl Into2 Bool for Int where { … }
                            //
                            //     let f = {} -> do
                            //       let s = 5.conv2 {}
                            //       0
                            //
                            // infers `{} with Into2 'b Int -> Int`: the body picks an
                            // implementation by `'b`, and `'b` appears in neither the
                            // parameters nor the result, so the caller has nothing to
                            // choose with either. Mono then "specialized" such a
                            // function at the first use's type and a second use at
                            // another type ran the first one's impl.
                            //
                            // Asked HERE, on the solved signature, and deliberately
                            // not at emit. At emit the question cannot be answered
                            // for a decode ability at all: `decodeTargetVars` are
                            // withheld from the substitution on purpose, so
                            // `from_json`'s `'a` is BARE there for every correct
                            // program (see the note on this file's per-binding
                            // substitution, and UndeterminedCodecTargetTest). Here
                            // the solve has just finished and the variable is still
                            // related to the signature's own, so a discharged
                            // obligation is simply absent and only a genuinely
                            // undeterminable one is left.
                            for ((key, binding) in letResult.contextBindings) {
                                val scheme = (binding as? TypingContext.Binding.Polymorphic)?.scheme ?: continue
                                val fnType = scheme.type as? MezzeType.FunctionType ?: continue
                                if (fnType.abilityConstraints.isEmpty()) continue

                                // PARAMETERS, RESULT and EFFECT ROW — never the
                                // ability constraints themselves. Walking the whole
                                // FunctionType counts a constraint's own variable as
                                // "mentioned", which is exactly the occurrence that
                                // makes it phantom, and the check then passes
                                // everything. An effect row DOES count: a caller who
                                // handles `<Exception having 'error = 'e>` determines
                                // `'e`.
                                //
                                // By ID. Both sides come out of this one FunctionType,
                                // so the ids ARE the relation; comparing the written
                                // names instead would be the fallback that
                                // `AnnotationValidator.resolveToSameType` kept and
                                // that nothing needs (the whole suite passes with it
                                // disabled).
                                val namedBySignature = (
                                    com.mezze.language.ast.solving.collectFreeTypeVars(fnType.paramType) +
                                        com.mezze.language.ast.solving.collectFreeTypeVars(fnType.returnType) +
                                        fnType.effects?.effectInstances.orEmpty().flatMap { inst ->
                                            inst.associatedTypes.values.flatMap {
                                                com.mezze.language.ast.solving.collectFreeTypeVars(it)
                                            }
                                        }
                                    ).mapTo(mutableSetOf()) { it.id }

                                // An ASSOCIATED TYPE is a functional dependency:
                                // `NumAdd 'r for 'l having 'out` says `out` is a
                                // function of `l` and `r`. So determining a
                                // constraint's class arguments determines its
                                // associated types, and those count as determined in
                                // turn. Closed to a fixpoint, because the chain can be
                                // any length:
                                //
                                //     let complex = { x, y, z } -> do
                                //       let sum     = x + y     # NumAdd 'b 'a → 'd
                                //       let product = sum * z   # NumMul 'c 'd → 'e
                                //       "Result: $(int::to_str { n = product })"
                                //
                                // returns `Str`, so neither `'d` nor `'e` is in the
                                // signature — and both are determined the moment a
                                // caller passes concrete `x`, `y` and `z`. Without
                                // this closure the rule refuses that function, and
                                // nine others like it.
                                fun assocVarsOf(c: MezzeType.FunctionAbilityConstraint) =
                                    c.associatedTypeBindings.values.flatMap {
                                        com.mezze.language.ast.solving.collectFreeTypeVars(it)
                                    }.mapTo(mutableSetOf()) { it.id }
                                // The RECEIVER's position is not this rule's business,
                                // and the codebase already says why: an operation that
                                // takes the value can only ever run on a value that
                                // exists, so a receiver nothing determines is
                                // unreachable code rather than a missing dictionary
                                // (`dispatchesWithoutAValue`, and the note on
                                // `requirementReachesHiddenVar`). What chooses an
                                // implementation is the NON-receiver argument.
                                //
                                // That distinction is what separates the two shapes.
                                // A recursive `length` carries
                                // `with NumAdd 'c Int having 'out = Int` — a leak from
                                // its own forward reference, where `'c` is the
                                // self-call's result and sits in NumAdd's RECEIVER
                                // slot. `5.conv2 {}` carries `with Into2 'b Int`,
                                // where `'b` is the type converted INTO: the receiver
                                // is the `Int` in hand and `'b` is what picks the impl.
                                fun receiverIndexOf(c: MezzeType.FunctionAbilityConstraint): Int? {
                                    val decl = registry.lookupAbility(c.abilityName) ?: return 0
                                    if (c.typeArgs.size != decl.typeParams.size) return null
                                    val rp = decl.receiverTypeParam ?: return 0
                                    return decl.typeParams
                                        .indexOfFirst { it.removePrefix("'") == rp.removePrefix("'") }
                                        .takeIf { it >= 0 }
                                }
                                fun classArgVarsOf(c: MezzeType.FunctionAbilityConstraint): Set<Int> {
                                    val assoc = assocVarsOf(c)
                                    val recv = receiverIndexOf(c)
                                    // Through THIS binding's substitution. The stored
                                    // tuple keeps the variable a RECURSIVE self-call
                                    // was instantiated with — `length` records
                                    // `NumAdd Int 'c` while its own result type is
                                    // still being inferred — and the solve has since
                                    // bound it to `Int`. Resolving here asks "is this
                                    // still open after the solve", which is the
                                    // question; reading the stored tuple raw refuses
                                    // every recursive arithmetic function.
                                    return c.typeArgs
                                        .filterIndexed { i, _ -> i != recv }
                                        .flatMap {
                                            com.mezze.language.ast.solving
                                                .collectFreeTypeVars(substitution.apply(it))
                                        }
                                        .mapTo(mutableSetOf()) { it.id } - assoc
                                }
                                // Only a class argument the operation PRODUCES.
                                //
                                // This is the doctrine T0215 already states for a
                                // receiver-less op — "the type it produces is what
                                // chooses the implementation" — and it is what
                                // separates a real ambiguity from a type that is
                                // undetermined because nothing ever computes it:
                                //
                                //   conv2: {} -> 'b                  'b is PRODUCED
                                //   from_query: {} -> Result 'a _    'a is PRODUCED
                                //   add: { it: 'l, r: 'r } -> 'out   'r is CONSUMED
                                //
                                // A consumed argument left open means the value was
                                // never produced, and Mezze has no bottom type to say
                                // so. All three shapes that reach here are that:
                                //
                                //   let f = { y } -> x + y        # never called
                                //   sum + will_fail {}            # will_fail throws
                                //
                                // Legitimate programs, undetermined operand types,
                                // and nothing to choose at runtime because the
                                // operation never runs. Refusing them would be wrong;
                                // a produced type that nothing determines is the case
                                // where an implementation must be chosen and cannot
                                // be.
                                fun producesParam(abilityName: String, paramName: String): Boolean {
                                    val decl = registry.lookupAbility(abilityName) ?: return false
                                    return decl.operations.any { op ->
                                        fun mentions(t: com.mezze.language.parsedast.ParsedTypeAnnotation) =
                                            com.mezze.language.ast.solving
                                                .collectFreeTypeVars(convertTypeAnnotation(t))
                                                .any { it.name.removePrefix("'") == paramName }
                                        mentions(op.returnType) && !mentions(op.paramType)
                                    }
                                }
                                fun producedOpenSlots(c: MezzeType.FunctionAbilityConstraint): Boolean {
                                    val decl = registry.lookupAbility(c.abilityName) ?: return true
                                    if (c.typeArgs.size != decl.typeParams.size) return true
                                    val open = classArgVarsOf(c)
                                    return c.typeArgs.withIndex().any { (i, arg) ->
                                        com.mezze.language.ast.solving
                                            .collectFreeTypeVars(substitution.apply(arg))
                                            .any { it.id in open } &&
                                            producesParam(c.abilityName, decl.typeParams[i].removePrefix("'"))
                                    }
                                }

                                val determined = namedBySignature.toMutableSet()
                                var grew = true
                                while (grew) {
                                    grew = false
                                    for (c in fnType.abilityConstraints) {
                                        if (!determined.containsAll(classArgVarsOf(c))) continue
                                        if (determined.addAll(assocVarsOf(c))) grew = true
                                    }
                                }

                                for (c in fnType.abilityConstraints) {
                                    // `addAbilityConstraintsToFunctionType` stores what
                                    // SOLVING worked out (`resolveArgs`), so a tuple
                                    // with no variables left is already determined and
                                    // has nothing to ask about. That is what a
                                    // recursive `length` reduces to: it records
                                    // `NumAdd Int 'c` while its own result is still a
                                    // variable, `'c` resolves to `Int`, and the stored
                                    // constraint reads `NumAdd Int Int`. Ten functions
                                    // (`sum`, `fib`, `is_pythagorean`, …) are the
                                    // reason this is read from the resolved tuple and
                                    // not from the scheme's name-keyed `typeParam`,
                                    // which keeps the variable it started from.
                                    if (determined.containsAll(classArgVarsOf(c))) continue
                                    if (!producedOpenSlots(c)) continue
                                    throw TypeCheckError(
                                        "'${c.abilityName}' is needed at a type `${key.localName}` does not " +
                                            "mention: the body chooses an implementation by a type that appears " +
                                            "in neither its parameters nor its result, so no caller can choose " +
                                            "it either. Use the value so its type is fixed, or return it so the " +
                                            "caller fixes it.",
                                        item.loc,
                                        code = ErrorCode.T0215_AMBIGUOUS_ABILITY_TYPE,
                                        hints = listOf(
                                            "consume the value at a known type, or give the function a result " +
                                                "type that mentions it",
                                        ),
                                    )
                                }
                            }

                            currentContext = currentContext.extendAll(letResult.contextBindings)

                            for ((bindingName, solvedType) in solvedBindingsWithEffects) {
                                if (results.containsKey(bindingName)) {
                                    val existingResult = results[bindingName]!!
                                    results[bindingName] = existingResult.copy(type = solvedType)
                                }
                            }
                        }
                    }
                } catch (e: TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                    if (item.isSimpleBinding) {
                        currentContext = currentContext.extendMono(item.variableName, MezzeType.VarType(TypeVar.fresh()))
                    }
                }
                is ParsedTypeDecl,
                is ParsedAbilityDecl,
                is ParsedEffectDecl -> {
                }
                is ParsedPerformerDecl -> try {
                    implValidator.validateEffectImplementation(item, currentContext)
                } catch (e: TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                }
                is ParsedImplAbilityDecl -> try {
                    implValidator.validateAbilityImplementation(item, currentContext)
                    if (item.isInherent) {
                        currentContext = processInherentImpl(item, currentContext, results)
                    }
                } catch (e: TypeCheckError) {
                    collectedTypeCheckErrors.add(if (e.loc == null) TypeCheckError(e.message ?: "Type error", item.loc, e.errorType, e.metadata, code = e.code, hints = e.hints, fixHints = e.fixHints) else e)
                }
                is ParsedTopLevelExpr -> {
                    val constraintContext = ConstraintContext(currentContext, performerToEffect = performerToEffect, performerAddedEffects = performerAddedEffects, abilityNames = abilityNames, effectNames = effectNames, effectDeclarations = registry.effectDeclarations, abilityDeclarations = registry.abilityDeclarations, typeDeclarations = registry.typeDeclarations)
                    val result = expressionGenerator.generate(
                        item.expression,
                        constraintContext
                    )
                    results["_toplevel"] = result

                    val structuralConstraints = result.constraints.filter { constraint ->
                        constraint is Constraint.HasField || constraint is Constraint.HasConstructor
                    }
                    if (structuralConstraints.isNotEmpty()) {
                        val solveResult = solver.solve(structuralConstraints)
                        if (solveResult is ConstraintSolver.SolveResult.Success) {
                            perBindingSubstitution = solveResult.substitution.compose(perBindingSubstitution)
                        } else if (solveResult is ConstraintSolver.SolveResult.Failure) {
                            // This solve used to keep its Success and drop its Failure
                            // on the floor. So `match p is Nonsense { x }` as the
                            // module result type-checked — the solver had already
                            // produced "Cannot use constructor 'Nonsense' on
                            // non-variant type" and nobody read it — and the program
                            // then died at runtime with "no variant tag matched —
                            // typechecker exhaustiveness guarantee violated". The
                            // identical match `let`-bound was rejected properly: the
                            // same top-level-vs-bound asymmetry the ability re-solve
                            // just below was written to close.
                            //
                            // Only constructor resolution is surfaced, and by typed
                            // code. A constructor no type in scope declares can never
                            // become valid later, so deferring it to the global solver
                            // defers it to nobody. Equality and field failures stay
                            // deferred — the global solver owns their inference
                            // ordering, which is the rule the filter below states.
                            perBindingSolveErrors.addAll(
                                solveResult.errors.filter { it.code == ErrorCode.V0704_UNKNOWN_CONSTRUCTOR }
                            )
                        }
                    }

                    // A bare trailing expression must still discharge its ABILITY
                    // obligations. The global solve strips HasConstructor/HasField
                    // before its ability check (collectGlobalConstraints), so the var
                    // an `AbilityRequired` rides on is never pinned to the concrete
                    // argument type and the obligation is silently dropped — an
                    // ability-constrained call used as the module result (e.g.
                    // `sorted_set::singleton { x = Red }` where Color has no `Ord`)
                    // type-checked and then crashed at runtime, though the identical
                    // call was correctly rejected once `let`-bound. Re-solve the FULL
                    // constraint set here (so the arg equalities + HasConstructor pin
                    // the ability var to the concrete type, e.g. Red → Color) but
                    // report ONLY the ability failures — equality / method-resolution
                    // mismatches must stay deferred to the global solver, which owns
                    // their inference ordering, so we must not surface those here.
                    //
                    // FIELD resolution is surfaced here too, and for a blunter
                    // reason: the comment above says the global solver owns those,
                    // and it does not — `collectGlobalConstraints` strips HasField
                    // before the global solve, so "deferred" meant "dropped by
                    // both". Measured, `(1).zzz {}` as the module result type-checked
                    // and died at runtime with "Expected record for field 'zzz', got:
                    // Long", while `let v = (1).zzz {}` was correctly rejected with
                    // "Cannot access field 'zzz' on non-record type: Int". That is
                    // the third instance of this exact top-level-vs-bound asymmetry
                    // in this one block.
                    //
                    // Discriminated by the CONSTRAINT, not by a message or a name:
                    // these failures carry no distinguishing ErrorCode, but the
                    // error records the constraint that produced it, and a HasField
                    // failure is a HasField failure whatever it says.
                    val abilityObligations = result.abilityConstraints + result.associatedTypeConstraints
                    run {
                        // Unconditional now. It used to run only when there were
                        // ability obligations, and `(1).zzz {}` has none — so the one
                        // solve that sees the full constraint set, with receiver types
                        // pinned by the equalities, never ran for exactly the programs
                        // that needed it.
                        val solveResult = solver.solve(result.constraints + abilityObligations)
                        if (solveResult is ConstraintSolver.SolveResult.Failure) {
                            // Keep only genuine ability failures — both the directly
                            // solved obligation and the deferred validateAbilityConstraints
                            // re-check stamp ErrorCode.V0402_ABILITY_NOT_SATISFIED, so a
                            // single typed code discriminates them (no magic-string or
                            // constraint-shape matching). Equality / method-resolution
                            // failures are dropped here; the global solver owns those.
                            // ...and constructor resolution, for the same reason and
                            // by the same means. `match p is Nonsense { x }` fails
                            // here with "Cannot use constructor … on non-variant
                            // type", and dropping it let a top-level match on a name
                            // no type declares type-check and then die at runtime
                            // ("no variant tag matched — typechecker exhaustiveness
                            // guarantee violated"). The identical match `let`-bound
                            // was rejected — the same asymmetry this block already
                            // exists to close for abilities.
                            val keptCodes = setOf(
                                ErrorCode.V0402_ABILITY_NOT_SATISFIED,
                                ErrorCode.V0704_UNKNOWN_CONSTRUCTOR,
                                // The field is ABSENT — the receiver is a scalar, an
                                // opaque newtype, or a record without it. That can
                                // never become valid later, so deferring it defers it
                                // to nobody. A field TYPE mismatch is a different
                                // failure, carries no code, and stays deferred: the
                                // global solver widens a structural error union across
                                // a nested `Result` flatten, and surfacing that here
                                // rejects a program that is fine.
                                ErrorCode.T0205_FIELD_NOT_FOUND,
                            )
                            perBindingSolveErrors.addAll(
                                solveResult.errors.filter { it.code in keptCodes }
                            )
                        }
                    }
                }
                is ParsedUseDecl,
                is ParsedPubUseDecl -> {
                }
            }
        }

        return ProgramConstraintResult(
            constraintResults = results,
            perBindingMustBeOpenRowVars = solver.getMustBeOpenRowVars(),
            perBindingSolveErrors = perBindingSolveErrors,
            perBindingSubstitution = perBindingSubstitution,
            collectedTypeCheckErrors = collectedTypeCheckErrors,
            inferredImplConstraints = implValidator.drainInferredImplConstraints(),
            implOpSolutions = implValidator.drainImplOpSolutions(),
        )
    }

    /**
     * For each ability named in the annotation, the set of CONCRETE types its
     * impls declare for each associated type (assocName(bare) → set of types).
     * Used by R2 on a generic receiver: an annotated `having 'x = T` where no
     * impl of that ability declares `'x = T` is a mismatch. Empty when there is
     * no context (e.g. cache paths) — R2 then only fires for concrete receivers.
     */
    private fun implDeclaredHaving(
        context: TypingContext?,
        annotationConstraints: List<ParsedFnAbilityConstraint>,
    ): Map<String, Map<String, Set<MezzeType>>> {
        if (context == null) return emptyMap()
        return annotationConstraints.map { it.abilityName }.distinct().associateWith { abilityName ->
            val byName = mutableMapOf<String, MutableSet<MezzeType>>()
            for (impl in context.getAbilityImpls(abilityName)) {
                for ((rawName, ann) in impl.associatedTypeBindings) {
                    val t = runCatching { annotationValidator.publicAnnToMezze(ann) }.getOrNull() ?: continue
                    if (t is MezzeType.VarType) continue
                    byName.getOrPut(rawName.removePrefix("'")) { mutableSetOf() }.add(t)
                }
            }
            byName.mapValues { it.value.toSet() }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * VALIDATION-ONLY re-harvest. This is NOT a second effect-row-membership
     * setter: its result feeds ONLY `validateEffectsNotOverpromised` (the #274-B
     * under-promise check) at the single call site below and is never written
     * back to the emitted type. Effect-row MEMBERSHIP is owned by the gen-time
     * harvest (`collectClosureArgEffects` in ExprFunctionConstraints) plus
     * `unifyEffectRows`; this only recovers the poly-intermediary effect that is
     * knowable exclusively post-solve so the over-promise check does not miss it.
     *
     * BUG C (#331): re-run the callback-effect harvest on the SOLVED argument
     * types of this decl's `<...>` HOF calls and fold any effect names the
     * gen-time pass missed into [inferredType]'s top-level effect row. The
     * gen-time harvest skips an arg field that is still a VarType (a callback
     * routed through a polymorphic intermediary); post-solve that field resolves
     * to its concrete FunctionType, so re-harvesting recovers its effects. Only
     * adds effects — never removes — so it can tighten `validateEffectsNot-
     * Overpromised`'s under-promise check without loosening anything. Returns
     * [inferredType] unchanged when there is nothing to add or it is not a
     * function type.
     */
    private fun enrichWithReHarvestedClosureEffects(
        inferredType: MezzeType,
        pending: List<ConstraintResult.PendingClosureHarvest>,
        substitution: Substitution,
    ): MezzeType {
        if (pending.isEmpty() || inferredType !is MezzeType.FunctionType) return inferredType
        val extra = mutableSetOf<String>()
        for (harvest in pending) {
            val solvedArg = substitution.apply(harvest.argType)
            if (solvedArg !is MezzeType.RecordType) continue
            for ((_, fieldType) in solvedArg.fields) {
                val fnType = substitution.apply(fieldType) as? MezzeType.FunctionType ?: continue
                val effRow = fnType.effects ?: continue
                extra.addAll(effRow.effects - harvest.caughtByOp)
                for (instance in effRow.effectInstances) {
                    if (instance.name !in harvest.caughtByOp) extra.add(instance.name)
                }
            }
        }
        if (extra.isEmpty()) return inferredType
        val existing = inferredType.effects
        val merged = (existing?.effects ?: emptySet()) + extra
        if (existing != null && merged == existing.effects) return inferredType
        val newRow = existing?.copy(effects = merged)
            ?: MezzeType.EffectRow(effects = merged)
        return inferredType.copy(effects = newRow)
    }

    private fun validateLetAnnotation(
        item: ParsedLetDecl,
        preConvertedAnnotationType: MezzeType,
        result: ConstraintResult,
        substitution: Substitution,
        resolvedAssociatedBindings: Map<Pair<String, Int>, Map<String, MezzeType>> = emptyMap(),
        context: TypingContext? = null,
    ) {
        val annotation = item.typeAnnotation
        val varName = (item.pattern as ParsedVariablePattern).name

        val inferredTypeRaw = substitution.apply(result.type)
        val inferredTypeWithAbilities = addAbilityConstraintsToFunctionType(
            inferredTypeRaw, result.abilityConstraints, substitution, resolvedAssociatedBindings
        )

        if (annotation is ParsedFunctionType) {
            val abilityConstrainedVars = result.abilityConstraints.map { it.typeVar }.toSet()
            // Associated-type vars bound via `having 'out = T` in the annotation are
            // EXPECTED to be forced to a concrete type by the impl — that's what the
            // binding means. Exempt them from the "annotation over-generalizes" check;
            // their correctness is enforced by unification (unifyFunctions unifies
            // ability associated-type bindings, mirroring the effect-row path).
            val havingVarNames = (preConvertedAnnotationType as? MezzeType.FunctionType)
                ?.abilityConstraints.orEmpty()
                .flatMap { it.associatedTypeBindings.keys }
                .map { it.removePrefix("'") }
                .toSet()
            val havingVars = com.mezze.language.ast.collectTypeVarsInOrder(preConvertedAnnotationType)
                .filter { it.name.removePrefix("'") in havingVarNames }
                .toSet()
            // B1 containment: collect the ability associated-result (`having 'out`)
            // vars this binding's inferred obligations left UNRESOLVED (still a var
            // under the final substitution). If an annotation var resolves to one of
            // these, the annotation is satisfied by an impl-computed associated type,
            // not the var itself — over-claim; reject (see validatePolymorphismPreserved).
            val unresolvedOutVarIds = result.associatedTypeConstraints
                .flatMap { it.associatedTypeVars.values }
                .mapNotNull { (substitution.apply(MezzeType.VarType(it)) as? MezzeType.VarType)?.variable?.id }
                .toSet()
            // …but an annotation var that IS an associated type BY NAME (e.g.
            // `getFirst: {c:'c} with Container 'c -> Option 'item`, where `'item` is
            // Container's associated type and the `with`-clause omits `having`) is a
            // legitimate reference to that associated type, not an over-claim. Exempt
            // annotation vars whose name matches a declared associated-type name.
            val associatedTypeNameVars = com.mezze.language.ast.collectTypeVarsInOrder(preConvertedAnnotationType)
                .filter { av ->
                    val n = av.name.removePrefix("'")
                    result.associatedTypeConstraints.any { atc ->
                        atc.associatedTypeVars.keys.any { it.removePrefix("'") == n }
                    }
                }
                .toSet()
            annotationValidator.validatePolymorphismPreserved(
                preConvertedAnnotationType, substitution, varName,
                item.value.loc, abilityConstrainedVars + havingVars + associatedTypeNameVars,
                unresolvedOutVarIds
            )
        } else {
            // A non-function annotation that is (or contains only) type VARIABLES is
            // a promise of full polymorphism — `let x : 'a = 5` claims x works at
            // EVERY type (∀a. a). The body `5` is only Int, so the annotation
            // over-generalizes and must be rejected (matching Haskell's `x :: a;
            // x = 5` → "No instance for Num a"). The ParsedFunctionType branch above
            // already enforces this INSIDE function types via
            // validatePolymorphismPreserved; run the same check for the top-level
            // scalar case, which previously slipped through (alphaEquivalent's
            // `VarType -> true` accepted it and silently narrowed x to Int). A bare
            // scalar annotation has no ability/having/associated-type exemptions.
            val annotationVars = com.mezze.language.ast.collectTypeVarsInOrder(preConvertedAnnotationType)
            if (annotationVars.isNotEmpty()) {
                annotationValidator.validatePolymorphismPreserved(
                    preConvertedAnnotationType, substitution, varName,
                    item.value.loc, emptySet(), emptySet()
                )
            }
        }

        // #274-B: run even when annotation.effects is null (annotation
        // implicitly promises pure). Missing rows = "pure", still a
        // constraint the body must satisfy.
        if (annotation is ParsedFunctionType && !bodyContainsNativeCall(item.value)) {
            // BUG C (#331): fold in effects the gen-time callback harvest missed
            // because a `<...>` HOF's callback arg was a VarType then (e.g. routed
            // through a polymorphic intermediary). Re-harvest the SOLVED arg types.
            val effectsForOverpromiseCheck = enrichWithReHarvestedClosureEffects(
                inferredTypeWithAbilities, result.pendingClosureArgEffects, substitution
            )
            annotationValidator.validateEffectsNotOverpromised(
                preConvertedAnnotationType, effectsForOverpromiseCheck,
                varName, item.value.loc
            )
        }

        if (annotation is ParsedFunctionType && annotation.abilityConstraints.isNotEmpty()) {
            val resolvedAbilityConstraints = result.abilityConstraints.map { constraint ->
                val resolvedTypeVar = substitution.apply(MezzeType.VarType(constraint.typeVar))
                if (resolvedTypeVar is MezzeType.VarType) {
                    constraint.copy(typeVar = resolvedTypeVar.variable)
                } else {
                    constraint
                }
            }
            annotationValidator.validateAbilityOnCorrectTypeVar(
                preConvertedAnnotationType, resolvedAbilityConstraints,
                varName, substitution, item.value.loc
            )

            // Validate the annotation's `having 'x = T` bindings: reject an
            // undeclared associated-type name (R5), and a wrong concrete value
            // vs the impls' declared bindings (R2) — both hold even on a generic
            // fn. On a generic receiver the resolved var isn't concrete, so R2
            // compares against the concrete `having` values the ability's impls
            // declare, keyed assocName → set of declared types across impls.
            annotationValidator.validateHavingAnnotations(
                preConvertedAnnotationType,
                annotation.abilityConstraints,
                result.abilityConstraints,
                resolvedAssociatedBindings,
                implDeclaredHaving(context, annotation.abilityConstraints),
                varName, item.value.loc
            )
        }
    }

    /**
     * Outcome of turning a solved `let` binding into its final context binding(s).
     *
     * [contextBindings] extend the typing context; [solvedBindingsWithEffects] is the
     * per-name solved type carried forward to update the stored [ConstraintResult]s.
     */
    data class LetBindingResult(
        val contextBindings: Map<BindingKey, TypingContext.Binding>,
        val solvedBindingsWithEffects: Map<String, MezzeType>,
        /**
         * Obligations this binding's inferred signature would have ABSORBED whose
         * arguments are concrete and unsatisfied. Empty for an annotated binding
         * (there the obligation is discharged against the written type, and the
         * solver already reports it). Returned rather than reported so bindLet
         * stays pure w.r.t. generator state; the caller owns error collection.
         */
        val absorbedUnsatisfiable: List<ConstraintSolver.ConstraintError> = emptyList(),
    )

    /**
     * bindLet — the single primitive that turns a solved `let` binding into its final
     * context binding(s). Extracted from the top-level `ParsedLetDecl` path so the
     * do-block let path (S1.3) can route through the SAME code. Pure w.r.t. generator
     * state: everything it needs is passed in and it returns a value (no field mutation).
     *
     * It performs, in order:
     *  (a) authoritative-type selection — annotation wins over the structural inferred
     *      type (aliases preserved via WellKnownTypeApp with the declared hash);
     *  (b) let-annotation validation (side-effecting: may throw a TypeCheckError);
     *  (c) solved-binding selection (annotated var vs pattern bindings);
     *  (d) ability-constraint carry into the solved function type;
     *  (e) effect harvest onto the enclosing arrow;
     *  (f) generalization / value-restriction + ability-constraint carry onto the scheme
     *      (delegated to [buildBindingForContext] per binding).
     *
     * @param currentContext the accumulated context whose free vars gate generalization.
     * @param outerContext   the original module context used for annotation validation
     *                       (mirrors the historical top-level call, which passed the
     *                       pre-loop `context`, not `currentContext`).
     */
    internal fun bindLet(
        item: ParsedLetDecl,
        result: ConstraintResult,
        patternResult: PatternConstraintResult,
        preConvertedAnnotationType: MezzeType?,
        solveResult: ConstraintSolver.SolveResult.Success,
        substitution: Substitution,
        currentContext: TypingContext,
        outerContext: TypingContext,
        solver: ConstraintSolver,
        suppressGeneralization: Boolean = false,
        harvestEffectsIntoBinding: Boolean = true,
    ): LetBindingResult {
        // (a) authoritative type: annotated alias/type wins over the structural type.
        val authoritativeType = if (item.pattern is ParsedVariablePattern && preConvertedAnnotationType != null) {
            if (preConvertedAnnotationType is MezzeType.TypeApplication) {
                val isUserDefinedAlias = registry.typeDeclarations.containsKey(preConvertedAnnotationType.constructor)

                if (isUserDefinedAlias) {
                    WellKnownTypeApp.of(
                        constructor = preConvertedAnnotationType.constructor,
                        args = preConvertedAnnotationType.args.map { substitution.apply(it) },
                        typeHash = (preConvertedAnnotationType.identity as? TypeIdentity.Declared)?.hash
                    )
                } else {
                    substitution.apply(preConvertedAnnotationType)
                }
            } else {
                substitution.apply(preConvertedAnnotationType)
            }
        } else {
            substitution.apply(result.type)
        }

        // (b) validate the annotation against the inferred type (may throw).
        if (item.pattern is ParsedVariablePattern && preConvertedAnnotationType != null) {
            validateLetAnnotation(item, preConvertedAnnotationType, result, substitution, solveResult.resolvedAssociatedBindings, outerContext)
        }

        // (c) select the solved bindings: the annotated var (authoritative) or the
        // substitution-applied pattern bindings.
        val rawSolvedBindings = if (item.pattern is ParsedVariablePattern && authoritativeType != substitution.apply(result.type)) {
            mapOf(item.variableName to authoritativeType)
        } else {
            patternResult.bindings.mapValues { (_, type) ->
                substitution.apply(type)
            }
        }

        // (c') #403: CLOSE field-access-inferred record/variant rows in this
        // binding's finalized type BEFORE it enters the typing context that later
        // top-level bindings consult. Accessing `.f` on an un-annotated param opens
        // a fresh generated RowVar tail on the receiver (solveHasField grows the row
        // as more fields are discovered). That open tail is an INFERENCE artifact,
        // not a promise of row-polymorphism: the language rule is that a record
        // param REJECTS extra fields unless the author explicitly wrote `{ f, ... }`.
        // The emit phase already runs this exact close (closeGeneratedRowVars) so
        // the PRINTED signature reads closed — but emit runs AFTER the whole-module
        // solve, so a later binding's CALL SITE (e.g. `describe { order = {..,qty} }`)
        // was still unifying against the open row and wrongly absorbing the extra
        // `qty`. Closing here, one phase earlier, makes call-site checking see the
        // same closed row the signature advertises.
        //
        // The carve-out lives entirely inside closeGeneratedRowVars: a tail stays
        // OPEN when it is user-specified (`userOpen_`, the explicit `{ f, ... }`
        // escape hatch) OR listed in mustBeOpenRowVars (a row unified with a
        // genuinely open/forwarded param — e.g. `g = { r } -> openf { rec = r }`
        // where openf's param is `{ x, ... }`). Only a purely field-access-derived
        // generated tail is dropped, so genuine row-polymorphism is preserved.
        val closeOpenRowVars = solver.getMustBeOpenRowVars()
        val solvedBindings = rawSolvedBindings.mapValues { (_, type) ->
            closeGeneratedRowVars(type, closeOpenRowVars)
        }

        // (d) carry ability constraints into function-typed bindings.
        val absorbedUnsatisfiable = mutableListOf<ConstraintSolver.ConstraintError>()
        val solvedBindingsWithAbilities = solvedBindings.mapValues { (name, solvedType) ->
            val withAbilities = addAbilityConstraintsToFunctionType(
                solvedType,
                result.abilityConstraints,
                substitution,
                solveResult.resolvedAssociatedBindings,
                solver::abilityConstraintDischarge,
                dropDischargedConstraints = item.typeAnnotation == null,
                onAbsorbedUnsatisfiable = { origin, resolved ->
                    absorbedUnsatisfiable.add(
                        ConstraintSolver.ConstraintError(
                            message = solver.describeMissingAbility(origin.abilityName, resolved),
                            constraint = origin.copy(typeArgs = resolved),
                            context = origin.context,
                            code = ErrorCode.V0402_ABILITY_NOT_SATISFIED,
                        ),
                    )
                },
            )
            // S9b (#375): fold the poly-intermediary effect that the gen-time
            // harvest missed into the EMITTED row for an UN-ANNOTATED binding, so
            // the inferred type is honest (`go: {} -> {} <Log>`, not bare) when the
            // callback reaches an effectful `<...>` HOF through a polymorphic
            // intermediary. Same post-solve re-harvest the over-promise validator
            // uses; only ADDS effect NAMES to the top-level arrow row (never type
            // vars), so generalization and downstream unification are unaffected,
            // and adding names only tightens the #362 conservation check. Skipped
            // when annotated (the annotation already supplies the authoritative
            // row, and the validator compares annotation-vs-inferred separately).
            if (item.typeAnnotation == null) {
                enrichWithReHarvestedClosureEffects(withAbilities, result.pendingClosureArgEffects, substitution)
            } else withAbilities
        }

        // (e) Effect-model migration (S4, docs/effect-model.md): value types are bare —
        // effects ride the arrow, never the binding value. A top-level effectful
        // NON-function binding is a stranded effect and is rejected by
        // TypeValidator.validateParsedTopLevelEffects (which reads the incurred effects
        // off the ConstraintResult, not off a value-carried row); a function binding
        // carries its effects latently on its arrow. (`harvestEffectsIntoBinding` is now
        // vestigial — kept until the bindLet signature is cleaned up.)
        val solvedBindingsWithEffects = solvedBindingsWithAbilities

        // (f) generalization / value-restriction + ability-constraint carry per binding.
        val contextBindings = solvedBindingsWithEffects.map { (name, solvedType) ->
            val bindingKey = BindingKey.unqualified(name)
            bindingKey to buildBindingForContext(
                solvedType, bindingKey, currentContext, item, result,
                substitution, authoritativeType, suppressGeneralization
            )
        }.toMap()

        return LetBindingResult(contextBindings, solvedBindingsWithEffects, absorbedUnsatisfiable)
    }

    private fun buildBindingForContext(
        solvedType: MezzeType,
        bindingKey: BindingKey,
        currentContext: TypingContext,
        item: ParsedLetDecl,
        result: ConstraintResult,
        substitution: Substitution,
        authoritativeType: MezzeType,
        suppressGeneralization: Boolean = false,
    ): TypingContext.Binding {
        val isLambdaBinding = item.value is ParsedLambda

        // #257 value restriction (relaxed): a binding generalizes iff it is a
        // FUNCTION VALUE. A function — whether written as a lambda or PRODUCED by a
        // partial application (`add { x = 5 }` : `{ y } -> …`) — is safe to
        // generalize: it holds no mutable cell observable across instantiations
        // (each call re-enters fresh, closures capture args by value). Anything
        // whose type is NOT a function (`[]` : Seq 'a, `tvar {…}` : TVar 'a,
        // `None`, a literal, a plain variable) is monomorphic — pinned at the first
        // use, so a later use at a different type is an error (`let x = []; y:[Int]
        // = x; z:[Str] = x` rejects the 2nd view). Keying on the SOLVED TYPE being a
        // function (not on syntax or type names) is the uniform rule: it keeps
        // curried partial applications polymorphic AND closes the mutable-cell
        // soundness hole, since a mutable cell's type is never a function.
        // S1.3: a do-block lambda that CAPTURES an outer term variable is left
        // monomorphic — generalizing it (even over its own free params) interacts
        // badly with monomorphization/lowering of the captured value, and can leak
        // an outer monomorphic type var across two instantiations (see
        // PolyAuditProbeTest "a do-block let whose body forces it equal to an outer
        // mono param is not generalized"). The caller (do-block path) sets this; the
        // top-level path never captures, so it always passes false.
        val generalizes = !suppressGeneralization && (isLambdaBinding || solvedType is MezzeType.FunctionType)
        val quantifiableVars =
            if (generalizes) collectFreeTypeVars(solvedType) - getContextFreeVars(currentContext, excludeBindings = setOf(bindingKey))
            else emptySet()

        val hasExplicitAbilityConstraints = when (val annotation = item.typeAnnotation) {
            is ParsedFunctionType -> annotation.abilityConstraints.isNotEmpty()
            else -> false
        }

        val hasUserDefinedAbilityConstraints = if (hasExplicitAbilityConstraints || isLambdaBinding) {
            false
        } else {
            result.abilityConstraints.any { constraint ->
                val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(constraint.abilityName)
                if (isBuiltInAbility) {
                    false
                } else {
                    val resolvedType = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    val resolvedTypeVar = if (resolvedType is MezzeType.VarType) resolvedType.variable else null
                    val mainVarIsQuantifiable = resolvedTypeVar != null && resolvedTypeVar in quantifiableVars

                    val typeArgsHaveQuantifiableVars = constraint.typeArgs.any { typeArg ->
                        collectFreeTypeVars(typeArg).any { freeVar ->
                            val resolved = substitution.apply(MezzeType.VarType(freeVar))
                            resolved is MezzeType.VarType && resolved.variable in quantifiableVars
                        }
                    }

                    mainVarIsQuantifiable || typeArgsHaveQuantifiableVars
                }
            }
        }

        if (quantifiableVars.isNotEmpty() && !hasUserDefinedAbilityConstraints) {
            // The solved function type still carries the `having 'out = 'c` link on
            // its FunctionAbilityConstraints (stored at the lambda arrow). Pull those
            // bindings across to the scheme's AbilityConstraint so a cross-decl call
            // can resolve the associated type — TypeScheme.clearAbilityConstraints
            // strips them from the stored type, so this parallel list is the only
            // survivor. Match by ability name + primary (receiver) var.
            val solvedAbilityConstraints =
                (solvedType as? MezzeType.FunctionType)?.abilityConstraints ?: emptyList()
            fun bindingsFor(abilityName: String, resolvedPrimary: MezzeType): Map<String, MezzeType> {
                // Match the solved FunctionAbilityConstraint for this obligation and
                // read back its (freshened) `having 'out = …` bindings. We match on
                // the resolved PRIMARY type, not on it being a var: for a class-param
                // ability like `NumAdd 'r for 'l` the primary is the co-operand `'r`,
                // which is frequently CONCRETE (`Int` from a literal `0`). Keying on
                // "primary must be a var" (the old behaviour) dropped `out` for exactly
                // `{x} -> x + 0`, freezing the generalized result and re-opening B1.
                val primaryVarId = (resolvedPrimary as? MezzeType.VarType)?.variable?.id
                return solvedAbilityConstraints.firstOrNull { fac ->
                    if (fac.abilityName != abilityName) return@firstOrNull false
                    val facPrimary = fac.typeArgs.firstOrNull()?.let { substitution.apply(it) }
                        ?: return@firstOrNull false
                    if (primaryVarId != null) {
                        (facPrimary as? MezzeType.VarType)?.variable?.id == primaryVarId
                    } else {
                        facPrimary == resolvedPrimary
                    }
                }?.associatedTypeBindings?.mapValues { (_, v) -> substitution.apply(v) } ?: emptyMap()
            }

            val abilityConstraints = if (hasExplicitAbilityConstraints && authoritativeType is MezzeType.FunctionType) {
                authoritativeType.abilityConstraints.mapNotNull { constraint ->
                    val typeVarArg = constraint.typeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()
                    if (typeVarArg != null) {
                        AbilityConstraint(
                            abilityName = constraint.abilityName,
                            abilityHash = constraint.abilityHash,
                            typeParam = typeVarArg.variable.name,
                            typeVar = typeVarArg.variable,
                            typeArgs = constraint.typeArgs,
                            associatedTypeBindings = constraint.associatedTypeBindings
                                .mapValues { (_, v) -> substitution.apply(v) }
                        )
                    } else null
                }
            } else {
                val quantifiableVarIds = quantifiableVars.map { it.id }.toSet()
                result.abilityConstraints.mapNotNull { constraint ->
                    val resolvedConstraintVar = substitution.apply(MezzeType.VarType(constraint.typeVar))
                    val constraintVarId = if (resolvedConstraintVar is MezzeType.VarType) {
                        resolvedConstraintVar.variable.id
                    } else {
                        constraint.typeVar.id
                    }
                    val resolvedTypeArgs = constraint.typeArgs.map { substitution.apply(it) }
                    // Keep the constraint if the receiver OR ANY class-arg is
                    // quantifiable. A class-param obligation like `NumAdd Int 'a`
                    // (from `{x}->x+1`, where the literal `1` fixes the class-arg to
                    // Int and the receiver `'a` is the generalized param) must be
                    // carried even though the receiver alone might resolve to a
                    // non-quantifiable var — otherwise calling the generalized fn
                    // with a bad receiver type (`f {x = "s"}`) has NO obligation to
                    // reject `NumAdd Int Str`, silently laundering the type error.
                    val classArgQuantifiable = resolvedTypeArgs.any { arg ->
                        collectFreeTypeVars(arg).any { it.id in quantifiableVarIds }
                    }
                    if (constraintVarId in quantifiableVarIds || classArgQuantifiable) {
                        val quantVar = quantifiableVars.find { it.id == constraintVarId }
                        // If the receiver resolved to a CONCRETE type (not a var),
                        // capture it — `typeParam` (a name) cannot, and losing it
                        // would drop the obligation at the call site.
                        val concreteReceiver = (resolvedConstraintVar as? MezzeType.VarType)?.let { null }
                            ?: resolvedConstraintVar
                        AbilityConstraint(
                            abilityName = constraint.abilityName,
                            abilityHash = constraint.abilityHash,
                            typeParam = quantVar?.name ?: constraint.typeVar.name,
                            typeVar = quantVar ?: constraint.typeVar,
                            typeArgs = resolvedTypeArgs,
                            associatedTypeBindings = bindingsFor(
                                constraint.abilityName,
                                resolvedConstraintVar
                            ),
                            concreteReceiver = concreteReceiver
                        )
                    } else null
                }
            }

            val scheme = TypeScheme(
                quantifiedVars = quantifiableVars.toList(),
                constraints = abilityConstraints,
                type = solvedType
            )
            return TypingContext.Binding.Polymorphic(scheme, contentHash = null)
        } else {
            return TypingContext.Binding.Monomorphic(solvedType, contentHash = null)
        }
    }

    private fun processInherentImpl(
        item: ParsedImplAbilityDecl,
        context: TypingContext,
        results: MutableMap<String, ConstraintResult>,
    ): TypingContext {
        var currentContext = context
        for (op in item.operations) {
            val targetType = if (item.targetTypes.isNotEmpty()) {
                convertTypeAnnotation(item.targetTypes.first())
            } else {
                MezzeType.VarType(TypeVar.fresh())
            }

            if (op.implementation !is ParsedLambda) continue
            val lambda = op.implementation as ParsedLambda

            // This impl's OWN method — by the impl's identity, never by its target,
            // which two impls of one constructor share.
            val methodInfo = item.contentHash?.let { currentContext.findMethodOfImpl(it, op.name) }

            val paramNames = when (val params = lambda.params) {
                is ParsedLambdaParams.Untyped -> params.names
                is ParsedLambdaParams.Typed -> params.fields.map { it.name }
            }

            val paramTypes = if (op.paramType != null) {
                val expectedParam = convertTypeAnnotation(op.paramType!!)
                if (expectedParam is MezzeType.RecordType) {
                    paramNames.associateWith { name ->
                        expectedParam.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                    }
                } else {
                    paramNames.associateWith { expectedParam }
                }
            } else {
                when (val params = lambda.params) {
                    // One scope for the whole parameter list — see
                    // TypeAnnotationConverter.convertSignature.
                    is ParsedLambdaParams.Typed ->
                        convertSignatureAnnotations(params.fields.map { it.name to it.type }).toMap()
                    else -> paramNames.associateWith { name ->
                        if (name == Keywords.IT) targetType
                        else MezzeType.VarType(TypeVar.fresh())
                    }
                }
            }

            var bodyContext = currentContext
            for ((name, type) in paramTypes) {
                bodyContext = bodyContext.extendMono(name, type)
            }

            val constraintContext = ConstraintContext(
                bodyContext,
                expectedType = null,
                performerToEffect = performerToEffect,
                performerAddedEffects = performerAddedEffects,
                abilityNames = abilityNames,
                effectNames = effectNames,
                effectDeclarations = registry.effectDeclarations,
                abilityDeclarations = registry.abilityDeclarations,
                typeDeclarations = registry.typeDeclarations
            )

            val bodyResult = expressionGenerator.generate(lambda.body, constraintContext)

            val constraints = bodyResult.constraints.toMutableList()

            val registeredMethodType = methodInfo?.methodType
            if (registeredMethodType is MezzeType.FunctionType) {
                constraints.add(Constraint.Equality(
                    left = registeredMethodType.returnType,
                    right = bodyResult.type,
                    context = "Inherent impl return type for ${op.name}",
                    loc = op.loc,
                ))
                val paramRecordType = MezzeType.RecordType(fields = paramTypes, rowVar = null)
                constraints.add(Constraint.Equality(
                    left = registeredMethodType.paramType,
                    right = paramRecordType,
                    context = "Inherent impl param type for ${op.name}",
                    loc = op.loc,
                ))
            }

            val opKey = "_inherent_${item.loc}_${op.name}"
            val implParamRecord = MezzeType.RecordType(fields = paramTypes, rowVar = null)
            val bodyEffectRow: MezzeType.EffectRow? =
                if (bodyResult.effects.isEmpty() && bodyResult.effectInstances.isEmpty()) {
                    null
                } else {
                    MezzeType.EffectRow.simple(
                        effects = bodyResult.effects,
                        rowVar = null,
                        performed = false
                    ).copy(effectInstances = bodyResult.effectInstances)
                }
            val implFunctionType = MezzeType.FunctionType(
                paramType = implParamRecord,
                returnType = bodyResult.type,
                effects = bodyEffectRow
            )
            // #180: refresh the registered InherentMethodInfo with the body-derived FunctionType
            if (methodInfo != null && bodyEffectRow != null) {
                val refreshedInfo = methodInfo.copy(methodType = implFunctionType)
                currentContext = currentContext.extendWithAnonymousMethod(refreshedInfo)
            }
            // Built from the BODY's result rather than field-by-field. Listing the
            // ones this op needs and letting the rest default is how an impl body's
            // side tables were lost: ten of twenty-six were named, so everything the
            // body recorded about itself — including the per-read field types emit
            // wants — never reached the module. Measured: a field read inside an
            // `impl` mints an unknown placeholder where the same read in an ordinary
            // function does not, and that single position is the largest entry in
            // the placeholder ratchet.
            //
            // `undefinedVariables` is cleared DELIBERATELY: an impl body's
            // references are resolved against the impl's own scope, and the ones
            // this pass could not see are not the module's undefined variables.
            results[opKey] = bodyResult.copy(
                type = implFunctionType,
                constraints = constraints,
                undefinedVariables = emptyList(),
            )
        }
        return currentContext
    }

    private fun generateLetBinding(
        binding: ParsedLetDecl,
        context: TypingContext,
        recordLiteralBindings: Set<String>,
        preConvertedAnnotationType: MezzeType? = null
    ): ConstraintResult {
        DebugLogger.debug(Category.ConstraintGenerator) {
            val exprInfo = if (binding.value is ParsedIdentifier) {
                " Identifier='${(binding.value as ParsedIdentifier).localName}'"
            } else ""
            "generateLetBinding: binding=${binding.variableName} expr=${binding.value.javaClass.simpleName}$exprInfo"
        }

        val expectedType = preConvertedAnnotationType ?: binding.typeAnnotation?.let { convertTypeAnnotation(it) }

        val bindingName = getBindingName(binding)
        val enclosingFunctionName = if (binding.value is ParsedLambda) bindingName else null

        // abilities declare the same method (#304) — the user's explicit
        // declaration wins over a coincidentally-named prelude ability.
        val annotationAbilityNames = (binding.typeAnnotation as? ParsedFunctionType)
            ?.abilityConstraints
            ?.map { it.abilityName }
            ?.toSet()
            .orEmpty()

        val constraintContext = ConstraintContext(
            context,
            expectedType = expectedType,
            performerToEffect = performerToEffect,
            performerAddedEffects = performerAddedEffects,
            abilityNames = abilityNames,
            effectNames = effectNames,
            effectDeclarations = registry.effectDeclarations,
            abilityDeclarations = registry.abilityDeclarations,
            recordLiteralBindings = recordLiteralBindings,
            typeDeclarations = registry.typeDeclarations,
            currentBindingName = bindingName,
            enclosingFunctionName = enclosingFunctionName,
            annotationAbilityNames = annotationAbilityNames
        )

        val exprResult = expressionGenerator.generate(
            binding.value,
            constraintContext
        )

        val bindingType = exprResult.type

        val patternResult = patternGenerator.generate(
            binding.pattern,
            bindingType,
            constraintContext,
            binding.value.loc
        )

        val allConstraints = exprResult.constraints + patternResult.constraints

        val typeAnnotationValue = binding.typeAnnotation
        val (finalConstraints, abilityConstraints) = if (typeAnnotationValue != null && expectedType != null) {
            val annotatedType = expectedType

            annotationValidator.validateAnnotationAgainstExpression(binding, annotatedType, typeAnnotationValue, binding.value, exprResult.type)

            val annotationAbilityConstraints = if (typeAnnotationValue is ParsedFunctionType) {
                // Prefer the already-converted annotation FunctionType (typeArgs are
                // MezzeType); fall back to converting the parsed annotation's args.
                // Either way, each constraint's first type-VAR is the primary; skip
                // constraints with no variable (nothing to require).
                val preConvertedFnType = annotatedType as? MezzeType.FunctionType
                val abilityTypeArgs: List<Pair<String, List<MezzeType>>> =
                    if (preConvertedFnType != null) {
                        preConvertedFnType.abilityConstraints.map { it.abilityName to it.typeArgs }
                    } else {
                        typeAnnotationValue.abilityConstraints.map { c ->
                            c.abilityName to c.typeArgs.map { convertTypeAnnotation(it) }
                        }
                    }
                abilityTypeArgs.mapNotNull { (abilityName, typeArgs) ->
                    val typeVarArg = typeArgs.filterIsInstance<MezzeType.VarType>().firstOrNull()
                        ?: return@mapNotNull null
                    Constraint.AbilityRequired(
                        typeVar = typeVarArg.variable,
                        abilityName = abilityName,
                        abilityHash = registry.abilityDeclarations[abilityName]?.contentHash,
                        typeArgs = typeArgs,
                        context = "Type annotation on ${getBindingName(binding)}",
                        loc = binding.loc,
                    )
                }
            } else {
                emptyList()
            }

            val bindingName = getBindingName(binding)
            val constraintsWithAnnotation = allConstraints + Constraint.Equality(
                left = exprResult.type,
                right = annotatedType,
                context = "Type annotation on $bindingName",
                loc = binding.value.loc,
                errorContext = ErrorContext.TypeAnnotation(bindingName = bindingName)
            )

            Pair(constraintsWithAnnotation, exprResult.abilityConstraints + annotationAbilityConstraints)
        } else {
            Pair(allConstraints, exprResult.abilityConstraints)
        }

        val expectedHints: Map<Loc, MezzeType> =
            if (typeAnnotationValue != null && expectedType != null) {
                val fnAnnotation = expectedType as? MezzeType.FunctionType
                val lambdaBody = (binding.value as? ParsedLambda)?.body
                if (fnAnnotation != null && lambdaBody != null) {
                    mapOf(lambdaBody.loc to fnAnnotation.returnType)
                } else {
                    mapOf(binding.value.loc to expectedType)
                }
            } else emptyMap()

        return exprResult.copy(
            constraints = finalConstraints,
            abilityConstraints = abilityConstraints,
            expectedTypeAt = exprResult.expectedTypeAt + expectedHints,
        )
    }

    private fun getContextFreeVars(context: TypingContext, excludeBindings: Set<BindingKey> = emptySet()): Set<TypeVar> =
        context.freeTypeVars(excludeBindings)

    internal fun addAbilityConstraintsToFunctionType(
        type: MezzeType,
        abilityConstraints: List<Constraint.AbilityRequired>,
        substitution: Substitution,
        resolvedAssociatedBindings: Map<Pair<String, Int>, Map<String, MezzeType>> = emptyMap(),
        // Answers "did solving settle these args, and do they have an impl?" —
        // supplied by the caller (which owns the solver). Null ⇒ treat everything
        // as UNDECIDED (conservative: never drop, never report). Only
        // consulted when [dropDischargedConstraints] is true — which the caller sets
        // false for a decl carrying an explicit annotation, so the inferred type
        // still reflects that written signature for the annotation-match to compare.
        discharge: ((abilityName: String, args: List<MezzeType>, abilityHash: com.mezze.language.typedast.Hash?) -> ConstraintSolver.AbilityDischarge)? = null,
        dropDischargedConstraints: Boolean = false,
        // Receives an obligation whose args resolved CONCRETE and UNSATISFIED while
        // an unannotated signature was being finalized — see [isFullyDischarged].
        // Null ⇒ nobody is listening, and the constraint is simply carried (the
        // pre-existing behaviour, which is what the annotated path wants).
        onAbsorbedUnsatisfiable: ((Constraint.AbilityRequired, List<MezzeType>) -> Unit)? = null,
    ): MezzeType {
        DebugLogger.debugSection(Category.ConstraintGenerator, "addAbilityConstraintsToFunctionType") {
            val lines = mutableListOf("Type: $type")
            if (type is MezzeType.FunctionType) {
                lines.add("Param type: ${type.paramType}")
                if (type.paramType is MezzeType.RecordType) {
                    val record = type.paramType as MezzeType.RecordType
                    lines.add("  Record fields:")
                    record.fields.forEach { (name, fieldType) ->
                        lines.add("    * $name: $fieldType")
                    }
                }
                lines.add("Return type: ${type.returnType}")
            }
            lines.add("Ability constraints received: ${abilityConstraints.size} constraints")
            abilityConstraints.forEach { lines.add("  * $it") }
            lines
        }

        if (type !is MezzeType.FunctionType) {
            return type
        }

        val typeVars = mutableSetOf<TypeVar>()
        collectTypeVariables(type.paramType, typeVars)
        collectTypeVariables(type.returnType, typeVars)

        DebugLogger.debugSection(Category.ConstraintGenerator, "Collected type vars") {
            typeVars.map { "* ${it.name} (id=${it.id})" }
        }

        // Each produced constraint stays PAIRED with the obligation it came from, so
        // a failure found below can be reported at the call that raised it rather
        // than at the `let`. The pairing is by construction — no re-matching, which
        // would have to guess which original a solved arg tuple belongs to.
        val functionAbilityConstraints = abilityConstraints.mapNotNull { constraint ->
            val solvedTypeVar = substitution.apply(MezzeType.VarType(constraint.typeVar))

            // The constraint's primary/receiver var (`constraint.typeVar`) is the one
            // to attach the obligation to — but for a class-param ability like
            // `NumAdd 'r for 'l` inferred from `{x}->x+N`, the receiver `'l` may have
            // resolved to a CONCRETE type (the `+N` fixes it) while the FREE quantifiable
            // var that IS this fn's param sits in `typeArgs` (the class-arg `'r`). When
            // the receiver solved concrete, fall back to the first class-arg var that is
            // one of THIS fn's own param vars — so a constrained fn passed through a HOF
            // (`wrap {f = addTen}`) still attaches `with NumAdd Int 'a` to the HOF's
            // param/result instead of silently dropping it. We match the fallback by
            // IDENTITY (id) against `typeVars`, never by name, and only pick a var that
            // is genuinely one of this fn's params (so we never invent a bogus obligation).
            val attachVar: TypeVar? =
                if (solvedTypeVar is MezzeType.VarType) {
                    typeVars.find { it.name == solvedTypeVar.variable.name }
                } else {
                    // Receiver resolved concrete — anchor on a param var carried in the
                    // class-args (keeps the fn polymorphic; that var stays free).
                    constraint.typeArgs.firstNotNullOfOrNull { arg ->
                        val a = substitution.apply(arg)
                        (a as? MezzeType.VarType)?.variable?.let { v -> typeVars.find { it.id == v.id } }
                    }
                }

            if (attachVar != null) {
                val matchingTypeVar = attachVar

                val constraintResolvedHash = constraint.abilityHash ?: registry.resolveAbilityHash(constraint.abilityName)
                if (!WellKnownHashConstants.isInspect(constraintResolvedHash)) {
                    // Carry the FULL type-arg tuple: primary (the matched var) plus
                    // the constraint's extra ability args, substituted. Hardcoding a
                    // single arg dropped the extras for a multi-param ability, which
                    // both malformed the constraint and produced a duplicate against
                    // the correct multi-arg one from the call-site path.
                    //
                    // Populate `having` bindings resolved during solving for this
                    // ability on this primary var (keyed by original primary id).
                    // Concrete or var-valued — mirrors how effects carry
                    // associatedTypes, so the with-clause renders + unifies.
                    val bindings = resolvedAssociatedBindings[constraint.abilityName to constraint.typeVar.id]
                        ?: emptyMap()
                    // Normal path: the matched var IS the receiver, so it leads the tuple
                    // (receiver + class-args). Fallback path (receiver resolved concrete,
                    // matched var came from the class-args): the tuple is the SOLVED
                    // receiver followed by the substituted class-args — prepending the
                    // matched (class-arg) var would drop the concrete receiver (`Int`) and
                    // print `NumAdd 'a 'a`. So use the solved receiver as the lead.
                    val arity = registry.lookupAbility(constraint.abilityName)?.typeParams?.size
                    val substitutedArgs = constraint.typeArgs.map { substitution.apply(it) }
                    val leadArgs: List<MezzeType> = abilityClassArgs(
                        primary = if (solvedTypeVar is MezzeType.VarType)
                            MezzeType.VarType(matchingTypeVar) else solvedTypeVar,
                        typeArgs = substitutedArgs,
                        arity = arity,
                    )
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = leadArgs,
                        associatedTypeBindings = bindings,
                        abilityHash = constraint.abilityHash
                    ) to constraint
                } else null
            } else null
        }

        // Dedup on (abilityName, DISTINCT typeArg identities). Using the distinct
        // set rather than the positional list collapses a malformed self-repeat
        // like `Producer 'a 'a` (single-param receiver accidentally prepended
        // onto a typeArgs that already held it) against the correct `Producer 'a`
        // — while keeping a genuine multi-param `Scale 'by 'a` (two DISTINCT args)
        // separate from `Scale 'a`. When duplicates collapse, prefer the one that
        // carries associated-type bindings (the populated one) over an empty
        // sibling from another pass.
        fun argKey(arg: MezzeType) = when (arg) {
            is MezzeType.VarType -> "v:" + arg.variable.name
            else -> "t:" + arg.toString()
        }
        // A finalized function type should not carry an ability constraint that is
        // already fully DISCHARGED — one whose resolved args are all concrete AND
        // satisfied by an impl. Such a constraint imposes nothing on a caller (an
        // arg-free `main = {} -> show { it = { price = 1 } }` must infer `{} -> Str`,
        // and `{} -> (42).to_dbg {}` must not keep `Inspect Int`). This is
        // deliberately narrow: it fires ONLY when every arg is ground AND an impl
        // matches — a still-open var, an unsatisfiable concrete arg, or a missing
        // discharge oracle all KEEP the constraint, so genuine polymorphism
        // (`show : {it:'a} with Priced 'a`), unsatisfiable residuals, and the
        // explicit-annotation path above are all untouched.
        //
        // The unsatisfiable-concrete case is NOT one of those three. A concrete type
        // either has the ability or it does not — there is nothing to generalize
        // over, so `with Eq Sink` on an inferred signature is not a weaker claim,
        // it is a meaningless one. Carrying it meant `f = {} -> ([Sink.(1)]).peek {}`
        // inferred `{} -> Sink with Eq Sink` and type-checked, because nothing called
        // `f` and so nothing ever demanded the `Eq`; writing `: {} -> Sink` removed
        // the place to put it and the SAME program was correctly rejected. An
        // annotation decided whether a program type-checked. So the constraint is
        // still kept (dropping it would cascade), but [onAbsorbedUnsatisfiable] is
        // told, and the binding reports it.
        // The obligation a constraint came from, for its source location. A
        // constraint riding on `type` was attached by an earlier pass and carries no
        // origin of its own — but the SAME obligation is normally still in
        // `abilityConstraints`, so recover it there: same ability (by hash where both
        // sides carry one) and the same SOLVED argument tuple. Set-compared because
        // the two records order receiver and class-args differently.
        fun originFor(c: MezzeType.FunctionAbilityConstraint, resolved: List<MezzeType>): Constraint.AbilityRequired? =
            abilityConstraints.firstOrNull { o ->
                val sameAbility =
                    if (o.abilityHash != null && c.abilityHash != null) o.abilityHash == c.abilityHash
                    else o.abilityName == c.abilityName
                sameAbility &&
                    (listOf(MezzeType.VarType(o.typeVar)) + o.typeArgs)
                        .map { substitution.apply(it) }.toSet() == resolved.toSet()
            }

        fun isFullyDischarged(c: MezzeType.FunctionAbilityConstraint, origin: Constraint.AbilityRequired?): Boolean {
            if (!dropDischargedConstraints) return false
            val resolved = c.typeArgs.map { substitution.apply(it) }
            if (resolved.isEmpty() || resolved.any { collectFreeTypeVars(it).isNotEmpty() }) return false
            val answer = discharge?.invoke(c.abilityName, resolved, c.abilityHash)
                ?: ConstraintSolver.AbilityDischarge.UNDECIDED
            if (answer == ConstraintSolver.AbilityDischarge.UNSATISFIED) {
                (origin ?: originFor(c, resolved))?.let { onAbsorbedUnsatisfiable?.invoke(it, resolved) }
            }
            return answer == ConstraintSolver.AbilityDischarge.DISCHARGED
        }
        // Store what solving worked out, not the variables it started from.
        //
        // A constraint carried over from `type.abilityConstraints` arrived with
        // its ORIGINAL type variables. Solving may since have bound them —
        // `describe`'s `FromJson 'k350 'w474` is really
        // `FromJson { code: Str, ... } Json` — but that answer lived only in
        // the substitution, and was applied when PRINTING a signature and
        // nowhere else. The stored type kept the bare vars, so a later pass
        // re-derived a weaker answer from them and the honest one was lost.
        //
        // Applying the substitution here makes the stored constraint say what
        // the solver concluded. A still-open variable substitutes to itself, so
        // genuine polymorphism is unchanged.
        fun resolveArgs(c: MezzeType.FunctionAbilityConstraint) =
            // Close solver-generated open-record tails in the resolved arg (a
            // decode/encode target grown by field access). The concrete fields
            // are unchanged — only the invented `...` tail is dropped — so the
            // codec derivation (already done) and dispatch are unaffected, while
            // the printed signature reads as the exact record, not `{ …, … }`.
            c.copy(typeArgs = c.typeArgs.map { closeGeneratedRowVars(substitution.apply(it)) })

        // A constraint already on `type` arrived from an earlier pass with no
        // obligation attached — it has no origin, so an unsatisfiable one found
        // there is reported by whichever binding first absorbed it, not twice here.
        val allConstraints = (type.abilityConstraints.map { it to null } + functionAbilityConstraints)
            .map { (c, origin) -> resolveArgs(c) to origin }
            .filterNot { (c, origin) -> isFullyDischarged(c, origin) }
            .map { (c, _) -> c }
            .sortedByDescending { it.associatedTypeBindings.size }
            .distinctBy { c -> c.abilityName to c.typeArgs.map(::argKey).distinct() }

        DebugLogger.debugSection(Category.ConstraintGenerator, "addAbilityConstraintsToFunctionType result") {
            val lines = mutableListOf("All constraints: ${allConstraints.size} constraints")
            allConstraints.forEach { lines.add("  * $it") }
            lines.add("Function ability constraints added: ${functionAbilityConstraints.size}")
            functionAbilityConstraints.forEach { (c, _) -> lines.add("  * $c") }
            lines
        }

        return MezzeType.FunctionType(
            paramType = type.paramType,
            returnType = type.returnType,
            effects = type.effects,
            abilityConstraints = allConstraints
        )
    }

    private fun getBindingName(binding: ParsedLetDecl): String =
        binding.pattern.extractBindings().firstOrNull() ?: when (binding.pattern) {
            is ParsedWildcardPattern -> "_wildcard"
            is ParsedLiteralPattern -> "_literal"
            else -> "_pattern"
        }

    /**
     * Reorder decls so a top-level `let` callee is inferred before a caller
     * that references it, when the dependency is acyclic. Only reorders `let`
     * bindings relative to each other; every non-`let` decl keeps its original
     * index, and lets are placed by a STABLE topological sort so unrelated
     * bindings and cycles preserve source order (the previous behaviour). This
     * makes a forward reference to a polymorphic fn resolve to its real scheme
     * instead of a monomorphic placeholder var (#126).
     *
     * Edges: `let a` depends on `let b` iff a's body references the name `b`
     * (b bound by another top-level `let`). Self-references and cycles are
     * broken by falling back to source order — safe because that reproduces
     * today's placeholder path, which is already correct for direct/mutual
     * recursion (the callee is in scope via its own placeholder).
     */
    private fun dependencyOrderedDecls(decls: List<ParsedDecl>): List<ParsedDecl> {
        val lets = decls.filterIsInstance<ParsedLetDecl>()
        if (lets.size < 2) return decls  // nothing to reorder

        val letByName: Map<String, ParsedLetDecl> = lets
            .filter { it.isSimpleBinding }
            .associateBy { it.variableName }
        if (letByName.isEmpty()) return decls

        // Dependencies: name -> set of top-level let names its body references.
        val deps: Map<ParsedLetDecl, Set<ParsedLetDecl>> = lets.associateWith { d ->
            val referenced = mutableSetOf<String>()
            collectReferencedNames(d.value, referenced)
            referenced.mapNotNull { name ->
                letByName[name]?.takeIf { it !== d }  // ignore self-reference
            }.toSet()
        }

        // Stable DFS post-order → callee-before-caller. Cycles: a node already
        // on the stack is skipped (edge dropped), so the cycle keeps the order
        // its members are first visited in, i.e. source order.
        val sortedLets = ArrayList<ParsedLetDecl>(lets.size)
        val visited = HashSet<ParsedLetDecl>()
        val onStack = HashSet<ParsedLetDecl>()
        fun visit(d: ParsedLetDecl) {
            if (d in visited) return
            onStack.add(d)
            for (dep in deps[d].orEmpty()) {
                if (dep !in onStack) visit(dep)
            }
            onStack.remove(d)
            visited.add(d)
            sortedLets.add(d)
        }
        for (d in lets) visit(d)  // source order drives the outer walk → stable

        // If the topo order equals source order (no forward references to
        // reorder — the overwhelmingly common case), return the original list
        // unchanged so this pass is a guaranteed no-op. Compare by identity so
        // destructuring lets (record patterns, no `variableName`) are safe.
        if (sortedLets.zip(lets).all { (a, b) -> a === b }) return decls

        // Weave the sorted lets back into the original decl stream, keeping
        // every non-let decl at its original position.
        val letQueue = ArrayDeque(sortedLets)
        return decls.map { decl ->
            if (decl is ParsedLetDecl) letQueue.removeFirst() else decl
        }
    }

    /**
     * Collect FREE identifier names referenced inside an expr — names that
     * resolve to an enclosing/top-level binding, not to a local one. Must be
     * scope-aware: a lambda param / do-let / match-binding named `x` shadows a
     * top-level `x`, so a reference under that binder is NOT a dependency on the
     * top-level `x`. Ignoring this created false edges (and false cycles, e.g.
     * `let f = {x} -> x` "depending on" a top-level `x`) that mis-reordered
     * unrelated bindings. `bound` is the set of names shadowed at this point.
     */
    private fun collectReferencedNames(
        expr: ParsedExpr,
        out: MutableSet<String>,
        bound: Set<String> = emptySet(),
    ) {
        when (expr) {
            is ParsedIdentifier ->
                if (expr.qualifier == null && expr.localName !in bound) out.add(expr.localName)
            is ParsedLambda -> {
                val params = when (val p = expr.params) {
                    is ParsedLambdaParams.Untyped -> p.names
                    is ParsedLambdaParams.Typed -> p.fields.map { it.name }
                }
                collectReferencedNames(expr.body, out, bound + params)
            }
            is ParsedDo -> {
                // A do-block's `let`/`bind` names scope over later statements
                // and the result; accumulate them left-to-right.
                var scope = bound
                for (stmt in expr.statements) {
                    when (stmt) {
                        is ParsedStatement.Let -> {
                            collectReferencedNames(stmt.value, out, scope)
                            scope = scope + stmt.pattern.extractBindings()
                        }
                        is ParsedStatement.Expr -> collectReferencedNames(stmt.expr, out, scope)
                    }
                }
                collectReferencedNames(expr.result, out, scope)
            }
            is ParsedMatch -> {
                collectReferencedNames(expr.scrutinee, out, bound)
                for (case in expr.cases) {
                    val caseBound = bound + case.patterns.flatMap { it.extractBindings() } +
                        case.ifLetBindings.flatMap { it.pattern.extractBindings() }
                    case.guard?.let { collectReferencedNames(it, out, caseBound) }
                    case.ifLetBindings.forEach { collectReferencedNames(it.expr, out, caseBound) }
                    collectReferencedNames(case.body, out, caseBound)
                }
            }
            else -> for (child in expr.children()) collectReferencedNames(child, out, bound)
        }
    }

    private fun convertTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        typeVarCache.clear()
        sharedRowVar.reset()
        return TypeAnnotationConverter.convertWith(
            annotation, constraintConversionConfig, typeVarCache, sharedRowVar
        )
    }

    /** The annotations of ONE signature, in a single variable scope.
     *  See [TypeAnnotationConverter.convertSignature]. */
    private fun convertSignatureAnnotations(
        entries: List<Pair<String, ParsedTypeAnnotation>>,
    ): List<Pair<String, MezzeType>> =
        TypeAnnotationConverter.convertSignature(entries, constraintConversionConfig)

    companion object {
        fun create(
            registry: DeclarationRegistry = DeclarationRegistry.EMPTY,
            performerToEffect: Map<String, String> = emptyMap(),
            performerAddedEffects: Map<String, Set<String>> = emptyMap(),
            abilityNames: Set<String> = emptySet(),
            effectNames: Set<String> = emptySet(),
            isStdlibModule: Boolean = false,
            sharedGeneratedCodecImpls: MutableMap<com.mezze.language.typedast.Hash, com.mezze.language.typechecker.solving.AbilityChecker.GeneratedCodecImpl>? = null,
        ): ConstraintGenerator {
            val expressionGen = ExpressionConstraintGenerator()
            expressionGen.registry = registry
            expressionGen.isStdlibModule = isStdlibModule
            val patternGen = PatternConstraintGenerator()

            val generator = ConstraintGenerator(
                expressionGen,
                patternGen,
                registry,
                performerToEffect,
                performerAddedEffects,
                abilityNames,
                effectNames,
                sharedGeneratedCodecImpls,
            )
            // S1.3: route the do-block `let` path through the SAME bindLet the
            // top-level path uses. bindLet needs ConstraintGenerator's private
            // annotation-validation state, so it stays there; we hand the do-block
            // path a bound reference (same injection pattern as `registry`).
            expressionGen.bindLetFn = { args ->
                generator.bindLet(
                    item = args.item,
                    result = args.result,
                    patternResult = args.patternResult,
                    preConvertedAnnotationType = args.preConvertedAnnotationType,
                    solveResult = args.solveResult,
                    substitution = args.substitution,
                    currentContext = args.currentContext,
                    outerContext = args.outerContext,
                    solver = args.solver,
                    suppressGeneralization = args.suppressGeneralization,
                    harvestEffectsIntoBinding = args.harvestEffectsIntoBinding,
                )
            }
            return generator
        }
    }

}


internal fun bodyContainsNativeCall(expr: ParsedExpr): Boolean = when (expr) {
    is ParsedIdentifier -> expr.localName.startsWith(com.mezze.language.ast.MagicParams.NATIVE_PREFIX)
    is ParsedLambda -> bodyContainsNativeCall(expr.body)
    is ParsedFunctionCall -> bodyContainsNativeCall(expr.function)
    is ParsedDo -> bodyContainsNativeCall(expr.result) || expr.statements.any { stmt ->
        when (stmt) {
            is ParsedStatement.Let -> bodyContainsNativeCall(stmt.value)
            is ParsedStatement.Expr -> bodyContainsNativeCall(stmt.expr)
            else -> false
        }
    }
    else -> false
}

data class PatternConstraintResult(
    val bindings: Map<String, MezzeType>,
    val constraints: List<Constraint>,
    val warnings: List<String> = emptyList(),
    val bindingLocations: Map<String, Loc> = emptyMap()
)

/**
 * Fill [out] with `nativeName -> { fields } -> return`, read off each `pub let`
 * that wraps exactly one native call.
 *
 * The wrapper states the native's type: `pub let clamp: { n: Int, min: Int, max:
 * Int } -> Int = { n, min, max } -> __native_int_clamp { n = n, min = min, max =
 * max }` maps each of the native's OWN field names to the declared type of the
 * parameter handed to it. Anything less direct than that — a computed argument, a
 * field the wrapper does not have a parameter for — is skipped, because then the
 * wrapper's type is not the native's.
 */
private fun collectNativeDeclaredTypes(
    decls: List<ParsedDecl>,
    annotationTypes: Map<ParsedLetDecl, MezzeType>,
    out: MutableMap<String, MezzeType>,
) {
    val conflicting = HashSet<String>()
    for (decl in decls) {
        if (decl !is ParsedLetDecl) continue
        val declType = annotationTypes[decl] as? MezzeType.FunctionType ?: continue
        val wrapperParams = (declType.paramType as? MezzeType.RecordType)?.fields ?: continue
        val lambda = decl.value as? ParsedLambda ?: continue
        val call = lambda.body as? ParsedFunctionCall ?: continue
        val callee = call.function as? ParsedIdentifier ?: continue
        val nativeName = callee.localName
        if (!nativeName.startsWith(MagicParams.NATIVE_PREFIX)) continue
        val args = call.arguments as? ParsedRecordLit ?: continue

        val fields = LinkedHashMap<String, MezzeType>()
        var direct = true
        for (entry in args.entries) {
            val field = entry as? ParsedRecordEntry.Field
            if (field == null) { direct = false; break }
            val passed = field.value as? ParsedIdentifier
            if (passed == null) { direct = false; break }
            val paramType = wrapperParams[passed.localName]
            if (paramType == null) { direct = false; break }
            fields[field.name] = paramType
        }
        if (!direct || fields.isEmpty()) continue

        val nativeType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(fields),
            returnType = declType.returnType,
        )
        val existing = out[nativeName]
        when {
            nativeName in conflicting -> Unit
            existing == null -> out[nativeName] = nativeType
            existing != nativeType -> { out.remove(nativeName); conflicting.add(nativeName) }
        }
    }
}
