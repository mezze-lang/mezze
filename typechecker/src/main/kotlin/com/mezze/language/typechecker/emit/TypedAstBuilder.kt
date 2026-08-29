package com.mezze.language.typechecker.emit

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
    private val substitution: Substitution,
    private val constraintResults: Map<String, ConstraintResult>,
    private val abilityImpls: Map<Hash, TypingContext.AbilityImplInfo>,
    private val registry: DeclarationRegistry,
    private val performerToEffect: Map<String, String>,
    private val importContext: ModuleImportContext?,
    private val identifierContentHashes: Map<BindingKey, Hash>,
    private val implDeclarations: Map<String, ParsedPerformerDecl>,
    private val tailCallLocations: Set<Loc>,
    private val mustBeOpenRowVars: Set<RowVar> = emptySet(),
    private val methodCallResolutions: Map<Pair<Loc, String>, MethodCallInfo> = emptyMap(),
    private val callReturnTypes: Map<Loc, MezzeType> = emptyMap(),
    // #195 mono-fix #1: per-match-case binding name → solver TyVar. The
    // constraint generator records the SAME fresh TyVars that PatternConstraints
    // allocated and HasField unified with concrete types; applying `substitution`
    // here gives the resolved type for each binding. Used in buildMatchCase to
    // populate PatternBinding.type + identifierContext for guard/body emit
    // instead of constructing fresh disconnected placeholders.
    private val matchCaseBindings: Map<Loc, Map<String, MezzeType>> = emptyMap(),
) {

    private val identifierContext = mutableMapOf<String, MezzeType>()

    private val typeVarCache = mutableMapOf<String, TypeVar>()

    /**
     * In-scope CLOSED record aliases, as (name, field-name→field-type). Built
     * once from local decls + imported types (so `pub`/import scoping is
     * respected — a private or un-imported alias never appears here). Used to
     * LABEL a bare record literal whose finalized shape exactly matches one:
     * `type Lang = { name: Str, released: Bool }` + `{ name = "M", released =
     * False }` ⇒ the literal's RecordType.typeName becomes "Lang". This is a
     * display/label only — the type stays structurally the same record, so it
     * still passes anywhere the bare `{ name, released }` (open OR closed) is
     * accepted. Same-shape collisions are impossible (rejected at declaration).
     */
    private val closedRecordAliases: List<Pair<String, Map<String, MezzeType>>> by lazy {
        val decls = registry.allTypeDecls + (importContext?.importedTypes?.values ?: emptyList())
        decls.mapNotNull { decl ->
            val def = decl.definition
            if (def is ParsedRecordDef && !def.isOpen) {
                decl.name to def.fields.associate { it.name to convertParsedTypeAnnotation(it.type) }
            } else null
        }
    }

    /** Retired — `type X = { … }` is now transparent; adoption happens at display time. */
    private fun labelRecordWithAlias(record: MezzeType.RecordType): MezzeType.RecordType = record

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

    private fun pushScope(names: MutableList<String>, isLambdaBoundary: Boolean) {
        lambdaParamScopes.addLast(names)
        lambdaScopeIsLambdaBoundary.addLast(isLambdaBoundary)
    }

    private fun popScope() {
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
            val destrName = "__destructured_${decls.size}"
            val loc = decl.loc
            val rawType = constraintResults[destrName]?.let { substitution.apply(it.type) }
                ?: constraintResults["__destructured__"]?.let { substitution.apply(it.type) }
                ?: MezzeType.VarType(TypeVar.freshWithName("unknown"))
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
            ?: MezzeType.VarType(TypeVar.freshWithName("unknown"))
        val closedType = closeGeneratedRowVars(rawType, mustBeOpenRowVars)
        // If the binding's inferred type is a bare closed record whose shape
        // exactly matches an in-scope closed alias, LABEL it with that alias name
        // so `x` reads as `Lang` (hover, errors, signatures). Structural, so `x`
        // still passes anywhere the bare record — open or closed — is accepted.
        val type = (closedType as? MezzeType.RecordType)?.let { labelRecordWithAlias(it) } ?: closedType

        val value = buildExpr(decl.value, file, type)

        identifierContext[name] = type

        val hash = ParsedDeclHasher.hashLetDecl(decl, file)
            ?: ParsedDeclHasher.hashLetDecl(decl)

        val freeVars = collectTypeVarsInOrder(type, sortFields = false)
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
        val hash = decl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(decl)

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
            dependencies = emptyList(),
            operations = ops,
            loc = loc
        ))

        exports[decl.name] = ExportEntry(decl.name, hash, MezzeType.VarType(TypeVar.freshWithName(decl.name)), ExportKind.ABILITY)
    }

    fun buildCodecImpl(decl: ParsedImplAbilityDecl, impls: MutableList<TypedAbilityImpl>) {
        buildAbilityImpl(decl, "<codec-generated>", impls)
    }

    private fun buildAbilityImpl(
        decl: ParsedImplAbilityDecl,
        file: String,
        impls: MutableList<TypedAbilityImpl>
    ) {
        val loc = decl.loc

        val abilityHash = when (val target = decl.target) {
            is ParsedImplTarget.Named -> {
                val abilityName = target.ability.localName
                val abilityDecl = registry.lookupAbility(abilityName)
                    ?: importContext?.importedAbilities?.get(abilityName)
                    ?: error("Ability '$abilityName' not found in declarations or imports")
                abilityDecl.contentHash ?: ParsedDeclHasher.hashAbilityDecl(abilityDecl)
            }
            is ParsedImplTarget.Inherent -> Hash.ZERO
        }

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

        val hash = decl.contentHash ?: ParsedDeclHasher.hashImplAbilityDecl(decl)

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
        val hash = decl.contentHash ?: ParsedDeclHasher.hashEffectDecl(decl)

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
        val contentHash = decl.contentHash
            ?: implDeclarations[decl.performerName]?.contentHash
            ?: identifierContentHashes[BindingKey(null, decl.performerName)]
        val hash = decl.contentHash ?: ParsedDeclHasher.hashPerformerDecl(decl)

        val effectName = decl.effect.localName
        val effectHash = resolveEffectHash(effectName)

        val params = decl.params.map { param ->
            typeVarCache.clear()
            TypedPerformerParam(param.name, convertParsedTypeAnnotation(param.type), param.loc)
        }

        val assocTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
            convertParsedTypeAnnotation(ann)
        }

        val ops = decl.operations.associate { op ->
            val opExpr = buildExpr(op.implementation, file, MezzeType.VarType(TypeVar.freshWithName("op_${op.name}")))
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

    fun buildExpr(expr: ParsedExpr, file: String, contextType: MezzeType = MezzeType.VarType(TypeVar.freshWithName("unknown"))): TypedExpr {
        val loc = expr.loc
        return when (expr) {
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
            is ParsedNewtypeConstruct -> {
                // Runtime erasure: newtype construction is identity.
                buildExpr(expr.value, file, contextType)
            }
            is ParsedVariantConstruct -> {
                if (expr.constructor == BuiltinTypes.TRUE && expr.value == null) {
                    TypedBoolLit(true, MezzeType.BoolType, loc)
                } else if (expr.constructor == BuiltinTypes.FALSE && expr.value == null) {
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
            is ParsedIdentifier -> {
                val name = expr.localName
                val qualifier = expr.qualifier

                val type = resolveIdentifierType(name, qualifier)

                val binding = resolveBinding(name, qualifier)

                TypedVar(name, binding, type, loc)
            }
            is ParsedLambda -> {
                val params = buildLambdaParams(expr.params, contextType, loc)

                val savedContext = mutableMapOf<String, MezzeType?>()
                for (param in params) {
                    savedContext[param.name] = identifierContext[param.name]
                    identifierContext[param.name] = param.type
                }

                pushScope(params.map { it.name }.toMutableList(), isLambdaBoundary = true)

                val returnType = when (val ct = substitution.apply(contextType)) {
                    is MezzeType.FunctionType -> substitution.apply(ct.returnType)
                    else -> MezzeType.VarType(TypeVar.freshWithName("ret"))
                }

                val body = buildExpr(expr.body, file, returnType)

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
                val ctxFnType = substitution.apply(contextType) as? MezzeType.FunctionType
                val paramRecordType = ctxFnType?.paramType
                    ?: MezzeType.RecordType(params.associate { p -> p.name to p.type })
                val funcType = MezzeType.FunctionType(
                    paramType = paramRecordType,
                    returnType = body.type,
                    effects = ctxFnType?.effects
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
            is ParsedRecordLit -> {
                // #261: if the record's expected type is a concrete record (e.g.
                // this record is a call argument and `contextType` is the callee's
                // param type), thread each field's expected type down to its value.
                // A field whose value is a lambda then gets its param types pushed
                // from the declaration, so an untyped callback param is stamped
                // concrete for ability-method dispatch.
                val expectedFields = (substitution.apply(contextType) as? MezzeType.RecordType)?.fields
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
                val recordType = labelRecordWithAlias(MezzeType.RecordType(fieldTypes))

                TypedRecord(entries, recordType, loc)
            }
            is ParsedIf -> {
                val cond = buildExpr(expr.condition, file, MezzeType.BoolType)
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
                    val cases = expr.cases.map { c ->
                        buildMatchCase(c, file, contextType)
                    }
                    val resultType = cases.firstOrNull()?.body?.type ?: substitution.apply(contextType)
                    TypedMatch(scrutinee, cases, resultType, loc)
                }
            }
            is ParsedDo -> buildDoBlock(expr, file, contextType)
            is ParsedSeqLit -> {
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
                TypedVar("@", BindingRef.Local(0, 0), substitution.apply(contextType), loc)
            }
            is ParsedParens -> buildExpr(expr.expr, file, contextType)
            is ParsedPerform -> {
                val handlers = expr.handlers.map { h ->
                    buildHandlerRef(h, file, loc)
                }
                val body = buildExpr(expr.body, file, contextType)
                val performType = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                    ?: body.type
                TypedPerform(handlers, body, performType, loc)
            }
            is ParsedEffectCall -> {
                val opName = expr.operation.localName
                val qualifier = expr.operation.qualifier

                val effectName = qualifier?.segments?.lastOrNull() ?: opName
                val effectHash = resolveEffectHash(effectName)

                val argExpr = buildExpr(expr.arguments, file)

                TypedPerformOp(effectHash, opName, argExpr, substitution.apply(contextType), loc)
            }
        }
    }

    private fun buildFunctionCall(expr: ParsedFunctionCall, file: String, contextType: MezzeType): TypedExpr {
        val loc = expr.loc

        if (expr.function is ParsedMemberAccess) {
            val memberAccess = expr.function as ParsedMemberAccess
            val receiverName = when (val r = memberAccess.receiver) {
                is ParsedIdentifier -> r.localName
                is ParsedVariantConstruct -> r.constructor
                else -> null
            }
            if (receiverName != null) {
                val isEffect = registry.lookupEffect(receiverName) != null
                    || importContext?.importedEffects?.containsKey(receiverName) == true
                if (isEffect) {
                    val effectHash = resolveEffectHash(receiverName)
                    val opName = memberAccess.member
                    val argExpr = buildExpr(expr.arguments, file)
                    val effectDecl = registry.lookupEffect(receiverName)
                        ?: importContext?.importedEffects?.get(receiverName)
                    val resolvedReturnType = effectDecl?.operations
                        ?.firstOrNull { it.name == opName }
                        ?.let { convertParsedTypeAnnotation(it.returnType) }
                    val applied = substitution.apply(contextType)
                    val finalType = if (applied is MezzeType.VarType && resolvedReturnType != null) {
                        resolvedReturnType
                    } else applied
                    return TypedPerformOp(effectHash, opName, argExpr, finalType, loc)
                }
            }
        }

        val fnIsMemberAccess = expr.function is ParsedMemberAccess
        val memberName = if (fnIsMemberAccess) (expr.function as ParsedMemberAccess).member else null
        val methodInfo = if (memberName != null) methodCallResolutions[expr.loc to memberName] else null
        if (methodInfo != null && fnIsMemberAccess) {
            return buildMethodCall(expr, methodInfo, file, contextType)
        }

        val fn = buildExpr(expr.function, file)
        // #261: thread the callee's PARAM type into the argument so an untyped
        // callback param whose type is pushed from a declared fn type
        // (`apply: { f: { x: Int } -> Str }` ⇒ `apply { f = { x } -> … }`) is
        // stamped CONCRETE at emit. Without this, the arg record — and the lambda
        // inside it — is built context-free, the pushed `Int` never reaches the
        // param, and an ability-method call on it emits an uncached CallIndirect
        // that crashes at runtime (mono's methodReceiverNotConcrete bail).
        val fnParamType = (substitution.apply(fn.type) as? MezzeType.FunctionType)?.paramType
        val argExpr = if (fnParamType != null) buildExpr(expr.arguments, file, fnParamType)
            else buildExpr(expr.arguments, file)

        val solvedReturn = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
        val callType = solvedReturn
            ?: when (val ft = substitution.apply(fn.type)) {
                is MezzeType.FunctionType -> substitution.apply(ft.returnType)
                else -> substitution.apply(contextType)
            }

        val isTailCall = isTailCallLocation(expr.loc)

        return TypedCall(
            function = fn,
            arg = argExpr,
            instantiation = null,
            isTailCall = isTailCall,
            type = callType,
            loc = loc
        )
    }

    private fun buildMethodCall(
        expr: ParsedFunctionCall,
        methodInfo: ConstraintResult.MethodCallInfo,
        file: String,
        contextType: MezzeType,
    ): TypedCall {
        val loc = expr.loc
        val memberAccess = expr.function as ParsedMemberAccess
        val receiverExpr = buildExpr(memberAccess.receiver, file)
        val argExpr = buildExpr(expr.arguments, file)

        val solvedFnType = methodInfo.methodFunctionType?.let { substitution.apply(it) }

        // #160: when the inherent impl was deferred (the receiver's concrete
        // type was only known after solving, e.g. `help {..}.to_str {}`), pick
        // the candidate whose target matches the SOLVED receiver type. Falls
        // back to the statically-recorded implContentHash if no candidate
        // matches (e.g. a receiver that solved to a non-candidate type — the
        // solver would already have errored in practice).
        val resolvedImplHash: Hash? = run {
            if (methodInfo.candidateImpls.isEmpty()) return@run methodInfo.implContentHash
            val solvedReceiver = substitution.apply(receiverExpr.type)
            // #280/F1: prefer the SOLVED receiver's declaration hash — it is the
            // rename-safe, module-distinct identity, so two same-named inherent
            // impls from different modules pick correctly instead of by first-name-
            // match (silent wrong-body dispatch). Name only when the receiver has
            // no hash (builtins). Mirrors ExprMethodConstraints' resolution.
            val receiverHash = ((solvedReceiver as? MezzeType.VariantType)?.identity as? TypeIdentity.Declared)?.hash
                ?: ((solvedReceiver as? MezzeType.TypeApplication)?.identity as? TypeIdentity.Declared)?.hash
            val byHash = if (receiverHash != null) {
                methodInfo.candidateImpls.firstOrNull { it.targetTypeHash == receiverHash }?.implContentHash
            } else null
            byHash ?: run {
                val name = receiverTypeName(solvedReceiver)
                methodInfo.candidateImpls.firstOrNull { it.targetTypeName == name }?.implContentHash
            } ?: methodInfo.implContentHash
        }

        val receiverFieldName = methodInfo.receiverFieldName

        // #226: the STATIC ability-call form `Ability.method { it = v, ... }` carries
        // the receiver in the args' `it` field; `memberAccess.receiver` is the
        // ability NAME (a nullary variant), not the receiver value. Recover the real
        // receiver from the args so both the `fn` field-access receiver (built-in
        // dispatch reads its TYPE — must be `Int`, not the ability-variant VarType,
        // else built-in Eq/Ord route to the wrong dispatch and return a wrong result)
        // and the wrapped arg use the actual value.
        val staticReceiverValue: TypedExpr? =
            if (argExpr is TypedRecord) {
                argExpr.entries.filterIsInstance<TypedRecordEntry.Field>()
                    .firstOrNull { it.name == receiverFieldName }?.value
            } else null

        val fn = if (methodInfo.methodHash != null) {
            val hash = methodInfo.methodHash!!
            TypedVar(
                name = methodInfo.methodName,
                binding = BindingRef.ByHash(hash),
                type = solvedFnType ?: substitution.apply(contextType),
                // Narrow member-name loc, not the whole `receiver.method` span,
                // so hover/goto-def on a chained call resolve to THIS method
                // rather than the outermost one. Safe for tree descent: the
                // receiver lives in the call's ARG record ({it: receiver}), not
                // inside this function node, so narrowing it strands nothing.
                loc = memberAccess.memberLoc
            )
        } else if (methodInfo.isInherentMethod && resolvedImplHash != null) {
            val implHash = resolvedImplHash
            val opHash = Hash.computeOpHash(implHash, methodInfo.methodName)
            TypedVar(
                name = methodInfo.methodName,
                binding = BindingRef.ByHash(opHash),
                type = solvedFnType ?: substitution.apply(contextType),
                loc = memberAccess.memberLoc
            )
        } else if (staticReceiverValue != null) {
            // Built-in (or otherwise unresolved) static ability call: rebuild the
            // field-access on the real receiver VALUE so the compiler's receiverType
            // (fn.record.type) is the element type, giving the correct dispatch.
            TypedFieldAccess(staticReceiverValue, methodInfo.methodName, -1, solvedFnType ?: substitution.apply(contextType), memberAccess.memberLoc)
        } else {
            buildExpr(expr.function, file)
        }

        // #226: the STATIC ability-call form `Ability.method { it = v, ... }`
        // (desugared to a method call by the typechecker) already carries the
        // receiver in the args' `it` field — the `memberAccess.receiver` here is
        // the ability NAME (a nullary variant), NOT the receiver value. Building
        // `receiverExpr` from it and prepending `{ it = <Ability-variant> }`
        // would shadow the real `it = v` (duplicate field), so the body would
        // see the ability variant instead of the argument (silent wrong result,
        // or a crash for arithmetic bodies). Detect this structurally: if the
        // arg record ALREADY binds `receiverFieldName`, that field IS the
        // receiver — don't synthesize another from the name.
        val argsAlreadyHaveReceiver = argExpr is TypedRecord &&
            argExpr.entries.any { it is TypedRecordEntry.Field && it.name == receiverFieldName }

        val wrappedArg = when {
            argsAlreadyHaveReceiver -> argExpr as TypedRecord
            argExpr is TypedRecord -> {
                val receiverField = TypedRecordEntry.Field(receiverFieldName, receiverExpr, memberAccess.receiver.loc)
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
                val receiverField = TypedRecordEntry.Field(receiverFieldName, receiverExpr, memberAccess.receiver.loc)
                val entries = listOf(receiverField)
                TypedRecord(entries, MezzeType.RecordType(mapOf(receiverFieldName to receiverExpr.type)), loc)
            }
        }

        val callReturnType = when (val ft = substitution.apply(fn.type)) {
            is MezzeType.FunctionType -> substitution.apply(ft.returnType)
            else -> {
                val perCallSite = callReturnTypes[expr.loc]?.let { substitution.apply(it) }
                perCallSite ?: substitution.apply(contextType)
            }
        }

        // Correct the method-var's displayed type to the ACTUAL call site. For a
        // deferred inherent method (receiver pinned only after solving, e.g.
        // `…eq {}.to_str {}` where the receiver is the Bool result of `.eq`),
        // `methodFunctionType` was captured against the wrong candidate receiver
        // (often the first / stale one — `{ it: Int }`), so hover/goto-def showed
        // the wrong receiver even though dispatch (resolvedImplHash) and the arg
        // record are correct. Rebuild the fn type from the real param record
        // (wrappedArg.type carries `{ it: <solvedReceiver>, … }`) and return type.
        val correctedFn = if (fn is TypedVar && fn.name == methodInfo.methodName) {
            fn.copy(type = MezzeType.FunctionType(
                paramType = substitution.apply(wrappedArg.type),
                returnType = callReturnType,
            ))
        } else fn

        val isTailCall = isTailCallLocation(expr.loc)

        val typeArgsResolved = methodInfo.typeArgs.map { substitution.apply(it) }
        val abilityImplsResolved = methodInfo.abilityImpls.map { ref ->
            AbilityImplRef(
                abilityHash = ref.abilityHash,
                implHash = ref.implHash,
                targetType = substitution.apply(ref.targetType),
            )
        }
        val instantiation = if (typeArgsResolved.isEmpty() && abilityImplsResolved.isEmpty()) {
            null
        } else {
            TypeInstantiation(
                typeArgs = typeArgsResolved,
                abilityImpls = abilityImplsResolved,
            )
        }

        return TypedCall(
            function = correctedFn,
            arg = wrappedArg,
            instantiation = instantiation,
            isTailCall = isTailCall,
            type = callReturnType,
            loc = loc
        )
    }

    private fun buildDoBlock(expr: ParsedDo, file: String, contextType: MezzeType): TypedBlock {
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
                        val destrName = "__destr_${srcIdx}"
                        val destrHash = ParsedDeclHasher.hashExpr(stmt.value)
                        val valueType = stmt.typeAnnotation
                            ?.let { substitution.apply(convertParsedTypeAnnotation(it)) }
                            ?: MezzeType.VarType(TypeVar.freshWithName("let_destr"))
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
                            ?: MezzeType.VarType(TypeVar.freshWithName("let_$name"))

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

    private fun buildMatchCase(case: ParsedMatchCase, file: String, contextType: MezzeType): TypedCase {
        val patterns = case.patterns.map { buildPattern(it, file) }
        val bindingNames = case.patterns.flatMap { p -> p.extractBindings() }.toMutableList()

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

        pushScope(bindingNames.toMutableList(), isLambdaBoundary = false)
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

        for ((name, prev) in savedContext) {
            if (prev != null) identifierContext[name] = prev else identifierContext.remove(name)
        }
        popScope()

        return TypedCase(patterns, bindings, guard, body, case.loc, typedIfLetBindings)
    }

    private fun buildPattern(pattern: ParsedPattern, file: String): TypedPattern {
        val unknownType = MezzeType.VarType(TypeVar.freshWithName("pat"))
        val loc = pattern.loc
        return when (pattern) {
            is ParsedVariablePattern -> TypedVarPattern(pattern.name, unknownType, loc)
            is ParsedWildcardPattern -> TypedWildcardPattern(unknownType, loc)
            is ParsedConstructorPattern -> {
                if (pattern.constructor == BuiltinTypes.TRUE && pattern.payload == null) {
                    TypedLiteralPattern(TypedBoolLit(true, MezzeType.BoolType, loc), MezzeType.BoolType, loc)
                } else if (pattern.constructor == BuiltinTypes.FALSE && pattern.payload == null) {
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
            is ParsedNewtypePattern -> {
                // Runtime erasure: newtype destructuring is identity.
                buildPattern(pattern.inner, file)
            }
        }
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

        return TypedHandlerRef(
            performerHash = performerHash,
            effectHash = effectHash,
            performerName = performerName,
            params = paramsExpr,
            loc = loc
        )
    }

    private fun resolveIdentifierType(name: String, qualifier: ParsedModulePath?): MezzeType {
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

        identifierContext[name]?.let { return substitution.apply(it) }

        constraintResults[name]?.let { result ->
            return substitution.apply(result.type)
        }

        importContext?.importedBindings?.get(name)?.type?.let {
            return substitution.apply(it)
        }

        return MezzeType.VarType(TypeVar.freshWithName("unknown"))
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

    private fun isTailCallLocation(loc: Loc): Boolean {
        return loc in tailCallLocations || tailCallLocations.any { tl ->
            tl.startLine == loc.startLine && tl.startCol == loc.startCol && tl.file == loc.file
        }
    }

    private fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
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
                MezzeType.SeqType(elementType = convertParsedTypeAnnotation(annotation.elementType))
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
            emitBinding = { name, hash, type, access ->
                identifierContext[name] = type
                stmts.add(TypedStatement.Let(name, hash, type, access, loc))
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
            emitBinding = { name, hash, type, access ->
                val scheme = V2TypeScheme(emptyList(), emptyList(), type)
                decls.add(TypedLetDecl(hash, name, emptyList(), access, scheme, isPublic, loc))
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
        emitBinding: (name: String, hash: Hash, type: MezzeType, access: TypedFieldAccess) -> Unit,
        emitNested: (entries: List<ParsedRecordPatternEntry>, fieldName: String, fieldType: MezzeType, fieldAccess: TypedFieldAccess) -> Unit
    ) {
        for (entry in entries) {
            val fieldName = entry.name
            val fieldType = when (parentType) {
                is MezzeType.RecordType -> parentType.fields[fieldName] ?: MezzeType.VarType(TypeVar.freshWithName("field_$fieldName"))
                else -> MezzeType.VarType(TypeVar.freshWithName("field_$fieldName"))
            }
            val parentBinding = resolveBinding(parentVarName, null)
            val parentVar = TypedVar(parentVarName, parentBinding, parentType, loc)
            val fieldAccess = TypedFieldAccess(parentVar, fieldName, -1, fieldType, loc)

            when (val p = entry.pattern) {
                null -> emitBinding(fieldName, fieldHash(parentVarName, fieldName), fieldType, fieldAccess)
                is ParsedVariablePattern -> emitBinding(p.name, fieldHash(parentVarName, p.name), fieldType, fieldAccess)
                is ParsedRecordPattern -> emitNested(p.entries, fieldName, fieldType, fieldAccess)
                else -> emitBinding(fieldName, fieldHash(parentVarName, fieldName), fieldType, fieldAccess)
            }
        }
    }

    private fun fieldHash(parentVarName: String, name: String): Hash =
        MezzeHashBuilder().also { it.append("field"); it.append(parentVarName); it.append(name) }.buildHash()

    private fun resolveEffectHash(effectName: String): Hash {
        val decl = registry.lookupEffect(effectName)
            ?: importContext?.importedEffects?.get(effectName)
            ?: error("Effect '$effectName' not found in declarations or imports")
        return decl.contentHash ?: ParsedDeclHasher.hashEffectDecl(decl)
    }

}
