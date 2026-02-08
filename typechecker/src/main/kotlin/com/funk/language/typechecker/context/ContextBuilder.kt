package com.funk.language.typechecker.context

import com.funk.language.ast.*
import com.funk.language.typechecker.validation.AbilityValidator
import com.funk.language.typechecker.validation.DuplicateDeclarationValidator

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
        val performerToEffect: Map<String, String>
    )

    /**
     * Build typing context from program.
     * This is a pure function - no side effects!
     *
     * @param program The program to build context from
     * @param parentContext Optional parent context (for nested scopes/modules)
     * @param importedImpls Optional imported effect implementations (from module imports)
     */
    fun build(
        program: Program,
        parentContext: TypingContext = TypingContext.empty,
        importedImpls: Map<String, com.funk.language.ast.ImplDeclaration> = emptyMap()
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
        for (abilityImplDecl in abilityImplDecls) {
            AbilityValidator.validateAbilityImplementation(
                impl = abilityImplDecl,
                abilityDeclarations = abilityDecls
            )
        }

        return BuildResult(
            context = ctx,
            typeDeclarations = typeDecls,
            abilityDeclarations = abilityDecls,
            effectDeclarations = effectDecls,
            performerToEffect = performerToEffectMap
        )
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
    private fun buildConstructorType(typeDecl: TypeDeclaration, constructor: VariantConstructorDef): FunkType {
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

            FunkType.VariantType(
                constructors = constructors,
                rowVar = null
            )
        } else {
            // Polymorphic variant: Result 'a 'b
            // Use cached type variables for type parameters
            FunkType.TypeApplication(
                constructor = typeDecl.name,
                args = typeDecl.typeParams.map { param ->
                    val typeVar = typeVarCache.getOrPut(param) {
                        TypeVar(param)
                    }
                    FunkType.VarType(typeVar)
                }
            )
        }

        // If constructor has data, it's a function: data -> Result
        return when (val dataType = constructor.dataType) {
            null -> resultType // No data, just the type itself
            else -> FunkType.FunctionType(
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
        val abilityRecordType = FunkType.RecordType(
            fields = abilityDecl.operations.associate { op ->
                val functionType = FunkType.FunctionType(
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
        val effectRecordType = FunkType.RecordType(
            fields = effectDecl.operations.associate { op ->
                // Convert the type annotation and strip any effects
                // Effect operations are stored as plain function types
                // IMPORTANT: Use convertTypeAnnotationInternal to avoid clearing cache between operations
                val convertedReturnType = convertTypeAnnotationInternal(op.returnType)
                val convertedParamType = convertTypeAnnotationInternal(op.paramType)
                val plainFunctionType = FunkType.FunctionType(
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

            // Convert associated type bindings to FunkTypes
            // The binding keys in implDecl should already have the ' prefix (e.g., 'state)
            // but the effectDecl.associatedTypes might not
            val bindingFields = implDecl.associatedTypeBindings.mapValues { (_, typeAnnotation) ->
                convertTypeAnnotationInternal(typeAnnotation)
            }

            // Store as RecordType with associated type names as field names
            // CRITICAL: Keys must include the variable name EXACTLY as it appears in the operation types
            // For associated type 'env, the key should be "env" (without ') to match TypeVar(name='env)
            val performerType = FunkType.RecordType(
                fields = effectDecl.associatedTypes.associate { assocType ->
                    // Remove ' prefix from assocType name to match TypeVar naming
                    val varName = assocType.removePrefix("'")
                    val value = bindingFields[assocType] ?: bindingFields["'$assocType"] ?: FunkType.VarType(TypeVar.fresh())
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

        // Create impl key: "Eq_String", "Convert_Int_String", etc.
        val implKey = "${implDecl.abilityName}_${targetTypeNames.joinToString("_")}"

        // Create impl info
        val implInfo = TypingContext.AbilityImplInfo(
            abilityName = implDecl.abilityName,
            targetTypes = targetTypeNames,
            sourceModule = null  // TODO: Track source module if needed
        )

        // Store in context
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
        }
    }

    /**
     * Convert type annotation to FunkType.
     * This is a simple conversion - no inference, no validation.
     *
     * This is the public entry point that clears the type variable cache
     * before conversion to ensure each top-level annotation gets fresh variables.
     */
    fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
        // Clear cache before each top-level conversion
        // This ensures that type variables in separate annotations don't alias
        typeVarCache.clear()
        return convertTypeAnnotationInternal(annotation)
    }

    /**
     * Internal recursive type annotation converter.
     * Uses cached type variables to ensure consistency within a single annotation.
     */
    private fun convertTypeAnnotationInternal(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Bool" -> FunkType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    "Ordering" -> FunkType.OrderingType
                    else -> {
                        // Type variable or type constructor
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            // Use cached type variable to ensure all occurrences of 'a refer to the same instance
                            val varName = annotation.name.removePrefix("'")
                            val typeVar = typeVarCache.getOrPut(varName) {
                                TypeVar(varName)
                            }
                            FunkType.VarType(typeVar)
                        } else {
                            // Type constructor with no args - treat as type application
                            FunkType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                FunkType.FunctionType(
                    paramType = convertTypeAnnotationInternal(annotation.paramType),
                    returnType = convertTypeAnnotationInternal(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        // If the effect row is polymorphic (<...> or <Effect, ...>), create a fresh row variable
                        val rowVar = if (effectRow.isPolymorphic) RowVar.fresh() else null
                        FunkType.EffectRow(effectRow.effects.toSet(), rowVar)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        FunkType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertTypeAnnotationInternal(it) }
                        )
                    }
                )
            }
            is RecordTypeAnnotation -> {
                FunkType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotationInternal(entry.type)
                    },
                    rowVar = annotation.restField?.let { RowVar(it) }
                )
            }
            is VariantTypeAnnotation -> {
                FunkType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotationInternal(it) }
                    },
                    rowVar = null  // Variant annotations don't have extensibility in current AST
                )
            }
            is TypeApplicationAnnotation -> {
                // Special handling for built-in collection types
                // These have dedicated FunkType representations for optimization
                when (annotation.constructor) {
                    "List" -> {
                        if (annotation.args.size == 1) {
                            FunkType.ListType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            FunkType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    "Array" -> {
                        if (annotation.args.size == 1) {
                            FunkType.ArrayType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            FunkType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    "Set" -> {
                        if (annotation.args.size == 1) {
                            FunkType.SetType(convertTypeAnnotationInternal(annotation.args[0]))
                        } else {
                            FunkType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    "Map" -> {
                        if (annotation.args.size == 2) {
                            FunkType.MapType(
                                keyType = convertTypeAnnotationInternal(annotation.args[0]),
                                valueType = convertTypeAnnotationInternal(annotation.args[1])
                            )
                        } else {
                            FunkType.TypeApplication(annotation.constructor, annotation.args.map { convertTypeAnnotationInternal(it) })
                        }
                    }
                    // Note: Deque support will be added when DequeType is defined in FunkType
                    else -> {
                        FunkType.TypeApplication(
                            constructor = annotation.constructor,
                            args = annotation.args.map { convertTypeAnnotationInternal(it) }
                        )
                    }
                }
            }
            is ArrayTypeAnnotation -> {
                FunkType.ArrayType(
                    elementType = convertTypeAnnotationInternal(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                // Effect rows shouldn't appear as standalone types in well-formed programs
                // But handle gracefully
                FunkType.VarType(TypeVar.fresh())
            }
        }
    }

    /**
     * Collect all type variables from a FunkType.
     * Helper for building polymorphic type schemes.
     */
    private fun collectTypeVarsFromType(type: FunkType, typeVars: MutableSet<TypeVar>) {
        when (type) {
            is FunkType.VarType -> typeVars.add(type.variable)
            is FunkType.FunctionType -> {
                collectTypeVarsFromType(type.paramType, typeVars)
                collectTypeVarsFromType(type.returnType, typeVars)
                type.abilityConstraints.forEach { constraint ->
                    constraint.typeArgs.forEach { collectTypeVarsFromType(it, typeVars) }
                }
            }
            is FunkType.RecordType -> {
                type.fields.values.forEach { collectTypeVarsFromType(it, typeVars) }
            }
            is FunkType.VariantType -> {
                type.constructors.values.forEach { it?.let { collectTypeVarsFromType(it, typeVars) } }
            }
            is FunkType.TypeApplication -> {
                type.args.forEach { collectTypeVarsFromType(it, typeVars) }
            }
            is FunkType.ArrayType -> collectTypeVarsFromType(type.elementType, typeVars)
            is FunkType.ListType -> collectTypeVarsFromType(type.elementType, typeVars)
            is FunkType.SetType -> collectTypeVarsFromType(type.elementType, typeVars)
            is FunkType.MapType -> {
                collectTypeVarsFromType(type.keyType, typeVars)
                collectTypeVarsFromType(type.valueType, typeVars)
            }
            is FunkType.SuspendedType -> collectTypeVarsFromType(type.valueType, typeVars)
            // Primitive types have no type variables
            is FunkType.IntType, is FunkType.FloatType, is FunkType.StringType,
            is FunkType.BooleanType, is FunkType.NatType, is FunkType.FracType,
            is FunkType.OrderingType, is FunkType.BytesType, is FunkType.ByteBufferType, is FunkType.EffectRow -> {}
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
        // 1. stdlib's list module to use the optimized ListType/FunkCons/FunkNil
        // 2. Users to define their own List variant type if desired
    }
}
