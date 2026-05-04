package com.mezze.language.typechecker.context

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashes
import com.mezze.language.typechecker.validation.AbilityValidator
import com.mezze.language.typechecker.validation.DuplicateDeclarationValidator

/**
 * Builds immutable typing context from program declarations.
 *
 * Phase 1 of type checking: Build environment.
 * - Process type declarations
 * - Process ability declarations
 * - Process effect declarations
 * - Create type schemes for top-level bindings
 * - NO type inference, NO validation (those are later phases)
 *
 * Replaces the scattered context-building logic throughout TypeChecker
 * with a single, clear phase.
 */
class ContextBuilder {
    /**
     * Type variable cache to ensure all occurrences of a type variable name
     * within a single type declaration refer to the same TypeVar instance.
     *
     * CRITICAL: This prevents occurs check failures where 'a in the type
     * parameters and 'a in the payload would be different instances, causing
     * the type system to think we have infinite types like 'a = {val: 'a}.
     */
    private val typeVarCache = mutableMapOf<String, TypeVar>()

    /**
     * Effect declarations available during the current build() invocation.
     * Populated at the start of build() so that convertTypeAnnotationV1Internal
     * can resolve effect names to their content hashes for EffectRow.effectHashes.
     */
    private var currentEffectDecls: Map<String, ParsedEffectDecl> = emptyMap()

    /**
     * Ability declarations available during the current build() invocation.
     * Populated at the start of build() so that convertTypeAnnotationV1Internal
     * can resolve ability names to their content hashes for FunctionAbilityConstraint.abilityHash.
     */
    private var currentAbilityDecls: Map<String, ParsedAbilityDecl> = emptyMap()

    /**
     * Type declarations available during the current build() invocation.
     * Populated at the start of build() so that convertTypeAnnotationV1Internal
     * can resolve type constructor names to their content hashes for TypeApplication.typeHash.
     */
    private var currentTypeDecls: Map<String, ParsedTypeDecl> = emptyMap()

    /**
     * Build result - contains all typing environments
     */
    data class BuildResult(
        val context: TypingContext,
        val typeDeclarations: Map<String, ParsedTypeDecl>,
        val abilityDeclarations: Map<String, ParsedAbilityDecl>,
        val effectDeclarations: Map<String, ParsedEffectDecl>,
        val performerToEffect: Map<String, String>,  // performer name → effect name
        val performerToEffectHash: Map<com.mezze.language.typedast.Hash, com.mezze.language.typedast.Hash> = emptyMap(),  // performer contentHash → effect contentHash
        /** Maps performer names to the set of effects they ADD (not handle) */
        val performerAddedEffects: Map<String, Set<String>> = emptyMap(),
        /** Maps performer names to their ParsedPerformerDecl (for content hash lookup) */
        val implDeclarations: Map<String, ParsedPerformerDecl> = emptyMap(),
        /** ParsedModule with impl declarations resolved (ParsedPerformerDecl for abilities → ParsedImplAbilityDecl) */
        val resolvedModule: ParsedModule? = null
    )

    /**
     * Build typing context from a parsed module.
     * This is a pure function - no side effects!
     *
     * @param module The parsed module to build context from
     * @param parentContext Optional parent context (for nested scopes/modules)
     * @param importedImpls Optional imported effect implementations (from module imports)
     * @param importedAbilities Optional imported ability declarations (for validating ability impls with qualified names)
     * @param importedTypes Optional imported type declarations (for orphan rule validation)
     */
    fun build(
        module: ParsedModule,
        parentContext: TypingContext = TypingContext.empty,
        importedImpls: Map<String, com.mezze.language.parsedast.ParsedPerformerDecl> = emptyMap(),
        importedAbilities: Map<String, ParsedAbilityDecl> = emptyMap(),
        importedTypes: Map<String, ParsedTypeDecl> = emptyMap(),
        importedEffects: Map<String, ParsedEffectDecl> = emptyMap(),
        currentModulePath: String? = null
    ): BuildResult {
        val file = module.path

        // Collect declarations by type — use v2 ParsedDecl types directly
        val typeDecls = module.decls.filterIsInstance<ParsedTypeDecl>()
            .associateBy { it.name }
            .toMutableMap()  // Make mutable so we can add built-in types

        // Add built-in variant types (Option, Result) if not already defined
        addBuiltInParsedTypes(typeDecls)

        val abilityDecls = module.decls.filterIsInstance<ParsedAbilityDecl>()
            .associateBy { it.name }

        val effectDecls = module.decls.filterIsInstance<ParsedEffectDecl>()
            .associateBy { it.name }
            .mapValues { (_, decl) ->
                if (decl.contentHash != null) decl
                else {
                    val hash = com.mezze.language.parsedast.ParsedDeclHasher.hashEffectDecl(decl)
                    if (hash != null) decl.copy(contentHash = hash) else decl
                }
            }

        // v1 conversions completely removed — all consumers now use v2 types directly

        // Resolve impl declarations: the parser can't distinguish ability impls from
        // effect impls (same `impl X for Y where {}` syntax). The typechecker knows which
        // names are abilities vs effects from imports, so we resolve here.
        val allAbilityNames = abilityDecls.keys + importedAbilities.keys +
            importedAbilities.values.map { it.name }.toSet()
        val allEffectNames = effectDecls.keys + importedEffects.keys +
            importedEffects.values.map { it.name }.toSet()

        val resolvedParsedPerformerDecls = mutableListOf<ParsedPerformerDecl>()  // real effect performers
        val resolvedAbilityImplDecls = mutableListOf<ParsedImplAbilityDecl>()  // ability impls
        // Track which ParsedPerformerDecl were converted to ability impls (by identity)
        val convertedPerformerDecls = mutableMapOf<ParsedPerformerDecl, ParsedImplAbilityDecl>()

        // First, add explicitly parsed ability impls (compute content hashes if needed)
        for (impl in module.decls.filterIsInstance<ParsedImplAbilityDecl>()) {
            resolvedAbilityImplDecls.add(ensureContentHash(impl, file))
        }

        // Then resolve ParsedPerformerDecl: ability impl or effect performer?
        for (perfDecl in module.decls.filterIsInstance<ParsedPerformerDecl>()) {
            val refName = perfDecl.effect.localName
            val isAbility = refName in allAbilityNames
            val isEffect = refName in allEffectNames

            if (isAbility && !isEffect) {
                // This is actually an ability impl, convert it to ParsedImplAbilityDecl
                val converted = ParsedImplAbilityDecl(
                    target = ParsedImplTarget.Named(perfDecl.effect),
                    targetTypes = listOf(ParsedNamedType(perfDecl.performerName, perfDecl.loc)),
                    forallVars = emptyList(),
                    associatedTypeBindings = perfDecl.associatedTypeBindings,
                    operations = perfDecl.operations.map { op ->
                        ParsedImplOp(
                            name = op.name,
                            paramType = op.paramType?.takeUnless { it is ParsedRecordType && it.entries.isEmpty() },
                            returnType = op.returnType?.takeUnless { it is ParsedRecordType && it.entries.isEmpty() },
                            implementation = op.implementation,
                            abilityConstraints = emptyList(),
                            loc = op.loc
                        )
                    },
                    loc = perfDecl.loc,
                    contentHash = null
                )
                val withHash = ensureContentHash(converted, file)
                resolvedAbilityImplDecls.add(withHash)
                convertedPerformerDecls[perfDecl] = withHash
            } else {
                // Real effect performer — compute content hash via ParsedDeclHasher
                val perfWithHash = if (perfDecl.contentHash != null) perfDecl
                    else perfDecl.copy(contentHash = com.mezze.language.parsedast.ParsedDeclHasher.hashPerformerDecl(perfDecl))
                resolvedParsedPerformerDecls.add(perfWithHash)
            }
        }

        // v1 implDecls removed — all consumers now use resolvedParsedPerformerDecls
        val abilityImplDecls = resolvedAbilityImplDecls

        // Validate duplicates — tolerate prelude duplicates when modules are merged
        // Build a list of all v2 declarations for validation (types, abilities, effects, ability impls, performers, lets)
        val allDeclsForValidation: List<ParsedDecl> = module.decls.filterIsInstance<ParsedTypeDecl>() +
            module.decls.filterIsInstance<ParsedAbilityDecl>() +
            module.decls.filterIsInstance<ParsedEffectDecl>() +
            module.decls.filterIsInstance<ParsedPerformerDecl>() +
            abilityImplDecls +
            module.decls.filterIsInstance<ParsedLetDecl>()
        try {
            DuplicateDeclarationValidator.validateDecls(allDeclsForValidation)
        } catch (e: TypeCheckError) {
            // Only suppress duplicates from prelude injection (stdlib performer types)
            // Re-throw user code duplicates
            val msg = e.message ?: ""
            val preludeTypes = PrimitiveTypes.allNames + BuiltinTypes.coreTypeNames
            val isPrelude = preludeTypes.any { msg.contains("'$it'") && msg.contains("performer") }
            if (!isPrelude) throw e
        }

        // Merge local and imported effect declarations for lookups (v2 types)
        val allEffectDecls = effectDecls + importedEffects

        // Make effect declarations available to convertTypeAnnotationV1Internal so it can
        // populate EffectRow.effectHashes with content hashes for hermetic checks.
        currentEffectDecls = allEffectDecls

        // Make ability and type declarations available to convertTypeAnnotationV1Internal
        // so it can populate abilityHash / typeHash content hashes.
        currentAbilityDecls = abilityDecls + importedAbilities
        currentTypeDecls = typeDecls + importedTypes

        // v1 effect decls for internal ContextBuilder methods
        // allV1AbilityDecls and allV1TypeDecls removed — validators now use v2 types

        // v1ImportedImpls removed — all consumers now use importedImpls (v2) directly

        // Build performer to effect mapping (using v2 types)
        val localMapping = resolvedParsedPerformerDecls.associate { perf ->
            val effectDecl = allEffectDecls[perf.effect.localName]
            val effectName = effectDecl?.name ?: perf.effect.localName
            perf.performerName to effectName
        }
        val importedMapping = importedImpls.mapValues { (_, perf) ->
            val effectDecl = allEffectDecls[perf.effect.localName]
            effectDecl?.name ?: perf.effect.localName
        }
        val performerToEffectMap = importedMapping + localMapping  // Local impls override imports

        // Build hash-based performer→effect mapping
        val performerToEffectHashMap = mutableMapOf<com.mezze.language.typedast.Hash, com.mezze.language.typedast.Hash>()
        for (perf in resolvedParsedPerformerDecls) {
            val perfHash = perf.contentHash ?: continue
            val effectDecl = allEffectDecls[perf.effect.localName]
            val effHash = effectDecl?.contentHash ?: continue
            performerToEffectHashMap[perfHash] = effHash
        }
        for ((_, perf) in importedImpls) {
            val perfHash = perf.contentHash ?: continue
            val effectDecl = allEffectDecls[perf.effect.localName]
            val effHash = effectDecl?.contentHash ?: continue
            performerToEffectHashMap[perfHash] = effHash
        }

        // Build context with bindings for declarations (uses v1 types internally)
        var ctx = parentContext

        // Add type constructors to context (v2 types directly)
        for (typeDecl in typeDecls.values) {
            ctx = addParsedTypeConstructors(ctx, typeDecl)
        }

        // Note: True and False are parsed as VariantConstruction nodes (uppercase variants).

        // Add ability methods to context — both local AND imported abilities (v2 types)
        for (abilityDecl in abilityDecls.values) {
            ctx = addAbilityMethods(ctx, abilityDecl)
        }
        for (abilityDecl in importedAbilities.values) {
            if (ctx.getAbilityOperationType(abilityDecl.name) == null) {
                ctx = addAbilityMethods(ctx, abilityDecl)
            }
        }

        // Add effect operations to context (using v2 ParsedEffectDecl directly)
        for (effectDecl in effectDecls.values) {
            ctx = addEffectOperations(ctx, effectDecl)
        }

        // Add effect performers (impl blocks) to context (using v2 types directly)
        for (perfDecl in resolvedParsedPerformerDecls) {
            ctx = addEffectPerformer(ctx, perfDecl, allEffectDecls)
        }
        for ((_, perfDecl) in importedImpls) {
            ctx = addEffectPerformer(ctx, perfDecl, allEffectDecls)
        }

        // Add ability implementations to context (with ability declaration hashes for hash-based index)
        val allParsedAbilityDeclsForHash = abilityDecls + importedAbilities
        for (abilityImplDecl in abilityImplDecls) {
            ctx = addAbilityImpl(ctx, abilityImplDecl, allParsedAbilityDeclsForHash, importedAbilities, file)
        }

        // VALIDATION PHASE: Run ability validations after context is built (v2 types)
        val allParsedAbilityDecls = abilityDecls + importedAbilities
        val allParsedTypeDecls = typeDecls + importedTypes

        AbilityValidator.validateNameCollisions(
            abilityDeclarations = abilityDecls,
            typeDeclarations = allParsedTypeDecls,
            effectDeclarations = effectDecls
        )

        for (abilityDecl in abilityDecls.values) {
            AbilityValidator.validateAbilityDependencies(abilityDecl, allParsedAbilityDecls)
        }

        for (abilityDecl in abilityDecls.values) {
            AbilityValidator.validateAbilityDeclaration(abilityDecl)
        }

        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateAbilityImplementation(
                impl = abilityImplDecl,
                abilityDeclarations = allParsedAbilityDecls,
                typeDeclarations = allParsedTypeDecls
            )
        }

        // Validate orphan rules
        val localAbilityNames = abilityDecls.keys
        val declaredTypeNames = module.decls.filterIsInstance<ParsedTypeDecl>().map { it.name }.toSet()
        val homeTypeNames = if (currentModulePath != null) {
            BuiltinTypeRegistry.getLocalTypesForModule(currentModulePath)
        } else emptySet()
        val localTypeNames = declaredTypeNames + homeTypeNames
        val importedAbilityNames = importedAbilities.keys
        val importedTypeNames = importedTypes.keys

        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateOrphanRules(
                impl = abilityImplDecl,
                localAbilityNames = localAbilityNames,
                localTypeNames = localTypeNames,
                importedAbilityNames = importedAbilityNames,
                importedTypeNames = importedTypeNames
            )
        }

        // Build performer added effects mapping (using v2 types)
        val performerAddedEffectsMap = (resolvedParsedPerformerDecls + importedImpls.values).associate { perf ->
            perf.performerName to perf.operations.flatMap { op ->
                op.effects?.effects ?: emptyList()
            }.toSet()
        }.filterValues { it.isNotEmpty() }

        // Build implDeclarations map: performer name -> ImplDeclaration
        val implDeclarationsMap: Map<String, ParsedPerformerDecl> = resolvedParsedPerformerDecls.associateBy { it.performerName } +
            importedImpls

        // Build resolvedModule: replace converted ParsedPerformerDecl with ParsedImplAbilityDecl
        val resolvedMod = if (convertedPerformerDecls.isEmpty()) {
            module
        } else {
            val resolvedDecls = module.decls.map { decl ->
                if (decl is ParsedPerformerDecl) {
                    convertedPerformerDecls[decl] ?: decl
                } else {
                    decl
                }
            }
            ParsedModule(module.path, resolvedDecls)
        }

        return BuildResult(
            context = ctx,
            typeDeclarations = typeDecls,
            abilityDeclarations = abilityDecls,
            effectDeclarations = effectDecls,
            performerToEffect = performerToEffectMap,
            performerToEffectHash = performerToEffectHashMap,
            performerAddedEffects = performerAddedEffectsMap,
            implDeclarations = implDeclarationsMap,
            resolvedModule = resolvedMod
        )
    }


    /**
     * Add type constructors to context.
     */
    fun addParsedTypeConstructors(ctx: TypingContext, typeDecl: ParsedTypeDecl, isImported: Boolean = false): TypingContext {
        var result = ctx
        when (val definition = typeDecl.definition) {
            is ParsedVariantDef -> {
                for (constructor in definition.constructors) {
                    val constructorType = buildParsedConstructorType(typeDecl, constructor)
                    val typeVars = mutableSetOf<TypeVar>()
                    collectTypeVarsFromType(constructorType, typeVars)
                    val scheme = TypeScheme(typeVars.toList(), emptyList(), constructorType)
                    result = result.extendPoly(constructor.name, scheme)
                }
            }
            is ParsedRecordDef -> { /* Records don't have constructors */ }
            is ParsedNewtypeDef -> {
                if (isImported) return result
                typeVarCache.clear()
                val wrappedMezzeType = convertParsedTypeAnnotationInternal(definition.wrappedType)
                val typeArgs = typeDecl.typeParams.map { param ->
                    MezzeType.VarType(typeVarCache.getOrPut(param) { TypeVar(param) })
                }
                val newtypeResult = MezzeType.TypeApplication(typeDecl.name, typeArgs)
                val constructorType = MezzeType.FunctionType(paramType = wrappedMezzeType, returnType = newtypeResult)
                val typeVars = mutableSetOf<TypeVar>()
                collectTypeVarsFromType(constructorType, typeVars)
                result = result.extendPoly(definition.constructor, TypeScheme(typeVars.toList(), emptyList(), constructorType))
            }
        }
        return result
    }

    private fun buildParsedConstructorType(typeDecl: ParsedTypeDecl, constructor: ParsedVariantCtorDef): MezzeType {
        typeVarCache.clear()
        val resultType = if (typeDecl.typeParams.isEmpty()) {
            when (val def = typeDecl.definition) {
                is ParsedNewtypeDef -> MezzeType.TypeApplication(typeDecl.name, emptyList())
                else -> {
                    val constructors = when (def) {
                        is ParsedVariantDef -> def.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertParsedTypeAnnotationInternal(it) }
                        }
                        is ParsedRecordDef -> emptyMap()
                        is ParsedNewtypeDef -> emptyMap()
                    }
                    MezzeType.VariantType(
                        constructors = constructors, rowVar = null,
                        typeName = typeDecl.name, typeHash = typeDecl.contentHash
                    )
                }
            }
        } else {
            MezzeType.TypeApplication(
                constructor = typeDecl.name,
                args = typeDecl.typeParams.map { param ->
                    MezzeType.VarType(typeVarCache.getOrPut(param) { TypeVar(param) })
                }
            )
        }
        return when (val dataType = constructor.dataType) {
            null -> resultType
            else -> MezzeType.FunctionType(
                paramType = convertParsedTypeAnnotationInternal(dataType),
                returnType = resultType
            )
        }
    }

    /**
     * Add ability methods to context
     */
    private fun addAbilityMethods(ctx: TypingContext, abilityDecl: ParsedAbilityDecl): TypingContext {
        var result = ctx
        typeVarCache.clear()

        val abilityRecordType = MezzeType.RecordType(
            fields = abilityDecl.operations.associate { op ->
                val constraints = op.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertParsedTypeAnnotationInternal(it) },
                        abilityHash = currentAbilityDecls[constraint.abilityName]?.contentHash
                    )
                }
                val functionType = MezzeType.FunctionType(
                    paramType = convertParsedTypeAnnotationInternal(op.paramType),
                    returnType = convertParsedTypeAnnotationInternal(op.returnType),
                    abilityConstraints = constraints
                )
                op.name to functionType
            }
        )
        result = result.extendWithAbilityOperationType(abilityDecl.name, abilityRecordType)

        return result
    }

    /**
     * Add effect operations to context
     */
    private fun addEffectOperations(ctx: TypingContext, effectDecl: ParsedEffectDecl): TypingContext {
        var result = ctx

        // CRITICAL: Clear cache once BEFORE converting all operations for this effect
        // This ensures all operations in this effect use the same TypeVar instances
        // for associated types (e.g., all 'env references become TypeVar(name='env))
        typeVarCache.clear()

        // Add effect record type (for qualified access like Log.write)
        // NOTE: We don't add effects to the operation types here to avoid module import issues
        // Instead, effects are added when the operation is called (in tryGenerateEffectOperationCall)
        val effectRecordType = MezzeType.RecordType(
            fields = effectDecl.operations.associate { op ->
                // Convert the type annotation and strip any effects
                // Effect operations are stored as plain function types
                // IMPORTANT: Use internal converter to avoid clearing cache between operations
                val convertedReturnType = convertParsedTypeAnnotationInternal(op.returnType)
                val convertedParamType = convertParsedTypeAnnotationInternal(op.paramType)
                val plainFunctionType = MezzeType.FunctionType(
                    paramType = convertedParamType,
                    returnType = convertedReturnType,
                    effects = null  // NO effects here - added during call
                )
                op.name to plainFunctionType
            }
        )
        // Use dedicated map instead of "${name}_ability" pattern
        result = result.extendWithEffectOperationType(effectDecl.name, effectRecordType)

        return result
    }

    /**
     * Analyze an impl body to find calls to `suspend_with` and extract the suspension type.
     *
     * The suspension type is what's passed as `value` to `suspend_with`. This determines
     * what the perform expression should return.
     *
     * Example: For `throw = {error} -> cont.suspend_with {value = Err {error}}`
     *   - The suspension expression is `Err {error}`
     *   - We infer the type pattern `| Err { error: 'error } |` → `Result 'val 'error`
     *
     * @param implDecl The impl declaration to analyze
     * @param associatedTypes Map of associated type names to their bound types
     * @return The inferred suspension type, or null if not found/inferrable
     */
    private fun extractSuspensionTypeV2(
        implDecl: ParsedPerformerDecl,
        associatedTypes: Map<String, MezzeType>
    ): MezzeType? {
        // Walk parsed AST directly (no v1 conversion needed)
        for (op in implDecl.operations) {
            val impl = op.implementation
            if (impl !is ParsedLambda) continue
            val suspendWithValue = findSuspendWithValueParsed(impl.body)
            if (suspendWithValue != null) {
                return inferSuspensionTypeFromParsedExpr(suspendWithValue, associatedTypes)
            }
        }
        return null
    }

    /**
     * Recursively find the value expression passed to suspend_with in parsed AST.
     * Looks for patterns like: cont.suspend_with {value = expr}
     */
    private fun findSuspendWithValueParsed(expr: ParsedExpr): ParsedExpr? {
        return when (expr) {
            is ParsedFunctionCall -> {
                // Check if this is a call to suspend_with
                val callee = expr.function
                if (callee is ParsedMemberAccess && callee.member == "suspend_with") {
                    // Found suspend_with call, extract the value from arguments
                    val args = expr.arguments
                    if (args is ParsedRecordLit) {
                        for (entry in args.entries) {
                            if (entry is ParsedRecordEntry.Field && entry.name == "value") {
                                return entry.value
                            }
                        }
                    }
                }
                // Not a suspend_with call, recurse into arguments
                findSuspendWithValueParsed(expr.arguments)
            }
            is ParsedDo -> {
                // Check the result expression
                findSuspendWithValueParsed(expr.result) ?: run {
                    // Check statements
                    for (stmt in expr.statements.reversed()) {
                        when (stmt) {
                            is ParsedStatement.Expr -> {
                                val found = findSuspendWithValueParsed(stmt.expr)
                                if (found != null) return found
                            }
                            is ParsedStatement.Let -> {
                                val found = findSuspendWithValueParsed(stmt.value)
                                if (found != null) return found
                            }
                        }
                    }
                    null
                }
            }
            is ParsedIf -> {
                // Check both branches
                findSuspendWithValueParsed(expr.thenBranch) ?: findSuspendWithValueParsed(expr.elseBranch)
            }
            is ParsedMatch -> {
                // Check all cases
                for (case in expr.cases) {
                    val found = findSuspendWithValueParsed(case.body)
                    if (found != null) return found
                }
                null
            }
            is ParsedLambda -> findSuspendWithValueParsed(expr.body)
            else -> null
        }
    }

    /**
     * Infer the suspension type from a parsed expression passed to suspend_with.
     *
     * For variant constructors like `Err {error}`, we create a variant type.
     * For other expressions, we try to infer a reasonable type.
     */
    private fun inferSuspensionTypeFromParsedExpr(
        expr: ParsedExpr,
        associatedTypes: Map<String, MezzeType>
    ): MezzeType? {
        return when (expr) {
            is ParsedVariantConstruct -> {
                // Variant like Err {error} or Ok {val}
                val constructorName = expr.constructor
                val exprValue = expr.value
                val payloadType = if (exprValue != null) {
                    inferParsedPayloadType(exprValue, associatedTypes)
                } else null

                // Create a variant type with this constructor
                // For Result patterns, we need both Ok and Err constructors
                if (constructorName == BuiltinTypes.ERR || constructorName == BuiltinTypes.OK) {
                    // This is a Result type - create Result 'val 'error
                    // CRITICAL: Use the SAME type variable from associatedTypes
                    // This ensures constraint propagation works - when the operation
                    // call constrains 'error to String, the suspension type also sees it
                    val errorType = associatedTypes["error"] ?: MezzeType.VarType(TypeVar.fresh())
                    val valType = MezzeType.VarType(TypeVar.fresh())

                    MezzeType.TypeApplication(
                        constructor = BuiltinTypes.RESULT,
                        args = listOf(valType, errorType)
                    )
                } else if (constructorName == BuiltinTypes.NONE) {
                    // This is Option None - create Option 'a
                    MezzeType.TypeApplication(
                        constructor = BuiltinTypes.OPTION,
                        args = listOf(MezzeType.VarType(TypeVar.fresh()))
                    )
                } else if (constructorName == BuiltinTypes.SOME) {
                    // This is Option Some - create Option with the payload type
                    val innerType = payloadType ?: MezzeType.VarType(TypeVar.fresh())
                    MezzeType.TypeApplication(
                        constructor = BuiltinTypes.OPTION,
                        args = listOf(innerType)
                    )
                } else {
                    // Generic variant - create open variant type
                    val constructors: Map<String, MezzeType?> = mapOf(constructorName to payloadType)
                    MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = RowVar.fresh()
                    )
                }
            }
            is ParsedRecordLit -> {
                // Record literal - infer field types
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is ParsedRecordEntry.Field) {
                        val entryValue = entry.value
                        val fieldType = if (entryValue != null) {
                            inferParsedPayloadType(entryValue, associatedTypes)
                        } else {
                            // Shorthand {foo} - look up in associated types
                            associatedTypes[entry.name] ?: MezzeType.VarType(TypeVar.fresh())
                        }
                        fields[entry.name] = fieldType
                    }
                }
                MezzeType.RecordType(fields)
            }
            is ParsedIdentifier -> {
                // Reference to a variable - check if it's an associated type
                val name = expr.localName
                associatedTypes[name] ?: MezzeType.VarType(TypeVar.fresh())
            }
            else -> {
                // For other expressions, use a fresh type variable
                MezzeType.VarType(TypeVar.fresh())
            }
        }
    }

    /**
     * Infer the type of a payload expression from parsed AST.
     */
    private fun inferParsedPayloadType(expr: ParsedExpr, associatedTypes: Map<String, MezzeType>): MezzeType {
        return when (expr) {
            is ParsedRecordLit -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is ParsedRecordEntry.Field) {
                        val entryValue = entry.value
                        val fieldType = if (entryValue != null) {
                            inferParsedPayloadType(entryValue, associatedTypes)
                        } else {
                            // Shorthand {foo} - look up in associated types
                            associatedTypes[entry.name] ?: MezzeType.VarType(TypeVar.fresh())
                        }
                        fields[entry.name] = fieldType
                    }
                }
                MezzeType.RecordType(fields)
            }
            is ParsedIdentifier -> {
                val name = expr.localName
                associatedTypes[name] ?: MezzeType.VarType(TypeVar.fresh())
            }
            else -> MezzeType.VarType(TypeVar.fresh())
        }
    }

    /**
     * Add effect performer to context.
     *
     * Stores the performer's associated type bindings so they can be used
     * for type substitution during effect operation calls.
     *
     * Example: For `impl State for Counter having 'state = Int`, stores:
     * - Key: content hash of ImplDeclaration
     * - Value: PerformerTypeInfo with RecordType containing {'state -> Int}
     */
    private fun addEffectPerformer(
        ctx: TypingContext,
        implDecl: ParsedPerformerDecl,
        effectDecls: Map<String, ParsedEffectDecl>
    ): TypingContext {
        // Ensure content hash is available (compute if missing)
        val implWithHash = if (implDecl.contentHash != null) implDecl else {
            implDecl.copy(contentHash = com.mezze.language.parsedast.ParsedDeclHasher.hashPerformerDecl(implDecl))
        }
        @Suppress("NAME_SHADOWING")
        val implDecl = implWithHash

        // Get the effect declaration to check associated types
        val effectDecl = effectDecls[implDecl.effect.localName] ?: return ctx

        // If the effect has associated types, store the performer's bindings
        if (effectDecl.associatedTypes.isNotEmpty()) {
            // Clear cache for performer bindings
            typeVarCache.clear()

            // Convert associated type bindings to MezzeTypes
            val bindingFields = implDecl.associatedTypeBindings.mapValues { (_, typeAnnotation) ->
                convertParsedTypeAnnotationInternal(typeAnnotation)
            }

            // Store as RecordType with associated type names as field names
            val performerType = MezzeType.RecordType(
                fields = effectDecl.associatedTypes.associate { assocType ->
                    val varName = assocType.removePrefix("'")
                    val value = bindingFields[assocType] ?: bindingFields["'$assocType"] ?: MezzeType.VarType(TypeVar.fresh())
                    varName to value
                }
            )

            val contentHash = implDecl.contentHash
                ?: throw IllegalStateException(
                    "Content hash not computed for impl: ${implDecl.performerName}. " +
                    "Ensure computeDeclarationHashes was called before ContextBuilder."
                )

            val performerFieldsType = if (implDecl.params.isNotEmpty()) {
                MezzeType.RecordType(
                    fields = implDecl.params.associate { param ->
                        param.name to convertParsedTypeAnnotationInternal(param.type)
                    }
                )
            } else null

            // Extract the suspension type from the impl body
            val associatedTypesMap = performerType.fields.mapKeys { (k, _) -> k }
            val suspensionType = extractSuspensionTypeV2(implDecl, associatedTypesMap)

            val performerInfo = TypingContext.PerformerTypeInfo(
                performerName = implDecl.performerName,
                effectName = implDecl.effect.localName,
                associatedTypes = performerType,
                performerFields = performerFieldsType,
                suspensionType = suspensionType,
                contentHash = contentHash
            )

            return ctx.extendWithPerformerTypeInfo(contentHash, performerInfo)
        }

        // Even if no associated types, we still need to store performer fields for continuation inference
        if (implDecl.params.isNotEmpty()) {
            val contentHash = implDecl.contentHash
                ?: throw IllegalStateException(
                    "Content hash not computed for impl: ${implDecl.performerName}. " +
                    "Ensure computeDeclarationHashes was called before ContextBuilder."
                )

            val performerFieldsType = MezzeType.RecordType(
                fields = implDecl.params.associate { param ->
                    param.name to convertParsedTypeAnnotationInternal(param.type)
                }
            )

            val suspensionType = extractSuspensionTypeV2(implDecl, emptyMap())

            val performerInfo = TypingContext.PerformerTypeInfo(
                performerName = implDecl.performerName,
                effectName = implDecl.effect.localName,
                associatedTypes = MezzeType.RecordType(emptyMap()),
                performerFields = performerFieldsType,
                suspensionType = suspensionType,
                contentHash = contentHash
            )

            return ctx.extendWithPerformerTypeInfo(contentHash, performerInfo)
        }

        return ctx
    }

    /**
     * Add ability implementation to context.
     *
     * Creates a key like "Eq_String" or "Convert_Int_String" and stores
     * the implementation info for later validation.
     */
    private fun addAbilityImpl(
        ctx: TypingContext,
        implDecl: ParsedImplAbilityDecl,
        allAbilityDecls: Map<String, ParsedAbilityDecl> = emptyMap(),
        importedAbilities: Map<String, ParsedAbilityDecl> = emptyMap(),
        file: String = "<module>"
    ): TypingContext {
        // Extract target type names from type annotations
        val targetTypeNames = implDecl.targetTypes.map { getTypeName(it) }

        // Use content hash as the key - no more mangled strings!
        // Content hashes are computed in Phase 0.5 before ContextBuilder runs
        val implKey = implDecl.contentHash
            ?: throw IllegalStateException(
                "Content hash not computed for impl: ${implDecl.abilityName ?: "inherent"} ${implDecl.targetTypes}. " +
                "Ensure computeDeclarationHashes was called before ContextBuilder."
            )

        // Handle inherent impls: impl Type where { methods }
        if (implDecl.isInherent) {
            return addAnonymousImpl(ctx, implDecl, targetTypeNames, implKey, file)
        }

        // Extract type variables from generic impl header (e.g., 'a from `impl Eq 'a for ['a]`)
        // Only actual type variables (starting with ') are tracked — concrete types (like Str, Float)
        // in abilityTypeArgs are not type variables.
        val typeVars = implDecl.abilityTypeArgs
            .filterIsInstance<ParsedNamedType>()
            .filter { it.name.startsWith("'") }
            .map { it.name }
            .toSet()

        // Use allTypeArgs which combines concrete abilityTypeArgs (before 'for') with targetTypes (after 'for').
        // For `impl Into Str for Int`: allTypeArgs=[Str, Int]
        // For `impl Eq 'a for ['a]`: allTypeArgs=[['a]] (type var 'a excluded as it's a quantifier)
        val allTargetAnnotations = implDecl.allTypeArgs
        val allTargetNames = allTargetAnnotations.map { getTypeName(it) }

        // Collect method-level constraints from all operations (v2 directly)
        val operationConstraints = implDecl.operations.flatMap { op -> op.abilityConstraints }

        // Create impl info — look up ability declaration hash for hash-based index
        val abilityDeclHash = allAbilityDecls[implDecl.abilityName!!]?.contentHash
            ?: importedAbilities[implDecl.abilityName!!]?.contentHash
        val implKeyHash = implKey
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = implDecl.abilityName!!,  // safe: not inherent
            abilityDeclHash = abilityDeclHash,
            targetTypes = allTargetNames,
            targetTypeAnnotations = allTargetAnnotations,
            associatedTypeBindings = implDecl.associatedTypeBindings,
            sourceModule = null,
            contentHash = implKeyHash,
            typeVars = typeVars,
            operationConstraints = operationConstraints
        )

        // Store in context by content hash
        return ctx.extendWithImpl(implKeyHash, implInfo)
    }

    /**
     * Register anonymous impl methods in context.
     * Anonymous impls (`impl Type where { ... }`) attach methods directly to types.
     */
    private fun addAnonymousImpl(
        ctx: TypingContext,
        implDecl: ParsedImplAbilityDecl,
        targetTypeNames: List<String>,
        implKey: com.mezze.language.typedast.Hash,
        file: String = "<module>"
    ): TypingContext {
        var result = ctx
        val targetTypeName = targetTypeNames.firstOrNull() ?: return ctx
        val implKeyHash = implKey

        for (op in implDecl.operations) {
            // Build method type from annotations if available
            val opParamType = op.paramType
            val paramType = if (opParamType != null) {
                convertParsedTypeAnnotation(opParamType)
            } else {
                // Infer param type from the lambda's parameters (v2 ParsedExpr)
                val lambda = op.implementation as? com.mezze.language.parsedast.ParsedLambda
                if (lambda != null) {
                    when (val params = lambda.params) {
                        is com.mezze.language.parsedast.ParsedLambdaParams.Typed -> {
                            MezzeType.RecordType(
                                fields = params.fields.associate { entry ->
                                    entry.name to convertParsedTypeAnnotation(entry.type)
                                },
                                rowVar = null
                            )
                        }
                        is com.mezze.language.parsedast.ParsedLambdaParams.Untyped -> {
                            if (params.names.isEmpty()) {
                                MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                            } else {
                                MezzeType.RecordType(
                                    fields = params.names.associateWith { MezzeType.VarType(TypeVar.fresh()) },
                                    rowVar = null
                                )
                            }
                        }
                    }
                } else {
                    MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                }
            }

            val opReturnType = op.returnType
            val returnType = if (opReturnType != null) {
                convertParsedTypeAnnotation(opReturnType)
            } else {
                // Return type unknown at registration time — use a fresh type variable.
                // The actual type will be inferred during typechecking of the method call.
                MezzeType.VarType(TypeVar.fresh())
            }

            val methodType = MezzeType.FunctionType(
                paramType = paramType,
                returnType = returnType
            )

            val methodInfo = TypingContext.InherentMethodInfo(
                targetTypeName = targetTypeName,
                methodName = op.name,
                methodType = methodType,
                implContentHash = implKeyHash,
                implStartLine = implDecl.loc.startLine,
                implStartCol = implDecl.loc.startCol,
                implSourceFile = implDecl.loc.file
            )

            result = result.extendWithAnonymousMethod(methodInfo)
        }

        // Also register as a regular impl so the interpreter can find it by content hash
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = null,  // inherent impl
            targetTypes = targetTypeNames,
            targetTypeAnnotations = implDecl.targetTypes,
            associatedTypeBindings = implDecl.associatedTypeBindings,
            sourceModule = null,
            contentHash = implKeyHash
        )
        result = result.extendWithImpl(implKeyHash, implInfo)

        return result
    }

    /**
     * Register an imported inherent impl's methods in the context.
     * This bypasses validation (since the impl was already validated in its home module)
     * and only registers the InherentMethodInfo + AbilityImplInfo entries.
     *
     * Builds method types from annotations or lambda parameter shapes.
     * The v2 ParsedImplAbilityDecl does not carry inferredType; types will be
     * unified during type checking of method calls.
     */
    fun registerImportedInherentImpl(
        ctx: TypingContext,
        implDecl: ParsedImplAbilityDecl,
        implKey: com.mezze.language.typedast.Hash,
        file: String = "<import>",
        // Pre-inferred return types harvested from the home module's
        // TypedAbilityImpl.operations (issue #213). Keyed by op name. Used
        // when the operation has no return-type annotation — we'd otherwise
        // assign a fresh unbound TypeVar and lose the body's solved type.
        opReturnTypes: Map<String, MezzeType> = emptyMap(),
        // Pre-inferred param-record types — sibling of opReturnTypes for the
        // arg side (issue #230). When the operation's lambda has untyped
        // params, this map's record fields beat fresh TypeVars so callbacks
        // like `Seq.all`'s `p: { x: 'a } -> Bool` reject `nums.all {p = 5}`.
        opParamTypes: Map<String, MezzeType> = emptyMap()
    ): TypingContext {
        var result = ctx
        val targetTypeNames = implDecl.targetTypes.map { getTypeName(it) }
        val targetTypeName = targetTypeNames.firstOrNull() ?: return ctx
        val implKeyHash = implKey

        // Resolve the impl's target type once. Used to bind `it` in unannotated
        // method signatures so generic impls (e.g. `impl Seq 'a where { first }`)
        // share their type variables across `it` and the return type — without
        // this, fresh-instantiation at the call site has nothing to unify the
        // receiver's actual element type against.
        //
        // The TypeVars in `implTargetType` come from `convertParsedTypeAnnotation`
        // (e.g. fresh `'a`), but the harvested `opReturnTypes` use TypeVars from
        // the home module's solver run with potentially different identity.
        val implTargetType: MezzeType? = implDecl.targetTypes.firstOrNull()?.let {
            convertParsedTypeAnnotation(it)
        }
        // Build a name → TypeVar map from the impl target's annotation so we can
        // align return-type TypeVars by name when they happen to match.
        val targetTypeVarsByName: MutableMap<String, com.mezze.language.ast.TypeVar> = mutableMapOf()
        if (implTargetType != null) collectVarTypesByName(implTargetType, targetTypeVarsByName)
        // The impl target's free TypeVars in declaration order. Used for
        // positional alignment when name-based lookup fails (the solver-side
        // TypeVars in harvested return types often carry generated names that
        // don't match the user-facing `'a`).
        val targetVarsInOrder: List<com.mezze.language.ast.TypeVar> = if (implTargetType != null) {
            collectVarTypesInOrder(implTargetType)
        } else emptyList()

        for (op in implDecl.operations) {
            // Issue #230: prefer the harvested post-solve param-record type
            // when available — that's the only place callback fields like
            // `p: { x: 'a } -> Bool` survive. Falls back to annotations,
            // then to a fresh-var record built from the lambda's untyped
            // param names (the legacy behaviour, which under-constrained
            // call sites).
            val paramType = opParamTypes[op.name]
                ?: op.paramType?.let { convertParsedTypeAnnotation(it) }
                ?: run {
                    val lambda = op.implementation as? com.mezze.language.parsedast.ParsedLambda
                    if (lambda != null) {
                        when (val params = lambda.params) {
                            is com.mezze.language.parsedast.ParsedLambdaParams.Typed -> MezzeType.RecordType(
                                fields = params.fields.associate { entry ->
                                    entry.name to convertParsedTypeAnnotation(entry.type)
                                },
                                rowVar = null
                            )
                            is com.mezze.language.parsedast.ParsedLambdaParams.Untyped -> MezzeType.RecordType(
                                fields = if (params.names.isEmpty()) emptyMap()
                                         else params.names.associateWith { name ->
                                             // Receiver param: bind to impl's target type so
                                             // `it`'s shape (and any 'a vars in `Seq 'a`) is
                                             // visible at the call site.
                                             if (name == "it" && implTargetType != null) implTargetType
                                             else MezzeType.VarType(TypeVar.fresh())
                                         },
                                rowVar = null
                            )
                        }
                    } else {
                        MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                    }
                }
            val rawReturnType = op.returnType?.let { convertParsedTypeAnnotation(it) }
                ?: opReturnTypes[op.name]
                ?: MezzeType.VarType(TypeVar.fresh())
            // Align return-type TypeVars with the impl target's TypeVars so
            // e.g. `'a` in `Option 'a` (return) is the *same* TypeVar instance
            // as `'a` in `Seq 'a` (it). Fresh-instantiation at the call site
            // then renames both consistently. We try name-based alignment
            // first (works when both sides carry the same user-facing name);
            // falls back to positional alignment when the harvested return
            // type's vars are solver-generated names that don't match.
            //
            // Issue #230 also brings harvested PARAM types into the picture.
            // Their TypeVars come from the same solver run as the return
            // type's, so apply identical alignment to BOTH halves —
            // otherwise the call-site receiver's `Seq Int` would unify with
            // a different `'a` than `Option 'a` ever sees, and the return
            // pin from #213/#215 silently breaks for first/last/etc.
            val returnType = if (targetVarsInOrder.isNotEmpty()) {
                val byName = alignVarTypesByName(rawReturnType, targetTypeVarsByName)
                val freeAfterName = collectVarTypesInOrder(byName)
                if (freeAfterName.size == targetVarsInOrder.size &&
                    freeAfterName.zip(targetVarsInOrder).any { (a, b) -> a != b }) {
                    val positional = freeAfterName.zip(targetVarsInOrder).toMap()
                    alignVarTypesByMapping(byName, positional)
                } else byName
            } else rawReturnType

            val alignedParamType = if (targetVarsInOrder.isNotEmpty() && paramType is MezzeType.RecordType) {
                // First pass: align by user-facing name (`'a` in both sides).
                val byName = alignVarTypesByName(paramType, targetTypeVarsByName) as MezzeType.RecordType
                // Second pass: positional alignment for solver-generated
                // names — but only when the param record's free vars line
                // up positionally with the target's. If the harvested
                // record uses a different number of free vars (e.g.
                // multi-arg ops with extra fresh vars unrelated to the
                // target), skip positional and trust whatever name-match
                // gave us.
                val freeAfterName = collectVarTypesInOrder(byName)
                if (freeAfterName.size == targetVarsInOrder.size &&
                    freeAfterName.zip(targetVarsInOrder).any { (a, b) -> a != b }) {
                    val positional = freeAfterName.zip(targetVarsInOrder).toMap()
                    alignVarTypesByMapping(byName, positional) as MezzeType.RecordType
                } else byName
            } else paramType

            val methodType = MezzeType.FunctionType(paramType = alignedParamType, returnType = returnType)

            val methodInfo = TypingContext.InherentMethodInfo(
                targetTypeName = targetTypeName,
                methodName = op.name,
                methodType = methodType,
                implContentHash = implKeyHash,
                implStartLine = implDecl.loc.startLine,
                implStartCol = implDecl.loc.startCol,
                implSourceFile = implDecl.loc.file,
                isImported = true
            )
            result = result.extendWithAnonymousMethod(methodInfo)
        }

        // Also register as a regular impl so the interpreter can find it by content hash
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = null,
            targetTypes = targetTypeNames,
            targetTypeAnnotations = implDecl.targetTypes,
            associatedTypeBindings = implDecl.associatedTypeBindings,
            sourceModule = null,
            contentHash = implKeyHash
        )
        result = result.extendWithImpl(implKeyHash, implInfo)

        return result
    }

    /**
     * Extract type name from type annotation for impl keys.
     *
     * Examples:
     * - IdentifierTypeAnnotation("String") → "String"
     * - TypeApplicationAnnotation("Option", [Int]) → "Option_Int"
     * - RecordTypeAnnotation → "{...}" (fallback for complex types)
     */
    /**
     * Extract type name from ParsedTypeAnnotation for impl keys.
     */
    private fun getTypeName(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name.removePrefix("'")
            is ParsedTypeApp -> {
                val allArgsAreTypeVars = annotation.args.all { arg ->
                    arg is ParsedNamedType && (arg.name.startsWith("'") || arg.name.first().isLowerCase())
                }
                if (allArgsAreTypeVars) {
                    annotation.constructor
                } else {
                    val argNames = annotation.args.map { getTypeName(it) }
                    if (argNames.isEmpty()) annotation.constructor
                    else "${annotation.constructor}_${argNames.joinToString("_")}"
                }
            }
            is ParsedFunctionType -> "Function"
            is ParsedRecordType -> "Record"
            is ParsedVariantType -> "Variant"
            is ParsedSeqType -> "Seq_${getTypeName(annotation.elementType)}"
            is ParsedQualifiedType -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val argNames = annotation.args.map { getTypeName(it) }
                if (argNames.isEmpty()) "${modulePart}_${annotation.typeName}"
                else "${modulePart}_${annotation.typeName}_${argNames.joinToString("_")}"
            }
        }
    }

    /**
     * Convert type annotation to MezzeType.
     * This is a simple conversion - no inference, no validation.
     *
     * This is the public entry point that clears the type variable cache
     * before conversion to ensure each top-level annotation gets fresh variables.
     */
    // Inline type annotation conversion (avoids dependency on util/TypeAnnotationConverter). This version does
    // type declaration lookups (currentTypeDecls, currentEffectDecls, currentAbilityDecls),
    // hermetic effect validation, content hash resolution, and List/Seq/Set/Map sugar
    // that require ContextBuilder state not available to the shared utility.
    /**
     * Convert a ParsedTypeAnnotation to MezzeType.
     * This is the primary converter — works directly on v2 types.
     */
    fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        typeVarCache.clear()
        return convertParsedTypeAnnotationInternal(annotation)
    }

    /**
     * Internal recursive v2 type annotation converter.
     * Uses cached type variables to ensure consistency within a single annotation.
     */
    private fun convertParsedTypeAnnotationInternal(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                PrimitiveTypes.fromName(annotation.name)?.let { return it }
                if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                    val varName = annotation.name.removePrefix("'")
                    val typeVar = typeVarCache.getOrPut(varName) { TypeVar(varName) }
                    MezzeType.VarType(typeVar)
                } else {
                    MezzeType.TypeApplication(annotation.name, emptyList(), currentTypeDecls[annotation.name]?.contentHash)
                }
            }
            is com.mezze.language.parsedast.ParsedFunctionType -> {
                MezzeType.FunctionType(
                    paramType = convertParsedTypeAnnotationInternal(annotation.paramType),
                    returnType = convertParsedTypeAnnotationInternal(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        validateHermeticEffects(effectRow.allEffectNames)
                        val hashes = effectRow.effects.mapNotNull { name ->
                            val hash = currentEffectDecls[name]?.contentHash ?: return@mapNotNull null
                            name to hash
                        }.toMap()
                        val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null
                        MezzeType.EffectRow.simple(effectRow.effects.toSet(), rowVar, effectRow.isPerformed, hashes)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertParsedTypeAnnotationInternal(it) },
                            abilityHash = currentAbilityDecls[constraint.abilityName]?.contentHash
                        )
                    }
                )
            }
            is com.mezze.language.parsedast.ParsedRecordType -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertParsedTypeAnnotationInternal(entry.type)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is com.mezze.language.parsedast.ParsedVariantType -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertParsedTypeAnnotationInternal(it) }
                    },
                    rowVar = null
                )
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                val resolvedTypeHash = currentTypeDecls[annotation.constructor]?.contentHash
                when (annotation.constructor) {
                    "List" -> if (annotation.args.size == 1) MezzeType.ListType(convertParsedTypeAnnotationInternal(annotation.args[0]))
                        else MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertParsedTypeAnnotationInternal(it) }, resolvedTypeHash)
                    "Seq" -> if (annotation.args.size == 1) MezzeType.SeqType(convertParsedTypeAnnotationInternal(annotation.args[0]))
                        else MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertParsedTypeAnnotationInternal(it) }, resolvedTypeHash)
                    "Set" -> if (annotation.args.size == 1) MezzeType.SetType(convertParsedTypeAnnotationInternal(annotation.args[0]))
                        else MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertParsedTypeAnnotationInternal(it) }, resolvedTypeHash)
                    "Map" -> if (annotation.args.size == 2) MezzeType.MapType(
                            keyType = convertParsedTypeAnnotationInternal(annotation.args[0]),
                            valueType = convertParsedTypeAnnotationInternal(annotation.args[1])
                        ) else MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertParsedTypeAnnotationInternal(it) }, resolvedTypeHash)
                    else -> MezzeType.TypeApplication(
                        constructor = annotation.constructor,
                        args = annotation.args.map { convertParsedTypeAnnotationInternal(it) },
                        typeHash = resolvedTypeHash
                    )
                }
            }
            is com.mezze.language.parsedast.ParsedSeqType -> {
                MezzeType.SeqType(elementType = convertParsedTypeAnnotationInternal(annotation.elementType))
            }
            is com.mezze.language.parsedast.ParsedQualifiedType -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                MezzeType.TypeApplication(
                    constructor = qualifiedName,
                    args = annotation.args.map { convertParsedTypeAnnotationInternal(it) },
                    typeHash = currentTypeDecls[qualifiedName]?.contentHash
                )
            }
        }
    }

    /**
     * Collect all type variables from a MezzeType.
     * Helper for building polymorphic type schemes.
     */
    private fun collectTypeVarsFromType(type: MezzeType, typeVars: MutableSet<TypeVar>) {
        when (type) {
            is MezzeType.VarType -> typeVars.add(type.variable)
            is MezzeType.FunctionType -> {
                collectTypeVarsFromType(type.paramType, typeVars)
                collectTypeVarsFromType(type.returnType, typeVars)
                type.abilityConstraints.forEach { constraint ->
                    constraint.typeArgs.forEach { collectTypeVarsFromType(it, typeVars) }
                }
            }
            is MezzeType.RecordType -> {
                type.fields.values.forEach { collectTypeVarsFromType(it, typeVars) }
            }
            is MezzeType.VariantType -> {
                type.constructors.values.forEach { it?.let { collectTypeVarsFromType(it, typeVars) } }
            }
            is MezzeType.TypeApplication -> {
                type.args.forEach { collectTypeVarsFromType(it, typeVars) }
            }
            is MezzeType.SeqType -> collectTypeVarsFromType(type.elementType, typeVars)
            is MezzeType.ListType -> collectTypeVarsFromType(type.elementType, typeVars)
            is MezzeType.SetType -> collectTypeVarsFromType(type.elementType, typeVars)
            is MezzeType.SortedMapType -> {
                collectTypeVarsFromType(type.keyType, typeVars)
                collectTypeVarsFromType(type.valueType, typeVars)
            }
            is MezzeType.SortedSetType -> collectTypeVarsFromType(type.elementType, typeVars)
            is MezzeType.MapType -> {
                collectTypeVarsFromType(type.keyType, typeVars)
                collectTypeVarsFromType(type.valueType, typeVars)
            }
            is MezzeType.SuspendedType -> collectTypeVarsFromType(type.valueType, typeVars)
            is MezzeType.NewtypeType -> collectTypeVarsFromType(type.wrappedType, typeVars)
            // Primitive types have no type variables
            is MezzeType.IntType, is MezzeType.FloatType, is MezzeType.StringType,
            is MezzeType.BoolType, is MezzeType.NatType, is MezzeType.FracType,
            is MezzeType.OrderingType, is MezzeType.BytesType, is MezzeType.ByteBufferType,
            is MezzeType.CharType, is MezzeType.EffectRow -> {}
        }
    }

    /**
     * Register built-in variant types like Bool, Option and Result.
     *
     * These are fundamental types that should always be available.
     * They are added automatically if not already defined in the program.
     */

    /**
     * Register built-in variant types as ParsedTypeDecl (v2 types).
     * Mirrors addBuiltInTypes but produces ParsedTypeDecl instead of TypeDeclaration.
     */
    private fun addBuiltInParsedTypes(typeDecls: MutableMap<String, ParsedTypeDecl>) {
        val syntheticLoc = Loc(file = "<builtin>", startLine = 0, startCol = 0, endLine = 0, endCol = 0)

        if (!typeDecls.containsKey("Bool")) {
            typeDecls["Bool"] = ParsedTypeDecl(
                name = "Bool",
                typeParams = emptyList(),
                definition = ParsedVariantDef(
                    constructors = listOf(
                        ParsedVariantCtorDef("True", null, syntheticLoc),
                        ParsedVariantCtorDef("False", null, syntheticLoc)
                    ),
                    isOpen = false,
                    loc = syntheticLoc
                ),
                visibility = ParsedVisibility.PUBLIC,
                loc = syntheticLoc
            )
        }

        if (!typeDecls.containsKey(BuiltinTypes.OPTION)) {
            typeDecls[BuiltinTypes.OPTION] = ParsedTypeDecl(
                name = BuiltinTypes.OPTION,
                typeParams = listOf("a"),
                definition = ParsedVariantDef(
                    constructors = listOf(
                        ParsedVariantCtorDef(BuiltinTypes.SOME,
                            ParsedRecordType(listOf(ParsedRecordTypeEntry(BuiltinTypes.VAL_FIELD, ParsedNamedType("a", syntheticLoc), syntheticLoc)), false, syntheticLoc),
                            syntheticLoc),
                        ParsedVariantCtorDef(BuiltinTypes.NONE, null, syntheticLoc)
                    ),
                    isOpen = false,
                    loc = syntheticLoc
                ),
                visibility = ParsedVisibility.PUBLIC,
                loc = syntheticLoc
            )
        }

        if (!typeDecls.containsKey(BuiltinTypes.RESULT)) {
            typeDecls[BuiltinTypes.RESULT] = ParsedTypeDecl(
                name = BuiltinTypes.RESULT,
                typeParams = listOf("a", "e"),
                definition = ParsedVariantDef(
                    constructors = listOf(
                        ParsedVariantCtorDef(BuiltinTypes.OK,
                            ParsedRecordType(listOf(ParsedRecordTypeEntry(BuiltinTypes.VAL_FIELD, ParsedNamedType("a", syntheticLoc), syntheticLoc)), false, syntheticLoc),
                            syntheticLoc),
                        ParsedVariantCtorDef(BuiltinTypes.ERR,
                            ParsedRecordType(listOf(ParsedRecordTypeEntry(BuiltinTypes.ERROR_FIELD, ParsedNamedType("e", syntheticLoc), syntheticLoc)), false, syntheticLoc),
                            syntheticLoc)
                    ),
                    isOpen = false,
                    loc = syntheticLoc
                ),
                visibility = ParsedVisibility.PUBLIC,
                loc = syntheticLoc
            )
        }

        if (!typeDecls.containsKey("Ordering")) {
            typeDecls["Ordering"] = ParsedTypeDecl(
                name = "Ordering",
                typeParams = emptyList(),
                definition = ParsedVariantDef(
                    constructors = listOf(
                        ParsedVariantCtorDef("LT", null, syntheticLoc),
                        ParsedVariantCtorDef("EQ", null, syntheticLoc),
                        ParsedVariantCtorDef("GT", null, syntheticLoc)
                    ),
                    isOpen = false,
                    loc = syntheticLoc
                ),
                visibility = ParsedVisibility.PUBLIC,
                loc = syntheticLoc
            )
        }
    }

    /** Ensure a ParsedImplAbilityDecl has a content hash (compute via ParsedDeclHasher if missing). */
    private fun ensureContentHash(decl: ParsedImplAbilityDecl, @Suppress("UNUSED_PARAMETER") file: String): ParsedImplAbilityDecl {
        if (decl.contentHash != null) return decl
        return decl.copy(contentHash = com.mezze.language.parsedast.ParsedDeclHasher.hashImplAbilityDecl(decl))
    }

    companion object {
        /**
         * Validate that an effect set doesn't violate hermetic effect rules.
         *
         * Hermetic effects (like Mut) cannot coexist with other effects
         * to ensure data-race freedom.  Uses hash-only identification via
         * WellKnownHashes.isMut().
         *
         * @param effects The set of effect names
         * @param effectHashes Map of effectName → contentHash for hash-based identification
         * @param sourceLoc Optional source location for error messages
         * @throws TypeCheckError if a hermetic effect coexists with other effects
         */
        fun validateHermeticEffects(
            effects: Set<String>,
            effectHashes: Map<String, com.mezze.language.typedast.Hash> = emptyMap(),
            sourceLoc: com.mezze.language.parsedast.Loc? = null
        ) {
            // Resolve effect hash from row or WellKnownHashes
            fun resolveHash(name: String): com.mezze.language.typedast.Hash? = effectHashes[name] ?: WellKnownHashes.resolveHash(name)

            val hermeticEffect = effects.firstOrNull { name ->
                WellKnownHashes.isMut(resolveHash(name))
            }
            if (hermeticEffect != null && effects.size > 1) {
                val otherEffects = effects.filter { name ->
                    !WellKnownHashes.isMut(resolveHash(name))
                }.joinToString(", ")
                throw TypeCheckError(
                    "$hermeticEffect effect is hermetic: cannot coexist with other effects ($otherEffects). " +
                        "$hermeticEffect must be the only effect in scope to prevent data races. " +
                        "Either handle the other effects before entering the $hermeticEffect scope, " +
                        "or restructure the code to separate $hermeticEffect from other effectful operations.",
                    sourceLoc
                )
            }
        }
    }

    /**
     * Walk a MezzeType collecting all distinct VarTypes in left-to-right
     * order. Used for positional alignment between the impl target's
     * declared type vars and a harvested return type whose vars carry
     * solver-generated names.
     */
    private fun collectVarTypesInOrder(type: MezzeType): List<com.mezze.language.ast.TypeVar> {
        val seen = LinkedHashSet<com.mezze.language.ast.TypeVar>()
        fun walk(t: MezzeType) {
            when (t) {
                is MezzeType.VarType -> seen.add(t.variable)
                is MezzeType.FunctionType -> { walk(t.paramType); walk(t.returnType) }
                is MezzeType.RecordType -> t.fields.values.forEach { walk(it) }
                is MezzeType.VariantType -> t.constructors.values.filterNotNull().forEach { walk(it) }
                is MezzeType.SeqType -> walk(t.elementType)
                is MezzeType.ListType -> walk(t.elementType)
                is MezzeType.SortedSetType -> walk(t.elementType)
                is MezzeType.SetType -> walk(t.elementType)
                is MezzeType.MapType -> { walk(t.keyType); walk(t.valueType) }
                is MezzeType.SortedMapType -> { walk(t.keyType); walk(t.valueType) }
                is MezzeType.TypeApplication -> t.args.forEach { walk(it) }
                is MezzeType.SuspendedType -> walk(t.valueType)
                is MezzeType.NewtypeType -> walk(t.wrappedType)
                else -> {}
            }
        }
        walk(type)
        return seen.toList()
    }

    /**
     * Replace each VarType in `type` with the corresponding TypeVar from
     * `mapping`. TypeVars not in the mapping are left untouched.
     */
    private fun alignVarTypesByMapping(
        type: MezzeType,
        mapping: Map<com.mezze.language.ast.TypeVar, com.mezze.language.ast.TypeVar>
    ): MezzeType = when (type) {
        is MezzeType.VarType -> mapping[type.variable]?.let { MezzeType.VarType(it) } ?: type
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = alignVarTypesByMapping(type.paramType, mapping),
            returnType = alignVarTypesByMapping(type.returnType, mapping),
            effects = type.effects,
            abilityConstraints = type.abilityConstraints
        )
        is MezzeType.RecordType -> MezzeType.RecordType(
            fields = type.fields.mapValues { alignVarTypesByMapping(it.value, mapping) },
            rowVar = type.rowVar
        )
        is MezzeType.VariantType -> MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, v) -> v?.let { alignVarTypesByMapping(it, mapping) } }
        )
        is MezzeType.SeqType -> MezzeType.SeqType(alignVarTypesByMapping(type.elementType, mapping))
        is MezzeType.ListType -> MezzeType.ListType(alignVarTypesByMapping(type.elementType, mapping))
        is MezzeType.SetType -> MezzeType.SetType(alignVarTypesByMapping(type.elementType, mapping))
        is MezzeType.SortedSetType -> MezzeType.SortedSetType(alignVarTypesByMapping(type.elementType, mapping))
        is MezzeType.MapType -> MezzeType.MapType(
            keyType = alignVarTypesByMapping(type.keyType, mapping),
            valueType = alignVarTypesByMapping(type.valueType, mapping)
        )
        is MezzeType.SortedMapType -> MezzeType.SortedMapType(
            keyType = alignVarTypesByMapping(type.keyType, mapping),
            valueType = alignVarTypesByMapping(type.valueType, mapping)
        )
        is MezzeType.TypeApplication -> MezzeType.TypeApplication(
            type.constructor,
            type.args.map { alignVarTypesByMapping(it, mapping) },
            type.typeHash
        )
        is MezzeType.SuspendedType -> MezzeType.SuspendedType(
            valueType = alignVarTypesByMapping(type.valueType, mapping),
            effects = type.effects
        )
        is MezzeType.NewtypeType -> MezzeType.NewtypeType(
            type.name, type.constructor, alignVarTypesByMapping(type.wrappedType, mapping)
        )
        else -> type
    }

    /**
     * Walk a MezzeType collecting `name -> TypeVar` pairs for every VarType
     * encountered. Used by `registerImportedInherentImpl` to align
     * harvested return-type TypeVars with the impl target's TypeVars by
     * name.
     */
    private fun collectVarTypesByName(
        type: MezzeType,
        out: MutableMap<String, com.mezze.language.ast.TypeVar>
    ) {
        when (type) {
            is MezzeType.VarType -> { out.putIfAbsent(type.variable.name, type.variable) }
            is MezzeType.FunctionType -> {
                collectVarTypesByName(type.paramType, out); collectVarTypesByName(type.returnType, out)
            }
            is MezzeType.RecordType -> type.fields.values.forEach { collectVarTypesByName(it, out) }
            is MezzeType.VariantType -> type.constructors.values.filterNotNull().forEach { collectVarTypesByName(it, out) }
            is MezzeType.SeqType -> collectVarTypesByName(type.elementType, out)
            is MezzeType.ListType -> collectVarTypesByName(type.elementType, out)
            is MezzeType.SortedSetType -> collectVarTypesByName(type.elementType, out)
            is MezzeType.SetType -> collectVarTypesByName(type.elementType, out)
            is MezzeType.MapType -> { collectVarTypesByName(type.keyType, out); collectVarTypesByName(type.valueType, out) }
            is MezzeType.SortedMapType -> { collectVarTypesByName(type.keyType, out); collectVarTypesByName(type.valueType, out) }
            is MezzeType.TypeApplication -> type.args.forEach { collectVarTypesByName(it, out) }
            is MezzeType.SuspendedType -> collectVarTypesByName(type.valueType, out)
            is MezzeType.NewtypeType -> collectVarTypesByName(type.wrappedType, out)
            else -> {}
        }
    }

    /**
     * Replace each VarType in `type` with the corresponding TypeVar from
     * `byName` if their names match. Other VarTypes are left untouched.
     */
    private fun alignVarTypesByName(
        type: MezzeType,
        byName: Map<String, com.mezze.language.ast.TypeVar>
    ): MezzeType = when (type) {
        is MezzeType.VarType -> byName[type.variable.name]?.let { MezzeType.VarType(it) } ?: type
        is MezzeType.FunctionType -> MezzeType.FunctionType(
            paramType = alignVarTypesByName(type.paramType, byName),
            returnType = alignVarTypesByName(type.returnType, byName),
            effects = type.effects,
            abilityConstraints = type.abilityConstraints
        )
        is MezzeType.RecordType -> MezzeType.RecordType(
            fields = type.fields.mapValues { alignVarTypesByName(it.value, byName) },
            rowVar = type.rowVar
        )
        is MezzeType.VariantType -> MezzeType.VariantType(
            constructors = type.constructors.mapValues { (_, v) -> v?.let { alignVarTypesByName(it, byName) } }
        )
        is MezzeType.SeqType -> MezzeType.SeqType(alignVarTypesByName(type.elementType, byName))
        is MezzeType.ListType -> MezzeType.ListType(alignVarTypesByName(type.elementType, byName))
        is MezzeType.SetType -> MezzeType.SetType(alignVarTypesByName(type.elementType, byName))
        is MezzeType.SortedSetType -> MezzeType.SortedSetType(alignVarTypesByName(type.elementType, byName))
        is MezzeType.MapType -> MezzeType.MapType(
            keyType = alignVarTypesByName(type.keyType, byName),
            valueType = alignVarTypesByName(type.valueType, byName)
        )
        is MezzeType.SortedMapType -> MezzeType.SortedMapType(
            keyType = alignVarTypesByName(type.keyType, byName),
            valueType = alignVarTypesByName(type.valueType, byName)
        )
        is MezzeType.TypeApplication -> MezzeType.TypeApplication(
            type.constructor,
            type.args.map { alignVarTypesByName(it, byName) },
            type.typeHash
        )
        is MezzeType.SuspendedType -> MezzeType.SuspendedType(
            valueType = alignVarTypesByName(type.valueType, byName),
            effects = type.effects
        )
        is MezzeType.NewtypeType -> MezzeType.NewtypeType(
            type.name, type.constructor, alignVarTypesByName(type.wrappedType, byName)
        )
        else -> type
    }
}
