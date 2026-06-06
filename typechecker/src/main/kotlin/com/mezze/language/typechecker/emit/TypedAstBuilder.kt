package com.mezze.language.typechecker.emit

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.parser.MezzeParser as ParserBridge
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.typedast.*
import com.mezze.language.typedast.TypeScheme as V2TypeScheme
import com.mezze.language.typechecker.NativeFunctionTypes
import com.mezze.language.typechecker.constraints.ConstraintResult
import com.mezze.language.typechecker.constraints.ConstraintResult.MethodCallInfo
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.module.ModuleImportContext
import com.mezze.language.typechecker.solving.Substitution
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.solving.closeGeneratedRowVars

/**
 * TypedAstBuilder — walks ParsedModule + Substitution + ConstraintResults
 * and directly produces TypedModule (from the typedast package).
 *
 * This is the primary type resolution pass.
 * It reads from ParsedAST (not v1 AST) and has no dependency on the v1 AST types.
 *
 * Key design:
 * - Reads ParsedExpr, not v1 Expression
 * - Re-derives types by looking up constraintResults and identifierContext
 * - Applies substitution to resolve type variables to concrete types
 * - Produces TypedModule suitable for the Monomorphizer
 *
 * Type resolution strategy:
 * 1. Literals → known type
 * 2. Identifiers → lookup in identifierContext (populated as let-bindings are processed)
 * 3. Top-level bindings → look up in constraintResults by binding name
 * 4. All types → apply substitution to resolve type variables
 */
class TypedAstBuilder(
    private val substitution: Substitution,
    private val constraintResults: Map<String, ConstraintResult>,
    private val abilityImpls: Map<Hash, TypingContext.AbilityImplInfo>,
    private val typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,
    private val abilityDeclarations: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>,
    private val effectDeclarations: Map<String, com.mezze.language.parsedast.ParsedEffectDecl>,
    private val performerToEffect: Map<String, String>,
    private val importContext: ModuleImportContext?,
    private val identifierContentHashes: Map<BindingKey, Hash>,
    private val implDeclarations: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl>,
    private val tailCallLocations: Set<com.mezze.language.parsedast.Loc>,
    private val mustBeOpenRowVars: Set<RowVar> = emptySet(),
    private val methodCallResolutions: Map<Pair<Loc, String>, MethodCallInfo> = emptyMap(),
    // Per-call-site return types from the constraint solver. Keyed by the
    // ParsedFunctionCall's loc; values are the fresh return-type vars that the
    // constraint solver unified with the actual return type. Substituting
    // through the global substitution gives the monomorphized return type at
    // that specific call site — the type the TypedCall's `.type` should carry.
    // Without this, qualified-import calls (`seq::filter { ... }`) only know
    // their generic scheme, and the call's `.type` falls back to `unknown`,
    // breaking LSP per-segment chain inlay hints.
    private val callReturnTypes: Map<Loc, MezzeType> = emptyMap(),
    // #195 mono-fix #1: per-match-case binding name → solver TyVar. The
    // constraint generator records the SAME fresh TyVars that PatternConstraints
    // allocated and HasField unified with concrete types; applying `substitution`
    // here gives the resolved type for each binding. Used in buildMatchCase to
    // populate PatternBinding.type + identifierContext for guard/body emit
    // instead of constructing fresh disconnected placeholders.
    private val matchCaseBindings: Map<Loc, Map<String, MezzeType>> = emptyMap(),
    // Locs of `ParsedStatement.Let` nodes synthesized by the do-bind desugar
    // from a `pat <- expr` source. Used to set `TypedStatement.Let.isBind = true`
    // — LSP consults it for hover position-lookup on the bind pattern name.
    private val bindLetLocs: Set<Loc> = emptySet(),
) {

    /**
     * Local identifier type context — populated as we process let-bindings.
     *
     * Maps binding name → MezzeType (after substitution applied).
     * Populated as we process let-bindings in order.
     */
    private val identifierContext = mutableMapOf<String, MezzeType>()

    /**
     * Local binding hash index — name → content hash string.
     *
     * Populated from identifierContentHashes (unqualified keys only) and
     * importContext bindings.
     */
    private val localBindingHashes: Map<String, Hash> by lazy {
        val result = mutableMapOf<String, Hash>()
        // From identifierContentHashes: only unqualified keys
        for ((key, hash) in identifierContentHashes) {
            if (key.qualifier == null) {
                result[key.localName] = hash
            }
        }
        // From import context
        importContext?.importedBindings?.forEach { (_, binding) ->
            result[binding.name] = binding.contentHash
        }
        result
    }

    /**
     * Ability impl index — abilityName → list of (contentHash, targetTypes).
     * Used for resolving builtin ability calls to real impl hashes.
     */
    private val abilityImplIndex: Map<String, List<AbilityImplIndexEntry>> by lazy {
        val index = mutableMapOf<String, MutableList<AbilityImplIndexEntry>>()
        for ((hash, info) in abilityImpls) {
            val name = info.abilityName ?: continue
            index.getOrPut(name) { mutableListOf() }
                .add(AbilityImplIndexEntry(hash, info.targetTypes))
        }
        index
    }

    /** Cache for ParsedTypeAnnotation → MezzeType conversion (type var cache) */
    private val typeVarCache = mutableMapOf<String, TypeVar>()

    private data class AbilityImplIndexEntry(
        val contentHash: Hash,
        val targetTypes: List<String>
    )

    /**
     * Pre-compute content hashes for all let declarations in the module.
     * Adds them to identifierContentHashes so resolveBinding() can find them.
     * This is needed because impl bodies may reference functions defined later
     * in the same module, and we need hash-based resolution for all of them.
     */
    private fun precomputeContentHashes(module: ParsedModule) {
        val mutableHashes = identifierContentHashes.toMutableMap()

        // Add hashes from imported bindings (needed for impl bodies that call imported functions)
        importContext?.importedBindings?.forEach { (name, binding) ->
            if (binding.contentHash != null) {
                val key = BindingKey.unqualified(name)
                if (key !in mutableHashes) {
                    mutableHashes[key] = binding.contentHash!!
                }
            }
        }

        // Add hashes for all let declarations in this module
        val moduleHashes = com.mezze.language.parsedast.ParsedDeclHasher.hashAllLetDecls(module)
        for ((name, hash) in moduleHashes) {
            val key = BindingKey.unqualified(name)
            if (key !in mutableHashes) {
                mutableHashes[key] = hash
            }
        }
        precomputedHashes = mutableHashes
    }

    /** Extended identifier content hashes (includes precomputed module-level hashes) */
    private var precomputedHashes: Map<BindingKey, Hash>? = null

    /** Stack of lambda parameter names — these shadow content hashes */
    private val lambdaParamScopes = ArrayDeque<MutableList<String>>()

    /** Get the effective identifier content hashes (precomputed if available) */
    private val effectiveContentHashes: Map<BindingKey, Hash>
        get() = precomputedHashes ?: identifierContentHashes

    /**
     * Walk a ParsedModule and produce a TypedModule.
     *
     * Processes declarations in order, populating identifierContext
     * so that later bindings can reference earlier ones.
     */
    fun build(module: ParsedModule): TypedModule {
        // Pre-compute content hashes for all let declarations in this module.
        // This ensures that impl bodies can reference any function in the module
        // by hash, even if the function appears later in the source.
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

        // Build imports from import context
        val imports = buildImports(module.path)

        // Compute module hash from all declaration hashes
        val allHashes = decls.map { it.hash.hex } +
            abilityDeclsList.map { it.hash.hex } +
            abilityImplsList.map { it.hash.hex } +
            effectDeclsList.map { it.hash.hex } +
            performerDeclsList.map { it.hash.hex }
        val moduleHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { b -> for (h in allHashes.sorted()) b.append(h) }.buildHash()

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
            dependencies = emptyList() // TODO: compute from imports
        )
    }

    private fun buildLetDecl(
        decl: ParsedLetDecl,
        file: String,
        decls: MutableList<TypedDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        // Check for record destructuring pattern: let { a, b, c } = expr
        if (!decl.isSimpleBinding && decl.pattern is ParsedRecordPattern) {
            val pattern = decl.pattern as ParsedRecordPattern
            val destrName = "__destructured_${decls.size}"
            val loc = decl.loc
            val rawType = constraintResults[destrName]?.let { substitution.apply(it.type) }
                ?: constraintResults["__destructured__"]?.let { substitution.apply(it.type) }
                ?: MezzeType.VarType(TypeVar.freshWithName("unknown"))
            val type = closeGeneratedRowVars(rawType)
            val value = buildExpr(decl.value, file, type)

            val destrHash = com.mezze.language.parsedast.ParsedDeclHasher.hashLetDecl(decl)
            val scheme = V2TypeScheme(emptyList(), emptyList(), type)
            decls.add(TypedLetDecl(destrHash, destrName, emptyList(), value, scheme, false, loc))

            // For each field, create a separate TypedLetDecl with field access
            // Handles renames ({ x as renamed }) and nested patterns ({ inner: { x, y } })
            val isPublic = decl.visibility == ParsedVisibility.PUBLIC
            emitRecordDestructureDeclFields(pattern.entries, destrName, type, loc, isPublic, decls, exports)
            return
        }

        val name = if (decl.isSimpleBinding) decl.variableName else "__destructured__"
        val loc = decl.loc

        // Look up type from constraintResults, apply substitution, and close generated row vars
        val rawType = constraintResults[name]?.let { substitution.apply(it.type) }
            ?: MezzeType.VarType(TypeVar.freshWithName("unknown"))
        val type = closeGeneratedRowVars(rawType, mustBeOpenRowVars)

        // Build the expression
        val value = buildExpr(decl.value, file, type)

        // Register binding in identifier context so later bindings can reference it
        identifierContext[name] = type

        // Compute content hash for this declaration
        val hash = com.mezze.language.parsedast.ParsedDeclHasher.hashLetDecl(decl, file)
            ?: com.mezze.language.parsedast.ParsedDeclHasher.hashLetDecl(decl)

        // Derive typeParams from the surface type. Any free TypeVar
        // remaining after substitution at top-level scope IS a quantifier
        // (let-generalization) — there's no enclosing forall to capture
        // it. Mono needs typeParams to know which type vars to specialize
        // at call sites.
        val freeVars = com.mezze.language.ast.collectTypeVarsInOrder(type, sortFields = false)
            .distinctBy { it.name }
            .map { TypeParam(it.name, TypeKind.Star) }
        val scheme = V2TypeScheme(freeVars, emptyList(), type)
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
        val hash = decl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(decl)

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
        }

        val isPublic = decl.visibility == ParsedVisibility.PUBLIC
        decls.add(TypedTypeDecl(hash, decl.name, emptyList(), def, isPublic, loc))
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
        val hash = decl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashAbilityDecl(decl)

        val ops = decl.operations.mapIndexed { idx, op ->
            typeVarCache.clear()
            val paramType = convertParsedTypeAnnotation(op.paramType)
            typeVarCache.clear()
            val returnType = convertParsedTypeAnnotation(op.returnType)
            TypedAbilityOp(op.name, idx, paramType, returnType, op.loc)
        }

        decls.add(TypedAbilityDecl(
            hash = hash,
            name = decl.name,
            typeParams = emptyList(),
            receiverTypeParam = decl.receiverTypeParam,
            associatedTypes = decl.associatedTypes,
            dependencies = emptyList(), // TODO: resolve dependency hashes
            operations = ops,
            loc = loc
        ))

        exports[decl.name] = ExportEntry(decl.name, hash, MezzeType.VarType(TypeVar.freshWithName(decl.name)), ExportKind.ABILITY)
    }


    /**
     * Public entry point for building auto-derived codec ability impls.
     * Called from TypeChecker after the main build() pass to inject
     * EncodeRepr/DecodeRepr impls generated by CodecGenerator.
     */
    fun buildCodecImpl(decl: ParsedImplAbilityDecl, impls: MutableList<TypedAbilityImpl>) {
        buildAbilityImpl(decl, "<codec-generated>", impls)
    }

    private fun buildAbilityImpl(
        decl: ParsedImplAbilityDecl,
        file: String,
        impls: MutableList<TypedAbilityImpl>
    ) {
        val loc = decl.loc

        // Resolve ability hash from target — must match the TypedAbilityDecl.hash
        // which was a different hash and caused abilityHash != decl.hash mismatch.
        val abilityHash = when (val target = decl.target) {
            is ParsedImplTarget.Named -> {
                val abilityName = target.ability.localName
                val abilityDecl = abilityDeclarations[abilityName]
                    ?: importContext?.importedAbilities?.get(abilityName)
                    ?: error("Ability '$abilityName' not found in declarations or imports")
                abilityDecl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashAbilityDecl(abilityDecl)
            }
            is ParsedImplTarget.Inherent -> Hash.ZERO
        }

        // Build target types
        val targetTypes = decl.targetTypes.map { convertParsedTypeAnnotation(it) }

        // Build associated type bindings
        val assocTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
            convertParsedTypeAnnotation(ann)
        }

        // Build operation expressions.
        //
        // For inherent impls (`impl Type where { ... }`), ConstraintGenerator stores
        // the solved body type under the key `_inherent_<loc>_<opName>`. Pulling it
        // out lets us push a real FunctionType down as the contextType, so the
        // lambda-branch in buildExpr can return the post-substitution body type
        // instead of bottoming out at the placeholder `'ret`. Without this,
        // importers can't recover the operation's return type at the module
        // boundary (issue #213).
        val ops = decl.operations.associate { op ->
            val isInherent = decl.target is com.mezze.language.parsedast.ParsedImplTarget.Inherent
            val targetTypeForOp = if (isInherent) {
                decl.targetTypes.firstOrNull()?.let { convertParsedTypeAnnotation(it) }
            } else null
            // ConstraintGenerator now stashes the impl op's full function
            // type (post-solve param record + return) under the same key
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

            val ctxType: MezzeType = when {
                isInherent && solvedFn != null -> solvedFn
                isInherent && targetTypeForOp != null && solvedReturn != null ->
                    MezzeType.FunctionType(
                        paramType = MezzeType.RecordType(mapOf("it" to targetTypeForOp)),
                        returnType = solvedReturn
                    )
                else -> MezzeType.VarType(TypeVar.freshWithName("op_${op.name}"))
            }
            val opExpr = buildExpr(op.implementation, file, ctxType)
            op.name to opExpr
        }

        val hash = decl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashImplAbilityDecl(decl)

        val forallVars = decl.forallVars.map { TypeParam(it, TypeKind.Star) }

        impls.add(TypedAbilityImpl(
            hash = hash,
            abilityHash = abilityHash,
            targetTypes = targetTypes,
            forallVars = forallVars,
            associatedTypeBindings = assocTypeBindings,
            operations = ops,
            loc = loc
        ))
    }

    private fun buildEffectDecl(
        decl: ParsedEffectDecl,
        file: String,
        decls: MutableList<TypedEffectDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        val loc = decl.loc
        val hash = decl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashEffectDecl(decl)

        val ops = decl.operations.mapIndexed { idx, op ->
            typeVarCache.clear()
            val paramType = convertParsedTypeAnnotation(op.paramType)
            typeVarCache.clear()
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
        // Use the content hash so it matches what importers look up.
        // Try: decl.contentHash, then implDeclarations, then identifierContentHashes, then fallback
        val contentHash = decl.contentHash
            ?: implDeclarations[decl.performerName]?.contentHash
            ?: identifierContentHashes[BindingKey(null, decl.performerName)]
        val hash = decl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashPerformerDecl(decl)

        // Resolve effect hash — must match the TypedEffectDecl.hash
        val effectName = decl.effect.localName
        val effectHash = resolveEffectHash(effectName)

        // Build params
        val params = decl.params.map { param ->
            typeVarCache.clear()
            TypedPerformerParam(param.name, convertParsedTypeAnnotation(param.type), param.loc)
        }

        // Build associated type bindings
        val assocTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
            convertParsedTypeAnnotation(ann)
        }

        // Build operation expressions
        val ops = decl.operations.associate { op ->
            val opExpr = buildExpr(op.implementation, file, MezzeType.VarType(TypeVar.freshWithName("op_${op.name}")))
            op.name to opExpr
        }

        // Compute per-operation content hashes via TypedASTHasher
        // Each operation hash includes the effect hash + op name + body content
        val opHashes = ops.mapValues { (opName, opBody) ->
            val opBuilder = com.mezze.language.ast.hashing.MezzeHashBuilder()
            opBuilder.append(byteArrayOf(com.mezze.language.ast.hashing.HashTags.IMPL_DECLARATION))
            opBuilder.append(effectHash.hex)
            opBuilder.append(hash.hex)
            opBuilder.append(opName)
            val bodyHash = com.mezze.language.typedast.TypedASTHasher.hashExpr(opBody)
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
        val hash = com.mezze.language.parsedast.ParsedDeclHasher.hashExpr(decl.expression)
        val type = MezzeType.VarType(TypeVar.freshWithName("unknown"))
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

    /**
     * Convert a ParsedExpr to a TypedExpr.
     *
     * The [contextType] is a hint for the expected type (used when no other
     * type information is available). For most expressions the type is derived
     * by recursion.
     */
    fun buildExpr(expr: ParsedExpr, file: String, contextType: MezzeType = MezzeType.VarType(TypeVar.freshWithName("unknown"))): TypedExpr {
        val loc = expr.loc
        return when (expr) {

            // ------------------------------------------------------------------
            // LITERALS — type is known
            // ------------------------------------------------------------------

            is ParsedIntLit -> TypedIntLit(expr.value, MezzeType.IntType, loc)
            is ParsedNatLit -> TypedNatLit(expr.value, MezzeType.NatType, loc)
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

            // ------------------------------------------------------------------
            // VARIANT CONSTRUCTION — True/False are booleans
            // ------------------------------------------------------------------

            is ParsedVariantConstruct -> {
                if (expr.constructor == "True" && expr.value == null) {
                    TypedBoolLit(true, MezzeType.BoolType, loc)
                } else if (expr.constructor == "False" && expr.value == null) {
                    TypedBoolLit(false, MezzeType.BoolType, loc)
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
                    val solvedType = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                        ?: substitution.apply(contextType)
                    TypedVariantConstruct(expr.constructor, payload, solvedType, loc)
                }
            }

            // ------------------------------------------------------------------
            // IDENTIFIER — resolve binding ref and type
            // ------------------------------------------------------------------

            is ParsedIdentifier -> {
                val name = expr.localName
                val qualifier = expr.qualifier

                // Resolve type: check identifierContext, then constraintResults
                val type = resolveIdentifierType(name, qualifier)

                // Resolve binding reference
                val binding = resolveBinding(name, qualifier)

                TypedVar(name, binding, type, loc)
            }

            // ------------------------------------------------------------------
            // LAMBDA
            // ------------------------------------------------------------------

            is ParsedLambda -> {
                // Determine parameter types
                val params = buildLambdaParams(expr.params, contextType, loc)

                // Push param types into identifierContext so body can resolve them
                val savedContext = mutableMapOf<String, MezzeType?>()
                for (param in params) {
                    savedContext[param.name] = identifierContext[param.name]
                    identifierContext[param.name] = param.type
                }

                // Track lambda params so resolveBinding prefers Local over ByHash
                lambdaParamScopes.addLast(params.map { it.name }.toMutableList())

                // Infer return type from context if available
                val returnType = when (val ct = substitution.apply(contextType)) {
                    is MezzeType.FunctionType -> substitution.apply(ct.returnType)
                    else -> MezzeType.VarType(TypeVar.freshWithName("ret"))
                }

                val body = buildExpr(expr.body, file, returnType)

                // Restore scopes
                lambdaParamScopes.removeLast()
                for ((name, prevType) in savedContext) {
                    if (prevType != null) identifierContext[name] = prevType
                    else identifierContext.remove(name)
                }

                // Function type: param record type → body type. When the
                // context type is a FunctionType, also carry its effect row
                // through. ConstraintGenerator stashes the post-solve impl
                // op FunctionType (with bodyResult.effects baked in, #180)
                // as the ctxType for inherent impl operations; without
                // preserving the effect row here, TypedLambda.type would
                // come out effectless and the import-side harvest would
                // see no effects. Same reasoning applies to top-level lets
                // with annotated effect arrows.
                val ctxFnType = substitution.apply(contextType) as? MezzeType.FunctionType
                val paramRecordType = ctxFnType?.paramType
                    ?: MezzeType.RecordType(params.associate { p -> p.name to p.type })
                val funcType = MezzeType.FunctionType(
                    paramType = paramRecordType,
                    returnType = body.type,
                    effects = ctxFnType?.effects
                )

                // Compute captures: variables in the body that reference outer scopes
                val captures = collectCaptures(body, params.map { it.name }.toSet())

                TypedLambda(
                    params = params,
                    body = body,
                    captures = captures,
                    type = funcType,
                    loc = loc
                )
            }

            // ------------------------------------------------------------------
            // FUNCTION CALL
            // ------------------------------------------------------------------

            is ParsedFunctionCall -> {
                // Effect operation detection: Log.log { ... }, STM.read { ... }, etc.
                // When a call targets EffectName.operation, emit TypedPerformOp instead of a regular call.
                if (expr.function is ParsedMemberAccess) {
                    val memberAccess = expr.function as ParsedMemberAccess
                    val receiverName = when (val r = memberAccess.receiver) {
                        is ParsedIdentifier -> r.localName
                        is ParsedVariantConstruct -> r.constructor  // Variant/effect names like Log, STM, Async
                        else -> null
                    }
                    if (receiverName != null) {
                        // Check if this is an effect declaration (local or imported)
                        val isEffect = effectDeclarations.containsKey(receiverName)
                            || importContext?.importedEffects?.containsKey(receiverName) == true
                        if (isEffect) {
                            // This matches TypedEffectDecl.hash and buildPerformerDecl's effectHash.
                            // No fallbacks needed — the name IS the canonical source of truth.
                            val effectHash = resolveEffectHash(receiverName)
                            val opName = memberAccess.member
                            val argExpr = buildExpr(expr.arguments, file)
                            // Type the perform-op as the operation's declared return type.
                            // We can't rely on contextType because the caller's expected
                            // type is often a stub TypeVar (e.g. `let_os` from a let
                            // binding without annotation). Reading the op's return type
                            // from the effect decl gives us the concrete return type
                            // that downstream method calls (`os.size {}`) need to
                            // resolve their ability dicts.
                            val effectDecl = effectDeclarations[receiverName]
                                ?: importContext?.importedEffects?.get(receiverName)
                            val resolvedReturnType = effectDecl?.operations
                                ?.firstOrNull { it.name == opName }
                                ?.let { convertParsedTypeAnnotation(it.returnType) }
                            val applied = substitution.apply(contextType)
                            val finalType = if (applied is MezzeType.VarType && resolvedReturnType != null) {
                                resolvedReturnType
                            } else applied
                            return TypedPerformOp(
                                effectHash, opName, argExpr,
                                finalType, loc
                            )
                        }
                    }
                }

                // Method call resolution is handled by the Monomorphizer's tryResolveMethodCall.
                // TypedAstBuilder emits TypedCall(TypedFieldAccess(receiver, method), args)
                // and the Monomorphizer detects this pattern and resolves to direct ByHash call.
                val fnIsMemberAccess = expr.function is ParsedMemberAccess
                val memberName = if (fnIsMemberAccess) (expr.function as ParsedMemberAccess).member else null
                var methodInfo = if (memberName != null) methodCallResolutions[expr.loc to memberName] else null
                if (methodInfo != null && fnIsMemberAccess) {
                    // Method-syntax call: x.is_some {} → resolved to direct function call.
                    // The constraint solver already resolved the method to a concrete impl
                    // operation with a content hash. Emit CallByHash instead of FieldAccess.
                    val memberAccess = expr.function as ParsedMemberAccess
                    val receiverExpr = buildExpr(memberAccess.receiver, file)
                    val argExpr = buildExpr(expr.arguments, file)

                    // Prefer the constraint-solved methodFunctionType when
                    // available — without it, contextType (often an `unknown`
                    // placeholder) leaks through and the method-call's `.type`
                    // ends up unresolved. That breaks LSP per-segment chain
                    // hints and any downstream consumer that reads the call's
                    // return type.
                    val solvedFnType = methodInfo.methodFunctionType?.let { substitution.apply(it) }

                    // Build the function reference — use ByHash
                    val fn = if (methodInfo.methodHash != null) {
                        // Direct call by hash — no field access, no lambda wrapper
                        val hash = methodInfo.methodHash!!
                        TypedVar(
                            name = methodInfo.methodName,
                            binding = BindingRef.ByHash(hash),
                            type = solvedFnType ?: substitution.apply(contextType),
                            loc = memberAccess.loc
                        )
                    } else if (methodInfo.isInherentMethod && methodInfo.implContentHash != null) {
                        val implHash = methodInfo.implContentHash
                        val opHash = Hash.computeOpHash(implHash, methodInfo.methodName)
                        TypedVar(
                            name = methodInfo.methodName,
                            binding = BindingRef.ByHash(opHash),
                            type = solvedFnType ?: substitution.apply(contextType),
                            loc = memberAccess.loc
                        )
                    } else {
                        buildExpr(expr.function, file)
                    }

                    // Wrap the receiver into the arguments as the receiver field (e.g., "it")
                    val receiverFieldName = methodInfo.receiverFieldName
                    val receiverField = TypedRecordEntry.Field(receiverFieldName, receiverExpr, memberAccess.receiver.loc)

                    // Merge receiver into argument record
                    val wrappedArg = when (argExpr) {
                        is TypedRecord -> {
                            val allEntries = listOf(receiverField) + argExpr.entries
                            val allFieldTypes = mutableMapOf(receiverFieldName to receiverExpr.type)
                            for (entry in argExpr.entries) {
                                if (entry is TypedRecordEntry.Field) {
                                    allFieldTypes[entry.name] = entry.value.type
                                }
                            }
                            TypedRecord(allEntries, MezzeType.RecordType(allFieldTypes), argExpr.loc)
                        }
                        else -> {
                            val entries = listOf(receiverField)
                            TypedRecord(entries, MezzeType.RecordType(mapOf(receiverFieldName to receiverExpr.type)), loc)
                        }
                    }

                    val callReturnType = when (val ft = substitution.apply(fn.type)) {
                        is MezzeType.FunctionType -> substitution.apply(ft.returnType)
                        else -> {
                            // fn.type couldn't be specialized (e.g. ability-method
                            // resolution returned no methodFunctionType). Fall back
                            // to the constraint generator's per-call-site return
                            // type recorded in callReturnTypes — for ability calls
                            // this is the inferred return after solving (e.g.
                            // `Int` for `result.try {}` on a `Result Int Str`).
                            val perCallSite = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                            perCallSite ?: substitution.apply(contextType)
                        }
                    }

                    val isTailCall = isTailCallLocation(expr.loc)

                    // #195 mono-fix #2: stamp TypedCall.instantiation from the
                    // resolved MethodCallInfo so the monomorphizer's transitive
                    // worklist (TypeSubstitution.collectByHashRefs) can enqueue
                    // this call's specialization. Without it, every method call
                    // inside a generic body silently drops out of mono.
                    val typeArgsResolved = methodInfo.typeArgs.map { substitution.apply(it) }
                    val abilityImplsResolved = methodInfo.abilityImpls.map { ref ->
                        com.mezze.language.typedast.AbilityImplRef(
                            abilityHash = ref.abilityHash,
                            implHash = ref.implHash,
                            targetType = substitution.apply(ref.targetType),
                        )
                    }
                    val instantiation = if (typeArgsResolved.isEmpty() && abilityImplsResolved.isEmpty()) {
                        null
                    } else {
                        com.mezze.language.typedast.TypeInstantiation(
                            typeArgs = typeArgsResolved,
                            abilityImpls = abilityImplsResolved,
                        )
                    }

                    TypedCall(
                        function = fn,
                        arg = wrappedArg,
                        instantiation = instantiation,
                        isTailCall = isTailCall,
                        type = callReturnType,
                        loc = loc
                    )
                } else {
                    // Normal function call (no method resolution)
                    val fn = buildExpr(expr.function, file)
                    val argExpr = buildExpr(expr.arguments, file)

                    // Derive call return type. Prefer the constraint-solved
                    // per-call-site return type recorded by the constraint
                    // generator (already unified with the call's actual
                    // return at this site), falling back to the function's
                    // own returnType. Without the per-site lookup, calls to
                    // imported polymorphic functions (`seq::filter { ... }`)
                    // would carry the generic scheme's returnType (e.g.
                    // `[a]`) instead of the monomorphized one (`[Int]`).
                    val solvedReturn = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                    val callType = solvedReturn
                        ?: when (val ft = substitution.apply(fn.type)) {
                            is MezzeType.FunctionType -> substitution.apply(ft.returnType)
                            else -> substitution.apply(contextType)
                        }

                    val isTailCall = isTailCallLocation(expr.loc)

                    TypedCall(
                        function = fn,
                        arg = argExpr,
                        instantiation = null,
                        isTailCall = isTailCall,
                        type = callType,
                        loc = loc
                    )
                }
            }

            // ------------------------------------------------------------------
            // MEMBER ACCESS — field access or ability method
            // ------------------------------------------------------------------

            is ParsedMemberAccess -> {
                val receiver = buildExpr(expr.receiver, file)
                val memberName = expr.member
                val receiverType = substitution.apply(receiver.type)

                val fieldType = when (receiverType) {
                    is MezzeType.RecordType -> receiverType.fields[memberName]
                        ?: substitution.apply(contextType)
                    else -> substitution.apply(contextType)
                }

                TypedFieldAccess(
                    record = receiver,
                    field = memberName,
                    fieldIndex = -1, // filled by resolver later
                    type = fieldType,
                    loc = loc
                )
            }

            // ------------------------------------------------------------------
            // RECORD LITERAL
            // ------------------------------------------------------------------

            is ParsedRecordLit -> {
                val entries = expr.entries.map { entry ->
                    when (entry) {
                        is ParsedRecordEntry.Field -> {
                            val entryValue = entry.value
                            val fieldExpr = if (entryValue != null) {
                                buildExpr(entryValue, file)
                            } else {
                                // Punning: `{ x }` means `{ x = x }`
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

                // Derive record type from field entries
                val fieldTypes = entries
                    .filterIsInstance<TypedRecordEntry.Field>()
                    .associate { it.name to it.value.type }
                val recordType = MezzeType.RecordType(fieldTypes)

                TypedRecord(entries, recordType, loc)
            }

            // ------------------------------------------------------------------
            // IF EXPRESSION
            // ------------------------------------------------------------------

            is ParsedIf -> {
                val cond = buildExpr(expr.condition, file, MezzeType.BoolType)
                val thenBranch = buildExpr(expr.thenBranch, file, contextType)
                val elseBranch = buildExpr(expr.elseBranch, file, thenBranch.type)
                TypedIf(cond, thenBranch, elseBranch, thenBranch.type, loc)
            }

            // ------------------------------------------------------------------
            // MATCH EXPRESSION
            // ------------------------------------------------------------------

            is ParsedMatch -> {
                val scrutinee = buildExpr(expr.scrutinee, file)

                // Check for boolean match (True/False patterns) → convert to if-else
                val isBoolMatch = expr.cases.size == 2 &&
                    expr.cases.any { c -> c.patterns.any { it is ParsedConstructorPattern && it.constructor == "True" } } &&
                    expr.cases.any { c -> c.patterns.any { it is ParsedConstructorPattern && it.constructor == "False" } }

                if (isBoolMatch) {
                    val trueCase = expr.cases.find { c ->
                        c.patterns.any { it is ParsedConstructorPattern && it.constructor == "True" }
                    }
                    val falseCase = expr.cases.find { c ->
                        c.patterns.any { it is ParsedConstructorPattern && it.constructor == "False" }
                    }
                    val thenBranch = buildExpr(trueCase!!.body, file, contextType)
                    val elseBranch = buildExpr(falseCase!!.body, file, thenBranch.type)
                    TypedIf(scrutinee, thenBranch, elseBranch, thenBranch.type, loc)
                } else {
                    val cases = expr.cases.map { c ->
                        buildMatchCase(c, file, contextType)
                    }
                    val resultType = cases.firstOrNull()?.body?.type ?: substitution.apply(contextType)
                    TypedMatch(scrutinee, cases, resultType, loc)
                }
            }

            // ------------------------------------------------------------------
            // DO BLOCK
            // ------------------------------------------------------------------

            is ParsedDo -> {
                // Push a block scope so let bindings are tracked for capture by inner lambdas.
                // Without this, top-level do-block let bindings resolve as BindingRef.ByHash
                // instead of BindingRef.Local, causing inner lambdas to miss captures.
                val pushedBlockScope = lambdaParamScopes.isEmpty()
                if (pushedBlockScope) {
                    lambdaParamScopes.addLast(mutableListOf())
                }
                val stmts = mutableListOf<TypedStatement>()
                for (stmt in expr.statements) {
                    when (stmt) {
                        is ParsedStatement.Let -> {
                            val pattern = stmt.pattern
                            if (pattern is com.mezze.language.parsedast.ParsedRecordPattern) {
                                // Desugar record destructuring:
                                // let { a, b, c } = record →
                                //   let __destr = record
                                //   let a = __destr.a
                                //   let b = __destr.b
                                //   let c = __destr.c
                                val destrName = "__destr_${stmts.size}"
                                val valueType = stmt.typeAnnotation
                                    ?.let { substitution.apply(convertParsedTypeAnnotation(it)) }
                                    ?: MezzeType.VarType(TypeVar.freshWithName("let_destr"))
                                val value = buildExpr(stmt.value, file, valueType)
                                val resolvedType = substitution.apply(value.type)
                                identifierContext[destrName] = resolvedType
                                if (lambdaParamScopes.isNotEmpty() && destrName !in lambdaParamScopes.last()) {
                                    lambdaParamScopes.last().add(destrName)
                                }
                                val destrHash = com.mezze.language.parsedast.ParsedDeclHasher.hashExpr(stmt.value)
                                stmts.add(TypedStatement.Let(destrName, destrHash, resolvedType, value, stmt.loc))

                                // For each field in the pattern, create a field access let
                                // Handles renames ({ x as renamed }) and nested patterns ({ inner: { x, y } })
                                emitRecordDestructureFields(pattern.entries, destrName, resolvedType, stmt.loc, stmts)
                            } else {
                                val name = when (pattern) {
                                    is ParsedVariablePattern -> pattern.name
                                    else -> "__pattern__"
                                }
                                val valueType = stmt.typeAnnotation
                                    ?.let { substitution.apply(convertParsedTypeAnnotation(it)) }
                                    ?: MezzeType.VarType(TypeVar.freshWithName("let_$name"))

                                // RECURSIVE LET: For `let name = lambda { ... }` in do blocks,
                                // pre-register the binding so the lambda body can resolve `name`
                                // as a local reference. Without this, recursive references fall
                                // through to BindingRef.Local(0,0) and fail at runtime.
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
                                val resolvedType = substitution.apply(value.type)

                                // Detect if this is actually recursive: lambda body references the binding name
                                val isRecursive = isLambdaValue && value is TypedLambda &&
                                                   refsName(value.body, name)

                                // Register binding for later references in this block
                                identifierContext[name] = resolvedType

                                // Also add to current lambda scope (no-op if already added above)
                                if (lambdaParamScopes.isNotEmpty()) {
                                    val currentScope = lambdaParamScopes.last()
                                    if (name !in currentScope) {
                                        currentScope.add(name)
                                    }
                                }

                                // Use v1 content hash if available, otherwise compute
                                val v1LetHash = identifierContentHashes[BindingKey.unqualified(name)]
                                val contentHash = v1LetHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashExpr(stmt.value)
                                val isBind = stmt.loc in bindLetLocs
                                stmts.add(TypedStatement.Let(name, contentHash, resolvedType, value, stmt.loc, isRecursive, isBind))
                            }
                        }
                        is ParsedStatement.Expr -> {
                            val exprTyped = buildExpr(stmt.expr, file)
                            stmts.add(TypedStatement.Expr(exprTyped, stmt.loc))
                        }
                        is ParsedStatement.Bind -> {
                            // ParsedStatement.Bind must be eliminated by DoBindDesugar
                            // before TypedAstBuilder runs. Reaching here means a
                            // traversal site in the desugar missed this position.
                            throw IllegalStateException(
                                "ParsedStatement.Bind reached TypedAstBuilder at ${stmt.loc} — " +
                                "DoBindDesugar must rewrite Bind statements to Let before this phase."
                            )
                        }
                    }
                }
                val result = buildExpr(expr.result, file, contextType)
                if (pushedBlockScope) {
                    lambdaParamScopes.removeLast()
                }
                TypedBlock(stmts, result, result.type, loc)
            }

            // ------------------------------------------------------------------
            // SEQ LITERAL
            // ------------------------------------------------------------------

            is ParsedSeqLit -> {
                // Determine element type from context or first element
                val elementContextType = when (val ct = substitution.apply(contextType)) {
                    is MezzeType.SeqType -> ct.elementType
                    else -> MezzeType.VarType(TypeVar.freshWithName("elem"))
                }
                val elements = expr.elements.map { elem ->
                    when (elem) {
                        is ParsedSeqElement.Regular ->
                            TypedSeqElement.Regular(buildExpr(elem.expr, file, elementContextType))
                        is ParsedSeqElement.Spread ->
                            TypedSeqElement.Spread(buildExpr(elem.expr, file, MezzeType.SeqType(elementContextType)))
                    }
                }
                val elemType = (elements.firstOrNull() as? TypedSeqElement.Regular)?.expr?.type ?: elementContextType
                TypedSeq(elements, MezzeType.SeqType(elemType), loc)
            }

            // ------------------------------------------------------------------
            // PIPE
            // ------------------------------------------------------------------

            // Operators — desugar to method call for TypedAST
            is ParsedBinaryOp -> {
                val op = com.mezze.language.parser.OperatorMapping.getBinaryOp(expr.op)
                if (op != null) {
                    val desugared = ParsedFunctionCall(
                        function = ParsedMemberAccess(expr.left, op.method, expr.loc),
                        arguments = ParsedRecordLit(listOf(ParsedRecordEntry.Field("r", expr.right, expr.loc)), expr.loc),
                        loc = expr.loc
                    )
                    buildExpr(desugared, file, contextType)
                } else {
                    // Unknown operator — build as typed literal error
                    TypedIntLit(0, MezzeType.IntType, loc)
                }
            }

            is ParsedUnaryOp -> {
                val op = com.mezze.language.parser.OperatorMapping.getUnaryOp(expr.op)
                if (op != null) {
                    val desugared = ParsedFunctionCall(
                        function = ParsedMemberAccess(expr.operand, op.method, expr.loc),
                        arguments = ParsedRecordLit(emptyList(), expr.loc),
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
                // Pipe placeholder should be desugared before reaching here.
                // Emit as a special var with a recognizable name.
                TypedVar("@", BindingRef.Local(0, 0), substitution.apply(contextType), loc)
            }

            // ------------------------------------------------------------------
            // PARENTHESES — transparent
            // ------------------------------------------------------------------

            is ParsedParens -> buildExpr(expr.expr, file, contextType)

            // ------------------------------------------------------------------
            // PERFORM EXPRESSION
            // ------------------------------------------------------------------

            is ParsedPerform -> {
                val handlers = expr.handlers.map { h ->
                    buildHandlerRef(h, file, loc)
                }
                val body = buildExpr(expr.body, file, contextType)
                // Prefer the constraint solver's discharged result type
                // (recorded per-perform-loc). Without this, the perform's
                // outer type stays at the body's pre-discharge form, which
                // hides the handler's effect → value transformation (e.g.
                // `ExceptionToResult` turning `<Exception>` into `Result _ _`).
                // That matters when the perform is itself a receiver (the
                // nested do-with-bind case): without the right type here,
                // `.try {}` mis-dispatches at IR-build time.
                val performType = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                    ?: body.type
                TypedPerform(handlers, body, performType, loc)
            }

            // ------------------------------------------------------------------
            // EFFECT CALL
            // ------------------------------------------------------------------

            is ParsedEffectCall -> {
                val opName = expr.operation.localName
                val qualifier = expr.operation.qualifier

                // Resolve which effect this operation belongs to
                val effectName = qualifier?.segments?.lastOrNull() ?: opName
                // Use canonical hash — matches TypedEffectDecl.hash
                val effectHash = resolveEffectHash(effectName)

                val argExpr = buildExpr(expr.arguments, file)

                TypedPerformOp(effectHash, opName, argExpr, substitution.apply(contextType), loc)
            }
        }
    }

    private fun buildMatchCase(case: ParsedMatchCase, file: String, contextType: MezzeType): TypedCase {
        val patterns = case.patterns.map { buildPattern(it, file) }
        val bindingNames = case.patterns.flatMap { p -> p.extractBindings() }.toMutableList()

        // Collect if-let binding names and build TypedIfLetBindings
        val typedIfLetBindings = mutableListOf<TypedIfLetBinding>()
        for (ifLet in case.ifLetBindings) {
            val ifLetBindingNames = ifLet.pattern.extractBindings()
            bindingNames.addAll(ifLetBindingNames)
            val typedPattern = buildPattern(ifLet.pattern, file)
            val typedExpr = buildExpr(ifLet.expr, file)
            typedIfLetBindings.add(TypedIfLetBinding(typedPattern, typedExpr, ifLetBindingNames, ifLet.loc))
        }

        // #195 mono-fix #1: use the solver's TyVars from PatternConstraintResult
        // (threaded through ConstraintResult.matchCaseBindings) instead of
        // constructing fresh `TypeVar(name)` placeholders. Applying substitution
        // here resolves to concrete types when HasField fired. Without this,
        // the placeholder's `(name, id=0)` identity never matches the solver's
        // `(<fresh-name>, id=N)`, so substitution.apply returns it unchanged.
        val solverBindings = matchCaseBindings[case.loc] ?: emptyMap()
        val resolvedTypes = bindingNames.associateWith { name ->
            val solverTy = solverBindings[name]
            if (solverTy != null) substitution.apply(solverTy)
            else MezzeType.VarType(TypeVar.freshWithName(name))
        }

        val bindings = bindingNames.map { name ->
            PatternBinding(name, resolvedTypes[name]!!, case.loc)
        }

        // Push pattern bindings so case body sees them as locals, not globals
        lambdaParamScopes.addLast(bindingNames.toMutableList())
        // Register binding types in identifierContext so resolveIdentifierType
        // returns the concrete type when guard/body references the binding —
        // without this, `w == h` in the guard sees `w: VarType("unknown")` and
        // method-call dispatch can't pick the inline op. Save prior context
        // entries (for shadowing) and restore after the case.
        val savedContext = HashMap<String, MezzeType?>()
        for (name in bindingNames) {
            savedContext[name] = identifierContext[name]
            identifierContext[name] = resolvedTypes[name]!!
        }

        val caseGuard = case.guard
        val guard = if (caseGuard != null) {
            buildExpr(caseGuard, file, MezzeType.BoolType)
        } else if (case.ifLetBindings.isNotEmpty()) {
            TypedBoolLit(true, MezzeType.BoolType, case.loc)
        } else {
            null
        }
        val body = buildExpr(case.body, file, contextType)

        // Restore prior identifierContext entries.
        for ((name, prev) in savedContext) {
            if (prev != null) identifierContext[name] = prev else identifierContext.remove(name)
        }
        lambdaParamScopes.removeLast()

        return TypedCase(patterns, bindings, guard, body, case.loc, typedIfLetBindings)
    }

    private fun buildPattern(pattern: ParsedPattern, file: String): TypedPattern {
        val unknownType = MezzeType.VarType(TypeVar.freshWithName("pat"))
        val loc = pattern.loc
        return when (pattern) {
            is ParsedVariablePattern -> TypedVarPattern(pattern.name, unknownType, loc)
            is ParsedWildcardPattern -> TypedWildcardPattern(unknownType, loc)
            is ParsedConstructorPattern -> {
                if (pattern.constructor == "True" && pattern.payload == null) {
                    TypedLiteralPattern(TypedBoolLit(true, MezzeType.BoolType, loc), MezzeType.BoolType, loc)
                } else if (pattern.constructor == "False" && pattern.payload == null) {
                    TypedLiteralPattern(TypedBoolLit(false, MezzeType.BoolType, loc), MezzeType.BoolType, loc)
                } else {
                    val payload = pattern.payload?.let { buildPattern(it, file) }
                    TypedVariantPattern(pattern.constructor, payload, unknownType, loc)
                }
            }
            is ParsedRecordPattern -> TypedRecordPattern(
                fields = pattern.entries.map { e ->
                    TypedFieldPattern(e.name, e.pattern?.let { buildPattern(it, file) }, e.loc)
                },
                isOpen = pattern.isOpen,
                type = unknownType,
                loc = loc
            )
            is ParsedLiteralPattern -> {
                val value = when (val v = pattern.value) {
                    is ParsedLiteralValue.IntValue -> TypedIntLit(v.value, MezzeType.IntType, loc)
                    is ParsedLiteralValue.NatValue -> TypedNatLit(v.value, MezzeType.NatType, loc)
                    is ParsedLiteralValue.FloatValue -> TypedFloatLit(v.value, MezzeType.FloatType, loc)
                    is ParsedLiteralValue.StringValue ->
                        TypedStringLit(listOf(TypedStringPart.Literal(v.value)), MezzeType.StringType, loc)
                    is ParsedLiteralValue.BoolValue -> TypedBoolLit(v.value, MezzeType.BoolType, loc)
                }
                TypedLiteralPattern(value, value.type, loc)
            }
            is ParsedSeqPattern -> TypedSeqPattern(
                elements = pattern.elements.map { buildPattern(it, file) },
                rest = pattern.restPattern,
                type = unknownType,
                loc = loc
            )
            is ParsedStringInterpPattern ->
                TypedStringInterpPattern(
                    parts = pattern.parts.map { part ->
                        when (part) {
                            is ParsedStringPatternPart.Literal -> TypedStringPatternPart.Literal(part.value)
                            is ParsedStringPatternPart.Capture -> TypedStringPatternPart.Capture(part.variableName)
                        }
                    },
                    type = MezzeType.StringType,
                    loc = loc
                )
        }
    }

    /**
     * Returns true if any TypedVar in [expr] has the given [name].
     * Used to detect recursive let-bound lambdas where the body refers to
     * its own binding name. Conservative: false positives are OK (just means
     * we mark something as recursive that isn't), false negatives are bugs.
     */
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

    /**
     * Collect captures from a typed expression body.
     * A capture is any TypedVar with BindingRef.Local(depth > 0) — it references
     * a variable from an outer lambda scope, not the current lambda's params.
     */
    private fun collectCaptures(body: TypedExpr, paramNames: Set<String>): List<TypedCapture> {
        val captures = mutableListOf<TypedCapture>()
        val seen = mutableSetOf<String>()
        walkForCaptures(body, paramNames, captures, seen)
        return captures
    }

    private fun walkForCaptures(expr: TypedExpr, paramNames: Set<String>, captures: MutableList<TypedCapture>, seen: MutableSet<String>) {
        if (expr is TypedVar) {
            val binding = expr.binding
            // Skip the lambda's own params — they're not captures, they're inputs.
            // The ByHash branch below already does this check; the Local branch
            // was missing it, which caused inner references to params (e.g. `acc`
            // inside `match x is Ok ... acc + val`) to be mis-collected as
            // captures. Fix: same guard, both branches.
            if (binding is BindingRef.Local && binding.depth > 0 && expr.name !in seen && expr.name !in paramNames) {
                seen.add(expr.name)
                captures.add(TypedCapture(expr.name, expr.type, binding))
            }
            // Also capture ByHash refs that are actually outer scope locals/params
            // (The TypedAstBuilder may resolve local params as ByHash if they have content hashes)
            if (binding is BindingRef.ByHash && expr.name !in seen && expr.name !in paramNames) {
                // Check if this name is in an outer lambda scope
                if (lambdaParamScopes.size >= 2) {
                    for (i in 0 until lambdaParamScopes.size - 1) {
                        if (expr.name in lambdaParamScopes[i]) {
                            seen.add(expr.name)
                            val depth = lambdaParamScopes.size - 1 - i
                            val index = lambdaParamScopes[i].indexOf(expr.name)
                            captures.add(TypedCapture(expr.name, expr.type, BindingRef.Local(depth, index)))
                            break
                        }
                    }
                }
            }
            return
        }
        if (expr is TypedLambda) {
            // Nested lambdas compute their own captures, but we need to propagate
            // captures through: if a nested lambda captures a variable from a grandparent
            // scope, the current lambda must also capture it to pass it down.
            for (cap in expr.captures) {
                if (cap.name !in paramNames && cap.name !in seen) {
                    val capBinding = cap.source
                    if (capBinding is BindingRef.Local && capBinding.depth > 1) {
                        // Variable from grandparent+ scope — current function must also capture it
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
        // leaf nodes: IntLit, StringLit, BoolLit, etc. — no children
    }

    private fun buildLambdaParams(
        params: ParsedLambdaParams,
        contextType: MezzeType,
        loc: Loc
    ): List<TypedParam> {
        val resolvedContext = substitution.apply(contextType)
        return when (params) {
            is ParsedLambdaParams.Typed -> {
                params.fields.map { field ->
                    typeVarCache.clear()
                    val type = substitution.apply(convertParsedTypeAnnotation(field.type))
                    TypedParam(field.name, type, field.loc)
                }
            }
            is ParsedLambdaParams.Untyped -> {
                // Try to get types from context function type
                val contextParamType = when (resolvedContext) {
                    is MezzeType.FunctionType -> resolvedContext.paramType
                    else -> null
                }
                // Per-name locs come from the parser (#232). Older inputs
                // may not have them populated — fall back to the lambda's
                // own loc in that case so behaviour is no worse than
                // before.
                val perNameLocs = params.nameLocs
                params.names.mapIndexed { idx, name ->
                    val type = when (contextParamType) {
                        is MezzeType.RecordType -> contextParamType.fields[name]
                            ?: MezzeType.VarType(TypeVar.freshWithName(name))
                        else -> MezzeType.VarType(TypeVar.freshWithName(name))
                    }
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

        // Look up performer hash — prefer identifierContentHashes (consistent with buildPerformerDecl),
        // then fall back to implDeclarations/importedImpls
        val implDecl = implDeclarations[performerName]
        val importedImpl = importContext?.importedImpls?.get(performerName)
        val performerHash = identifierContentHashes[BindingKey(null, performerName)]
            ?: implDecl?.contentHash
            ?: importedImpl?.contentHash
            ?: implDecl?.let { com.mezze.language.parsedast.ParsedDeclHasher.hashPerformerDecl(it) }
            ?: Hash("0".repeat(64))

        // Resolve effect hash from performer
        val effectName = implDecl?.effect?.localName
            ?: importedImpl?.effect?.localName
            ?: performerToEffect[performerName]
        val effectDecl = effectName?.let { effectDeclarations[it] ?: importContext?.importedEffects?.get(it) }
        // Use canonical hash — matches TypedEffectDecl.hash and buildPerformOp.
        // No fallbacks needed — the name IS the canonical source of truth.
        val effectHash = if (effectName != null) {
            resolveEffectHash(effectName)
        } else {
            Hash.ZERO
        }

        // Build params if present
        val paramsExpr = handler.params?.let { buildExpr(it, file) }

        return TypedHandlerRef(
            performerHash = performerHash,
            effectHash = effectHash,
            performerName = performerName,
            params = paramsExpr,
            loc = loc
        )
    }

    /**
     * Resolve the type of an identifier.
     *
     * Lookup order:
     * 1. identifierContext — types accumulated during current traversal
     * 2. constraintResults — top-level binding types from the typechecker
     * 3. importContext — imported bindings
     * 4. Unknown fallback
     */
    private fun resolveIdentifierType(name: String, qualifier: ParsedModulePath?): MezzeType {
        // For qualified identifiers, try to find via importContext.
        // importedBindings is keyed by `<qualifier>::<name>` (e.g.
        // `seq::filter`). Without this lookup the qualified-call's
        // TypedVar.type stays `unknown`, which breaks downstream LSP
        // per-segment chain inlay hints (the receiver-field's type ends
        // up unresolved). The returned type is the binding's GENERIC
        // scheme — we apply substitution but the polymorphic type vars
        // stay free (they're instantiated per-call-site by the constraint
        // solver, not stored back here). Inlay hints render `[a]` instead
        // of `[Int]` for chain segments, which is acceptable: the chain
        // shape is still visible. See #233.
        if (qualifier != null) {
            val qualifiedKey = "${qualifier.shortName}::$name"
            importContext?.importedBindings?.get(qualifiedKey)?.type?.let {
                return substitution.apply(it)
            }
            importContext?.importedBindings?.get(name)?.type?.let {
                return substitution.apply(it)
            }
            return MezzeType.VarType(TypeVar.freshWithName("unknown"))
        }

        // 1. Local context (apply substitution to resolve type variables)
        identifierContext[name]?.let { return substitution.apply(it) }

        // 2. Constraint results (top-level bindings)
        constraintResults[name]?.let { result ->
            return substitution.apply(result.type)
        }

        // 3. Import context
        importContext?.importedBindings?.get(name)?.type?.let {
            return substitution.apply(it)
        }

        // 4. Unknown
        return MezzeType.VarType(TypeVar.freshWithName("unknown"))
    }

    /**
     * Resolve the binding reference for an identifier.
     *
     * Lookup order:
     * 1. identifierContentHashes → ByHash (for local top-level bindings)
     * 2. importContext → ByHash (for imported bindings)
     * 3. Native function name → Native
     * 4. Fallback → Local(0, 0)
     */
    private fun resolveBinding(name: String, qualifier: ParsedModulePath?): BindingRef {
        // Lambda params / match bindings — search from innermost scope outward
        if (qualifier == null) {
            for (i in lambdaParamScopes.indices.reversed()) {
                val scope = lambdaParamScopes[i]
                if (name in scope) {
                    val depth = lambdaParamScopes.size - 1 - i  // 0 = innermost
                    val index = scope.indexOf(name)  // position within scope
                    return BindingRef.Local(depth, index)
                }
            }
        }

        val hashes = effectiveContentHashes

        // Qualified reference — try content hashes with qualifier
        if (qualifier != null) {
            val qualKey = BindingKey(qualifier.shortName, name)
            val hash = hashes[qualKey]
            if (hash != null) return BindingRef.ByHash(hash)
        }

        // Unqualified: check content hashes
        val unqualKey = BindingKey.unqualified(name)
        val localHash = hashes[unqualKey]
        if (localHash != null) {
            return BindingRef.ByHash(localHash)
        }

        // Check importContext
        importContext?.importedBindings?.get(name)?.let { binding ->
            return BindingRef.ByHash(binding.contentHash)
        }

        // Check if it's a native function
        if (name.startsWith("__native_") || NativeFunctionTypes.isNativeFunction(name)) {
            return BindingRef.Native(name)
        }

        // Fallback: local binding (will be resolved by Resolver stage)
        return BindingRef.Local(0, 0)
    }

    /**
     * Check if a source location is a tail call site.
     */
    private fun isTailCallLocation(loc: Loc): Boolean {
        return tailCallLocations.contains(loc) || tailCallLocations.any { tl ->
            tl.startLine == loc.startLine && tl.startCol == loc.startCol && tl.file == loc.file
        }
    }

    /**
     * Resolve which effect declaration owns a given operation name.
     */
    private fun resolveEffectForOperation(
        opName: String,
        qualifier: ParsedModulePath?
    ): com.mezze.language.parsedast.ParsedEffectDecl? {
        // If qualified, try to find by effect name
        if (qualifier != null) {
            val effectName = qualifier.shortName
            effectDeclarations[effectName]?.let { decl ->
                if (decl.operations.any { it.name == opName }) return decl
            }
            importContext?.importedEffects?.get(effectName)?.let { decl ->
                if (decl.operations.any { it.name == opName }) return decl
            }
        }

        // Unqualified: search all effect declarations for the operation
        for ((_, decl) in effectDeclarations) {
            if (decl.operations.any { it.name == opName }) return decl
        }
        for ((_, decl) in (importContext?.importedEffects ?: emptyMap())) {
            if (decl.operations.any { it.name == opName }) return decl
        }
        return null
    }

    // TODO: Could eventually use TypeAnnotationConverter from util/, but this version has
    // special Unit handling ("Unit" -> empty RecordType), uppercase -> TypeApp logic,
    // instance-level typeVarCache, open row vars, and associatedTypeBindings on ability
    // constraints that are specific to typed AST emission.
    /**
     * Convert a ParsedTypeAnnotation to MezzeType.
     *
     * Note: call `typeVarCache.clear()` before top-level calls to ensure
     * type variables in separate annotations don't alias.
     */
    private fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedNamedType -> {
                // Primitive types first
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

                when (annotation.name) {
                    "Unit" -> MezzeType.RecordType(emptyMap())
                    else -> {
                        // Type variable (lowercase or tick-prefixed) or type constructor
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            val typeVar = typeVarCache.getOrPut(annotation.name) {
                                TypeVar.freshWithName(annotation.name)
                            }
                            MezzeType.VarType(typeVar)
                        } else {
                            com.mezze.language.typechecker.util.WellKnownTypeApp.of(annotation.name, emptyList())
                        }
                    }
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
                        abilityHash = abilityDeclarations[constraint.abilityName]?.contentHash
                            ?: WellKnownHashConstants.resolveAbilityHash(constraint.abilityName)
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
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertParsedTypeAnnotation(it) }
                )
            }
            is ParsedSeqType -> {
                MezzeType.SeqType(elementType = convertParsedTypeAnnotation(annotation.elementType))
            }
            is ParsedQualifiedType -> {
                com.mezze.language.typechecker.util.WellKnownTypeApp.of(
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
                moduleHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append(binding.modulePath) }.buildHash(),
                declHash = binding.contentHash,
                type = binding.type,
                kind = ExportKind.FUNCTION
            )
        }
    }

    /**
     * Emit let-bindings for record destructuring entries in a do-block.
     * Handles:
     * - Punning: `{ x }` → `let x = parent.x`
     * - Rename: `{ x as renamed }` → `let renamed = parent.x`
     * - Nested: `{ inner: { x, y } }` → `let __inner = parent.inner; let x = __inner.x; let y = __inner.y`
     */
    private fun emitRecordDestructureFields(
        entries: List<ParsedRecordPatternEntry>,
        parentVarName: String,
        parentType: MezzeType,
        loc: Loc,
        stmts: MutableList<TypedStatement>
    ) {
        for (field in entries) {
            val fieldName = field.name  // The record field name
            val fieldType = when (parentType) {
                is MezzeType.RecordType -> parentType.fields[fieldName] ?: MezzeType.VarType(TypeVar.freshWithName("field_$fieldName"))
                else -> MezzeType.VarType(TypeVar.freshWithName("field_$fieldName"))
            }
            val parentBinding = resolveBinding(parentVarName, null)
            val parentVar = TypedVar(parentVarName, parentBinding, parentType, loc)
            val fieldAccess = TypedFieldAccess(parentVar, fieldName, -1, fieldType, loc)

            when (val p = field.pattern) {
                null -> {
                    // Punning: { x } means { x = x }
                    identifierContext[fieldName] = fieldType
                    val fieldHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(fieldName) }.buildHash()
                    stmts.add(TypedStatement.Let(fieldName, fieldHash, fieldType, fieldAccess, loc))
                }
                is ParsedVariablePattern -> {
                    // Rename: { x as renamed }
                    val bindingName = p.name
                    identifierContext[bindingName] = fieldType
                    val fieldHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(bindingName) }.buildHash()
                    stmts.add(TypedStatement.Let(bindingName, fieldHash, fieldType, fieldAccess, loc))
                }
                is ParsedRecordPattern -> {
                    // Nested: { inner: { x, y } }
                    val nestedVarName = "__destr_${fieldName}_${stmts.size}"
                    identifierContext[nestedVarName] = fieldType
                    val nestedHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(nestedVarName) }.buildHash()
                    stmts.add(TypedStatement.Let(nestedVarName, nestedHash, fieldType, fieldAccess, loc))
                    emitRecordDestructureFields(p.entries, nestedVarName, fieldType, loc, stmts)
                }
                else -> {
                    // Other patterns (wildcard, etc.) — bind with field name
                    identifierContext[fieldName] = fieldType
                    val fieldHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(fieldName) }.buildHash()
                    stmts.add(TypedStatement.Let(fieldName, fieldHash, fieldType, fieldAccess, loc))
                }
            }
        }
    }

    /**
     * Emit top-level TypedLetDecls for record destructuring entries.
     * Handles renames and nested patterns, analogous to [emitRecordDestructureFields] for do-blocks.
     */
    private fun emitRecordDestructureDeclFields(
        entries: List<ParsedRecordPatternEntry>,
        parentVarName: String,
        parentType: MezzeType,
        loc: Loc,
        isPublic: Boolean,
        decls: MutableList<TypedDecl>,
        exports: MutableMap<String, ExportEntry>
    ) {
        for (entry in entries) {
            val fieldName = entry.name
            val fieldType = when (parentType) {
                is MezzeType.RecordType -> parentType.fields[fieldName] ?: MezzeType.VarType(TypeVar.freshWithName("field_$fieldName"))
                else -> MezzeType.VarType(TypeVar.freshWithName("field_$fieldName"))
            }
            val destrBinding = resolveBinding(parentVarName, null)
            val destrVar = TypedVar(parentVarName, destrBinding, parentType, loc)
            val fieldAccess = TypedFieldAccess(destrVar, fieldName, -1, fieldType, loc)

            when (val p = entry.pattern) {
                null -> {
                    // Punning: { x }
                    val fieldHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(fieldName) }.buildHash()
                    val fieldScheme = V2TypeScheme(emptyList(), emptyList(), fieldType)
                    decls.add(TypedLetDecl(fieldHash, fieldName, emptyList(), fieldAccess, fieldScheme, isPublic, loc))
                    if (isPublic) {
                        exports[fieldName] = ExportEntry(fieldName, fieldHash, fieldType, ExportKind.FUNCTION)
                    }
                }
                is ParsedVariablePattern -> {
                    // Rename: { x as renamed }
                    val bindingName = p.name
                    val fieldHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(bindingName) }.buildHash()
                    val fieldScheme = V2TypeScheme(emptyList(), emptyList(), fieldType)
                    decls.add(TypedLetDecl(fieldHash, bindingName, emptyList(), fieldAccess, fieldScheme, isPublic, loc))
                    if (isPublic) {
                        exports[bindingName] = ExportEntry(bindingName, fieldHash, fieldType, ExportKind.FUNCTION)
                    }
                }
                is ParsedRecordPattern -> {
                    // Nested: { inner: { x, y } }
                    val nestedVarName = "__destructured_${fieldName}_${decls.size}"
                    val nestedHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(nestedVarName) }.buildHash()
                    val nestedScheme = V2TypeScheme(emptyList(), emptyList(), fieldType)
                    decls.add(TypedLetDecl(nestedHash, nestedVarName, emptyList(), fieldAccess, nestedScheme, false, loc))
                    emitRecordDestructureDeclFields(p.entries, nestedVarName, fieldType, loc, isPublic, decls, exports)
                }
                else -> {
                    // Other patterns — bind with field name
                    val fieldHash = com.mezze.language.ast.hashing.MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(fieldName) }.buildHash()
                    val fieldScheme = V2TypeScheme(emptyList(), emptyList(), fieldType)
                    decls.add(TypedLetDecl(fieldHash, fieldName, emptyList(), fieldAccess, fieldScheme, isPublic, loc))
                    if (isPublic) {
                        exports[fieldName] = ExportEntry(fieldName, fieldHash, fieldType, ExportKind.FUNCTION)
                    }
                }
            }
        }
    }

    /** Stable string representation of a type annotation for hashing (no location info). */
    private fun typeAnnotationToString(ann: ParsedTypeAnnotation): String = when (ann) {
        is ParsedNamedType -> ann.name
        is ParsedQualifiedType -> ann.modulePath.joinToString("::") + "::" + ann.typeName +
            if (ann.args.isNotEmpty()) " " + ann.args.joinToString(" ") { typeAnnotationToString(it) } else ""
        is ParsedFunctionType -> "(" + typeAnnotationToString(ann.paramType) + " -> " + typeAnnotationToString(ann.returnType) + ")"
        is ParsedRecordType -> "{" + ann.entries.joinToString(", ") { it.name + ": " + typeAnnotationToString(it.type) } + "}"
        is ParsedTypeApp -> ann.constructor + " " + ann.args.joinToString(" ") { typeAnnotationToString(it) }
        is ParsedSeqType -> "[" + typeAnnotationToString(ann.elementType) + "]"
        is ParsedVariantType -> "|" + ann.constructors.joinToString(",") { it.name } + "|"
        else -> ann.javaClass.simpleName
    }

    private fun resolveEffectHash(effectName: String): Hash {
        val decl = effectDeclarations[effectName]
            ?: importContext?.importedEffects?.get(effectName)
            ?: error("Effect '$effectName' not found in declarations or imports")
        return decl.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashEffectDecl(decl)
    }

    /**
     * Produce a stable string representation of a type for use in content hashes.
     * Not for display — just for hash inputs.
     */
    private fun typeToString(type: MezzeType): String {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "Str"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.CharType -> "Char"
            is MezzeType.BytesType -> "Bytes"
            is MezzeType.ByteBufferType -> "ByteBuffer"
            is MezzeType.VarType -> "'${type.variable.id}"
            is MezzeType.FunctionType -> "(${typeToString(type.paramType)})->(${typeToString(type.returnType)})"
            is MezzeType.RecordType -> "{${type.fields.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${typeToString(it.value)}" }}}"
            is MezzeType.VariantType -> "|${type.constructors.keys.sorted().joinToString(",")}|"
            is MezzeType.SeqType -> "[${typeToString(type.elementType)}]"
            is MezzeType.TypeApplication -> "${type.constructor}<${type.args.joinToString(",") { typeToString(it) }}>"
            else -> type.toString()
        }
    }

    /**
     * Get the simple type name used for ability impl matching.
     * Returns the type name string that matches against AbilityImplInfo.targetTypes.
     */
    private fun getTypeNameForImpl(type: MezzeType): String? = when (type) {
        is MezzeType.IntType -> "Int"
        is MezzeType.FloatType -> "Float"
        is MezzeType.StringType -> "Str"
        is MezzeType.BoolType -> "Bool"
        is MezzeType.NatType -> "Nat"
        is MezzeType.FracType -> "Frac"
        is MezzeType.CharType -> "Char"
        is MezzeType.BytesType -> "Bytes"
        is MezzeType.OrderingType -> "Ordering"
        is MezzeType.SeqType -> "[${getTypeNameForImpl(type.elementType) ?: "?"}]"
        is MezzeType.ListType -> "List_${getTypeNameForImpl(type.elementType) ?: "?"}"
        is MezzeType.SetType -> "Set_${getTypeNameForImpl(type.elementType) ?: "?"}"
        is MezzeType.MapType -> "Map_${getTypeNameForImpl(type.keyType) ?: "?"}_${getTypeNameForImpl(type.valueType) ?: "?"}"
        is MezzeType.TypeApplication -> type.constructor
        is MezzeType.RecordType -> type.typeName
        is MezzeType.VariantType -> type.typeName
        else -> null
    }

    /**
     * Resolve an ability method call to a BindingRef.
     *
     * Uses the ability impl index to find the impl matching the receiver type,
     * then resolves the method binding within that impl.
     *
     * Returns null if no matching impl is found (falls back to existing logic).
     */
    private fun resolveAbilityMethodBinding(
        abilityName: String,
        methodName: String,
        receiverType: MezzeType
    ): BindingRef? {
        val typeName = getTypeNameForImpl(receiverType) ?: return null
        val impls = abilityImplIndex[abilityName] ?: return null

        // Find the impl that matches the receiver type
        val matchingImpl = impls.firstOrNull { entry ->
            entry.targetTypes.any { it == typeName }
        }

        if (matchingImpl != null) {
            // We found the impl. Now we need the method's content hash.
            // The impl hash is matchingImpl.contentHash. The method binding
            // within the impl should be in identifierContentHashes as a qualified binding.
            // Try: BindingKey(abilityName, methodName) or the method name in the impl.
            val qualKey = BindingKey(abilityName, methodName)
            identifierContentHashes[qualKey]?.let {
                return BindingRef.ByHash(it)
            }

            // Try unqualified method name
            val unqualKey = BindingKey.unqualified(methodName)
            identifierContentHashes[unqualKey]?.let {
                return BindingRef.ByHash(it)
            }

            // Try import context
            importContext?.importedBindings?.get(methodName)?.let { binding ->
                return BindingRef.ByHash(binding.contentHash)
            }

            // Fall back to using the impl content hash itself as the method reference
            return BindingRef.ByHash(matchingImpl.contentHash)
        }

        return null
    }

    /**
     * Resolve an inherent method call to a BindingRef.
     *
     * Uses the impl content hash from MethodCallInfo to find the method binding.
     */
    private fun resolveInherentMethodBinding(
        methodName: String,
        implContentHash: Hash?
    ): BindingRef? {
        if (implContentHash != null) {
            return BindingRef.ByHash(implContentHash)
        }

        // Fallback: try to find by method name
        val unqualKey = BindingKey.unqualified(methodName)
        identifierContentHashes[unqualKey]?.let {
            return BindingRef.ByHash(it)
        }

        importContext?.importedBindings?.get(methodName)?.let { binding ->
            return BindingRef.ByHash(binding.contentHash)
        }

        return null
    }
}
