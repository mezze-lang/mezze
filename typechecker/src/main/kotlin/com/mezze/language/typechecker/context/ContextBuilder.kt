package com.mezze.language.typechecker.context

import com.mezze.language.ast.*
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
        val performerAddedEffects: Map<String, Set<String>> = emptyMap()
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
        importedTypes: Map<String, TypeDeclaration> = emptyMap()
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

        // Build performer to effect mapping
        // CRITICAL: Merge imported impls with local impls
        val localMapping = implDecls.associate { impl ->
            impl.performerName to impl.effectName
        }
        val importedMapping = importedImpls.mapValues { (_, impl) -> impl.effectName }
        val performerToEffectMap = importedMapping + localMapping  // Local impls override imports

        // Build context with bindings for declarations
        var ctx = parentContext

        // Add type constructors to context
        for (typeDecl in typeDecls.values) {
            ctx = addTypeConstructors(ctx, typeDecl)
        }

        // Note: lowercase 'true' and 'false' are handled by the lexer as BOOLEAN tokens,
        // which get parsed as BooleanLiteral AST nodes. No context bindings needed.

        // Add ability methods to context
        for (abilityDecl in abilityDecls.values) {
            ctx = addAbilityMethods(ctx, abilityDecl)
        }

        // Add effect operations to context
        for (effectDecl in effectDecls.values) {
            ctx = addEffectOperations(ctx, effectDecl)
        }

        // Add effect performers (impl blocks) to context
        for (implDecl in implDecls) {
            ctx = addEffectPerformer(ctx, implDecl, effectDecls)
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

        // 3. Validate ability implementations (associated types, etc.)
        // CRITICAL: Merge local and imported abilities to support qualified ability names
        // e.g., `impl codec::EncodeFormat MyType where { ... }` needs access to imported abilities
        val allAbilityDecls = abilityDecls + importedAbilities
        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateAbilityImplementation(
                impl = abilityImplDecl,
                abilityDeclarations = allAbilityDecls
            )
        }

        // 4. Validate orphan rules for ability implementations
        // An impl must be in the same module as either the ability or one of the target types
        val localAbilityNames = abilityDecls.keys
        val localTypeNames = typeDecls.keys
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

        return BuildResult(
            context = ctx,
            typeDeclarations = typeDecls,
            abilityDeclarations = abilityDecls,
            effectDeclarations = effectDecls,
            performerToEffect = performerToEffectMap,
            performerAddedEffects = performerAddedEffectsMap
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
                val functionType = MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(op.paramType),
                    returnType = convertTypeAnnotation(op.returnType)
                )
                op.name to functionType
            }
        )
        result = result.extendMono("${abilityDecl.name}_ability", abilityRecordType)

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
        result = result.extendMono("${effectDecl.name}_ability", effectRecordType)

        return result
    }

    /**
     * Add effect performer to context.
     *
     * Stores the performer's associated type bindings so they can be used
     * for type substitution during effect operation calls.
     *
     * Example: For `impl State for Counter having 'state = Int`, stores:
     * - Key: "Counter_performer"
     * - Value: RecordType with fields {'env -> String}
     */
    private fun addEffectPerformer(
        ctx: TypingContext,
        implDecl: ImplDeclaration,
        effectDecls: Map<String, EffectDeclaration>
    ): TypingContext {
        // Get the effect declaration to check associated types
        val effectDecl = effectDecls[implDecl.effectName] ?: return ctx

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

            return ctx.extendMono("${implDecl.performerName}_performer", performerType)
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
                "Content hash not computed for impl: ${implDecl.abilityName} ${implDecl.targetTypes}. " +
                "Ensure computeDeclarationHashes was called before ContextBuilder."
            )

        // Create impl info
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = implDecl.abilityName,
            targetTypes = targetTypeNames,
            sourceModule = null,  // TODO: Track source module if needed
            contentHash = implKey  // Same as the key
        )

        // Store in context by content hash
        return ctx.extendWithImpl(implKey, implInfo)
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
            is ArrayTypeAnnotation -> {
                // Array Int → "Array_Int"
                "Array_${getTypeName(annotation.elementType)}"
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
                when (annotation.name) {
                    "Int" -> MezzeType.IntType
                    "Float" -> MezzeType.FloatType
                    "String" -> MezzeType.StringType
                    "Bool" -> MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> MezzeType.NatType
                    "Frac" -> MezzeType.FracType
                    "Ordering" -> MezzeType.OrderingType
                    "Bytes" -> MezzeType.BytesType
                    "ByteBuffer" -> MezzeType.ByteBufferType
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
                        // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                        val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null
                        MezzeType.EffectRow(effectRow.effects.toSet(), rowVar, effectRow.isPerformed)
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
                    rowVar = annotation.restField?.let { RowVar(it) }
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
                    "Array" -> {
                        if (annotation.args.size == 1) {
                            MezzeType.ArrayType(convertTypeAnnotationInternal(annotation.args[0]))
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
            is ArrayTypeAnnotation -> {
                MezzeType.ArrayType(
                    elementType = convertTypeAnnotationInternal(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                // Effect rows shouldn't appear as standalone types in well-formed programs
                // But handle gracefully
                MezzeType.VarType(TypeVar.fresh())
            }
            is QualifiedTypeAnnotation -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                MezzeType.TypeApplication(
                    constructor = qualifiedName,
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
            is MezzeType.ArrayType -> collectTypeVarsFromType(type.elementType, typeVars)
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
            is MezzeType.BooleanType, is MezzeType.NatType, is MezzeType.FracType,
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
}
