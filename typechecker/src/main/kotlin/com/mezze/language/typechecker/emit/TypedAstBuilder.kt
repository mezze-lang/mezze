package com.mezze.language.typechecker.emit

import com.mezze.language.typechecker.solving.expandTypeApplicationToRecord
import com.mezze.language.typechecker.solving.expandTypeApplicationToVariant
import com.mezze.language.typechecker.solving.substituteTypeParamsByName
import com.mezze.language.typechecker.solving.substituteTypeVars
import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser as ParserBridge
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypeScheme as V2TypeScheme
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.ast.solving.Substitution
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.util.WellKnownTypeApp
import com.mezze.language.typechecker.util.receiverTypeName
import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.solving.closeGeneratedRowVars

class TypedAstBuilder(
    // S5.2 (#365): the SINGLE immutable solver→emit hand-off. Emit reads every
    // decided side-table (substitution, dispatch resolutions, tail calls, open
    // row vars, method-call info, call-return / match-case / match-scrutinee
    // types) from this frozen snapshot — it can no longer reach into live solver
    // state. The per-field accessors below preserve the previous names so the
    // build body is unchanged.
    private val solved: com.mezze.language.typechecker.phase.SolvedModule,
    private val constraintResults: Map<String, ConstraintResult>,
    internal val abilityImpls: Map<Hash, TypingContext.AbilityImplInfo>,
    internal val registry: DeclarationRegistry,
    private val performerToEffect: Map<String, String>,
    internal val importContext: ModuleImportContext?,
    private val identifierContentHashes: Map<BindingKey, Hash>,
    private val implDeclarations: Map<String, ParsedPerformerDecl>,
) {
    internal val substitution: Substitution get() = solved.substitution

    /** Param types for the NEXT ability-op lambda built (see buildAbilityImpl):
     *  the ability's declared op signature with the impl's class args substituted.
     *  They type `it`/`r` and everything read off them inside the body; the
     *  lambda's own surface type is still emitted laundered (fresh vars per
     *  param), exactly as before — mono keys specializations off that surface. */
    private var pendingOpParamTypes: Map<String, MezzeType>? = null

    /** True while building a seeded ability-impl op body (see [pendingOpParamTypes]).
     *  Only there are match-bound names typed STRUCTURALLY off the scrutinee: the
     *  solver never produced entries for these bodies. Everywhere else the solver's
     *  loc-keyed bindings are authoritative and a structural guess must not
     *  override an absence the compiler already relies on (codec bodies). */
    private val tailCallLocations: Set<Loc> get() = solved.tailCallLocations
    private val mustBeOpenRowVars: Set<RowVar> get() = solved.mustBeOpenRowVars
    internal val methodCallResolutions: Map<Pair<Loc, String>, MethodCallInfo>
        get() = solved.methodCallResolutions
    // S2.3 (#358): the SINGLE post-solve dispatch decision, keyed by call-site
    // Loc, produced by DispatchResolver. For an INHERENT method call whose site
    // has a Resolution, emit READS `resolution.opHash` (the resolved impl content
    // hash) instead of re-running its own structural impl re-pick — dispatch is
    // decided in exactly ONE place. Sites with no Resolution (VarType-deferred
    // receivers, ability collisions the resolver defers, builtins) keep the
    // legacy per-site resolution below; those are genuine deferrals, not a second
    // impl-selection heuristic.
    internal val dispatchResolutions: Map<Loc, com.mezze.language.typechecker.constraints.Resolution>
        get() = solved.dispatchResolutions
    internal val callReturnTypes: Map<Loc, MezzeType> get() = solved.callReturnTypes
    // #195 mono-fix #1: per-match-case binding name → solver TyVar. The
    // constraint generator records the SAME fresh TyVars that PatternConstraints
    // allocated and HasField unified with concrete types; applying `substitution`
    // here gives the resolved type for each binding. Used in buildMatchCase to
    // populate PatternBinding.type + identifierContext for guard/body emit
    // instead of constructing fresh disconnected placeholders.
    internal val matchCaseBindings: Map<Loc, Map<String, MezzeType>>
        get() = solved.matchCaseBindings
    // S5.0 (#376): the constraint-time solved (UNERASED) scrutinee type keyed by
    // the scrutinee expression's Loc — the SAME value the matchScrutineeTypes
    // side-table records and MatchValidator reads for exhaustiveness. Threaded here
    // so emit can stamp it onto TypedMatch.scrutineeSourceType. Runtime lowering
    // still uses the erased `scrutinee.type`; this is metadata for #364's future
    // exhaustiveness-off-the-node.
    private val matchScrutineeTypes: Map<Loc, MezzeType> get() = solved.matchScrutineeTypes
    // #396 (do-block let convergence): the solver's AUTHORITATIVE value type for
    // each do-block `let` RHS, keyed by the RHS expression's Loc — the SAME type
    // the top-level path reads from `constraintResults[name]`. buildDoBlock stamps
    // the binding's stored VALUE type from this instead of re-deriving it from the
    // erased RHS emit node (`substitution.apply(value.type)`), so do-block and
    // top-level lets converge on ONE type source and no per-facet leak (unsolved
    // var #354, effect row #378, newtype wrapper #395) can recur. Effect-row
    // ownership stays with the separate #378 rider below.
    private val doBindingSolvedTypes: Map<Loc, MezzeType> get() = solved.doBindingSolvedTypes
    private val lambdaParamTypes: Map<Loc, Map<String, MezzeType>> get() = solved.lambdaParamTypes
    private val performFullyDischarged: Map<Loc, Boolean> get() = solved.performFullyDischarged


    internal val identifierContext = mutableMapOf<String, MezzeType>()

    // #378: the CONCRETE (named) effects on the enclosing lambda's arrow row —
    // i.e. the effects a performed value LEAVES UNHANDLED at the function
    // boundary. A do-block `let` whose RHS performs effect E carries E on its
    // stored type only if E is unhandled here (E in the top of this stack); an
    // effect an enclosing `perform` discharges never reaches the arrow, so it is
    // absent from this set and correctly does NOT ride on the binding. One frame
    // per lambda; the top frame is the innermost enclosing function. The frame
    // holds the whole row (not just names) so a rider can copy the arrow's
    // associated-type binding (`Exception having 'error = Str`) verbatim.
    private val enclosingArrowEffects = ArrayDeque<MezzeType.EffectRow?>()

    private val typeVarCache = mutableMapOf<String, TypeVar>()

    internal val invariantsEnabled: Boolean
        get() = System.getProperty("mezze.invariants", "false") == "true"

    /**
     * S2.3 (#358): the OLD emit-side inherent impl re-pick, retained ONLY as the
     * fallback for call sites DispatchResolver deferred (no Resolution). It picks
     * the candidate whose target matches the SOLVED receiver — hash-first
     * (rename-safe, module-distinct), name only for hashless builtins — mirroring
     * DispatchResolver.pickInherent exactly. For a site WITH a Resolution this is
     * never the decider; under invariants it is still computed and asserted equal
     * to the Resolution so any divergence is caught by the suite.
     */
    internal fun legacyInherentImplHash(
        methodInfo: MethodCallInfo,
        receiverExpr: TypedExpr,
    ): Hash? {
        if (methodInfo.candidateImpls.isEmpty()) return methodInfo.implContentHash
        val solvedReceiver = substitution.apply(receiverExpr.type)
        // BUG 1: walk the receiver's hashes most-specific first — the applied hash
        // of `Result PyForeign IErr` before the bare `Result` constructor hash —
        // so two same-constructor impls resolve without a name comparison.
        val byHash = com.mezze.language.typechecker.util
            .pickByReceiverHash(solvedReceiver, methodInfo.candidateImpls) { it.targetTypeHash }
            ?.implContentHash
        return byHash ?: run {
            // Try the receiver's names in SPECIFICITY order — mangled form first
            // (`Result_Response_HttpError` for a concrete-arg TypeApplication),
            // constructor-only fallback second (`Result`). So a specific inherent
            // impl on `Result X Y` wins over the generic `impl Result 'a 'e` when
            // the receiver's args are known — this is what routes a chained
            // `<perform>.eval{}.as_str{}` on `Result <Lang>Foreign InteropError`
            // to the language-specific `Result`-impl instead of the same-named
            // inherent on the underlying `Foreign`. Same helper the
            // ConstraintSolver / DispatchResolver ladder uses.
            for (name in com.mezze.language.typechecker.util.receiverTypeNames(solvedReceiver)) {
                methodInfo.candidateImpls.firstOrNull { it.targetTypeName == name }
                    ?.implContentHash?.let { return@run it }
            }
            null
        } ?: methodInfo.implContentHash
    }

    /**
     * Nat is a newtype (`pub newtype Nat = Int` in std/nat.mz) — synthesize its
     * NewtypeType from the stdlib declaration so that literal `5` types the same
     * as `Nat.(5)`. Fallback for tests without prelude.
     */
    internal val natLiteralType: MezzeType by lazy {
        val decl = registry.typeDeclarations["Nat"]
        val newtypeDef = decl?.definition as? ParsedNewtypeDef
        if (newtypeDef != null) {
            val hash = decl.contentHash
            MezzeType.NewtypeType(
                name = "Nat",
                constructor = newtypeDef.constructor,
                wrappedType = MezzeType.IntType,
                identity = if (hash != null) TypeIdentity.Declared(hash, "Nat") else TypeIdentity.Anonymous,
            )
        } else {
            MezzeType.NewtypeType(
                name = "Nat",
                constructor = "Nat",
                wrappedType = MezzeType.IntType,
            )
        }
    }

    /**
     * In-scope CLOSED record aliases, as (name, content hash, field-name→field-type).
     * Built once from local decls + imported types (so `pub`/import scoping is
     * respected — a private or un-imported alias never appears here). Fed to THE
     * canonical `adoptRecordAlias` rule (shared with constraint generation) to LABEL
     * a bare record literal whose finalized shape exactly matches one:
     * `type Lang = { name: Str, released: Bool }` + `{ name = "M", released =
     * False }` ⇒ the literal adopts `Lang`'s Declared identity. Structural, so it
     * still passes anywhere the bare `{ name, released }` (open OR closed) is
     * accepted. Same-shape collisions are impossible (rejected at declaration).
     */
    private val closedRecordAliases: List<Triple<String, Hash, Map<String, MezzeType>>> by lazy {
        val decls = registry.allTypeDecls + (importContext?.importedTypes?.values ?: emptyList())
        decls.mapNotNull { decl ->
            val def = decl.definition
            if (def is ParsedRecordDef && !def.isOpen) {
                val hash = decl.contentHash ?: ParsedDeclHasher.hashTypeDecl(decl)
                // S3.1 identity-stability invariant: the hash emit stamps for an alias
                // name MUST equal the registry's canonical hash for that name — no
                // silent re-derivation drift between registry and emit. (Under
                // -Dmezze.invariants=true only; `typeHashFor` may be null for an
                // import-only alias the registry doesn't index by name, which is fine.)
                if (invariantsEnabled) {
                    val registryHash = registry.typeHashFor(decl.name)
                    require(registryHash == null || registryHash == hash) {
                        "Identity-stability invariant: emit hash for record alias '${decl.name}' " +
                            "($hash) diverges from registry hash ($registryHash)"
                    }
                }
                Triple(decl.name, hash, def.fields.associate { it.name to convertParsedTypeAnnotation(it.type) })
            } else null
        }
    }

    private fun precomputeContentHashes(module: ParsedModule) {
        val mutableHashes = identifierContentHashes.toMutableMap()

        importContext?.importedBindings?.forEach { (name, binding) ->
            if (binding.contentHash != null) {
                val key = BindingKey.unqualified(name)
                if (key !in mutableHashes) {
                    mutableHashes[key] = binding.contentHash!!
                }
            }
        }

        val moduleHashes = ParsedDeclHasher.hashAllLetDecls(module)
        for ((name, hash) in moduleHashes) {
            val key = BindingKey.unqualified(name)
            if (key !in mutableHashes) {
                mutableHashes[key] = hash
            }
        }
        precomputedHashes = mutableHashes
    }

    private var precomputedHashes: Map<BindingKey, Hash>? = null
    private val lambdaParamScopes = ArrayDeque<MutableList<String>>()

    private val lambdaScopeIsLambdaBoundary = ArrayDeque<Boolean>()

    internal fun pushScope(names: MutableList<String>, isLambdaBoundary: Boolean) {
        lambdaParamScopes.addLast(names)
        lambdaScopeIsLambdaBoundary.addLast(isLambdaBoundary)
    }

    internal fun popScope() {
        lambdaParamScopes.removeLast()
        lambdaScopeIsLambdaBoundary.removeLast()
    }

    private fun lambdaDepthFromScope(scopeIdx: Int): Int {
        var depth = 0
        for (i in (scopeIdx + 1) until lambdaParamScopes.size) {
            if (lambdaScopeIsLambdaBoundary.getOrNull(i) == true) {
                depth++
            }
        }
        return depth
    }

    private val effectiveContentHashes: Map<BindingKey, Hash>
        get() = precomputedHashes ?: identifierContentHashes

    fun build(module: ParsedModule): TypedModule {
        precomputeContentHashes(module)

        val decls = mutableListOf<TypedDecl>()
        val abilityDeclsList = mutableListOf<TypedAbilityDecl>()
        val abilityImplsList = mutableListOf<TypedAbilityImpl>()
        val effectDeclsList = mutableListOf<TypedEffectDecl>()
        val performerDeclsList = mutableListOf<TypedPerformerDecl>()
        val exports = mutableMapOf<String, ExportEntry>()

        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> buildLetDecl(decl, module.path, decls, exports)
                is ParsedTypeDecl -> buildTypeDecl(decl, module.path, decls, exports)
                is ParsedAbilityDecl -> buildAbilityDecl(decl, module.path, abilityDeclsList, exports)
                is ParsedImplAbilityDecl -> buildAbilityImpl(decl, module.path, abilityImplsList)
                is ParsedEffectDecl -> buildEffectDecl(decl, module.path, effectDeclsList, exports)
                is ParsedPerformerDecl -> buildPerformerDecl(decl, module.path, performerDeclsList, exports)
                is ParsedTopLevelExpr -> buildTopLevelExpr(decl, module.path, decls)
                is ParsedUseDecl -> { /* resolved at import phase */ }
                is ParsedPubUseDecl -> { /* resolved at import phase */ }
            }
        }

        val imports = buildImports(module.path)

        val allHashes = decls.map { it.hash.hex } +
            abilityDeclsList.map { it.hash.hex } +
            abilityImplsList.map { it.hash.hex } +
            effectDeclsList.map { it.hash.hex } +
            performerDeclsList.map { it.hash.hex }
        val moduleHash = MezzeHashBuilder().also { b -> for (h in allHashes.sorted()) b.append(h) }.buildHash()

        return TypedModule(
            path = module.path,
            hash = moduleHash,
            imports = imports,
            exports = exports,
            decls = decls,
            abilityDecls = abilityDeclsList,
            abilityImpls = abilityImplsList,
            effectDecls = effectDeclsList,
            performerDecls = performerDeclsList,
            dependencies = emptyList()
        )
    }

    private fun buildLetDecl(
        decl: ParsedLetDecl,
        file: String,
        decls: MutableList<TypedDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        if (!decl.isSimpleBinding && decl.pattern is ParsedRecordPattern) {
            val pattern = decl.pattern as ParsedRecordPattern
            val destrName = "${com.mezze.language.ast.MagicParams.DESTRUCTURE_DECL_PREFIX}${decls.size}"
            val loc = decl.loc
            // Keyed the way the GENERATOR keys it. `ConstraintGenerator.getBindingName`
            // files a destructuring `let` under its FIRST BOUND NAME — `theValue`
            // for `let { only = theValue } = data`. Emit looked up
            // `__destructured_<n>` and then `__destructured__`, and nothing has ever
            // recorded under either, so both missed every time and the type came
            // from a placeholder. Every field pulled out of the pattern was then
            // typed from that placeholder.
            //
            // `__destructured_<n>` was doubly wrong: it counts `decls.size`, so the
            // key moved when a prelude was prepended. Position is not identity.
            val solveKey = pattern.extractBindings().firstOrNull()
            val rawType = solveKey?.let { k -> constraintResults[k]?.let { substitution.apply(it.type) } }
                ?: error(
                    "no solved type for the destructuring binding at ${decl.loc} " +
                        "(looked under `$solveKey`, the first name it binds — the same " +
                        "key ConstraintGenerator.getBindingName files it under). Emit " +
                        "runs only on a module that type-checked, so it has one."
                )
            val type = closeGeneratedRowVars(rawType)
            val value = buildExpr(decl.value, file, type)

            val destrHash = ParsedDeclHasher.hashLetDecl(decl)
            val scheme = V2TypeScheme(emptyList(), emptyList(), type)
            decls.add(TypedLetDecl(destrHash, destrName, emptyList(), value, scheme, false, loc))

            val isPublic = decl.visibility == ParsedVisibility.PUBLIC
            emitRecordDestructureDeclFields(pattern.entries, destrName, type, loc, isPublic, decls, exports)
            return
        }

        val name = if (decl.isSimpleBinding) decl.variableName else "__destructured__"
        val loc = decl.loc

        val rawType = constraintResults[name]?.let { substitution.apply(it.type) }
            // MEASURED REACHABLE. Converting this to a hard error broke 58 tests
            // across the newtype, STM and atom families, so "emit runs only on a
            // module that type-checked, therefore every binding has a solved type"
            // is FALSE — some binding reaches emit without one. Which, and why, is
            // not yet known; until it is, this stays a named placeholder rather
            // than a crash.
            ?: noContextType("let binding with no solved type")
        // Closes RECORD/VARIANT row vars only; effect rows pass through untouched
        // (effect-row membership is owned by gen-time harvest + unifyEffectRows).
        val closedType = closeGeneratedRowVars(rawType, mustBeOpenRowVars)
        // If the binding's inferred type is a bare closed record whose shape
        // exactly matches an in-scope closed alias, LABEL it with that alias name
        // so `x` reads as `Lang` (hover, errors, signatures). Structural, so `x`
        // still passes anywhere the bare record — open or closed — is accepted.
        val type = (closedType as? MezzeType.RecordType)
            ?.let { com.mezze.language.ast.adoptRecordAlias(it, closedRecordAliases) } ?: closedType

        val value = buildExpr(decl.value, file, type)

        identifierContext[name] = type

        val hash = ParsedDeclHasher.hashLetDecl(decl, file)
            ?: ParsedDeclHasher.hashLetDecl(decl)

        val freeVars = collectTypeVarsInOrder(type, sortFields = false)
            .distinctBy { it.name }
            .map { TypeParam(it.name, TypeKind.Star) }
        // Carry the binding's ability constraints onto the mono scheme. Without this
        // the scheme is constraint-free, so monomorphization can't resolve a
        // `having 'out` / dict for a constrained fn propagated through a HOF
        // (`g = wrap {f = addTen}`), and the dict never gets threaded at runtime.
        val schemeConstraints = (type as? MezzeType.FunctionType)?.abilityConstraints
            ?.mapNotNull { c ->
                c.abilityHash?.let { h ->
                    com.mezze.language.typedast.TypedAbilityConstraint(abilityHash = h, typeArgs = c.typeArgs)
                }
            } ?: emptyList()
        val scheme = V2TypeScheme(freeVars, schemeConstraints, type)
        val isPublic = decl.visibility == ParsedVisibility.PUBLIC

        decls.add(TypedLetDecl(
            hash = hash,
            name = name,
            typeParams = freeVars,
            value = value,
            scheme = scheme,
            isPublic = isPublic,
            loc = loc
        ))

        if (isPublic && decl.isSimpleBinding) {
            exports[name] = ExportEntry(name, hash, type, ExportKind.FUNCTION)
        }
    }

    private fun buildTypeDecl(
        decl: ParsedTypeDecl,
        file: String,
        decls: MutableList<TypedDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        val loc = decl.loc
        val hash = decl.contentHash ?: ParsedDeclHasher.hashTypeDecl(decl)

        val def: TypedTypeDef = when (val d = decl.definition) {
            is ParsedRecordDef -> TypedRecordDef(
                fields = d.fields.associate { it.name to convertParsedTypeAnnotation(it.type) },
                isOpen = d.isOpen
            )
            is ParsedVariantDef -> TypedVariantDef(
                constructors = d.constructors.associate { it.name to it.dataType?.let { dt -> convertParsedTypeAnnotation(dt) } },
                isOpen = d.isOpen
            )
            is ParsedNewtypeDef -> TypedNewtypeDef(
                constructor = d.constructor,
                wrappedType = convertParsedTypeAnnotation(d.wrappedType),
                derivedAbilities = emptyList()
            )
            is ParsedTypeAliasDef -> TypedAliasDef(
                aliasedType = convertParsedTypeAnnotation(d.aliasedType)
            )
        }

        val isPublic = decl.visibility == ParsedVisibility.PUBLIC
        // The declaration's type parameters, positional (`Box 'a` → ['a]): what lets
        // a consumer open the nominal `Box 'a` into its shape `{v: 'a}` and bind
        // `'a` against an expanded value. Was always empty.
        decls.add(TypedTypeDecl(hash, decl.name, decl.typeParams.map { TypeParam(it, TypeKind.Star) }, def, isPublic, loc))
        if (isPublic) {
            exports[decl.name] = ExportEntry(decl.name, hash, MezzeType.VarType(TypeVar.freshWithName(decl.name)), ExportKind.TYPE)
        }
    }

    private fun buildAbilityDecl(
        decl: ParsedAbilityDecl,
        file: String,
        decls: MutableList<TypedAbilityDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        val loc = decl.loc
        val hash = decl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(decl)

        // ONE clear for the whole ability, not one per op.
        //
        // An op's type variables are not its own: the checker rejects any that is
        // not a parameter of the enclosing ability ("operation 'x' has undeclared
        // type variable(s)"). So every `'a` written anywhere in `ability Decode
        // 'config 'a for 'in` denotes the SAME `'a`, across ops and across each
        // op's param/return alike, and a clear anywhere inside the declaration
        // splits it. `ContextBuilder.addAbilityMethods` — which builds the typing
        // context for the same declaration — already scopes it this way; clearing
        // per op here made the two representations of one ability disagree about
        // which variables its ops share.
        typeVarCache.clear()
        val ops = decl.operations.mapIndexed { idx, op ->
            val paramType = convertParsedTypeAnnotation(op.paramType)
            val returnType = convertParsedTypeAnnotation(op.returnType)
            TypedAbilityOp(op.name, idx, paramType, returnType, op.loc)
        }

        decls.add(TypedAbilityDecl(
            hash = hash,
            name = decl.name,
            typeParams = emptyList(),
            receiverTypeParam = decl.receiverTypeParam,
            associatedTypes = decl.associatedTypes,
            dependencies = emptyList(),
            operations = ops,
            loc = loc
        ))

        exports[decl.name] = ExportEntry(decl.name, hash, MezzeType.VarType(TypeVar.freshWithName(decl.name)), ExportKind.ABILITY)
    }

    fun buildCodecImpl(decl: ParsedImplAbilityDecl, impls: MutableList<TypedAbilityImpl>) {
        // Codec bodies dispatch every step through `config`'s Tier-2 methods and
        // were shaped for untyped params — keep them as they were. A DERIVED shape
        // impl (anonymous-record Eq) built through this same path does compare
        // components, so it is seeded like any hand-written impl.
        buildAbilityImpl(decl, "<codec-generated>", impls, seedOpParams = decl.derived)
    }

    /**
     * Map each variable of a DECLARED arrow to whatever stands in its place on the
     * arrow that was actually inferred, by walking the two in parallel.
     *
     * An op's `with` obligations are converted from the ANNOTATION while its arrow
     * is built by inference, and the two conversions share no variable map — so
     * the `'a` written in `with Decode JsonC 'a` and the `'a` written in
     * `-> Result 'a DecodeError` become two unrelated `TypeVar`s. The obligation
     * then constrains a variable occurring NOWHERE in the signature it is attached
     * to, and anything downstream asking what the obligation is about finds
     * nothing to match it against. (That is why a `from_json` call could not say
     * which shape it decodes into: the link from `Decode … 'a` to the `'a` in the
     * return type was severed before codegen ever saw it.)
     *
     * Position is what relates the two, and a parallel walk is what recovers it.
     */
    private fun linkDeclaredVars(declared: MezzeType, inferred: MezzeType, out: MutableMap<String, MezzeType>) {
        when (declared) {
            is MezzeType.VarType -> out.putIfAbsent(declared.variable.name, inferred)
            is MezzeType.FunctionType -> if (inferred is MezzeType.FunctionType) {
                linkDeclaredVars(declared.paramType, inferred.paramType, out)
                linkDeclaredVars(declared.returnType, inferred.returnType, out)
            }
            is MezzeType.RecordType -> if (inferred is MezzeType.RecordType) {
                for ((k, t) in declared.fields) inferred.fields[k]?.let { linkDeclaredVars(t, it, out) }
            }
            is MezzeType.TypeApplication -> if (inferred is MezzeType.TypeApplication &&
                declared.args.size == inferred.args.size
            ) {
                for (i in declared.args.indices) linkDeclaredVars(declared.args[i], inferred.args[i], out)
            }
            is MezzeType.NewtypeType -> if (inferred is MezzeType.NewtypeType)
                linkDeclaredVars(declared.wrappedType, inferred.wrappedType, out)
            else -> Unit
        }
    }

    /** Rewrite a converted obligation's variables through [linkDeclaredVars]'s map. */
    private fun relinkType(type: MezzeType, link: Map<String, MezzeType>): MezzeType = when (type) {
        is MezzeType.VarType -> link[type.variable.name] ?: type
        is MezzeType.TypeApplication -> type.copy(args = type.args.map { relinkType(it, link) })
        is MezzeType.RecordType -> type.copy(fields = type.fields.mapValues { (_, t) -> relinkType(t, link) })
        else -> type
    }

    /**
     * The constructor's own payload type, written into the solved variant shape
     * where that shape left a variable.
     *
     * An UNDECLARED variant has no declaration to read a payload type from, so the
     * solver invents one and relies on unification to fill it. That does not always
     * reach the emitted type: `show { a = Grams { n = Money.(4) } }` emitted
     * `VariantType([Grams])` whose `Grams` payload was still `{ n: 'y }` — while
     * the payload EXPRESSION sitting right there was already typed
     * `{ n: Money }`. The construction is what gives the constructor its payload,
     * so the payload's own type is not a guess about the shape; it IS the shape.
     *
     * Downstream that gap is not a missing type but a wrong ANSWER: monomorphization
     * needs a concrete type argument to specialize on, derives `args=null` from a
     * payload that holds a variable, and the un-specialized body prints an erased
     * newtype as its payload (`4`, not `Money.(4)`).
     *
     * Only fills a HOLE: a payload the solved shape already resolved is left alone,
     * and a payload expression that is itself not concrete supplies nothing.
     */
    private fun payloadTypedVariant(
        solvedType: MezzeType,
        constructor: String,
        payload: TypedExpr?,
    ): MezzeType {
        if (solvedType !is MezzeType.VariantType) return solvedType
        val payloadType = payload?.type ?: return solvedType
        if (payloadType.holdsTypeVar()) return solvedType
        val current = solvedType.constructors[constructor]
        if (current != null && !current.holdsTypeVar()) return solvedType
        return solvedType.copy(constructors = solvedType.constructors + (constructor to payloadType))
    }

    private fun attachAbilityConstraints(
        expr: TypedExpr,
        constraints: List<MezzeType.FunctionAbilityConstraint>
    ): TypedExpr {
        val fnType = expr.type as? MezzeType.FunctionType ?: return expr
        val merged = fnType.copy(abilityConstraints = fnType.abilityConstraints + constraints)
        return when (expr) {
            is TypedLambda -> expr.copy(type = merged)
            else -> expr
        }
    }

    private fun buildAbilityImpl(
        decl: ParsedImplAbilityDecl,
        file: String,
        impls: MutableList<TypedAbilityImpl>,
        seedOpParams: Boolean = true,
    ) {
        val loc = decl.loc

        val abilityDecl: ParsedAbilityDecl? = when (val target = decl.target) {
            is ParsedImplTarget.Named -> {
                val abilityName = target.ability.localName
                registry.lookupAbility(abilityName)
                    ?: importContext?.importedAbilities?.get(abilityName)
                    ?: error("Ability '$abilityName' not found in declarations or imports")
            }
            is ParsedImplTarget.Inherent -> null
        }
        val abilityHash = abilityDecl?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) } ?: Hash.ZERO

        // Full class-arg tuple (concrete `abilityTypeArgs` before `for` + `targetTypes`
        // after), positional to the ability's type params ("extra params first, self
        // last"). Carrying the whole tuple lets the runtime op index disambiguate
        // class-param abilities whose impls share a receiver but differ in the
        // co-operand — e.g. `NumSub Duration for Instant` vs `NumSub Instant for
        // Instant`. Receiver-only matchers still work: the receiver stays present at
        // the ability's `receiverTypeParam` index. See project_classparam_runtime_dispatch_tuple.
        val targetTypes = decl.allTypeArgs.map { convertParsedTypeAnnotation(it) }

        val assocTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
            convertParsedTypeAnnotation(ann)
        }

        // boundary (issue #213).
        val ops = decl.operations.associate { op ->
            val isInherent = decl.target is ParsedImplTarget.Inherent
            val targetTypeForOp = if (isInherent) {
                decl.targetTypes.firstOrNull()?.let { convertParsedTypeAnnotation(it) }
            } else null
            // (#230). Pull both halves out — return-only fallback below
            // covers older cache rows / non-inherent paths.
            val solvedFn: MezzeType.FunctionType? = if (isInherent) {
                val key = "_inherent_${decl.loc}_${op.name}"
                (constraintResults[key]?.type?.let { substitution.apply(it) }
                    as? MezzeType.FunctionType)
            } else null
            val solvedReturn: MezzeType? = solvedFn?.returnType
                ?: if (isInherent) {
                    val key = "_inherent_${decl.loc}_${op.name}"
                    constraintResults[key]?.type?.let { substitution.apply(it) }
                } else null

            // An ability op is built against the ability's DECLARED op signature with
            // the impl's class-arg tuple substituted for the ability's type params
            // (`Eq.eq: { it: 'a, r: 'a } -> Bool` under `impl Eq for W` → `{ it: W,
            // r: W } -> Bool`) — exactly what ImplValidator checked the body against.
            // Building it against a fresh unknown instead left `it`/`r` and every
            // field / payload read off them untyped, so a component `==` in the body
            // fell to the structural default and never dispatched the component's
            // own impl (a newtype field's declared Eq was silently ignored). Only the
            // params are seeded from it (pendingOpParamTypes); the op's emitted
            // surface type stays the laundered one mono already specializes on.
            val declaredOpType: MezzeType.FunctionType? = abilityDecl?.let { ad ->
                val abilityOp = ad.operations.firstOrNull { it.name == op.name } ?: return@let null
                val classArgs = ad.typeParams.zip(targetTypes).toMap()
                typeVarCache.clear()
                MezzeType.FunctionType(
                    paramType = substituteTypeParamsByName(convertParsedTypeAnnotation(abilityOp.paramType), classArgs),
                    returnType = substituteTypeParamsByName(convertParsedTypeAnnotation(abilityOp.returnType), classArgs),
                )
            }
            val ctxType: MezzeType = when {
                isInherent && solvedFn != null -> solvedFn
                isInherent && targetTypeForOp != null && solvedReturn != null ->
                    MezzeType.FunctionType(
                        paramType = MezzeType.RecordType(mapOf("it" to targetTypeForOp)),
                        returnType = solvedReturn
                    )
                // The ability-impl arm. `ImplValidator` solved this very body; the
                // arrow it produced is a strictly better answer than a fresh
                // variable, which is only ever a statement that nothing is known.
                // Non-inherent by construction — the two arms above claim every
                // inherent op, and inherent ops have their own name-keyed channel.
                else -> MezzeType.VarType(TypeVar.freshInternal("op_${op.name}"))
            }
            // Every ability-impl op body is typed: a hand-written `it.q == r.q` on a
            // newtype field, or `a < b` on pattern-bound payloads in a user's
            // `impl Ord for Tree`, must dispatch the component's instance, never
            // fall to a structural compare. Two dispatch rules had to learn about
            // typed receivers for this: a return-driven codec method goes through
            // the enclosing dict even on a typed receiver (compileCall), and a
            // param typed by a SHAPE with variables (`['a]`) is not "concrete"
            // (scan). Codec bodies themselves stay untyped.
            // An op whose own signature carries a return-driven codec constraint
            // (`from_json … with Decode JsonC 'a`) dispatches `val.decode { config }`
            // by the TARGET type through that dict; a typed `val: Json` receiver would
            // make the compiler pick a decoder by receiver instead. Such ops keep the
            // untyped body until dispatch reads evidence (elaboration plan, C).
            val opCarriesCodecConstraint = op.abilityConstraints.any { c ->
                val h = registry.lookupAbility(c.abilityName)?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
                    ?: importContext?.importedAbilities?.get(c.abilityName)?.let { it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it) }
                WellKnownHashConstants.isCodecAbility(h)
            }
            // A newtype receiver USED to be excluded here. The reason was real:
            // its impl bodies exist to UNWRAP (`match it is Money.(a) -> a < b`),
            // and `patternBindingTypes` did not type `a` as the wrapped Int, so
            // the body's `a < b` dispatched Money's OWN lt through the enclosing
            // dict slot — unbounded recursion, StackOverflow
            // (NewtypeInUseRuntimeTest).
            //
            // The premise no longer holds. That arm returned `emptyMap()`,
            // dropping the inner pattern entirely; it now resolves the newtype's
            // payload through the registry, so `a` really is the wrapped type and
            // `a < b` is an Int comparison that cannot re-enter Money's lt.
            //
            // Leaving the exclusion in place was BUG 11: an unseeded body means an
            // untyped receiver, an untyped receiver lowers `<` to a dynamic field
            // read, and for a newtype — erased to its payload at runtime — that
            // silently used the WRAPPED type's ordering instead of the author's.
            // An INHERENT op has no ability declaration to read a signature from,
            // which is why it was excluded — but it does not need one: `ctxType`
            // above is already the op's own solved arrow (`{ it: <target> } ->
            // <return>`), built from the impl's target and the solve. Seeding from it
            // types `it` in the body exactly as the ability path types `it` and `r`,
            // and leaving it unseeded is the same defect BUG 11 was: an untyped
            // receiver lowers a comparison to a dynamic field read.
            val inherentOpType = if (isInherent) ctxType as? MezzeType.FunctionType else null
            val seedFrom: MezzeType? = when {
                isInherent -> inherentOpType?.paramType
                else -> declaredOpType?.paramType
            }
            val seedThisOp = seedOpParams && op.implementation is ParsedLambda &&
                seedFrom != null &&
                // Codec-flavored abilities (Encode/Decode/Format…) dispatch through
                // `config` and were shaped for untyped bodies — leave them as they are.
                !WellKnownHashConstants.isCodecAbility(abilityHash) &&
                !opCarriesCodecConstraint
            if (seedThisOp) {
                pendingOpParamTypes = (seedFrom as? MezzeType.RecordType)?.fields
            }
            val opExpr = try {
                buildExpr(op.implementation, file, ctxType)
            } finally {
                pendingOpParamTypes = null
            }
            // LAYER 1: carry the op's own `with <Ability> 'a` constraints onto its
            // compiled FunctionType so the dict-method prescan synthesizes its
            // `__dict_<method>` param. See project_impl_op_with_constraint_dropped.
            val opWithConstraints = if (op.abilityConstraints.isNotEmpty()) {
                val converted = op.abilityConstraints.map { c ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = c.abilityName,
                        typeArgs = c.typeArgs.map { convertParsedTypeAnnotation(it) },
                        abilityHash = registry.lookupAbility(c.abilityName)?.let {
                            it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it)
                        } ?: importContext?.importedAbilities?.get(c.abilityName)?.let {
                            it.contentHash ?: ParsedDeclHasher.hashAbilityDecl(it)
                        }
                    )
                }
                // Re-link the obligation's variables to the arrow they constrain.
                // `converted` and `opExpr.type` were built by separate conversions,
                // so without this the obligation names variables the signature does
                // not contain. `declaredOpType` is the bridge: it shares the
                // annotation's variable map with `converted`, and matching it
                // against the inferred arrow says which inferred variable each
                // written one became.
                val link = HashMap<String, MezzeType>()
                if (declaredOpType != null) linkDeclaredVars(declaredOpType, opExpr.type, link)
                val relinked =
                    if (link.isEmpty()) converted
                    else converted.map { c -> c.copy(typeArgs = c.typeArgs.map { relinkType(it, link) }) }
                attachAbilityConstraints(opExpr, relinked)
            } else opExpr
            op.name to opWithConstraints
        }

        val hash = decl.contentHash ?: ParsedDeclHasher.hashImplAbilityDecl(decl)

        val forallVars = decl.forallVars.map { TypeParam(it, TypeKind.Star) }

        impls.add(TypedAbilityImpl(
            hash = hash,
            abilityHash = abilityHash,
            targetTypes = targetTypes,
            forallVars = forallVars,
            associatedTypeBindings = assocTypeBindings,
            operations = ops,
            loc = loc,
            derived = decl.derived
        ))
    }

    private fun buildEffectDecl(
        decl: ParsedEffectDecl,
        file: String,
        decls: MutableList<TypedEffectDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        val loc = decl.loc
        val hash = decl.contentHash ?: ParsedDeclHasher.hashEffectDecl(decl)

        // One clear for the whole effect — see buildAbilityDecl, and
        // `ContextBuilder.addEffectOperations`, which scopes it the same way.
        typeVarCache.clear()
        val ops = decl.operations.mapIndexed { idx, op ->
            val paramType = convertParsedTypeAnnotation(op.paramType)
            val returnType = convertParsedTypeAnnotation(op.returnType)
            TypedEffectOp(op.name, idx, paramType, returnType, op.loc)
        }

        decls.add(TypedEffectDecl(hash, decl.name, ops, decl.associatedTypes, loc))
        exports[decl.name] = ExportEntry(decl.name, hash, MezzeType.VarType(TypeVar.freshWithName(decl.name)), ExportKind.EFFECT)
    }

    private fun buildPerformerDecl(
        decl: ParsedPerformerDecl,
        file: String,
        decls: MutableList<TypedPerformerDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        val loc = decl.loc
        val contentHash = decl.contentHash
            ?: implDeclarations[decl.performerName]?.contentHash
            ?: identifierContentHashes[BindingKey(null, decl.performerName)]
        val hash = decl.contentHash ?: ParsedDeclHasher.hashPerformerDecl(decl)

        val effectName = decl.effect.localName
        val effectHash = resolveEffectHash(effectName)

        // One clear for the whole parameter LIST: a performer's params belong to
        // one signature, so two of them written `'a` are the same `'a`. Clearing
        // per param made them different variables.
        typeVarCache.clear()
        val params = decl.params.map { param ->
            TypedPerformerParam(param.name, convertParsedTypeAnnotation(param.type), param.loc)
        }

        val assocTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
            convertParsedTypeAnnotation(ann)
        }

        val ops = decl.operations.associate { op ->
            val opExpr = buildExpr(op.implementation, file, MezzeType.VarType(TypeVar.freshInternal("op_${op.name}")))
            op.name to opExpr
        }

        val opHashes = ops.mapValues { (opName, opBody) ->
            val opBuilder = MezzeHashBuilder()
            opBuilder.append(byteArrayOf(HashTags.IMPL_DECLARATION))
            opBuilder.append(effectHash.hex)
            opBuilder.append(hash.hex)
            opBuilder.append(opName)
            val bodyHash = TypedASTHasher.hashExpr(opBody)
            opBuilder.append(bodyHash.hex)
            opBuilder.buildHash()
        }

        decls.add(TypedPerformerDecl(hash, effectHash, decl.performerName, params, assocTypeBindings, ops, opHashes, loc))
        exports[decl.performerName] = ExportEntry(
            decl.performerName, hash, MezzeType.VarType(TypeVar.freshWithName(decl.performerName)), ExportKind.PERFORMER
        )
    }

    private fun buildTopLevelExpr(
        decl: ParsedTopLevelExpr,
        file: String,
        decls: MutableList<TypedDecl>
    ) {
        val loc = decl.loc
        val hash = ParsedDeclHasher.hashExpr(decl.expression)
        // The generator records this expression's solved type under `_toplevel`
        // (ConstraintGenerator's ParsedTopLevelExpr arm). Minting a placeholder as
        // the context and building against THAT threw the answer away and handed
        // the body an undetermined type to work from.
        // Reads the type the generator recorded (ConstraintGenerator's
        // ParsedTopLevelExpr arm files it under `_toplevel`) instead of minting a
        // placeholder and building the body against THAT. Falls back rather than
        // failing: the recording is per-module, and emit also runs on paths that
        // did not go through that arm.
        val type = constraintResults["_toplevel"]?.let { substitution.apply(it.type) }
            ?: unreachedPosition("top-level expression with no recorded solve")
        val value = buildExpr(decl.expression, file, type)
        decls.add(TypedLetDecl(
            hash = hash,
            name = "__toplevel_expr_${decls.size}",
            typeParams = emptyList(),
            value = value,
            scheme = V2TypeScheme(emptyList(), emptyList(), type),
            isPublic = false,
            loc = loc
        ))
    }

    fun buildExpr(
        expr: ParsedExpr,
        file: String,
        // No expectation from above. A PLACEHOLDER, not a fresh variable: every
        // consumer that would guess from a type variable can tell the difference
        // (TypeVar.isPlaceholder), which is the whole point — "nobody told me" and
        // "this is polymorphic" used to be the same value.
        // NULL means "this position imposes no type", which is what it is. It used
        // to be a placeholder TypeVar — a value that IS a type, indistinguishable
        // from an ordinary variable the moment it left this function, and read
        // downstream as polymorphism. Absence is not polymorphism.
        contextType: MezzeType? = null,
    ): TypedExpr {
        val loc = expr.loc
        return when (expr) {
            is ParsedIntLit -> TypedIntLit(expr.value, MezzeType.IntType, loc)
            is ParsedNatLit -> TypedNatLit(expr.value, natLiteralType, loc)
            is ParsedFloatLit -> TypedFloatLit(expr.value, MezzeType.FloatType, loc)
            is ParsedFracLit -> TypedFracLit(expr.numerator, expr.denominator, MezzeType.FracType, loc)
            is ParsedCharLit -> TypedCharLit(expr.value, MezzeType.CharType, loc)

            is ParsedStringLit -> {
                val parts = expr.parts.map { part ->
                    when (part) {
                        is ParsedStringPart.Literal -> TypedStringPart.Literal(part.value)
                        is ParsedStringPart.Interpolation ->
                            TypedStringPart.Interpolation(buildExpr(part.expr, file, MezzeType.StringType))
                    }
                }
                TypedStringLit(parts, MezzeType.StringType, loc)
            }
            is ParsedNewtypeConstruct -> {
                // Runtime erasure: newtype construction is identity — the emitted
                // node IS the underlying value's node, so lowering unwraps for free.
                // #395: but its STATIC type must stay the `NewtypeType` wrapper, not
                // the erased underlying. A top-level binding keeps the wrapper because
                // it reads the constraint generator's authoritative type; a do-block /
                // record-field / list-element binding reads THIS node's `.type`, so we
                // stamp the solver's per-loc construction result (recorded in
                // callReturnTypes by generateNewtypeConstruction) back onto it. The
                // node's shape is unchanged, so runtime lowering is unaffected.
                val inner = buildExpr(expr.value, file, contextType)
                // The construction result type is the newtype identity — represented
                // as `TypeApplication(name)` (or a `NewtypeType`), never the bare
                // underlying. Stamp it so the wrapper survives on the erased node.
                val solvedNewtype = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                if (solvedNewtype != null) inner.withType(solvedNewtype) else inner
            }
            is ParsedVariantConstruct -> {
                if (expr.constructor == BuiltinTypes.TRUE && expr.value == null) {
                    TypedBoolLit(true, PrimitiveTypes.BOOL_TYPE, loc)
                } else if (expr.constructor == BuiltinTypes.FALSE && expr.value == null) {
                    TypedBoolLit(false, PrimitiveTypes.BOOL_TYPE, loc)
                } else {
                    val payload = expr.value?.let { buildExpr(it, file) }
                    // #182: prefer the constraint generator's per-loc inferred
                    // type for this construction site. Without this, sibling
                    // no-payload variants in the same record literal (e.g.
                    // `{ method = Get, body = None }`) collapse onto the
                    // shared default `TypeVar("unknown", 0)` (data-class
                    // equality), which the solver never pins — both fields
                    // end up with the same unresolved alias and downstream
                    // unification corrupts field-to-type alignment.
                    //
                    // But the per-loc type can stay an unsolved var for a bare
                    // no-payload variant in an unannotated record (`let order =
                    // { status = Queued }`): the solver pinned the FIELD type
                    // (`status: Status`) not this construction site. In that case
                    // fall back to the pushed `contextType` (the record field's
                    // expected type) when it is concrete, so the value reads as
                    // `Status`, not `'a`. Prefer whichever is concrete; per-loc
                    // wins when both are.
                    val perLoc = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                    val fromCtx = contextType?.let { substitution.apply(it) }
                    // MEASURED, twice, and reverted both times. `perLoc` and
                    // `fromCtx` are two solved descriptions of ONE node that name
                    // DIFFERENT type variables for the same type — that disagreement
                    // is the `flatten` elaboration row, and no rule HERE can settle
                    // it, because which side carries the variable the enclosing
                    // scheme quantifies flips from declaration to declaration:
                    //   - prefer the context whenever per-loc is still open:
                    //     closes `flatten`, opens 5 (map, map2, map_err, or_else,
                    //     zip_with) whose per-loc variable is the bound one.
                    //   - same, merged per ARGUMENT so payload-pinned args survive:
                    //     identical outcome, same 5.
                    // The fix is upstream — one elaboration, one variable per written
                    // one — see the baseline file's header.
                    val solvedType = when {
                        perLoc != null && perLoc !is MezzeType.VarType -> perLoc
                        fromCtx !is MezzeType.VarType -> fromCtx
                        else -> perLoc ?: fromCtx
                    }
                    TypedVariantConstruct(
                        expr.constructor, payload,
                        payloadTypedVariant(solvedType ?: noContextType("variant construct with no solved or context type"), expr.constructor, payload), loc,
                    )
                }
            }
            is ParsedIdentifier -> {
                val name = expr.localName
                val qualifier = expr.qualifier

                // Close solver-generated record/variant tails (an inferred
                // decode target grown field-by-field, an unannotated-param shape)
                // so `x` reads as the exact record its uses pin, not `{ …, … }`.
                // mustBeOpenRowVars still protects a genuinely-open row (a user
                // `…`, a polymorphic param), same policy as the let-decl close.
                val type = closeGeneratedRowVars(
                    resolveIdentifierType(name, qualifier, contextType),
                    mustBeOpenRowVars,
                )

                val binding = resolveBinding(name, qualifier)

                TypedVar(name, binding, type, loc)
            }
            is ParsedLambda -> {
                val seededParamTypes = pendingOpParamTypes
                pendingOpParamTypes = null
                val params = if (seededParamTypes != null) {
                    buildLambdaParams(
                        expr.params,
                        MezzeType.FunctionType(MezzeType.RecordType(seededParamTypes), MezzeType.VarType(TypeVar.freshInternal("ret"))),
                        loc,
                    )
                } else {
                    buildLambdaParams(expr.params, contextType, loc)
                }

                val savedContext = mutableMapOf<String, MezzeType?>()
                for (param in params) {
                    savedContext[param.name] = identifierContext[param.name]
                    identifierContext[param.name] = param.type
                }

                pushScope(params.map { it.name }.toMutableList(), isLambdaBoundary = true)

                val appliedCtx = contextType?.let { substitution.apply(it) }
                val returnType = when (appliedCtx) {
                    is MezzeType.FunctionType -> substitution.apply(appliedCtx.returnType)
                    else -> MezzeType.VarType(TypeVar.freshInternal("ret"))
                }

                // #378: the arrow's concrete (named) effect set is what a performed
                // value leaves unhandled at THIS function boundary. Push it so a
                // do-block `let` in the body can decide which performed effects ride
                // on its stored type. Non-function contextType -> empty (nothing
                // proven to escape).
                val arrowRow = (appliedCtx as? MezzeType.FunctionType)?.effects
                enclosingArrowEffects.addLast(arrowRow)
                val body = try {
                    buildExpr(expr.body, file, returnType)
                } finally {
                    enclosingArrowEffects.removeLast()
                }

                popScope()
                for ((name, prevType) in savedContext) {
                    if (prevType != null) identifierContext[name] = prevType
                    else identifierContext.remove(name)
                }

                // op FunctionType (with bodyResult.effects baked in, #180)
                // as the ctxType for inherent impl operations; without
                // preserving the effect row here, TypedLambda.type would
                // come out effectless and the import-side harvest would
                // see no effects. Same reasoning applies to top-level lets
                // with annotated effect arrows.
                val ctxFnType = contextType?.let { substitution.apply(it) } as? MezzeType.FunctionType
                // The pushed param type is the CALLEE's declaration, which for a
                // generic higher-order function is a variable: `seq::find`'s
                // `p: { x: 'a } -> Bool`. Taking it wholesale discarded the
                // parameter's OWN type — so `{ x: Rt } -> x.m == Get` passed to
                // `find` emitted `x` as `'a`, `x.m` could not be typed, and every
                // consumer downstream was left guessing on a receiver the program
                // had spelled out. Per parameter: keep what the parameter itself
                // knows, and take the pushed type only where it knows nothing.
                val pushedParams = (ctxFnType?.paramType as? MezzeType.RecordType)?.fields
                val paramRecordType = if (pushedParams != null) {
                    MezzeType.RecordType(
                        params.associate { p ->
                            val pushed = pushedParams[p.name]
                            val own = p.type
                            val keepOwn = own !is MezzeType.VarType &&
                                (pushed == null || pushed is MezzeType.VarType)
                            p.name to (if (keepOwn) own else (pushed ?: own))
                        } + pushedParams.filterKeys { k -> params.none { it.name == k } },
                    )
                } else {
                    ctxFnType?.paramType
                        ?: MezzeType.RecordType(params.associate { p ->
                            // Seeded ability-op params keep the laundered surface (see
                            // pendingOpParamTypes): typed locals, untyped signature.
                            p.name to (if (seededParamTypes != null) MezzeType.VarType(TypeVar.freshWithName(p.name)) else p.type)
                        })
                }
                // Propagate `with <Ability>` constraints from the enclosing arrow so
                // the compiler's prescan can see them on the TypedLambda's type when
                // deciding which method calls need dict-passing (Bug B / #86).
                val funcType = MezzeType.FunctionType(
                    paramType = paramRecordType,
                    returnType = body.type,
                    effects = ctxFnType?.effects,
                    abilityConstraints = ctxFnType?.abilityConstraints ?: emptyList()
                )

                val captures = collectCaptures(body, params.map { it.name }.toSet())

                TypedLambda(
                    params = params,
                    body = body,
                    captures = captures,
                    type = funcType,
                    loc = loc
                )
            }
            is ParsedFunctionCall -> buildFunctionCall(expr, file, contextType)
            is ParsedMemberAccess -> {
                val receiver = buildExpr(expr.receiver, file)
                val memberName = expr.member
                // Close generated tails here too: the receiver record and the
                // field read off it both carry the same inferred shape, so a
                // `a.b.c` chain reads as closed records at every level.
                val receiverType = closeGeneratedRowVars(substitution.apply(receiver.type), mustBeOpenRowVars)

                // The solver's refined type for this read (effect rows and all) wins
                // whenever it has one. Only when it left the read UNKNOWN is the type
                // taken off the receiver's declared shape — in either representation
                // (alias-expanded RecordType or nominal TypeApplication), which is what
                // an ability-impl body needs: its reads never went through the solver.
                val solvedFieldType = contextType?.let { substitution.apply(it) }
                val fieldType = when {
                    solvedFieldType != null && solvedFieldType !is MezzeType.VarType -> solvedFieldType
                    else -> (declaredShape(receiverType) as? MezzeType.RecordType)?.fields?.get(memberName)
                        ?: solvedFieldType
                        ?: noContextType("field read with no solved type and no declared shape")
                }.let { closeGeneratedRowVars(it, mustBeOpenRowVars) }

                TypedFieldAccess(
                    record = receiver,
                    field = memberName,
                    fieldIndex = -1, // filled by resolver later
                    type = fieldType,
                    loc = loc
                )
            }
            is ParsedRecordLit -> {
                // #261: if the record's expected type is a concrete record (e.g.
                // this record is a call argument and `contextType` is the callee's
                // param type), thread each field's expected type down to its value.
                // A field whose value is a lambda then gets its param types pushed
                // from the declaration, so an untyped callback param is stamped
                // concrete for ability-method dispatch.
                //
                // The record's OWN solved type (per-loc, from the solver) also
                // carries per-field types the caller-context might not — e.g. an
                // unannotated `let order = { status = Queued }` where the solver
                // pinned `status: Status` from downstream use. Prefer contextType's
                // fields when present, else fall back to the solved record's fields,
                // so a field value like `Queued` is emitted with its solved type
                // (`Status`) rather than a bare `'a`. (Hover/inlay read these.)
                val ctxFields = (contextType?.let { substitution.apply(it) } as? MezzeType.RecordType)?.fields
                val solvedFields = (callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                    as? MezzeType.RecordType)?.fields
                val expectedFields = when {
                    ctxFields == null -> solvedFields
                    solvedFields == null -> ctxFields
                    else -> solvedFields + ctxFields // contextType wins on overlap
                }
                val entries = expr.entries.map { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> {
                            val entryValue = entry.value
                            val expectedFieldType = expectedFields?.get(entry.name)
                            val fieldExpr = if (entryValue != null) {
                                if (expectedFieldType != null) buildExpr(entryValue, file, expectedFieldType)
                                else buildExpr(entryValue, file)
                            } else {
                                val type = resolveIdentifierType(entry.name, null)
                                val binding = resolveBinding(entry.name, null)
                                TypedVar(entry.name, binding, type, entry.loc)
                            }
                            TypedRecordEntry.Field(entry.name, fieldExpr, entry.loc)
                        }
                        is ParsedRecordEntry.Spread -> {
                            val spreadExpr = buildExpr(entry.expr, file)
                            TypedRecordEntry.Spread(spreadExpr, entry.loc)
                        }
                    }
                }

                // Spread first, then explicit fields — `{ ...base, x = 1 }` starts
                // from base's fields and overrides x, so a later explicit entry
                // must win. Taking only the Field entries (as this did) recorded
                // `{ x: Int }` for `{ ...point, x = 10 }`, dropping every field
                // the spread contributed. Constraint generation had it right, so
                // the program still typechecked and only the TypedAST was wrong —
                // which is what hover, inlay hints and go-to-definition read.
                val fieldTypes = LinkedHashMap<String, MezzeType>()
                for (e in entries) {
                    when (e) {
                        is TypedRecordEntry.Spread -> {
                            val spread = substitution.apply(e.expr.type)
                            if (spread is MezzeType.RecordType) fieldTypes.putAll(spread.fields)
                        }
                        is TypedRecordEntry.Field -> fieldTypes[e.name] = e.value.type
                    }
                }
                val recordType = com.mezze.language.ast.adoptRecordAlias(
                    MezzeType.RecordType(fieldTypes), closedRecordAliases)

                TypedRecord(entries, recordType, loc)
            }
            is ParsedIf -> {
                val cond = buildExpr(expr.condition, file, PrimitiveTypes.BOOL_TYPE)
                val thenBranch = buildExpr(expr.thenBranch, file, contextType)
                val elseBranch = buildExpr(expr.elseBranch, file, thenBranch.type)
                TypedIf(cond, thenBranch, elseBranch, thenBranch.type, loc)
            }
            is ParsedMatch -> {
                val scrutinee = buildExpr(expr.scrutinee, file)

                val isBoolMatch = expr.cases.size == 2 &&
                    expr.cases.any { c -> c.patterns.any { it is ParsedConstructorPattern && it.constructor == BuiltinTypes.TRUE } } &&
                    expr.cases.any { c -> c.patterns.any { it is ParsedConstructorPattern && it.constructor == BuiltinTypes.FALSE } }

                if (isBoolMatch) {
                    val trueCase = expr.cases.find { c ->
                        c.patterns.any { it is ParsedConstructorPattern && it.constructor == BuiltinTypes.TRUE }
                    }
                    val falseCase = expr.cases.find { c ->
                        c.patterns.any { it is ParsedConstructorPattern && it.constructor == BuiltinTypes.FALSE }
                    }
                    val thenBranch = buildExpr(trueCase!!.body, file, contextType)
                    val elseBranch = buildExpr(falseCase!!.body, file, thenBranch.type)
                    TypedIf(scrutinee, thenBranch, elseBranch, thenBranch.type, loc)
                } else {
                    val scrutineeShapeType = substitution.apply(scrutinee.type)
                    val cases = expr.cases.map { c ->
                        buildMatchCase(c, file, contextType, scrutineeShapeType)
                    }
                    val resultType = cases.firstOrNull()?.body?.type
                        ?: contextType?.let { substitution.apply(it) }
                        ?: unreachedPosition("match with no cases and no context")
                    // S5.0 (#376): stamp the UNERASED source-level scrutinee type
                    // (constraint-time solved, keyed by the scrutinee's Loc) onto the
                    // node. The erased `scrutinee.type` above is unchanged and still
                    // drives runtime lowering; this is the exhaustiveness oracle #364
                    // will read. Fall back to the erased type when unrecorded.
                    val scrutineeSourceType =
                        matchScrutineeTypes[expr.scrutinee.loc]?.let { substitution.apply(it) }
                            ?: scrutinee.type
                    TypedMatch(scrutinee, cases, resultType, loc, scrutineeSourceType)
                }
            }
            is ParsedDo -> buildDoBlock(expr, file, contextType)
            is ParsedSeqLit -> {
                val elementContextType = when (val ct = contextType?.let { substitution.apply(it) }) {
                    // The expected type says what the elements are. This used to read
                    // `is MezzeType.SeqType -> ct.elementType`; `[T]` is a
                    // TypeApplication since B.1, so it reads arg 0 of a `Seq`.
                    is MezzeType.TypeApplication ->
                        if (ct.constructor == BuiltinTypes.SEQ && ct.args.size == 1) ct.args[0]
                        else MezzeType.VarType(TypeVar.freshInternal("elem"))
                    else -> MezzeType.VarType(TypeVar.freshInternal("elem"))
                }
                val elements = expr.elements.map { elem ->
                    when (elem) {
                        is ParsedSeqElement.Regular ->
                            TypedSeqElement.Regular(buildExpr(elem.expr, file, elementContextType))
                        is ParsedSeqElement.Spread ->
                            TypedSeqElement.Spread(buildExpr(elem.expr, file, BuiltinTypes.seqApp(elementContextType)))
                    }
                }
                val elemType = (elements.firstOrNull() as? TypedSeqElement.Regular)?.expr?.type ?: elementContextType
                TypedSeq(elements, BuiltinTypes.seqApp(elemType), loc)
            }
            is ParsedBinaryOp -> {
                val op = expr.op.resolved
                if (op != null) {
                    val desugared = ParsedFunctionCall(
                        function = ParsedMemberAccess(expr.left, op.method, expr.loc),
                        arguments = ParsedRecordLit(listOf(ParsedRecordEntry.Field("r", expr.right, expr.loc)), expr.loc),
                        loc = expr.loc
                    )
                    buildExpr(desugared, file, contextType)
                } else {
                    TypedIntLit(0, MezzeType.IntType, loc)
                }
            }

            is ParsedPipe -> {
                val left = buildExpr(expr.left, file)
                val right = buildExpr(expr.right, file, contextType)
                TypedPipe(left, right, right.type, loc)
            }

            is ParsedPipePlaceholder -> {
                TypedVar("@", BindingRef.Local(0, 0),
                    contextType?.let { substitution.apply(it) }
                        ?: unreachedPosition("pipe placeholder with no piped-in type"), loc)
            }
            is ParsedParens -> buildExpr(expr.expr, file, contextType)
            is ParsedPerform -> {
                val handlers = expr.handlers.map { h ->
                    buildHandlerRef(h, file, loc)
                }
                val body = buildExpr(expr.body, file, contextType)
                val performType = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                    ?: body.type
                // Effect-model migration (docs/effect-model.md): source
                // fullyDischarges from the discharge channel populated at
                // constraint-gen (the perform's result value carries no residual
                // effects). Value types are bare now, so a perform loc absent from
                // the channel fully discharges by default.
                val fullyDischarges = performFullyDischarged[expr.loc] ?: true
                TypedPerform(handlers, body, performType, loc, fullyDischarges)
            }
            is ParsedEffectCall -> {
                val opName = expr.operation.localName
                val qualifier = expr.operation.qualifier

                val effectName = qualifier?.segments?.lastOrNull() ?: opName
                val effectHash = resolveEffectHash(effectName)

                val argExpr = buildExpr(expr.arguments, file)

                TypedPerformOp(effectHash, opName, argExpr,
                    contextType?.let { substitution.apply(it) }
                        ?: unreachedPosition("perform op with no solved result and no context"), loc)
            }
        }
    }


    private fun buildDoBlock(expr: ParsedDo, file: String, contextType: MezzeType?): TypedBlock {
        val pushedBlockScope = lambdaParamScopes.isEmpty()
        if (pushedBlockScope) {
            pushScope(mutableListOf(), isLambdaBoundary = false)
        }
        val stmts = mutableListOf<TypedStatement>()
        for ((srcIdx, stmt) in expr.statements.withIndex()) {
            when (stmt) {
                is ParsedStatement.Let -> {
                    val pattern = stmt.pattern
                    if (pattern is ParsedRecordPattern) {
                        // #316 (Leak A): disambiguate the synthetic destructure temp name by
                        // the SOURCE statement index, not stmts.size. A running count of
                        // already-LOWERED statements is a lowering artifact — it would shift
                        // the block's content hash if the lowerer's expansion changed, on
                        // byte-identical source. srcIdx is source-pure AND unique per statement.
                        val destrName = "${com.mezze.language.ast.MagicParams.DESTRUCTURE_PREFIX}${srcIdx}"
                        val destrHash = ParsedDeclHasher.hashExpr(stmt.value)
                        val valueType = stmt.typeAnnotation
                            ?.let { substitution.apply(convertParsedTypeAnnotation(it)) }
                            ?: MezzeType.VarType(TypeVar.freshInternal("let_destr"))
                        val value = buildExpr(stmt.value, file, valueType)
                        val resolvedType = substitution.apply(value.type)
                        identifierContext[destrName] = resolvedType
                        if (lambdaParamScopes.isNotEmpty() && destrName !in lambdaParamScopes.last()) {
                            lambdaParamScopes.last().add(destrName)
                        }
                        stmts.add(TypedStatement.Let(destrName, destrHash, resolvedType, value, stmt.loc))

                        emitRecordDestructureFields(pattern.entries, destrName, resolvedType, stmt.loc, stmts)
                    } else {
                        val name = when (pattern) {
                            is ParsedVariablePattern -> pattern.name
                            else -> "__pattern__"
                        }
                        val valueType = stmt.typeAnnotation
                            ?.let { substitution.apply(convertParsedTypeAnnotation(it)) }
                            ?: MezzeType.VarType(TypeVar.freshInternal("let_$name"))

                        val isLambdaValue = stmt.value is ParsedLambda &&
                                             pattern is ParsedVariablePattern
                        if (isLambdaValue) {
                            identifierContext[name] = valueType
                            if (lambdaParamScopes.isNotEmpty()) {
                                val currentScope = lambdaParamScopes.last()
                                if (name !in currentScope) {
                                    currentScope.add(name)
                                }
                            }
                        }

                        val value = buildExpr(stmt.value, file, valueType)
                        // Bind to the WRITTEN annotation's type when there is one, so the
                        // binding keeps the declared nominal identity (e.g. `Post`, a named
                        // record) instead of the value's bare structural shape. A record
                        // literal produces an Anonymous `RecordType`; using it here loses
                        // the name, so a later ability-method call `post.summarize {}` has a
                        // nameless receiver, `impl Summary for Post` can't be resolved at
                        // dispatch, and the call is lowered as a plain field-access that
                        // crashes at runtime (`Field not found: summarize`). Top-level lets
                        // already keep the annotated type (ConstraintGenerator's
                        // authoritativeType); this makes the do-block path consistent. The
                        // typechecker already proved the value conforms to the annotation,
                        // so preferring it is a pure identity-preservation, not a re-check.
                        // Guard: only when the annotation resolves to a concrete shape
                        // (not a bare type var, e.g. `let x : 'a = 5`), so we never trade
                        // the value's known type for an unsolved annotation var.
                        // #396 (convergence): the do-block binding's VALUE type comes
                        // from the SAME authoritative source the top-level path uses —
                        // the solver's per-binding solved type (bindLet's
                        // `solvedBindingsWithEffects`), recorded per-RHS-loc in
                        // `doBindingSolvedTypes`. That value already encodes bindLet's
                        // annotation-over-structural selection AND every facet the
                        // erased emit node dropped (unsolved var #354, newtype wrapper
                        // #395), so reading it here retires the per-facet re-derivation.
                        // The prior annotation/structural logic remains ONLY as the
                        // fallback for paths that produced no authoritative entry (no
                        // registry wired, a failed local solve, a non-variable pattern),
                        // so behaviour is unchanged where the map is absent.
                        val structuralType = substitution.apply(value.type)
                        val authoritativeType = doBindingSolvedTypes[stmt.value.loc]
                            ?.let { substitution.apply(it) }
                        val resolvedType =
                            authoritativeType
                                ?: if (stmt.typeAnnotation != null && valueType !is MezzeType.VarType) {
                                    valueType
                                } else {
                                    structuralType
                                }

                        val isRecursive = isLambdaValue && value is TypedLambda &&
                                           refsName(value.body, name)

                        identifierContext[name] = resolvedType

                        if (lambdaParamScopes.isNotEmpty()) {
                            val currentScope = lambdaParamScopes.last()
                            if (name !in currentScope) {
                                currentScope.add(name)
                            }
                        }

                        val v1LetHash = identifierContentHashes[BindingKey.unqualified(name)]
                        val contentHash = v1LetHash ?: ParsedDeclHasher.hashExpr(stmt.value)
                        // #378: a do-block `let` whose RHS PERFORMS an unhandled
                        // effect carries that effect row on the BINDING's stored
                        // type — effects ride on value types, not only on arrows.
                        // `let var = if b == 0 then Exception.throw {..} else b`
                        // gives `var : Int <Exception ..>`, not bare `Int`. The row
                        // is provenance on the STORED type only; `identifierContext`
                        // (what later `var` uses read) stays the bare value type, so
                        // downstream `var`-as-Int unification is unaffected and the
                        // effect is not double-counted onto the enclosing arrow (that
                        // row is harvested separately at the function boundary).
                        // Effect-model migration (S2): the binding's stored type is
                        // BARE. Any effect the RHS performs rides on the enclosing
                        // function's arrow (harvested at the function boundary), never
                        // on the binding — so hover shows `x : Int`, not `Int <E>`.
                        stmts.add(TypedStatement.Let(name, contentHash, resolvedType, value, stmt.loc, isRecursive))
                    }
                }
                is ParsedStatement.Expr -> {
                    val exprTyped = buildExpr(stmt.expr, file)
                    stmts.add(TypedStatement.Expr(exprTyped, stmt.loc))
                }
            }
        }
        val result = buildExpr(expr.result, file, contextType)
        if (pushedBlockScope) {
            popScope()
        }
        return TypedBlock(stmts, result, result.type, expr.loc)
    }


    private fun refsName(expr: TypedExpr, name: String): Boolean {
        if (expr is TypedVar && expr.name == name) return true
        when (expr) {
            is TypedLambda -> if (refsName(expr.body, name)) return true
            is TypedCall -> {
                if (refsName(expr.function, name)) return true
                if (refsName(expr.arg, name)) return true
            }
            is TypedIf -> {
                if (refsName(expr.condition, name)) return true
                if (refsName(expr.thenBranch, name)) return true
                if (refsName(expr.elseBranch, name)) return true
            }
            is TypedRecord -> {
                for (entry in expr.entries) {
                    when (entry) {
                        is TypedRecordEntry.Field -> if (refsName(entry.value, name)) return true
                        is TypedRecordEntry.Spread -> if (refsName(entry.expr, name)) return true
                    }
                }
            }
            is TypedFieldAccess -> if (refsName(expr.record, name)) return true
            is TypedBlock -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is TypedStatement.Let -> if (refsName(stmt.value, name)) return true
                        is TypedStatement.Expr -> if (refsName(stmt.expr, name)) return true
                    }
                }
                if (refsName(expr.result, name)) return true
            }
            is TypedMatch -> {
                if (refsName(expr.scrutinee, name)) return true
                for (case in expr.cases) {
                    if (refsName(case.body, name)) return true
                    val guard = case.guard
                    if (guard != null && refsName(guard, name)) return true
                }
            }
            is TypedRecordUpdate -> {
                if (refsName(expr.record, name)) return true
                for (u in expr.updates) {
                    when (u) {
                        is TypedRecordEntry.Field -> if (refsName(u.value, name)) return true
                        is TypedRecordEntry.Spread -> if (refsName(u.expr, name)) return true
                    }
                }
            }
            else -> {}  // other types (literals, etc) — assume no recursion
        }
        return false
    }

    private fun collectCaptures(body: TypedExpr, paramNames: Set<String>): List<TypedCapture> {
        val captures = mutableListOf<TypedCapture>()
        val seen = mutableSetOf<String>()
        walkForCaptures(body, paramNames, captures, seen)
        return captures
    }

    private fun walkForCaptures(expr: TypedExpr, paramNames: Set<String>, captures: MutableList<TypedCapture>, seen: MutableSet<String>) {
        if (expr is TypedVar) {
            val binding = expr.binding
            if (binding is BindingRef.Local && binding.depth > 0 && expr.name !in seen && expr.name !in paramNames) {
                seen.add(expr.name)
                captures.add(TypedCapture(expr.name, expr.type, binding))
            }
            if (binding is BindingRef.ByHash && expr.name !in seen && expr.name !in paramNames) {
                if (lambdaParamScopes.size >= 2) {
                    for (i in 0 until lambdaParamScopes.size - 1) {
                        if (expr.name in lambdaParamScopes[i]) {
                            val depth = lambdaDepthFromScope(i)
                            if (depth > 0) {
                                seen.add(expr.name)
                                val index = lambdaParamScopes[i].indexOf(expr.name)
                                captures.add(TypedCapture(expr.name, expr.type, BindingRef.Local(depth, index)))
                            }
                            break
                        }
                    }
                }
            }
            return
        }
        if (expr is TypedLambda) {
            for (cap in expr.captures) {
                if (cap.name !in paramNames && cap.name !in seen) {
                    val capBinding = cap.source
                    if (capBinding is BindingRef.Local && capBinding.depth > 1) {
                        seen.add(cap.name)
                        captures.add(TypedCapture(cap.name, cap.type, BindingRef.Local(capBinding.depth - 1, capBinding.index)))
                    }
                }
            }
            return
        }
        if (expr is TypedCall) { walkForCaptures(expr.function, paramNames, captures, seen); walkForCaptures(expr.arg, paramNames, captures, seen); return }
        if (expr is TypedIf) { walkForCaptures(expr.condition, paramNames, captures, seen); walkForCaptures(expr.thenBranch, paramNames, captures, seen); walkForCaptures(expr.elseBranch, paramNames, captures, seen); return }
        if (expr is TypedBlock) {
            for (s in expr.statements) { if (s is TypedStatement.Let) walkForCaptures(s.value, paramNames, captures, seen) else if (s is TypedStatement.Expr) walkForCaptures(s.expr, paramNames, captures, seen) }
            walkForCaptures(expr.result, paramNames, captures, seen); return
        }
        if (expr is TypedRecord) { for (entry in expr.entries) { when (entry) { is TypedRecordEntry.Field -> walkForCaptures(entry.value, paramNames, captures, seen); is TypedRecordEntry.Spread -> walkForCaptures(entry.expr, paramNames, captures, seen) } }; return }
        if (expr is TypedFieldAccess) { walkForCaptures(expr.record, paramNames, captures, seen); return }
        if (expr is TypedMatch) { walkForCaptures(expr.scrutinee, paramNames, captures, seen); for (c in expr.cases) { c.guard?.let { walkForCaptures(it, paramNames, captures, seen) }; walkForCaptures(c.body, paramNames, captures, seen) }; return }
        if (expr is TypedPerform) { walkForCaptures(expr.body, paramNames, captures, seen); return }
        if (expr is TypedPerformOp) { walkForCaptures(expr.arg, paramNames, captures, seen); return }
        if (expr is TypedVariantConstruct) { expr.payload?.let { walkForCaptures(it, paramNames, captures, seen) }; return }
        if (expr is TypedRecordUpdate) { walkForCaptures(expr.record, paramNames, captures, seen); for (entry in expr.updates) { if (entry is TypedRecordEntry.Field) walkForCaptures(entry.value, paramNames, captures, seen) }; return }
        if (expr is TypedPipe) { walkForCaptures(expr.left, paramNames, captures, seen); walkForCaptures(expr.right, paramNames, captures, seen); return }
        if (expr is TypedStringLit) {
            // #84: interpolation parts contain sub-expressions that may reference
            // captured vars (e.g. "$(prefix)/$(x)" inside a lambda). Without this
            // recursion, `prefix` would not be marked as a capture and the lambda
            // body would read a null value at runtime.
            for (part in expr.parts) {
                if (part is TypedStringPart.Interpolation) walkForCaptures(part.expr, paramNames, captures, seen)
            }
            return
        }
        if (expr is TypedSeq) {
            // #84 followup: seq literals `[a, b, c]` inside a lambda body contain
            // sub-expressions that may reference captured vars. Without this
            // recursion, e.g. `[path, x.name]` would miss `path` as a capture.
            for (elem in expr.elements) {
                when (elem) {
                    is TypedSeqElement.Regular -> walkForCaptures(elem.expr, paramNames, captures, seen)
                    is TypedSeqElement.Spread -> walkForCaptures(elem.expr, paramNames, captures, seen)
                }
            }
            return
        }
    }

    /** Bind [pattern]'s type variables by walking it against [value] in parallel.
     *  Same shape as the compiler's harvest of the same name; only the positions
     *  a variable occupies are recorded, never a name match. */
    /**
     * Match [pattern] against [value] and record what each of the pattern's
     * VARIABLES stands for.
     *
     * Keyed on the variable itself. It used to key on `variable.name`, which
     * silently merged two distinct variables that happened to share a spelling —
     * reachable, and measured twice on `out` (the `having 'out` associated type).
     */
    internal fun harvestVarBindings(pattern: MezzeType, value: MezzeType, out: MutableMap<TypeVar, MezzeType>) {
        if (pattern is MezzeType.VarType) {
            out.putIfAbsent(pattern.variable, value)
            return
        }
        when {
            pattern is MezzeType.RecordType && value is MezzeType.RecordType ->
                for ((k, pt) in pattern.fields) value.fields[k]?.let { harvestVarBindings(pt, it, out) }
            pattern is MezzeType.FunctionType && value is MezzeType.FunctionType -> {
                harvestVarBindings(pattern.paramType, value.paramType, out)
                harvestVarBindings(pattern.returnType, value.returnType, out)
            }
            pattern is MezzeType.TypeApplication && value is MezzeType.TypeApplication ->
                if (pattern.constructor == value.constructor && pattern.args.size == value.args.size)
                    pattern.args.zip(value.args).forEach { (p, v) -> harvestVarBindings(p, v, out) }
            pattern is MezzeType.VariantType && value is MezzeType.VariantType ->
                for ((k, pt) in pattern.constructors)
                    if (pt != null) value.constructors[k]?.let { harvestVarBindings(pt, it, out) }
            else -> Unit
        }
    }

    private fun buildLambdaParams(
        params: ParsedLambdaParams,
        contextType: MezzeType?,
        loc: Loc
    ): List<TypedParam> {
        val resolvedContext = contextType?.let { substitution.apply(it) }
        return when (params) {
            is ParsedLambdaParams.Typed -> {
                // Read the param's type off the SOLVED context first, exactly as the
                // untyped branch below does.
                //
                // Converting the annotation here instead mints a variable that exists
                // NOWHERE in the binding's own type — `typeVarCache.clear()` per field
                // guarantees it is fresh — so `{ x: 'a } -> …` and `{ x } -> …`
                // disagreed about which variable the parameter is. Monomorphization
                // substitutes a binding's quantified params BY NAME, so a body holding
                // the annotation's private variable is never specialized: the two forms
                // below print the same value differently.
                //
                //     let show = { x } -> x.to_dbg {}          -- Money.(4)
                //     let show = { x: 'a } -> x.to_dbg {}      -- 4
                //
                // An annotation must never change what a program means, and here
                // writing one made the answer WRONG rather than merely redundant. The
                // annotation has already done its work — the solve unified it with the
                // context, so the context is at least as specific — and is consulted
                // below only where there is no context to read (a lambda in a position
                // with no expected type), which is the one case it carries information
                // the context does not.
                val contextParamFields =
                    ((resolvedContext as? MezzeType.FunctionType)?.paramType as? MezzeType.RecordType)?.fields
                // One scope for the whole PARAMETER LIST, not one per parameter: a
                // lambda's params belong to a single signature, so two of them
                // written `'a` are the same `'a`. Clearing per field made them
                // different variables.
                typeVarCache.clear()
                params.fields.map { field ->
                    val type = contextParamFields?.get(field.name)
                        ?: substitution.apply(convertParsedTypeAnnotation(field.type))
                    TypedParam(field.name, type, field.loc)
                }
            }
            is ParsedLambdaParams.Untyped -> {
                val contextParamType = when (resolvedContext) {
                    is MezzeType.FunctionType -> resolvedContext.paramType
                    else -> null
                }
                // Per-name locs come from the parser (#232). Older inputs
                // may not have them populated — fall back to the lambda's
                // own loc in that case so behaviour is no worse than
                // before.
                val perNameLocs = params.nameLocs
                // What the SOLVE determined for this lambda's parameters. An
                // inline callback passed to a callee whose type nothing declares —
                // `__native_seq_sort_by { …, cmp = { a, b } -> … }` — gets no
                // expected type pushed in, and minting a variable per parameter
                // here threw away an answer the solver already had: the body says
                // what `a` and `b` are. Keyed by the lambda's own Loc, so it cannot
                // pick up a same-named parameter of a different lambda.
                val solvedParams = lambdaParamTypes[loc]
                params.names.mapIndexed { idx, name ->
                    val fromContext = (contextParamType as? MezzeType.RecordType)?.fields?.get(name)
                    val fromSolve = solvedParams?.get(name)
                        ?.let { substitution.apply(it) }
                        // A solved VARIABLE is still the answer — for the
                        // comparator above it is the declaration's own element
                        // variable, which is bound where a fresh one is not. Only
                        // an unknown placeholder is worth refusing.
                        ?.takeIf { !it.holdsUnknownPlaceholder() }
                    val type = fromContext
                        ?: fromSolve
                        ?: MezzeType.VarType(TypeVar.freshWithName(name))
                    val paramLoc = perNameLocs.getOrNull(idx) ?: loc
                    TypedParam(name, type, paramLoc)
                }
            }
        }
    }

    private fun buildHandlerRef(
        handler: ParsedPerformHandler,
        file: String,
        loc: Loc
    ): TypedHandlerRef {
        val performerName = handler.performer.localName

        val implDecl = implDeclarations[performerName]
        val importedImpl = importContext?.importedImpls?.get(performerName)
        // No Hash.ZERO fallback: both hashes land on HandlerBindIR →
        // HandlerChainEntry, and two chain entries carrying ZERO would
        // silently collide on the strict (effect_hash, op_name) filter —
        // the same misroute pattern as #246/#55. If a performer or its
        // effect can't be resolved here, the perform site is structurally
        // broken; fail loud so the caller surfaces a real typecheck error
        // instead of shipping a poisoned handler entry.
        val performerHash = identifierContentHashes[BindingKey(null, performerName)]
            ?: implDecl?.contentHash
            ?: importedImpl?.contentHash
            ?: implDecl?.let { ParsedDeclHasher.hashPerformerDecl(it) }
            ?: error("Performer '$performerName' has no content hash — cannot build handler chain entry")

        val effectName = implDecl?.effect?.localName
            ?: importedImpl?.effect?.localName
            ?: performerToEffect[performerName]
            ?: error("Performer '$performerName' has no resolvable effect — cannot build handler chain entry")
        val effectHash = resolveEffectHash(effectName)

        val paramsExpr = handler.params?.let { buildExpr(it, file) }

        // Detect scoped effects: performer has an initialize_scope op.
        val resolvedDecl = implDecl ?: importedImpl
        val isScoped = resolvedDecl?.operations?.any {
            it.name == com.mezze.language.ast.ScopedEffectConstants.INITIALIZE_SCOPE
        } ?: false

        return TypedHandlerRef(
            performerHash = performerHash,
            effectHash = effectHash,
            performerName = performerName,
            params = paramsExpr,
            loc = loc,
            isScoped = isScoped
        )
    }

    /**
     * The type of a referenced name.
     *
     * [expected] is what the POSITION requires — the caller's `contextType`. When a
     * name resolves nowhere the old code invented a placeholder; the expected type
     * is a real answer and is preferred. This is what types `__native_*`
     * references: a native is in no binding table, so every one of them used to
     * emit as an undetermined type, and at a call site the position's type is
     * `{args} -> <solved return>`, built from the call's own solved parts.
     */
    /**
     * The position imposed no type and nothing else determined one.
     *
     * This is the honest last resort, and it is deliberately NOT the default for
     * `buildExpr`'s `contextType` — absence of a context is `null`, which is what
     * it is. Minting one placeholder for every context-free build made a single
     * reason cover every position, so the gate could not tell which one gave up.
     */
    internal fun noContextType(reason: String): MezzeType =
        MezzeType.VarType(TypeVar.unknown(reason))

    /**
     * A position emit must never reach without a type.
     *
     * These eight were `noContextType(...)` placeholders too. MEASURED across
     * `:typechecker:test`, `:interpreter:test`, `:lsp:test` and `:cli:test` — a run
     * that minted roughly 920,000 placeholders in total — each of them minted
     * ZERO. Nothing in the corpus reaches them.
     *
     * An unreached last resort is not a safety net. A placeholder leaves emit
     * indistinguishable from an ordinary type variable, and downstream reads a
     * type variable as POLYMORPHISM and guesses from it — that is how a variant
     * comparison came to be answered by `Eq Int`'s operation. Reaching one of
     * these means emit ran on something that should not have type-checked, so the
     * honest answer is to say so at the position rather than invent a type and
     * fail somewhere far away.
     *
     * Same move this file already made for three of the original six reasons. The
     * NINE positions that do mint keep their placeholder — they are the remaining
     * elaboration work and are listed in `UnknownPlaceholderGateTest`.
     */
    internal fun unreachedPosition(position: String): Nothing =
        throw com.mezze.language.typechecker.TypeCheckError(
            message = "Internal compiler error: emit reached a position with no type — " +
                "$position. This is a bug in the compiler, not an error in your program — " +
                "please report it. Re-run with --verbose for the full stack trace.",
            errorType = com.mezze.language.typechecker.ErrorType.INTERNAL_ERROR,
            code = com.mezze.language.parsedast.diagnostics.ErrorCode.I0003_INTERNAL_EMIT_FAILURE,
        )

    private fun resolveIdentifierType(
        name: String,
        qualifier: ParsedModulePath?,
        expected: MezzeType? = null,
    ): MezzeType {
        fun giveUp(reason: String): MezzeType {
            val e = expected
            if (e != null && !e.holdsUnknownPlaceholder()) return substitution.apply(e)
            return MezzeType.VarType(TypeVar.unknown(reason))
        }
        // shape is still visible. See #233.
        if (qualifier != null) {
            val qualifiedKey = "${qualifier.shortName}::$name"
            importContext?.importedBindings?.get(qualifiedKey)?.type?.let {
                return substitution.apply(it)
            }
            importContext?.importedBindings?.get(name)?.type?.let {
                return substitution.apply(it)
            }
            // Also measured reachable — the same 58 failures. An unresolved
            // qualified name was expected to be a typecheck error raised before
            // emit; it is not, at least on the prelude-bearing path.
            return giveUp("qualified identifier not in imports")
        }

        identifierContext[name]?.let { return substitution.apply(it) }

        constraintResults[name]?.let { result ->
            return substitution.apply(result.type)
        }

        importContext?.importedBindings?.get(name)?.type?.let {
            return substitution.apply(it)
        }

        // An intrinsic is not an unresolved name. `MagicParams` states the
        // contract in its own doc — a native FFI symbol is "given a fresh/bottom
        // type and treated as an intrinsic (no undefined-variable error)" — and
        // the symbol is bound by the native registry at codegen, not by any
        // module's bindings. There is nothing here to resolve and nothing was
        // given up on.
        //
        // MEASURED on :typechecker:test: 29,983 of the 31,569 mints at this
        // position were `__native_*` or `crash` — 95% — so the entry that reads
        // "identifier resolved nowhere" was almost entirely symbols working as
        // designed, and the ~1,600 real failures in it were invisible.
        //
        // This SPLITS the position; it does not fix it. An intrinsic still gets
        // a placeholder, because emit genuinely has no type for one:
        // `NativeSignatures` carries IR slot kinds rather than MezzeTypes, and
        // is populated by the interpreter, which the typechecker compiles
        // before. Giving them a plain fresh variable instead was TRIED and is
        // worse — `ElaborationInvariants` collects unbound variables by name and
        // exempts the placeholder's reserved `unknown`, so 438 stdlib native
        // wrappers (`impl Bool.and`, `impl Float.add`, …) turned into new
        // elaboration gaps. Real removal needs native signatures carried as
        // types.
        if (MagicParams.isIntrinsicSymbol(name)) {
            return giveUp("intrinsic symbol with no type from the native registry")
        }

        return giveUp("identifier resolved nowhere and the position expects nothing")
    }

    private fun resolveBinding(name: String, qualifier: ParsedModulePath?): BindingRef {
        if (qualifier == null) {
            for (i in lambdaParamScopes.indices.reversed()) {
                val scope = lambdaParamScopes[i]
                if (name in scope) {
                    val depth = lambdaDepthFromScope(i)
                    val index = scope.indexOf(name)  // position within scope
                    return BindingRef.Local(depth, index)
                }
            }
        }

        val hashes = effectiveContentHashes

        if (qualifier != null) {
            val qualKey = BindingKey(qualifier.shortName, name)
            val hash = hashes[qualKey]
            if (hash != null) return BindingRef.ByHash(hash)
        }

        val unqualKey = BindingKey.unqualified(name)
        val localHash = hashes[unqualKey]
        if (localHash != null) {
            return BindingRef.ByHash(localHash)
        }

        importContext?.importedBindings?.get(name)?.let { binding ->
            return BindingRef.ByHash(binding.contentHash)
        }

        if (com.mezze.language.ast.MagicParams.isIntrinsicSymbol(name)) {
            return BindingRef.Native(name)
        }

        return BindingRef.Local(0, 0)
    }

    internal fun isTailCallLocation(loc: Loc): Boolean {
        return loc in tailCallLocations || tailCallLocations.any { tl ->
            tl.startLine == loc.startLine && tl.startCol == loc.startCol && tl.file == loc.file
        }
    }

    internal fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

                if (annotation.name.isTypeVarName()) {
                    val typeVar = typeVarCache.getOrPut(annotation.name) {
                        TypeVar.freshWithName(annotation.name)
                    }
                    MezzeType.VarType(typeVar)
                } else {
                    // Stamp the declared type's content hash (registry has the decl,
                    // hash is always computable) so the emitted TypeApplication carries
                    // real nominal identity (Declared), never Named. Mirrors the
                    // ability-hash resolution below.
                    WellKnownTypeApp.of(
                        annotation.name, emptyList(),
                        typeHash = registry.lookupType(annotation.name)?.let { d ->
                            d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d)
                        }
                    )
                }
            }
            is ParsedFunctionType -> {
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertParsedTypeAnnotation(it) },
                        associatedTypeBindings = constraint.associatedTypeBindings.mapValues { (_, ta) ->
                            convertParsedTypeAnnotation(ta)
                        },
                        abilityHash = registry.lookupAbility(constraint.abilityName)?.contentHash
                            ?: registry.resolveAbilityHash(constraint.abilityName)
                    )
                }
                MezzeType.FunctionType(
                    paramType = convertParsedTypeAnnotation(annotation.paramType),
                    returnType = convertParsedTypeAnnotation(annotation.returnType),
                    abilityConstraints = abilityConstraints
                )
            }
            is ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertParsedTypeAnnotation(entry.type)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is ParsedVariantType -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertParsedTypeAnnotation(it) }
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is ParsedTypeApp -> {
                WellKnownTypeApp.of(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertParsedTypeAnnotation(it) },
                    typeHash = registry.lookupType(annotation.constructor)?.let { d ->
                        d.contentHash ?: ParsedDeclHasher.hashTypeDecl(d)
                    }
                )
            }
            is ParsedSeqType -> {
                BuiltinTypes.seqApp(convertParsedTypeAnnotation(annotation.elementType))
            }
            is ParsedQualifiedType -> {
                WellKnownTypeApp.of(
                    constructor = annotation.modulePath.joinToString("::") + "::" + annotation.typeName,
                    args = annotation.args.map { convertParsedTypeAnnotation(it) }
                )
            }
        }
    }

    private fun buildImports(file: String): List<TypedImport> {
        val ctx = importContext ?: return emptyList()
        return ctx.importedBindings.map { (_, binding) ->
            TypedImport(
                localName = binding.name,
                originalName = binding.originalName,
                moduleHash = MezzeHashBuilder().also { it.append(binding.modulePath) }.buildHash(),
                declHash = binding.contentHash,
                type = binding.type,
                kind = ExportKind.FUNCTION
            )
        }
    }

    private fun emitRecordDestructureFields(
        entries: List<ParsedRecordPatternEntry>,
        parentVarName: String,
        parentType: MezzeType,
        loc: Loc,
        stmts: MutableList<TypedStatement>
    ) {
        walkRecordDestructure(entries, parentVarName, parentType, loc,
            emitBinding = { name, hash, type, access, bindingLoc ->
                identifierContext[name] = type
                // Emit the field binding with the FIELD's own token loc, not the
                // whole-statement loc — so hover on `host` in `let { host, port,
                // ... } = prod` resolves to THIS binding (`host : Str`) rather than
                // matching the synthetic `__destr_N` scrutinee binding (which keeps
                // the wide statement loc). Without a per-field loc every binding
                // shares one span and the first (synthetic) one wins the lookup.
                stmts.add(TypedStatement.Let(name, hash, type, access, bindingLoc))
            },
            emitNested = { nestedEntries, fieldName, fieldType, fieldAccess ->
                // #316 (Leak A): name the nested temp by its SOURCE field path
                // (parent + field), not stmts.size. The path uniquely identifies this
                // sub-record within the destructure and is stable across lowering changes;
                // it also feeds fieldHash below, so the content hash stays source-pure.
                val nestedVarName = "__destr_${parentVarName}_${fieldName}"
                identifierContext[nestedVarName] = fieldType
                val nestedHash = fieldHash(parentVarName, nestedVarName)
                stmts.add(TypedStatement.Let(nestedVarName, nestedHash, fieldType, fieldAccess, loc))
                emitRecordDestructureFields(nestedEntries, nestedVarName, fieldType, loc, stmts)
            }
        )
    }

    private fun emitRecordDestructureDeclFields(
        entries: List<ParsedRecordPatternEntry>,
        parentVarName: String,
        parentType: MezzeType,
        loc: Loc,
        isPublic: Boolean,
        decls: MutableList<TypedDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        walkRecordDestructure(entries, parentVarName, parentType, loc,
            emitBinding = { name, hash, type, access, bindingLoc ->
                val scheme = V2TypeScheme(emptyList(), emptyList(), type)
                // Field's own token loc (see emitRecordDestructureFields) so hover
                // resolves to this binding, not the synthetic scrutinee decl.
                decls.add(TypedLetDecl(hash, name, emptyList(), access, scheme, isPublic, bindingLoc))
                if (isPublic) {
                    exports[name] = ExportEntry(name, hash, type, ExportKind.FUNCTION)
                }
            },
            emitNested = { nestedEntries, fieldName, fieldType, fieldAccess ->
                // #316 (Leak A): source field path, not decls.size (see emitRecordDestructureFields).
                val nestedVarName = "__destructured_${parentVarName}_${fieldName}"
                val nestedHash = fieldHash(parentVarName, nestedVarName)
                val nestedScheme = V2TypeScheme(emptyList(), emptyList(), fieldType)
                decls.add(TypedLetDecl(nestedHash, nestedVarName, emptyList(), fieldAccess, nestedScheme, false, loc))
                emitRecordDestructureDeclFields(nestedEntries, nestedVarName, fieldType, loc, isPublic, decls, exports)
            }
        )
    }

    private fun walkRecordDestructure(
        entries: List<ParsedRecordPatternEntry>,
        parentVarName: String,
        parentType: MezzeType,
        loc: Loc,
        emitBinding: (name: String, hash: Hash, type: MezzeType, access: TypedFieldAccess, bindingLoc: Loc) -> Unit,
        emitNested: (entries: List<ParsedRecordPatternEntry>, fieldName: String, fieldType: MezzeType, fieldAccess: TypedFieldAccess) -> Unit
    ) {
        for (entry in entries) {
            val fieldName = entry.name
            val fieldType = when (parentType) {
                is MezzeType.RecordType -> parentType.fields[fieldName] ?: MezzeType.VarType(TypeVar.freshInternal("field_$fieldName"))
                else -> MezzeType.VarType(TypeVar.freshInternal("field_$fieldName"))
            }
            val parentBinding = resolveBinding(parentVarName, null)
            val parentVar = TypedVar(parentVarName, parentBinding, parentType, loc)

            // The binder's own token loc (narrow) — see #328. Used both for the
            // emitted binding AND for its field-access value, so the binder-name
            // hover branch (`cursor before value start`) fires and findInExpr does
            // not swallow the token with a wide-loc `__destr_N.field` expression.
            val p = entry.pattern
            val bindingLoc = when (p) {
                is ParsedVariablePattern -> p.loc
                else -> entry.loc
            }
            val fieldAccess = TypedFieldAccess(parentVar, fieldName, -1, fieldType, bindingLoc)

            when (p) {
                // Punned `{ host }` (null pattern) and any non-variable leaf.
                null -> emitBinding(fieldName, fieldHash(parentVarName, fieldName), fieldType, fieldAccess, bindingLoc)
                // Renamed `{ host = h }`: the binder `h` is the variable pattern.
                is ParsedVariablePattern -> emitBinding(p.name, fieldHash(parentVarName, p.name), fieldType, fieldAccess, bindingLoc)
                is ParsedRecordPattern -> emitNested(p.entries, fieldName, fieldType, fieldAccess)
                else -> emitBinding(fieldName, fieldHash(parentVarName, fieldName), fieldType, fieldAccess, bindingLoc)
            }
        }
    }

    private fun fieldHash(parentVarName: String, name: String): Hash =
        MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(name) }.buildHash()

    internal fun resolveEffectHash(effectName: String): Hash {
        val decl = registry.lookupEffect(effectName)
            ?: importContext?.importedEffects?.get(effectName)
            ?: error("Effect '$effectName' not found in declarations or imports")
        return decl.contentHash ?: ParsedDeclHasher.hashEffectDecl(decl)
    }

    /**
     * #378: build the effect row a do-block `let` binding's RHS PERFORMS, if any.
     *
     * The emitted RHS value carries no effect row on its own type (a
     * TypedPerformOp emits the bare VALUE type; the row is harvested onto the
     * enclosing arrow separately). To surface the performed effect on the
     * binding's stored type we recover it structurally: every unhandled
     * TypedPerformOp reachable from the RHS contributes its effect name. A
     * perform is "handled" here only when wrapped by a TypedPerform whose
     * handlers cover that effect hash — those are discharged and must NOT ride on
     * the binding. Returns null when the RHS performs nothing (pure binding stays
     * bare, no spurious `<*>` row).
     */
    private fun performedRowForBindingRhs(rhs: TypedExpr): MezzeType.EffectRow? {
        val hashToName = mutableMapOf<Hash, String>()
        fun registerName(name: String) {
            val h = try { resolveEffectHash(name) } catch (e: IllegalStateException) { return }
            hashToName[h] = name
        }
        registry.effectDeclarations.keys.forEach { registerName(it) }
        importContext?.importedEffects?.keys?.forEach { registerName(it) }

        val performed = linkedSetOf<String>()
        fun walk(e: TypedExpr, handled: Set<Hash>) {
            when (e) {
                is TypedPerformOp -> {
                    if (e.effectHash !in handled) {
                        hashToName[e.effectHash]?.let { performed.add(it) }
                    }
                    walk(e.arg, handled)
                }
                is TypedPerform -> {
                    val nowHandled = handled + e.handlers.map { it.effectHash }
                    e.handlers.forEach { it.params?.let { p -> walk(p, handled) } }
                    walk(e.body, nowHandled)
                }
                else -> e.forEachChild { walk(it, handled) }
            }
        }
        walk(rhs, emptySet())
        if (performed.isEmpty()) return null

        // Keep only effects still UNHANDLED at the enclosing function boundary.
        // An effect an enclosing `perform` discharges never reaches the arrow, so
        // it is absent from this set and must NOT ride on the binding (e.g. a
        // `let a = Async.spawn {..}` under `perform LoomAsync` stays bare — the
        // #362 conservation counterpart: the binding advertises only what the
        // function actually re-emits). No enclosing frame -> attach nothing.
        val arrowRow = enclosingArrowEffects.lastOrNull() ?: return null
        val unhandled = arrowRow.allEffectNames
        val riders = performed.filter { it in unhandled }
        if (riders.isEmpty()) return null

        // Carry the arrow's associated-type bindings for these effects so the
        // binding reads `Int <Exception having 'error = Str>`, matching the row
        // the function boundary harvested rather than a bare `<Exception>`.
        val arrowInstances = arrowRow.effectInstances.associateBy { it.name }
        val instances = riders.mapNotNull { arrowInstances[it] }.toSet()
        val simpleNames = riders.filter { it !in arrowInstances.keys }.toSet()
        return MezzeType.EffectRow(
            effects = simpleNames,
            effectInstances = instances,
            rowVar = null,
            performed = true,
        )
    }

}
