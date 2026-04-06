package com.mezze.language.typechecker.context

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.Visibility as ParsedVisibility
import com.mezze.language.parser.MezzeParser as ParserBridge
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
     * Populated at the start of build() so that convertTypeAnnotationInternal
     * can resolve effect names to their content hashes for EffectRow.effectHashes.
     */
    private var currentEffectDecls: Map<String, ParsedEffectDecl> = emptyMap()

    /**
     * Ability declarations available during the current build() invocation.
     * Populated at the start of build() so that convertTypeAnnotationInternal
     * can resolve ability names to their content hashes for FunctionAbilityConstraint.abilityHash.
     */
    private var currentAbilityDecls: Map<String, ParsedAbilityDecl> = emptyMap()

    /**
     * Type declarations available during the current build() invocation.
     * Populated at the start of build() so that convertTypeAnnotationInternal
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
        val performerToEffectHash: Map<String, String> = emptyMap(),  // performer contentHash → effect contentHash
        /** Maps performer names to the set of effects they ADD (not handle) */
        val performerAddedEffects: Map<String, Set<String>> = emptyMap(),
        /** Maps performer names to their ImplDeclaration (for content hash lookup) */
        val implDeclarations: Map<String, ImplDeclaration> = emptyMap()
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

        // Still need v1 types for internal ContextBuilder methods that haven't been migrated yet
        val v1TypeDecls = typeDecls.mapValues { (_, v) -> parsedTypeDeclToV1(v, file) }
        val v1AbilityDecls = abilityDecls.mapValues { (_, v) -> parsedAbilityDeclToV1(v, file) }
        val v1EffectDecls = effectDecls.mapValues { (_, v) -> parsedEffectDeclToV1(v, file) }
        val v1ImportedAbilities = importedAbilities.mapValues { (_, v) -> parsedAbilityDeclToV1(v, file) }
        val v1ImportedEffects = importedEffects.mapValues { (_, v) -> parsedEffectDeclToV1(v, file) }
        val v1ImportedTypes = importedTypes.mapValues { (_, v) -> parsedTypeDeclToV1(v, file) }

        // Resolve impl declarations: the parser can't distinguish ability impls from
        // effect impls (same `impl X for Y where {}` syntax). The typechecker knows which
        // names are abilities vs effects from imports, so we resolve here.
        val allAbilityNames = abilityDecls.keys + importedAbilities.keys +
            importedAbilities.values.map { it.name }.toSet()
        val allEffectNames = effectDecls.keys + importedEffects.keys +
            importedEffects.values.map { it.name }.toSet()

        val resolvedImplDecls = mutableListOf<ImplDeclaration>()       // real effect performers
        val resolvedAbilityImplDecls = mutableListOf<ImplAbilityDeclaration>()  // ability impls

        // First, add explicitly parsed ability impls
        resolvedAbilityImplDecls.addAll(
            module.decls.filterIsInstance<ParsedImplAbilityDecl>().map { parsedImplAbilityDeclToV1(it, file) }
        )

        // Then resolve ParsedPerformerDecl: ability impl or effect performer?
        for (perfDecl in module.decls.filterIsInstance<ParsedPerformerDecl>()) {
            val refName = perfDecl.effect.localName
            val isAbility = refName in allAbilityNames
            val isEffect = refName in allEffectNames

            if (isAbility && !isEffect) {
                // This is actually an ability impl, convert it
                val v1Perf = parsedPerformerDeclToV1(perfDecl, file)
                val targetType = IdentifierTypeAnnotation(v1Perf.performerName)
                val converted = ImplAbilityDeclaration(
                    target = ImplTarget.Named(v1Perf.effect),
                    targetTypes = listOf(targetType),
                    forallVars = emptyList(),
                    associatedTypeBindings = v1Perf.associatedTypeBindings,
                    operations = v1Perf.operations.map { op ->
                        ImplAbilityOperation(
                            name = op.name,
                            paramType = op.paramType?.takeUnless { it is RecordTypeAnnotation && it.entries.isEmpty() },
                            returnType = op.returnType?.takeUnless { it is RecordTypeAnnotation && it.entries.isEmpty() },
                            implementation = op.implementation,
                            abilityConstraints = emptyList()
                        )
                    },
                    contentHash = null
                )
                val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(converted)
                resolvedAbilityImplDecls.add(converted.copy(contentHash = hash.toFullHexString()))
            } else {
                // Real effect performer
                resolvedImplDecls.add(parsedPerformerDeclToV1(perfDecl, file))
            }
        }

        val implDecls = resolvedImplDecls
        val abilityImplDecls = resolvedAbilityImplDecls

        val letDecls = module.decls.filterIsInstance<ParsedLetDecl>()
            .map { parsedLetDeclToV1(it, file) }

        // Validate duplicates — tolerate prelude duplicates when modules are merged
        // Use the full decl lists (not deduplicated maps) to detect duplicates
        val typeDeclList = module.decls.filterIsInstance<ParsedTypeDecl>().map { parsedTypeDeclToV1(it, file) }
        val abilityDeclList = module.decls.filterIsInstance<ParsedAbilityDecl>().map { parsedAbilityDeclToV1(it, file) }
        val effectDeclList = module.decls.filterIsInstance<ParsedEffectDecl>().map { parsedEffectDeclToV1(it, file) }
        val allV1Items: List<ProgramItem> = typeDeclList + abilityDeclList + effectDeclList + implDecls + abilityImplDecls + letDecls
        try {
            DuplicateDeclarationValidator.validateProgram(Program(allV1Items))
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

        // Make effect declarations available to convertTypeAnnotationInternal so it can
        // populate EffectRow.effectHashes with content hashes for hermetic checks.
        currentEffectDecls = allEffectDecls

        // Make ability and type declarations available to convertTypeAnnotationInternal
        // so it can populate abilityHash / typeHash content hashes.
        currentAbilityDecls = abilityDecls + importedAbilities
        currentTypeDecls = typeDecls + importedTypes

        // v1 effect decls for internal ContextBuilder methods
        val allV1EffectDecls = v1EffectDecls + v1ImportedEffects
        val allV1AbilityDecls = v1AbilityDecls + v1ImportedAbilities
        val allV1TypeDecls = v1TypeDecls.toMutableMap()
        allV1TypeDecls.putAll(v1ImportedTypes)

        // Convert imported v2 performers to v1 ImplDeclarations for internal use
        val v1ImportedImpls: Map<String, ImplDeclaration> = importedImpls.mapValues { (_, perf) ->
            parsedPerformerDeclToV1(perf, file)
        }

        // Build performer to effect mapping
        // CRITICAL: Merge imported impls with local impls
        val localMapping = implDecls.associate { impl ->
            val effectDecl = allEffectDecls[impl.effect.localName]
            val effectName = effectDecl?.name ?: impl.effect.localName
            impl.performerName to effectName
        }
        val importedMapping = v1ImportedImpls.mapValues { (_, impl) ->
            val effectDecl = allEffectDecls[impl.effect.localName]
            effectDecl?.name ?: impl.effect.localName
        }
        val performerToEffectMap = importedMapping + localMapping  // Local impls override imports

        // Build hash-based performer→effect mapping
        val performerToEffectHashMap = mutableMapOf<String, String>()
        for (impl in implDecls) {
            val perfHash = impl.contentHash ?: continue
            val effectDecl = allEffectDecls[impl.effect.localName]
            val effHash = effectDecl?.contentHash ?: continue
            performerToEffectHashMap[perfHash] = effHash
        }
        for ((_, impl) in v1ImportedImpls) {
            val perfHash = impl.contentHash ?: continue
            val effectDecl = allEffectDecls[impl.effect.localName]
            val effHash = effectDecl?.contentHash ?: continue
            performerToEffectHashMap[perfHash] = effHash
        }

        // Build context with bindings for declarations (uses v1 types internally)
        var ctx = parentContext

        // Add type constructors to context
        for (typeDecl in v1TypeDecls.values) {
            ctx = addTypeConstructors(ctx, typeDecl)
        }

        // Note: True and False are parsed as VariantConstruction nodes (uppercase variants).

        // Add ability methods to context — both local AND imported abilities
        for (abilityDecl in v1AbilityDecls.values) {
            ctx = addAbilityMethods(ctx, abilityDecl)
        }
        for (abilityDecl in v1ImportedAbilities.values) {
            // Only add if not already present (local takes precedence)
            if (ctx.getAbilityOperationType(abilityDecl.name) == null) {
                ctx = addAbilityMethods(ctx, abilityDecl)
            }
        }

        // Add effect operations to context
        for (effectDecl in v1EffectDecls.values) {
            ctx = addEffectOperations(ctx, effectDecl)
        }

        // Add effect performers (impl blocks) to context
        for (implDecl in implDecls) {
            ctx = addEffectPerformer(ctx, implDecl, allV1EffectDecls)
        }
        for ((_, implDecl) in v1ImportedImpls) {
            ctx = addEffectPerformer(ctx, implDecl, allV1EffectDecls)
        }

        // Add ability implementations to context (with ability declaration hashes for hash-based index)
        val allV1AbilityDeclsForHash = v1AbilityDecls + v1ImportedAbilities
        for (abilityImplDecl in abilityImplDecls) {
            ctx = addAbilityImpl(ctx, abilityImplDecl, allV1AbilityDeclsForHash, v1ImportedAbilities)
        }

        // VALIDATION PHASE: Run ability validations after context is built
        AbilityValidator.validateNameCollisions(
            abilityDeclarations = v1AbilityDecls,
            typeDeclarations = allV1TypeDecls,
            effectDeclarations = v1EffectDecls
        )

        for (abilityDecl in v1AbilityDecls.values) {
            AbilityValidator.validateAbilityDependencies(abilityDecl, v1AbilityDecls)
        }

        for (abilityDecl in v1AbilityDecls.values) {
            AbilityValidator.validateAbilityDeclaration(abilityDecl)
        }

        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateAbilityImplementation(
                impl = abilityImplDecl,
                abilityDeclarations = allV1AbilityDecls,
                typeDeclarations = allV1TypeDecls
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

        // Build performer added effects mapping
        val performerAddedEffectsMap = (implDecls + v1ImportedImpls.values).associate { impl ->
            impl.performerName to extractAddedEffects(impl)
        }.filterValues { it.isNotEmpty() }

        // Build implDeclarations map: performer name -> ImplDeclaration
        val implDeclarationsMap = (implDecls.associateBy { it.performerName }) +
            v1ImportedImpls.mapValues { (_, impl) -> impl }

        return BuildResult(
            context = ctx,
            typeDeclarations = typeDecls,
            abilityDeclarations = abilityDecls,
            effectDeclarations = effectDecls,
            performerToEffect = performerToEffectMap,
            performerToEffectHash = performerToEffectHashMap,
            performerAddedEffects = performerAddedEffectsMap,
            implDeclarations = implDeclarationsMap
        )
    }


    /**
     * Extract effects that an impl handler ADDS (effects in operation signatures).
     *
     * Example: For `impl Log for FileLogger where {
     *            info: { msg: String } -> {} <FileSystem> = ...
     *          }`
     * Returns: setOf("FileSystem")
     *
     * These effects come from the PerformerOperation.effects field, which is parsed
     * from the effect annotation in the operation signature (e.g., <FileSystem>).
     */
    private fun extractAddedEffects(impl: ImplDeclaration): Set<String> {
        return impl.operations.flatMap { op ->
            // Effects are stored directly in PerformerOperation.effects
            op.effects?.effects ?: emptyList()
        }.toSet()
    }

    /**
     * Add variant constructors to context.
     * @param isImported If true, newtype constructors are skipped (opaque type rule).
     */
    fun addTypeConstructors(ctx: TypingContext, typeDecl: TypeDeclaration, isImported: Boolean = false): TypingContext {
        var result = ctx

        // For variant types, add each constructor
        when (val definition = typeDecl.definition) {
            is VariantDefinition -> {
                for (constructor in definition.constructors) {
                    val constructorType = buildConstructorType(typeDecl, constructor)

                    // Store constructors as POLYMORPHIC bindings so type variables are instantiated fresh on each use
                    // This prevents occurs check failures where the same TypeVar instance appears in multiple places
                    // Collect all type variables in the constructor type
                    val typeVars = mutableSetOf<TypeVar>()
                    collectTypeVarsFromType(constructorType, typeVars)
                    val scheme = TypeScheme(typeVars.toList(), emptyList(), constructorType)
                    result = result.extendPoly(constructor.name, scheme)
                }
            }
            is RecordDefinition -> {
                // Records don't have constructors in the same way
                // They have a single constructor with all fields
                // This will be handled separately if needed
            }
            is NewtypeDefinition -> {
                // Newtype constructors are private to the defining module (opaque type).
                // Importers can use the TYPE but not construct or destruct it.
                if (isImported) return result

                // Newtype constructor: UserId : Int -> TypeApplication("UserId", [])
                // The return type is TypeApplication (not NewtypeType) so type parameters
                // are preserved during unification. NewtypeType is only used in typeDeclarations
                // for derivation checking.
                typeVarCache.clear()
                val wrappedMezzeType = convertTypeAnnotationInternal(definition.wrappedType)
                val typeArgs = typeDecl.typeParams.map { param ->
                    MezzeType.VarType(typeVarCache.getOrPut(param) { TypeVar(param) })
                }
                val newtypeResult = MezzeType.TypeApplication(typeDecl.name, typeArgs)
                val constructorType = MezzeType.FunctionType(
                    paramType = wrappedMezzeType,
                    returnType = newtypeResult
                )
                val typeVars = mutableSetOf<TypeVar>()
                collectTypeVarsFromType(constructorType, typeVars)
                val scheme = TypeScheme(typeVars.toList(), emptyList(), constructorType)
                result = result.extendPoly(definition.constructor, scheme)
            }
        }

        return result
    }

    /**
     * Build function type for a variant constructor
     */
    private fun buildConstructorType(typeDecl: TypeDeclaration, constructor: VariantConstructorDef): MezzeType {
        // Clear cache at the start of building each constructor
        // This gives each constructor a fresh set of type variables
        typeVarCache.clear()

        val resultType = if (typeDecl.typeParams.isEmpty()) {
            // Monomorphic variant: Result
            // Get constructors from the variant definition
            when (val def = typeDecl.definition) {
                is NewtypeDefinition -> {
                    // Use TypeApplication so type parameters are preserved during unification
                    MezzeType.TypeApplication(typeDecl.name, emptyList())
                }
                else -> {
                    val constructors = when (def) {
                        is VariantDefinition -> def.constructors.associate { ctor ->
                            ctor.name to ctor.dataType?.let { convertTypeAnnotationInternal(it) }
                        }
                        is RecordDefinition -> emptyMap()
                        is NewtypeDefinition -> emptyMap() // unreachable
                    }
                    MezzeType.VariantType(
                        constructors = constructors,
                        rowVar = null,
                        typeName = typeDecl.name,
                        typeHash = typeDecl.contentHash
                    )
                }
            }
        } else {
            // Polymorphic variant: Result 'a 'b
            // Use cached type variables for type parameters
            MezzeType.TypeApplication(
                constructor = typeDecl.name,
                args = typeDecl.typeParams.map { param ->
                    val typeVar = typeVarCache.getOrPut(param) {
                        TypeVar(param)
                    }
                    MezzeType.VarType(typeVar)
                }
            )
        }

        // If constructor has data, it's a function: data -> Result
        return when (val dataType = constructor.dataType) {
            null -> resultType // No data, just the type itself
            else -> MezzeType.FunctionType(
                paramType = convertTypeAnnotationInternal(dataType),
                returnType = resultType
            )
        }
    }

    /**
     * Add ability methods to context
     */
    private fun addAbilityMethods(ctx: TypingContext, abilityDecl: AbilityDeclaration): TypingContext {
        var result = ctx

        // Add ability record type (for qualified access like Show.display)
        val abilityRecordType = MezzeType.RecordType(
            fields = abilityDecl.operations.associate { op ->
                // Convert method-level ability constraints to FunctionAbilityConstraint
                val constraints = op.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { convertTypeAnnotation(it) },
                        abilityHash = currentAbilityDecls[constraint.abilityName]?.contentHash
                    )
                }
                val functionType = MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(op.paramType),
                    returnType = convertTypeAnnotation(op.returnType),
                    abilityConstraints = constraints
                )
                op.name to functionType
            }
        )
        // Use dedicated map instead of "${name}_ability" pattern
        result = result.extendWithAbilityOperationType(abilityDecl.name, abilityRecordType)

        return result
    }

    /**
     * Add effect operations to context
     */
    private fun addEffectOperations(ctx: TypingContext, effectDecl: EffectDeclaration): TypingContext {
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
                // IMPORTANT: Use convertTypeAnnotationInternal to avoid clearing cache between operations
                val convertedReturnType = convertTypeAnnotationInternal(op.returnType)
                val convertedParamType = convertTypeAnnotationInternal(op.paramType)
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
    private fun extractSuspensionType(
        implDecl: ImplDeclaration,
        associatedTypes: Map<String, MezzeType>
    ): MezzeType? {
        // Look through all operations for suspend_with calls
        for (op in implDecl.operations) {
            val impl = op.implementation
            if (impl !is LambdaExpression) continue

            val suspendWithValue = findSuspendWithValue(impl.body)
            if (suspendWithValue != null) {
                return inferSuspensionTypeFromExpression(suspendWithValue, associatedTypes)
            }
        }
        return null
    }

    /**
     * Recursively find the value expression passed to suspend_with.
     * Looks for patterns like: cont.suspend_with {value = expr}
     */
    private fun findSuspendWithValue(expr: Expression): Expression? {
        return when (expr) {
            is FunctionCall -> {
                // Check if this is a call to suspend_with
                val callee = expr.function
                if (callee is MemberAccess && callee.member == "suspend_with") {
                    // Found suspend_with call, extract the value from arguments
                    val args = expr.arguments
                    if (args is RecordLiteral) {
                        for (entry in args.entries) {
                            if (entry is RecordFieldEntry && entry.name == "value") {
                                return entry.value
                            }
                        }
                    }
                }
                // Not a suspend_with call, recurse into arguments
                findSuspendWithValue(expr.arguments)
            }
            is DoExpression -> {
                // Check the result expression
                findSuspendWithValue(expr.result) ?: run {
                    // Check statements
                    for (stmt in expr.statements.reversed()) {
                        when (stmt) {
                            is ExpressionStatement -> {
                                val found = findSuspendWithValue(stmt.expression)
                                if (found != null) return found
                            }
                            is LetStatement -> {
                                val found = findSuspendWithValue(stmt.expression)
                                if (found != null) return found
                            }
                        }
                    }
                    null
                }
            }
            is IfExpression -> {
                // Check both branches
                findSuspendWithValue(expr.thenBranch) ?: findSuspendWithValue(expr.elseBranch)
            }
            is MatchExpression -> {
                // Check all cases
                for (case in expr.cases) {
                    val found = findSuspendWithValue(case.expression)
                    if (found != null) return found
                }
                null
            }
            is LambdaExpression -> findSuspendWithValue(expr.body)
            else -> null
        }
    }

    /**
     * Infer the suspension type from the expression passed to suspend_with.
     *
     * For variant constructors like `Err {error}`, we create a variant type.
     * For other expressions, we try to infer a reasonable type.
     */
    private fun inferSuspensionTypeFromExpression(
        expr: Expression,
        associatedTypes: Map<String, MezzeType>
    ): MezzeType? {
        return when (expr) {
            is VariantConstruction -> {
                // Variant like Err {error} or Ok {val}
                val constructorName = expr.constructor
                val exprValue = expr.value
                val payloadType = if (exprValue != null) {
                    inferPayloadType(exprValue, associatedTypes)
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
            is RecordLiteral -> {
                // Record literal - infer field types
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is RecordFieldEntry) {
                        val entryValue = entry.value
                        val fieldType = if (entryValue != null) {
                            inferPayloadType(entryValue, associatedTypes)
                        } else {
                            // Shorthand {foo} - look up in associated types
                            associatedTypes[entry.name] ?: MezzeType.VarType(TypeVar.fresh())
                        }
                        fields[entry.name] = fieldType
                    }
                }
                MezzeType.RecordType(fields)
            }
            is Identifier -> {
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
     * Infer the type of a payload expression.
     */
    private fun inferPayloadType(expr: Expression, associatedTypes: Map<String, MezzeType>): MezzeType {
        return when (expr) {
            is RecordLiteral -> {
                val fields = mutableMapOf<String, MezzeType>()
                for (entry in expr.entries) {
                    if (entry is RecordFieldEntry) {
                        val entryValue = entry.value
                        val fieldType = if (entryValue != null) {
                            inferPayloadType(entryValue, associatedTypes)
                        } else {
                            // Shorthand {foo} - look up in associated types
                            associatedTypes[entry.name] ?: MezzeType.VarType(TypeVar.fresh())
                        }
                        fields[entry.name] = fieldType
                    }
                }
                MezzeType.RecordType(fields)
            }
            is Identifier -> {
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
        implDecl: ImplDeclaration,
        effectDecls: Map<String, EffectDeclaration>
    ): TypingContext {
        // Get the effect declaration to check associated types
        val effectDecl = effectDecls[implDecl.effect.localName] ?: return ctx

        // If the effect has associated types, store the performer's bindings
        if (effectDecl.associatedTypes.isNotEmpty()) {
            // Clear cache for performer bindings
            typeVarCache.clear()

            // Convert associated type bindings to MezzeTypes
            // The binding keys in implDecl should already have the ' prefix (e.g., 'state)
            // but the effectDecl.associatedTypes might not
            val bindingFields = implDecl.associatedTypeBindings.mapValues { (_, typeAnnotation) ->
                convertTypeAnnotationInternal(typeAnnotation)
            }

            // Store as RecordType with associated type names as field names
            // CRITICAL: Keys must include the variable name EXACTLY as it appears in the operation types
            // For associated type 'env, the key should be "env" (without ') to match TypeVar(name='env)
            val performerType = MezzeType.RecordType(
                fields = effectDecl.associatedTypes.associate { assocType ->
                    // Remove ' prefix from assocType name to match TypeVar naming
                    val varName = assocType.removePrefix("'")
                    val value = bindingFields[assocType] ?: bindingFields["'$assocType"] ?: MezzeType.VarType(TypeVar.fresh())
                    varName to value  // Store with name like "env", not "'env"
                }
            )

            // Use content hash as the key for cross-module safety
            val contentHash = implDecl.contentHash
                ?: throw IllegalStateException(
                    "Content hash not computed for impl: ${implDecl.performerName}. " +
                    "Ensure computeDeclarationHashes was called before ContextBuilder."
                )

            // Convert performer parameters to a RecordType (e.g., {cont: Continuation 'a 'b 'c})
            // These are the parameters that must be passed when using the performer at a perform site
            val performerFieldsType = if (implDecl.performerParams.isNotEmpty()) {
                MezzeType.RecordType(
                    fields = implDecl.performerParams.associate { param ->
                        param.name to convertTypeAnnotationInternal(param.type)
                    }
                )
            } else null

            // Extract the suspension type from the impl body
            // This analyzes calls to suspend_with to determine what type the perform should return
            val associatedTypesMap = performerType.fields.mapKeys { (k, _) -> k }
            val suspensionType = extractSuspensionType(implDecl, associatedTypesMap)

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
        if (implDecl.performerParams.isNotEmpty()) {
            val contentHash = implDecl.contentHash
                ?: throw IllegalStateException(
                    "Content hash not computed for impl: ${implDecl.performerName}. " +
                    "Ensure computeDeclarationHashes was called before ContextBuilder."
                )

            val performerFieldsType = MezzeType.RecordType(
                fields = implDecl.performerParams.associate { param ->
                    param.name to convertTypeAnnotationInternal(param.type)
                }
            )

            // Extract the suspension type from the impl body (even without associated types)
            val suspensionType = extractSuspensionType(implDecl, emptyMap())

            val performerInfo = TypingContext.PerformerTypeInfo(
                performerName = implDecl.performerName,
                effectName = implDecl.effect.localName,
                associatedTypes = MezzeType.RecordType(emptyMap()),  // No associated types
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
        implDecl: ImplAbilityDeclaration,
        allAbilityDecls: Map<String, AbilityDeclaration> = emptyMap(),
        importedAbilities: Map<String, AbilityDeclaration> = emptyMap()
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
            return addAnonymousImpl(ctx, implDecl, targetTypeNames, implKey)
        }

        // Extract type variables from generic impl header (e.g., 'a from `impl Eq 'a for ['a]`)
        // Only actual type variables (starting with ') are tracked — concrete types (like Str, Float)
        // in abilityTypeArgs are not type variables.
        val typeVars = implDecl.abilityTypeArgs
            .filterIsInstance<IdentifierTypeAnnotation>()
            .filter { it.name.startsWith("'") }
            .map { it.name }
            .toSet()

        // Use allTypeArgs which combines concrete abilityTypeArgs (before 'for') with targetTypes (after 'for').
        // For `impl Into Str for Int`: allTypeArgs=[Str, Int]
        // For `impl Eq 'a for ['a]`: allTypeArgs=[['a]] (type var 'a excluded as it's a quantifier)
        val allTargetAnnotations = implDecl.allTypeArgs
        val allTargetNames = allTargetAnnotations.map { getTypeName(it) }

        // Collect method-level constraints from all operations
        val operationConstraints = implDecl.operations.flatMap { it.abilityConstraints }

        // Create impl info — look up ability declaration hash for hash-based index
        val abilityDeclHash = allAbilityDecls[implDecl.abilityName!!]?.contentHash
            ?: importedAbilities[implDecl.abilityName!!]?.contentHash
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = implDecl.abilityName!!,  // safe: not inherent
            abilityDeclHash = abilityDeclHash,
            targetTypes = allTargetNames,
            targetTypeAnnotations = allTargetAnnotations,
            associatedTypeBindings = implDecl.associatedTypeBindings,
            sourceModule = null,
            contentHash = implKey,
            typeVars = typeVars,
            operationConstraints = operationConstraints
        )

        // Store in context by content hash
        return ctx.extendWithImpl(implKey, implInfo)
    }

    /**
     * Register anonymous impl methods in context.
     * Anonymous impls (`impl Type where { ... }`) attach methods directly to types.
     */
    private fun addAnonymousImpl(
        ctx: TypingContext,
        implDecl: ImplAbilityDeclaration,
        targetTypeNames: List<String>,
        implKey: String
    ): TypingContext {
        var result = ctx
        val targetTypeName = targetTypeNames.firstOrNull() ?: return ctx

        for (op in implDecl.operations) {
            // Build method type from annotations if available
            val opParamType = op.paramType
            val paramType = if (opParamType != null) {
                convertTypeAnnotation(opParamType)
            } else {
                // Infer param type from the lambda's parameters
                val lambda = op.implementation as? LambdaExpression
                if (lambda != null) {
                    when (val params = lambda.parameters) {
                        is TypedParameters -> {
                            MezzeType.RecordType(
                                fields = params.recordType.entries.associate { entry ->
                                    entry.name to convertTypeAnnotation(entry.type)
                                },
                                rowVar = null
                            )
                        }
                        is UntypedParameters -> {
                            if (params.names.isEmpty()) {
                                MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                            } else {
                                // Untyped params — use fresh type vars
                                MezzeType.RecordType(
                                    fields = params.names.associateWith { MezzeType.VarType(TypeVar.fresh()) },
                                    rowVar = null
                                )
                            }
                        }
                        else -> MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                    }
                } else {
                    MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                }
            }

            val opReturnType = op.returnType
            val returnType = if (opReturnType != null) {
                convertTypeAnnotation(opReturnType)
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
                implContentHash = implKey
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
            contentHash = implKey
        )
        result = result.extendWithImpl(implKey, implInfo)

        return result
    }

    /**
     * Register an imported inherent impl's methods in the context.
     * This bypasses validation (since the impl was already validated in its home module)
     * and only registers the InherentMethodInfo + AbilityImplInfo entries.
     *
     * Uses inferred types from the already-typechecked impl (since annotations are often null
     * for impl operations written as `name = {args} -> body`).
     */
    fun registerImportedInherentImpl(
        ctx: TypingContext,
        implDecl: ImplAbilityDeclaration,
        implKey: String
    ): TypingContext {
        var result = ctx
        val targetTypeNames = implDecl.targetTypes.map { getTypeName(it) }
        val targetTypeName = targetTypeNames.firstOrNull() ?: return ctx

        for (op in implDecl.operations) {
            // Use the inferred type from the already-typechecked implementation.
            // The implementation is a lambda whose inferredType is FunctionType(paramType, returnType).
            val methodType = op.implementation.inferredType as? MezzeType.FunctionType
                ?: run {
                    // Fallback: build from annotations or lambda params
                    val paramType = op.paramType?.let { convertTypeAnnotation(it) }
                        ?: run {
                            val lambda = op.implementation as? LambdaExpression
                            if (lambda != null) {
                                when (val params = lambda.parameters) {
                                    is TypedParameters -> MezzeType.RecordType(
                                        fields = params.recordType.entries.associate { entry ->
                                            entry.name to convertTypeAnnotation(entry.type)
                                        },
                                        rowVar = null
                                    )
                                    is UntypedParameters -> MezzeType.RecordType(
                                        fields = if (params.names.isEmpty()) emptyMap()
                                                 else params.names.associateWith { MezzeType.VarType(TypeVar.fresh()) },
                                        rowVar = null
                                    )
                                    else -> MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                                }
                            } else {
                                MezzeType.RecordType(fields = emptyMap(), rowVar = null)
                            }
                        }
                    val returnType = op.returnType?.let { convertTypeAnnotation(it) }
                        ?: MezzeType.VarType(TypeVar.fresh())
                    MezzeType.FunctionType(paramType = paramType, returnType = returnType)
                }

            val methodInfo = TypingContext.InherentMethodInfo(
                targetTypeName = targetTypeName,
                methodName = op.name,
                methodType = methodType,
                implContentHash = implKey
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
            contentHash = implKey
        )
        result = result.extendWithImpl(implKey, implInfo)

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
    private fun getTypeName(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Remove leading quote for type variables
                annotation.name.removePrefix("'")
            }
            is TypeApplicationAnnotation -> {
                // For inherent impls with type vars (e.g., `impl Option 'a where { ... }`),
                // use just the constructor name so methods register under "Option".
                // For concrete type applications (e.g., ability impl for `Option Int`), include args.
                val allArgsAreTypeVars = annotation.args.all { arg ->
                    arg is IdentifierTypeAnnotation && (arg.name.startsWith("'") || arg.name.first().isLowerCase())
                }
                if (allArgsAreTypeVars) {
                    annotation.constructor
                } else {
                    val argNames = annotation.args.map { getTypeName(it) }
                    if (argNames.isEmpty()) {
                        annotation.constructor
                    } else {
                        "${annotation.constructor}_${argNames.joinToString("_")}"
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                // Function types are rare as ability targets, but handle them
                "Function"
            }
            is RecordTypeAnnotation -> {
                // Record types as ability targets are rare
                "Record"
            }
            is VariantTypeAnnotation -> {
                // Variant types as ability targets are rare
                "Variant"
            }
            is SeqTypeAnnotation -> {
                // Seq Int → "Seq_Int"
                "Seq_${getTypeName(annotation.elementType)}"
            }
            is EffectRowAnnotation -> {
                // Shouldn't happen, but handle gracefully
                "EffectRow"
            }
            is QualifiedTypeAnnotation -> {
                // codec::Entry String Json → "codec_Entry_String_Json"
                val modulePart = annotation.modulePath.joinToString("_")
                val argNames = annotation.args.map { getTypeName(it) }
                if (argNames.isEmpty()) {
                    "${modulePart}_${annotation.typeName}"
                } else {
                    "${modulePart}_${annotation.typeName}_${argNames.joinToString("_")}"
                }
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
    fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
        // Clear cache before each top-level conversion
        // This ensures that type variables in separate annotations don't alias
        typeVarCache.clear()
        return convertTypeAnnotationInternal(annotation)
    }

    /**
     * Convert a v2 ParsedTypeAnnotation to MezzeType.
     * Bridge method: converts v2 → v1, then calls the existing converter.
     */
    fun convertParsedTypeAnnotation(annotation: ParsedTypeAnnotation): MezzeType {
        typeVarCache.clear()
        val v1 = ParserBridge.toV1TypeAnnotation(annotation, "<converted>")
        return convertTypeAnnotationInternal(v1)
    }

    /**
     * Internal recursive type annotation converter.
     * Uses cached type variables to ensure consistency within a single annotation.
     */
    private fun convertTypeAnnotationInternal(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check primitive types first (includes Bool -> MezzeType.BoolType)
                PrimitiveTypes.fromName(annotation.name)?.let { return it }

                when (annotation.name) {
                    else -> {
                        // Type variable or type constructor
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            // Use cached type variable to ensure all occurrences of 'a refer to the same instance
                            val varName = annotation.name.removePrefix("'")
                            val typeVar = typeVarCache.getOrPut(varName) {
                                TypeVar(varName)
                            }
                            MezzeType.VarType(typeVar)
                        } else {
                            // Type constructor with no args - treat as type application
                            MezzeType.TypeApplication(annotation.name, emptyList(), currentTypeDecls[annotation.name]?.contentHash)
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                MezzeType.FunctionType(
                    paramType = convertTypeAnnotationInternal(annotation.paramType),
                    returnType = convertTypeAnnotationInternal(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        // Validate hermetic effects (e.g., Mutation cannot coexist with other effects)
                        // Use allEffectNames to include both simple effects and effect annotations
                        validateHermeticEffects(effectRow.allEffectNames)

                        // Build effectHashes: map each effect name to its content hash (if known)
                        val hashes = effectRow.effects.mapNotNull { name ->
                            val hash = currentEffectDecls[name]?.contentHash ?: return@mapNotNull null
                            name to hash
                        }.toMap()

                        // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                        val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null
                        MezzeType.EffectRow.simple(effectRow.effects.toSet(), rowVar, effectRow.isPerformed, hashes)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertTypeAnnotationInternal(it) },
                            abilityHash = currentAbilityDecls[constraint.abilityName]?.contentHash
                        )
                    }
                )
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotationInternal(entry.type)
                    },
                    rowVar = if (annotation.isOpen) RowVar.userOpen() else null
                )
            }
            is VariantTypeAnnotation -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotationInternal(it) }
                    },
                    rowVar = null  // Variant annotations don't have extensibility in current AST
                )
            }
            is TypeApplicationAnnotation -> {
                // Special handling for built-in collection types
                // These have dedicated MezzeType representations for optimization
                val resolvedTypeHash = currentTypeDecls[annotation.constructor]?.contentHash
                when (annotation.constructor) {
                    "List" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.ListType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) }, resolvedTypeHash)
                        }
                    }
                    "Seq" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.SeqType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) }, resolvedTypeHash)
                        }
                    }
                    "Set" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.SetType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) }, resolvedTypeHash)
                        }
                    }
                    "Map" -> {
                        if (annotation.args.size == 2) {
                            MezzeType.MapType(
                                keyType = convertTypeAnnotationInternal(annotation.args[0]),
                                valueType = convertTypeAnnotationInternal(annotation.args[1])
                            )
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) }, resolvedTypeHash)
                        }
                    }
                    // Note: Deque support will be added when DequeType is defined in MezzeType
                    else -> {
                        MezzeType.TypeApplication(
                            constructor = annotation.constructor,
                            args = annotation.args.map { convertTypeAnnotationInternal(it) },
                            typeHash = resolvedTypeHash
                        )
                    }
                }
            }
            is SeqTypeAnnotation -> {
                MezzeType.SeqType(
                    elementType = convertTypeAnnotationInternal(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                // Effect rows shouldn't appear as standalone types in well-formed programs
                // But handle gracefully
                MezzeType.VarType(TypeVar.fresh())
            }
            is QualifiedTypeAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.qualifiedName,
                    args = annotation.args.map { convertTypeAnnotationInternal(it) },
                    typeHash = currentTypeDecls[annotation.qualifiedName]?.contentHash
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

    // ============================================================================
    // ParsedDecl → v1 AST conversion helpers
    // ============================================================================

    /** Convert parsedast.Visibility to ast.Visibility (both share the same enum values). */
    private fun convertVisibility(v: com.mezze.language.parsedast.Visibility): com.mezze.language.ast.Visibility =
        when (v) {
            com.mezze.language.parsedast.Visibility.PUBLIC -> com.mezze.language.ast.Visibility.PUBLIC
            com.mezze.language.parsedast.Visibility.PRIVATE -> com.mezze.language.ast.Visibility.PRIVATE
        }

    // ============================================================================
    // v1 AST → ParsedDecl (v2) conversion helpers (for deprecated build(Program) overload)
    // ============================================================================

    private val syntheticLoc = Loc(file = "<v1-bridge>", startLine = 0, startCol = 0, endLine = 0, endCol = 0)

    /** Convert v1 TypeAnnotation to v2 ParsedTypeAnnotation (best effort). */
    private fun v1TypeAnnotationToParsed(ta: TypeAnnotation): ParsedTypeAnnotation = when (ta) {
        is IdentifierTypeAnnotation -> ParsedNamedType(ta.name, syntheticLoc)
        is TypeApplicationAnnotation -> ParsedTypeApp(ta.constructor, ta.args.map { v1TypeAnnotationToParsed(it) }, syntheticLoc)
        is RecordTypeAnnotation -> ParsedRecordType(
            ta.entries.map { ParsedRecordTypeEntry(it.name, v1TypeAnnotationToParsed(it.type), syntheticLoc) },
            ta.isOpen, syntheticLoc
        )
        is VariantTypeAnnotation -> ParsedVariantType(
            ta.constructors.map { ParsedVariantTypeCtor(it.name, it.dataType?.let { v1TypeAnnotationToParsed(it) }, syntheticLoc) },
            ta.isOpen, syntheticLoc
        )
        is FunctionTypeAnnotation -> ParsedFunctionType(
            v1TypeAnnotationToParsed(ta.paramType), v1TypeAnnotationToParsed(ta.returnType),
            ta.effects?.let { era ->
                ParsedEffectRow(
                    era.effects, era.effectAnnotations.map { ParsedEffectAnnotation(it.name, it.associatedTypes.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) },
                    era.isPolymorphic, era.isPerformed, syntheticLoc
                )
            },
            ta.abilityConstraints.map { c -> ParsedFnAbilityConstraint(c.abilityName, c.typeArgs.map { v1TypeAnnotationToParsed(it) }, c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) },
            syntheticLoc
        )
        is SeqTypeAnnotation -> ParsedSeqType(v1TypeAnnotationToParsed(ta.elementType), syntheticLoc)
        is QualifiedTypeAnnotation -> ParsedQualifiedType(ta.modulePath, ta.typeName, ta.args.map { v1TypeAnnotationToParsed(it) }, syntheticLoc)
        is EffectRowAnnotation -> ParsedNamedType("EffectRow", syntheticLoc)  // shouldn't occur as a type
    }

    /** Convert v1 TypeDeclaration to v2 ParsedTypeDecl. */
    private fun v1TypeDeclToParsed(td: TypeDeclaration): ParsedTypeDecl {
        val def: ParsedTypeDef = when (val d = td.definition) {
            is RecordDefinition -> ParsedRecordDef(
                d.fields.map { ParsedRecordFieldDef(it.name, v1TypeAnnotationToParsed(it.type), syntheticLoc) },
                d.isOpen, syntheticLoc
            )
            is VariantDefinition -> ParsedVariantDef(
                d.constructors.map { ParsedVariantCtorDef(it.name, it.dataType?.let { v1TypeAnnotationToParsed(it) }, syntheticLoc) },
                d.isOpen, syntheticLoc
            )
            is NewtypeDefinition -> ParsedNewtypeDef(d.constructor, v1TypeAnnotationToParsed(d.wrappedType), syntheticLoc)
        }
        return ParsedTypeDecl(
            name = td.name, typeParams = td.typeParams, definition = def,
            visibility = if (td.visibility == com.mezze.language.ast.Visibility.PUBLIC) ParsedVisibility.PUBLIC else ParsedVisibility.PRIVATE,
            loc = td.sourceLocation?.let { Loc(it.file ?: "<unknown>", it.line, it.column, it.line, it.column + (it.length ?: 0)) } ?: syntheticLoc,
            contentHash = td.contentHash
        )
    }

    /** Convert v1 AbilityDeclaration to v2 ParsedAbilityDecl. */
    private fun v1AbilityDeclToParsed(ad: AbilityDeclaration): ParsedAbilityDecl {
        return ParsedAbilityDecl(
            name = ad.name,
            typeParams = ad.typeParams,
            receiverTypeParam = ad.receiverTypeParam,
            associatedTypes = ad.associatedTypes,
            constraints = ad.constraints.map { ParsedAbilityConstraint(it.abilityName, it.typeParam, it.typeArgs.map { MezzeType -> ParsedNamedType(MezzeType.toString(), syntheticLoc) }, syntheticLoc) },
            dependencies = ad.dependencies.map { ParsedAbilityDep(it.abilityName, it.typeArgs.map { v1TypeAnnotationToParsed(it) }, syntheticLoc) },
            operations = ad.operations.map { op ->
                ParsedAbilityOp(
                    name = op.name,
                    paramType = v1TypeAnnotationToParsed(op.paramType),
                    returnType = v1TypeAnnotationToParsed(op.returnType),
                    effects = op.effects?.let { era ->
                        ParsedEffectRow(era.effects, era.effectAnnotations.map { ParsedEffectAnnotation(it.name, it.associatedTypes.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc) }, era.isPolymorphic, era.isPerformed, syntheticLoc)
                    },
                    abilityConstraints = op.abilityConstraints.map { c ->
                        ParsedFnAbilityConstraint(c.abilityName, c.typeArgs.map { v1TypeAnnotationToParsed(it) }, c.associatedTypeBindings.mapValues { (_, v) -> v1TypeAnnotationToParsed(v) }, syntheticLoc)
                    },
                    loc = syntheticLoc
                )
            },
            visibility = ParsedVisibility.PUBLIC,
            loc = ad.sourceLocation?.let { Loc(it.file ?: "<unknown>", it.line, it.column, it.line, it.column + (it.length ?: 0)) } ?: syntheticLoc,
            contentHash = ad.contentHash
        )
    }

    /** Convert v1 EffectDeclaration to v2 ParsedEffectDecl. */
    private fun v1EffectDeclToParsed(ed: EffectDeclaration): ParsedEffectDecl {
        return ParsedEffectDecl(
            name = ed.name,
            operations = ed.operations.map { op ->
                ParsedEffectOp(
                    name = op.name,
                    paramType = v1TypeAnnotationToParsed(op.paramType),
                    returnType = v1TypeAnnotationToParsed(op.returnType),
                    effects = op.effects,
                    loc = syntheticLoc
                )
            },
            associatedTypes = ed.associatedTypes,
            visibility = ParsedVisibility.PUBLIC,
            loc = ed.sourceLocation?.let { Loc(it.file ?: "<unknown>", it.line, it.column, it.line, it.column + (it.length ?: 0)) } ?: syntheticLoc,
            contentHash = ed.contentHash
        )
    }

    /**
     * Convert a ParsedTypeDecl to a v1 TypeDeclaration.
     */
    internal fun parsedTypeDeclToV1(decl: ParsedTypeDecl, file: String = "<converted>"): TypeDeclaration {
        val definition: TypeDefinition = when (val def = decl.definition) {
            is ParsedRecordDef -> RecordDefinition(
                fields = def.fields.map { f ->
                    RecordFieldDef(
                        name = f.name,
                        type = ParserBridge.toV1TypeAnnotation(f.type, file)
                    )
                },
                isOpen = def.isOpen
            )
            is ParsedVariantDef -> VariantDefinition(
                constructors = def.constructors.map { c ->
                    VariantConstructorDef(
                        name = c.name,
                        dataType = c.dataType?.let { ParserBridge.toV1TypeAnnotation(it, file) }
                    )
                },
                isOpen = def.isOpen
            )
            is ParsedNewtypeDef -> NewtypeDefinition(
                constructor = def.constructor,
                wrappedType = ParserBridge.toV1TypeAnnotation(def.wrappedType, file)
            )
        }
        val result = TypeDeclaration(
            name = decl.name,
            visibility = convertVisibility(decl.visibility),
            typeParams = decl.typeParams,
            definition = definition
        )
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashTypeDeclaration(result)
        return result.copy(contentHash = hash.toFullHexString())
    }

    /**
     * Convert a ParsedAbilityDecl to a v1 AbilityDeclaration.
     */
    private fun parsedAbilityDeclToV1(decl: ParsedAbilityDecl, file: String): AbilityDeclaration {
        val result = AbilityDeclaration(
            name = decl.name,
            typeParams = decl.typeParams,
            receiverTypeParam = decl.receiverTypeParam,
            associatedTypes = decl.associatedTypes,
            constraints = decl.constraints.map { c ->
                AbilityConstraint(
                    abilityName = c.abilityName,
                    typeParam = c.typeParam
                )
            },
            dependencies = decl.dependencies.map { d ->
                AbilityDependency(
                    abilityName = d.abilityName,
                    typeArgs = d.typeArgs.map { ParserBridge.toV1TypeAnnotation(it, file) }
                )
            },
            operations = decl.operations.map { op ->
                AbilityOperation(
                    name = op.name,
                    paramType = ParserBridge.toV1TypeAnnotation(op.paramType, file),
                    returnType = ParserBridge.toV1TypeAnnotation(op.returnType, file),
                    effects = op.effects?.let { row ->
                        EffectRowAnnotation(
                            effects = row.effects,
                            effectAnnotations = row.effectAnnotations.map { ea ->
                                EffectAnnotation(
                                    name = ea.name,
                                    associatedTypes = ea.associatedTypes.mapValues { (_, ann) ->
                                        ParserBridge.toV1TypeAnnotation(ann, file)
                                    }
                                )
                            },
                            isPolymorphic = row.isPolymorphic,
                            isPerformed = row.isPerformed
                        )
                    },
                    abilityConstraints = op.abilityConstraints.map { c ->
                        FunctionAbilityConstraint(
                            abilityName = c.abilityName,
                            typeArgs = c.typeArgs.map { ParserBridge.toV1TypeAnnotation(it, file) },
                            associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, ann) ->
                                ParserBridge.toV1TypeAnnotation(ann, file)
                            }
                        )
                    }
                )
            }
        )
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashAbilityDeclaration(result)
        return result.copy(contentHash = hash.toFullHexString())
    }

    /**
     * Convert a ParsedEffectDecl to a v1 EffectDeclaration.
     */
    private fun parsedEffectDeclToV1(decl: ParsedEffectDecl, file: String): EffectDeclaration {
        val result = EffectDeclaration(
            name = decl.name,
            operations = decl.operations.map { op ->
                EffectOperation(
                    name = op.name,
                    paramType = ParserBridge.toV1TypeAnnotation(op.paramType, file),
                    returnType = ParserBridge.toV1TypeAnnotation(op.returnType, file),
                    effects = op.effects
                )
            },
            associatedTypes = decl.associatedTypes
        )
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashEffectDeclaration(result)
        return result.copy(contentHash = hash.toFullHexString())
    }

    /**
     * Convert a ParsedPerformerDecl to a v1 ImplDeclaration.
     */
    private fun parsedPerformerDeclToV1(decl: ParsedPerformerDecl, file: String): ImplDeclaration {
        val effect = QualifiedRef(
            localName = decl.effect.localName,
            qualifier = decl.effect.qualifier?.let { ModulePath(it.segments) }
        )
        val result = ImplDeclaration(
            effect = effect,
            performerName = decl.performerName,
            performerParams = decl.params.map { p ->
                PerformerParameter(
                    name = p.name,
                    type = ParserBridge.toV1TypeAnnotation(p.type, file)
                )
            },
            associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
                ParserBridge.toV1TypeAnnotation(ann, file)
            },
            operations = decl.operations.map { op ->
                PerformerOperation(
                    name = op.name,
                    paramType = op.paramType?.let { ParserBridge.toV1TypeAnnotation(it, file) },
                    returnType = op.returnType?.let { ParserBridge.toV1TypeAnnotation(it, file) },
                    implementation = ParserBridge.toV1Expr(op.implementation, file),
                    effects = op.effects?.let { row ->
                        EffectRowAnnotation(
                            effects = row.effects,
                            effectAnnotations = row.effectAnnotations.map { ea ->
                                EffectAnnotation(
                                    name = ea.name,
                                    associatedTypes = ea.associatedTypes.mapValues { (_, ann) ->
                                        ParserBridge.toV1TypeAnnotation(ann, file)
                                    }
                                )
                            },
                            isPolymorphic = row.isPolymorphic,
                            isPerformed = row.isPerformed
                        )
                    }
                )
            }
        )
        // Compute content hash
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplDeclaration(result)
        return result.copy(contentHash = hash.toFullHexString())
    }

    /**
     * Convert a ParsedImplAbilityDecl to a v1 ImplAbilityDeclaration.
     */
    private fun parsedImplAbilityDeclToV1(decl: ParsedImplAbilityDecl, file: String = "<import>"): ImplAbilityDeclaration {
        val target: ImplTarget = when (val t = decl.target) {
            is ParsedImplTarget.Named -> ImplTarget.Named(
                ability = QualifiedRef(
                    localName = t.ability.localName,
                    qualifier = t.ability.qualifier?.let { ModulePath(it.segments) }
                )
            )
            is ParsedImplTarget.Inherent -> ImplTarget.Inherent
        }
        val result = ImplAbilityDeclaration(
            target = target,
            targetTypes = decl.targetTypes.map { ParserBridge.toV1TypeAnnotation(it, file) },
            abilityTypeArgs = decl.abilityTypeArgs.map { ParserBridge.toV1TypeAnnotation(it, file) },
            forallVars = decl.forallVars,
            associatedTypeBindings = decl.associatedTypeBindings.mapValues { (_, ann) ->
                ParserBridge.toV1TypeAnnotation(ann, file)
            },
            operations = decl.operations.map { op ->
                ImplAbilityOperation(
                    name = op.name,
                    paramType = op.paramType?.let { ParserBridge.toV1TypeAnnotation(it, file) },
                    returnType = op.returnType?.let { ParserBridge.toV1TypeAnnotation(it, file) },
                    implementation = ParserBridge.toV1Expr(op.implementation, file),
                    abilityConstraints = op.abilityConstraints.map { c ->
                        FunctionAbilityConstraint(
                            abilityName = c.abilityName,
                            typeArgs = c.typeArgs.map { ParserBridge.toV1TypeAnnotation(it, file) },
                            associatedTypeBindings = c.associatedTypeBindings.mapValues { (_, ann) ->
                                ParserBridge.toV1TypeAnnotation(ann, file)
                            }
                        )
                    }
                )
            },
            contentHash = null
        )
        // Compute content hash (same as Phase 0.6)
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashImplAbilityDeclaration(result)
        return result.copy(contentHash = hash.toFullHexString())
    }

    /**
     * Convert a ParsedLetDecl to a v1 LetBinding.
     */
    private fun parsedLetDeclToV1(decl: ParsedLetDecl, file: String): LetBinding {
        val result = LetBinding(
            pattern = ParserBridge.toV1Pattern(decl.pattern, file),
            visibility = convertVisibility(decl.visibility),
            typeAnnotation = decl.typeAnnotation?.let { ParserBridge.toV1TypeAnnotation(it, file) },
            expression = ParserBridge.toV1Expr(decl.value, file)
        )
        val hash = com.mezze.language.ast.hashing.ASTHasher.hashLetBinding(result)
        return result.copy(contentHash = hash.toFullHexString())
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
         * @param sourceLocation Optional source location for error messages
         * @throws TypeCheckError if a hermetic effect coexists with other effects
         */
        fun validateHermeticEffects(
            effects: Set<String>,
            effectHashes: Map<String, String> = emptyMap(),
            sourceLocation: SourceLocation? = null
        ) {
            // Resolve effect hash from row or WellKnownHashes
            fun resolveHash(name: String): String? = effectHashes[name] ?: WellKnownHashes.resolveHash(name)

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
                    sourceLocation
                )
            }
        }
    }
}
