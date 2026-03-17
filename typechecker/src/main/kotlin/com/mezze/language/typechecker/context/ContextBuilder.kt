package com.mezze.language.typechecker.context

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeCheckError
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
     * Build result - contains all typing environments
     */
    data class BuildResult(
        val context: TypingContext,
        val typeDeclarations: Map<String, TypeDeclaration>,
        val abilityDeclarations: Map<String, AbilityDeclaration>,
        val effectDeclarations: Map<String, EffectDeclaration>,
        val performerToEffect: Map<String, String>,
        /** Maps performer names to the set of effects they ADD (not handle) */
        val performerAddedEffects: Map<String, Set<String>> = emptyMap(),
        /** Maps performer names to their ImplDeclaration (for content hash lookup) */
        val implDeclarations: Map<String, ImplDeclaration> = emptyMap()
    )

    /**
     * Build typing context from program.
     * This is a pure function - no side effects!
     *
     * @param program The program to build context from
     * @param parentContext Optional parent context (for nested scopes/modules)
     * @param importedImpls Optional imported effect implementations (from module imports)
     * @param importedAbilities Optional imported ability declarations (for validating ability impls with qualified names)
     * @param importedTypes Optional imported type declarations (for orphan rule validation)
     */
    fun build(
        program: Program,
        parentContext: TypingContext = TypingContext.empty,
        importedImpls: Map<String, com.mezze.language.ast.ImplDeclaration> = emptyMap(),
        importedAbilities: Map<String, AbilityDeclaration> = emptyMap(),
        importedTypes: Map<String, TypeDeclaration> = emptyMap(),
        importedEffects: Map<String, EffectDeclaration> = emptyMap()
    ): BuildResult {
        // VALIDATION PHASE 1: Check for duplicate declarations BEFORE building context
        // This catches errors early and provides clear error messages
        // Based on OldTypeChecker.kt:839-1143
        DuplicateDeclarationValidator.validateProgram(program)

        // Collect declarations by type
        val typeDecls = program.items.filterIsInstance<TypeDeclaration>()
            .associateBy { it.name }
            .toMutableMap()  // Make mutable so we can add built-in types

        // Add built-in variant types (Option, Result) if not already defined
        addBuiltInTypes(typeDecls)

        val abilityDecls = program.items.filterIsInstance<AbilityDeclaration>()
            .associateBy { it.name }

        val effectDecls = program.items.filterIsInstance<EffectDeclaration>()
            .associateBy { it.name }

        val implDecls = program.items.filterIsInstance<ImplDeclaration>()

        val abilityImplDecls = program.items.filterIsInstance<ImplAbilityDeclaration>()

        // Merge local and imported effect declarations for lookups
        val allEffectDecls = effectDecls + importedEffects

        // Build performer to effect mapping
        // CRITICAL: Merge imported impls with local impls
        // Resolve effect names through the effect declarations to get canonical names.
        // The impl.effectName may be qualified (e.g., "log::Log") but we want the
        // actual effect name (e.g., "Log") from the EffectDeclaration.
        // Note: We only do direct lookup (no fallback search) to respect import scoping.
        // Validation in ConstraintGenerator will catch invalid effect names.
        val localMapping = implDecls.associate { impl ->
            val effectDecl = allEffectDecls[impl.effect.localName]
            val effectName = effectDecl?.name ?: impl.effect.localName
            impl.performerName to effectName
        }
        val importedMapping = importedImpls.mapValues { (_, impl) ->
            val effectDecl = allEffectDecls[impl.effect.localName]
            effectDecl?.name ?: impl.effect.localName
        }
        val performerToEffectMap = importedMapping + localMapping  // Local impls override imports

        // Build context with bindings for declarations
        var ctx = parentContext

        // Add type constructors to context
        for (typeDecl in typeDecls.values) {
            ctx = addTypeConstructors(ctx, typeDecl)
        }

        // Note: True and False are parsed as VariantConstruction nodes (uppercase variants).

        // Add ability methods to context
        for (abilityDecl in abilityDecls.values) {
            ctx = addAbilityMethods(ctx, abilityDecl)
        }

        // Add effect operations to context
        for (effectDecl in effectDecls.values) {
            ctx = addEffectOperations(ctx, effectDecl)
        }

        // Add effect performers (impl blocks) to context
        // (allEffectDecls was defined earlier for performer-to-effect mapping)
        // Include both local AND imported impls so their PerformerTypeInfo is available
        for (implDecl in implDecls) {
            ctx = addEffectPerformer(ctx, implDecl, allEffectDecls)
        }
        for ((_, implDecl) in importedImpls) {
            ctx = addEffectPerformer(ctx, implDecl, allEffectDecls)
        }

        // Add ability implementations to context
        for (abilityImplDecl in abilityImplDecls) {
            ctx = addAbilityImpl(ctx, abilityImplDecl)
        }

        // VALIDATION PHASE: Run ability validations after context is built
        // This follows the pattern from OldTypeChecker.kt which validates after registration

        // 1. Validate name collisions between abilities, types, and effects
        AbilityValidator.validateNameCollisions(
            abilityDeclarations = abilityDecls,
            typeDeclarations = typeDecls,
            effectDeclarations = effectDecls
        )

        // 2. Validate ability dependencies (no circular dependencies)
        for (abilityDecl in abilityDecls.values) {
            AbilityValidator.validateAbilityDependencies(
                abilityDeclaration = abilityDecl,
                abilityDeclarations = abilityDecls
            )
        }

        // 3. Validate ability declarations (operation effects)
        // Operations cannot have polymorphic (<...>) or performed (<*>) effects
        for (abilityDecl in abilityDecls.values) {
            AbilityValidator.validateAbilityDeclaration(abilityDecl)
        }

        // 4. Validate ability implementations (associated types, field/method conflicts, etc.)
        // CRITICAL: Merge local and imported abilities to support qualified ability names
        // e.g., `impl codec::EncodeFormat MyType where { ... }` needs access to imported abilities
        val allAbilityDecls = abilityDecls + importedAbilities
        val allTypeDecls = typeDecls + importedTypes
        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateAbilityImplementation(
                impl = abilityImplDecl,
                abilityDeclarations = allAbilityDecls,
                typeDeclarations = allTypeDecls
            )
        }

        // 5. Validate orphan rules for ability implementations
        // An impl must be in the same module as either the ability or one of the target types
        // CRITICAL: Use only types actually declared in source, NOT injected built-ins (Bool, Option, Result).
        // Built-in types are not "owned" by user modules, so users cannot impl for them.
        val localAbilityNames = abilityDecls.keys
        val localTypeNames = program.items.filterIsInstance<TypeDeclaration>().map { it.name }.toSet()
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
        // Maps performer name to the set of effects it ADDS (from operation return type signatures)
        // e.g., FileLogger has operation with return type -> {} <FileSystem>
        //       so FileLogger adds {"FileSystem"}
        val performerAddedEffectsMap = (implDecls + importedImpls.values).associate { impl ->
            impl.performerName to extractAddedEffects(impl)
        }.filterValues { it.isNotEmpty() }

        // Build implDeclarations map: performer name -> ImplDeclaration
        // Include both local impls and imported impls
        val implDeclarationsMap = (implDecls.associateBy { it.performerName }) +
            importedImpls.mapValues { (_, impl) -> impl }

        return BuildResult(
            context = ctx,
            typeDeclarations = typeDecls,
            abilityDeclarations = abilityDecls,
            effectDeclarations = effectDecls,
            performerToEffect = performerToEffectMap,
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
     * Add variant constructors to context
     */
    private fun addTypeConstructors(ctx: TypingContext, typeDecl: TypeDeclaration): TypingContext {
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
                // Newtypes have a single constructor
                // This will be handled separately if needed
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
            val constructors = when (val def = typeDecl.definition) {
                is VariantDefinition -> def.constructors.associate { ctor ->
                    ctor.name to ctor.dataType?.let { convertTypeAnnotationInternal(it) }
                }
                is RecordDefinition,
                is NewtypeDefinition -> emptyMap()
            }

            MezzeType.VariantType(
                constructors = constructors,
                rowVar = null,
                typeName = typeDecl.name  // Track the type alias name
            )
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
                        typeArgs = constraint.typeArgs.map { convertTypeAnnotation(it) }
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
                if (constructorName == "Err" || constructorName == "Ok") {
                    // This is a Result type - create Result 'val 'error
                    // CRITICAL: Use the SAME type variable from associatedTypes
                    // This ensures constraint propagation works - when the operation
                    // call constrains 'error to String, the suspension type also sees it
                    val errorType = associatedTypes["error"] ?: MezzeType.VarType(TypeVar.fresh())
                    val valType = MezzeType.VarType(TypeVar.fresh())

                    MezzeType.TypeApplication(
                        constructor = "Result",
                        args = listOf(valType, errorType)
                    )
                } else if (constructorName == "None") {
                    // This is Option None - create Option 'a
                    MezzeType.TypeApplication(
                        constructor = "Option",
                        args = listOf(MezzeType.VarType(TypeVar.fresh()))
                    )
                } else if (constructorName == "Some") {
                    // This is Option Some - create Option with the payload type
                    val innerType = payloadType ?: MezzeType.VarType(TypeVar.fresh())
                    MezzeType.TypeApplication(
                        constructor = "Option",
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
    private fun addAbilityImpl(ctx: TypingContext, implDecl: ImplAbilityDeclaration): TypingContext {
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

        // Create impl info
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = implDecl.abilityName!!,  // safe: not inherent
            targetTypes = targetTypeNames,
            targetTypeAnnotations = implDecl.targetTypes,  // Store original annotations for structural matching
            associatedTypeBindings = implDecl.associatedTypeBindings,  // Store associated type bindings like 'item = Int
            sourceModule = null,  // TODO: Track source module if needed
            contentHash = implKey  // Same as the key
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
                // Default: empty record (no args besides receiver)
                MezzeType.RecordType(fields = emptyMap(), rowVar = null)
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
                // Option Int → "Option_Int"
                val argNames = annotation.args.map { getTypeName(it) }
                if (argNames.isEmpty()) {
                    annotation.constructor
                } else {
                    "${annotation.constructor}_${argNames.joinToString("_")}"
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
                            MezzeType.TypeApplication(annotation.name, emptyList())
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

                        // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                        val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null
                        MezzeType.EffectRow.simple(effectRow.effects.toSet(), rowVar, effectRow.isPerformed)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertTypeAnnotationInternal(it) }
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
                when (annotation.constructor) {
                    "List" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.ListType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    "Seq" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.SeqType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    "Set" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.SetType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    "Map" -> {
                        if (annotation.args.size == 2) {
                            MezzeType.MapType(
                                keyType = convertTypeAnnotationInternal(annotation.args[0]),
                                valueType = convertTypeAnnotationInternal(annotation.args[1])
                            )
                        } else {
                            MezzeType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    // Note: Deque support will be added when DequeType is defined in MezzeType
                    else -> {
                        MezzeType.TypeApplication(
                            constructor = annotation.constructor,
                            args = annotation.args.map { convertTypeAnnotationInternal(it) }
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
                    args = annotation.args.map { convertTypeAnnotationInternal(it) }
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
    private fun addBuiltInTypes(typeDecls: MutableMap<String, TypeDeclaration>) {
        // Register Bool type if not already defined
        // Bool is internally represented as: type Bool = | True, False |
        if (!typeDecls.containsKey("Bool")) {
            val boolDef = VariantDefinition(
                listOf(
                    VariantConstructorDef("True", null),   // No payload
                    VariantConstructorDef("False", null)   // No payload
                ),
                false  // closed variant
            )
            typeDecls["Bool"] = TypeDeclaration(
                name = "Bool",
                typeParams = emptyList(),  // No type parameters
                definition = boolDef
            )
        }

        // Register Option type if not already defined
        if (!typeDecls.containsKey("Option")) {
            val somePayload = RecordTypeAnnotation(
                listOf(RecordTypeEntry("val", IdentifierTypeAnnotation("a")))
            )
            val optionDef = VariantDefinition(
                listOf(
                    VariantConstructorDef("Some", somePayload),
                    VariantConstructorDef("None", null)
                ),
                false  // closed variant
            )
            typeDecls["Option"] = TypeDeclaration(
                name = "Option",
                typeParams = listOf("a"),
                definition = optionDef
            )
        }

        // Register Result type if not already defined
        if (!typeDecls.containsKey("Result")) {
            val okPayload = RecordTypeAnnotation(
                listOf(RecordTypeEntry("val", IdentifierTypeAnnotation("a")))
            )
            val errPayload = RecordTypeAnnotation(
                listOf(RecordTypeEntry("error", IdentifierTypeAnnotation("e")))
            )
            val resultDef = VariantDefinition(
                listOf(
                    VariantConstructorDef("Ok", okPayload),
                    VariantConstructorDef("Err", errPayload)
                ),
                false  // closed variant
            )
            typeDecls["Result"] = TypeDeclaration(
                name = "Result",
                typeParams = listOf("a", "e"),
                definition = resultDef
            )
        }

        // Register List type if not already defined
        // Note: Built-in List type is NOT added here.
        // Instead, Cons/Nil constructors are handled specially in the constraint solver
        // when no user-defined type declaration exists for them.
        // This allows:
        // 1. stdlib's list module to use the optimized ListType/MezzeCons/MezzeNil
        // 2. Users to define their own List variant type if desired
    }

    companion object {
        /**
         * Names of hermetic effects that cannot coexist with other effects.
         * Hermetic effects are isolated for data-race freedom.
         */
        private val HERMETIC_EFFECTS = setOf("Mut")

        /**
         * Validate that an effect set doesn't violate hermetic effect rules.
         *
         * Hermetic effects (like Mut) cannot coexist with other effects
         * to ensure data-race freedom.
         *
         * @param effects The set of effect names
         * @param sourceLocation Optional source location for error messages
         * @throws TypeCheckError if a hermetic effect coexists with other effects
         */
        fun validateHermeticEffects(effects: Set<String>, sourceLocation: SourceLocation? = null) {
            val hermeticEffectsInSet = effects.intersect(HERMETIC_EFFECTS)
            if (hermeticEffectsInSet.isNotEmpty() && effects.size > 1) {
                val hermeticEffect = hermeticEffectsInSet.first()
                val otherEffects = (effects - HERMETIC_EFFECTS).joinToString(", ")
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
