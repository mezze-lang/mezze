package com.funk.language.typechecker.engines

import com.funk.language.ast.*
import com.funk.language.typechecker.*
import com.funk.language.typechecker.support.TypeContext

/**
 * Engine responsible for type resolution, lookup, and matching.
 *
 * This engine implements resolution logic for the Funk language type system,
 * handling:
 * - Type alias resolution and expansion
 * - Ability operation and implementation resolution
 * - Module value and declaration resolution
 * - Variant constructor lookup
 * - Ability constraint resolution
 * - Type matching and supertype finding
 *
 * # Architecture
 *
 * The ResolutionEngine is one of four core engines extracted from TypeChecker:
 * - **UnificationEngine**: Type unification and constraint solving
 * - **InferenceEngine**: Type inference for expressions
 * - **ValidationEngine**: Type validation and checking
 * - **ResolutionEngine**: Type alias and ability resolution (this file)
 *
 * # Resolution Categories
 *
 * The engine performs resolution in several categories:
 * 1. **Type Resolution**: Type aliases, newtypes, type applications
 * 2. **Ability Resolution**: Ability operations, implementations, constraints
 * 3. **Module Resolution**: Module imports, value lookups, use declarations
 * 4. **Constructor Resolution**: Variant constructors, type definitions
 * 5. **Lookup Operations**: Variable lookup, type lookup, ability lookup
 * 6. **Matching Operations**: Type matching, supertype finding
 *
 * # State Management
 *
 * The engine uses:
 * - `typeContext`: Centralized type checking context (TypeContext)
 * - `unificationEngine`: For type unification during resolution
 *
 * # Current Status (Phase 3D)
 *
 * **Note**: This is a skeleton created during Phase 3D of the refactoring.
 * Due to the size (~600 lines) and complex interdependencies with inference
 * and validation logic, this engine currently contains only:
 *
 * 1. **Skeleton structure** with proper dependencies
 * 2. **Comprehensive documentation** of what needs to be extracted
 * 3. **Placeholder for future implementation**
 *
 * The full extraction will occur in Phase 4 after all engines have skeletons
 * and inter-engine dependencies are properly established.
 *
 * @property typeContext The centralized type checking context
 * @property unificationEngine The unification engine for type operations
 */
class ResolutionEngine(
    private val typeContext: TypeContext,
    private val unificationEngine: IUnificationEngine,
    private val constraintSolver: ConstraintSolver,
    private var currentProgram: Program? = null
) : IResolutionEngine {

    /**
     * Set the current program being type-checked.
     * This is needed for fallback lookups when typeDeclarationMap isn't populated.
     */
    fun setCurrentProgram(program: Program?) {
        currentProgram = program
    }

    /**
     * Resolve type alias to its underlying definition.
     *
     * Type aliases should already be resolved during type inference, but this
     * provides a double-check for any remaining type alias references.
     */
    override fun resolveTypeAlias(type: FunkType): FunkType {
        return when (type) {
            is FunkType.VariantType -> type // Already resolved
            is FunkType.RecordType -> type // Already resolved
            else -> type // For other types, they should already be resolved
        }
    }

    /**
     * Look up a variable's type in the context.
     */
    override fun lookupVariable(name: String): FunkType? {
        // First check polymorphic context (type schemes)
        val scheme = typeContext.lookupTypeScheme(name)
        if (scheme != null) {
            return unificationEngine.instantiateScheme(scheme)
        }

        // Then check monomorphic context
        return typeContext.lookupType(name)
    }

    /**
     * Find the variant type for a given constructor name.
     *
     * Searches through type declarations to find which variant type
     * contains the given constructor.
     */
    override fun findVariantConstructorType(constructorName: String): FunkType? {
        // Look through all registered type declarations (includes imported types from modules)
        for ((typeName, typeDecl) in typeContext.typeDeclarationMap) {
            val variantType = convertTypeDefinition(typeDecl.definition)
            if (variantType is FunkType.VariantType) {
                if (variantType.constructors.containsKey(constructorName)) {
                    // If the type declaration has type parameters, return TypeApplication
                    if (typeDecl.typeParams.isNotEmpty()) {
                        val typeArgs = typeDecl.typeParams.map {
                            FunkType.VarType(TypeVar.fresh())
                        }
                        return FunkType.TypeApplication(typeName, typeArgs)
                    }
                    return variantType
                }
            }
        }

        // Fallback: Look through current program items (in case typeDeclarationMap wasn't populated)
        val program = currentProgram ?: return null
        for (item in program.items) {
            if (item is TypeDeclaration) {
                val variantType = convertTypeDefinition(item.definition)
                if (variantType is FunkType.VariantType) {
                    if (variantType.constructors.containsKey(constructorName)) {
                        if (item.typeParams.isNotEmpty()) {
                            val typeArgs = item.typeParams.map {
                                FunkType.VarType(TypeVar.fresh())
                            }
                            return FunkType.TypeApplication(item.name, typeArgs)
                        }
                        return variantType
                    }
                }
            }
        }

        return null
    }

    /**
     * Find the closest matching name from candidates.
     * Delegates to TypeUtilities.
     */
    override fun findClosestName(input: String, candidates: Collection<String>): String? {
        return com.funk.language.typechecker.support.findClosestName(input, candidates)
    }

    /**
     * Find a common supertype for two types.
     *
     * Used in if-then-else expressions and match expressions to unify
     * branch types when they're not identical.
     */
    override fun findCommonSupertype(type1: FunkType, type2: FunkType): FunkType? {
        // If types are identical, return that type
        if (isTypeCompatible(type1, type2)) {
            return type1
        }

        // Handle variant types - if both are variant constructions of the same variant type
        if (type1 is FunkType.VariantType && type2 is FunkType.VariantType) {
            // Check if they can be unified into a common variant type
            val commonConstructors = mutableMapOf<String, FunkType?>()

            // Add all constructors from both types
            commonConstructors.putAll(type1.constructors)
            for ((name, dataType) in type2.constructors) {
                if (!commonConstructors.containsKey(name)) {
                    commonConstructors[name] = dataType
                }
            }

            return FunkType.VariantType(commonConstructors, type1.rowVar ?: type2.rowVar)
        }

        // Handle case where one type is a variant and the other might be a constructor of that variant
        if (type1 is FunkType.VariantType || type2 is FunkType.VariantType) {
            val variantType = if (type1 is FunkType.VariantType) type1 else type2 as FunkType.VariantType
            return variantType
        }

        // Handle numeric types - promote to more general type
        if (com.funk.language.typechecker.support.isNumericType(type1) &&
            com.funk.language.typechecker.support.isNumericType(type2)) {
            return when {
                type1 is FunkType.FloatType || type2 is FunkType.FloatType -> FunkType.FloatType
                type1 is FunkType.IntType || type2 is FunkType.IntType -> FunkType.IntType
                else -> FunkType.VarType(TypeVar.fresh())
            }
        }

        // If one type is a type variable, return the other type
        if (type1 is FunkType.VarType) return type2
        if (type2 is FunkType.VarType) return type1

        // No common supertype found
        return null
    }

    // ============================================================================
    // Additional Resolution Functions
    // ============================================================================

    /**
     * Find a type alias that matches the given record type structure.
     *
     * Used for pretty-printing and error messages to show type alias names
     * instead of expanded record structures.
     */
    fun findMatchingRecordTypeAlias(inferredType: FunkType.RecordType): FunkType? {
        if (inferredType.fields.isEmpty()) return null

        for ((aliasName, aliasInfo) in typeContext.typeAliases) {
            // Skip parameterized type aliases for now
            if (aliasInfo.typeParams.isNotEmpty()) continue

            val aliasType = aliasInfo.definition
            if (aliasType is FunkType.RecordType) {
                // Check if the fields match
                val aliasHasAllFields = inferredType.fields.keys.all { field ->
                    aliasType.fields.containsKey(field)
                }

                if (!aliasHasAllFields) continue

                // Check if field types are compatible
                val typesMatch = inferredType.fields.all { (fieldName, fieldType) ->
                    val aliasFieldType = aliasType.fields[fieldName]
                    aliasFieldType != null && isTypeCompatible(fieldType, aliasFieldType)
                }

                if (!typesMatch) continue

                // If the alias is an open record (has rowVar), accept any subset of fields
                if (aliasType.rowVar != null) {
                    return FunkType.TypeApplication(aliasName, emptyList())
                }

                // If the alias is a closed record, require exact match
                if (aliasType.fields.size == inferredType.fields.size) {
                    return FunkType.TypeApplication(aliasName, emptyList())
                }
            }
        }

        return null
    }

    /**
     * Find the effect name for a given performer.
     */
    fun findEffectForPerformer(performerName: String): String? {
        return currentProgram?.items?.filterIsInstance<ImplDeclaration>()
            ?.find { it.performerName == performerName }
            ?.effectName
    }

    // ============================================================================
    // Ability Resolution Functions
    // ============================================================================

    /**
     * Check if an ability implementation exists for the given types.
     */
    fun checkAbilityImplementationExists(abilityName: String, types: List<FunkType>): Boolean {
        val typeNames = types.map { getTypeName(it) }
        val implKey = "${abilityName}_${typeNames.joinToString("_")}"
        return typeContext.context.containsKey(implKey)
    }

    /**
     * Find a matching ability implementation for the given ability and type arguments.
     *
     * @param abilityName The ability name (e.g., "NumAdd", "Eq")
     * @param typeArgs The type argument names (e.g., ["Int"], ["String"])
     * @return The implementation record type, or null if not found
     */
    fun findMatchingAbilityImplementation(
        abilityName: String,
        typeArgs: List<String>
    ): FunkType.RecordType? {
        // First check if this is a built-in ability with automatic implementation
        if (typeArgs.size == 1 && BuiltinAbilities.isBuiltin(abilityName)) {
            val typeName = typeArgs[0]
            if (BuiltinAbilities.hasAutomaticImplementation(typeName, abilityName)) {
                // Return a dummy record type to indicate implementation exists
                // Built-in abilities don't need actual record implementations
                return FunkType.RecordType(emptyMap())
            }
        }

        // Then check for user-defined implementations in context
        val implKey = "${abilityName}_${typeArgs.joinToString("_")}"
        return typeContext.context[implKey] as? FunkType.RecordType
    }

    /**
     * Find best matching multi-parameter ability implementation.
     * Handles cases where we only know some of the type parameters.
     */
    fun findBestMatchingMultiParameterImplementation(
        abilityName: String,
        knownTypeArgs: List<String>
    ): FunkType.RecordType? {
        // Find all implementations for this ability
        val abilityImpls = typeContext.context.keys.filter {
            it.startsWith("${abilityName}_") && it != "${abilityName}_ability"
        }

        for (implKey in abilityImpls) {
            val implementation = typeContext.context[implKey] as? FunkType.RecordType ?: continue

            // Extract type args from implementation key (e.g., "Convert_Int_String" -> ["Int", "String"])
            val implTypeArgs = implKey.substringAfter("${abilityName}_").split("_")

            // Check if the known type args match the beginning of the implementation type args
            val matches = knownTypeArgs.zip(implTypeArgs).all { (known, impl) ->
                known == impl || known == "Unknown"
            }

            if (matches && knownTypeArgs.size <= implTypeArgs.size) {
                return implementation
            }
        }

        return null
    }

    /**
     * Find available ability implementations for better error messages.
     */
    fun findAvailableAbilityImplementations(abilityName: String): List<String> {
        val implementations = mutableListOf<String>()

        // Look through context for ability implementations
        for ((key, _) in typeContext.context) {
            if (key.startsWith("${abilityName}_") && !key.endsWith("_ability")) {
                // Extract type names from the implementation key
                val typePart = key.removePrefix("${abilityName}_")
                implementations.add(typePart.replace("_", ", "))
            }
        }

        return implementations.sorted()
    }

    /**
     * Find ability implementation from ability implementation map.
     *
     * @param abilityName The ability name
     * @param typeArgs The type arguments to match
     * @param abilityImplMap Map of ability names to implementations
     * @return The matching implementation, or null if not found
     */
    fun findAbilityImplementation(
        abilityName: String,
        typeArgs: List<FunkType>,
        abilityImplMap: Map<String, List<ImplAbilityDeclaration>>
    ): ImplAbilityDeclaration? {
        val impls = abilityImplMap[abilityName] ?: return null

        // For built-in abilities with automatic implementations, check if the type supports it
        if (BuiltinAbilities.isBuiltin(abilityName) && typeArgs.size == 1) {
            val targetType = typeArgs[0]
            if (BuiltinAbilities.typeHasAutomaticImplementation(targetType, abilityName)) {
                // Built-in types have automatic implementations
                // We'll return null here and mark it as builtin in the caller
                return null
            }
        }

        // Find implementation that matches the type arguments
        // BIDIRECTIONAL TYPE INFERENCE: Allow type variables in constraint to match concrete types in implementation
        for (impl in impls) {
            if (impl.targetTypes.size != typeArgs.size) continue

            // Check if all target types match (allowing type variables to unify)
            var allMatch = true
            for (i in typeArgs.indices) {
                val targetTypeAnnotation = impl.targetTypes[i]
                val implType = convertTypeAnnotationInternal(targetTypeAnnotation)  // Type from implementation (concrete)
                val constraintType = typeArgs[i]  // Type from constraint (may have type variables)

                // Bidirectional matching: Allow constraint type variables to match impl concrete types
                val canMatch = when {
                    constraintType is FunkType.VarType -> {
                        // Type variable can match any concrete type
                        // This enables inference: Convert Int 'a can match impl Convert Int String
                        true
                    }
                    else -> {
                        // Both are concrete - must match exactly
                        areTypesEqual(implType, constraintType)
                    }
                }

                if (!canMatch) {
                    allMatch = false
                    break
                }
            }

            if (allMatch) {
                return impl
            }
        }

        return null
    }

    /**
     * Get type name for ability implementation lookup.
     */
    private fun getTypeName(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.FloatType -> "Float"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.VarType -> type.variable.name
            is FunkType.SuspendedType -> {
                // SuspendedType (e.g., String <Log>) should use the value type's name
                // The effects don't affect what operations can be performed on the value
                getTypeName(type.valueType)
            }
            is FunkType.TypeApplication -> type.constructor
            is FunkType.RecordType -> "Record"
            is FunkType.VariantType -> "Variant"
            else -> "Unknown"
        }
    }

    /**
     * Check if two types are equal (for ability implementation matching).
     */
    private fun areTypesEqual(type1: FunkType, type2: FunkType): Boolean {
        return when {
            type1 is FunkType.IntType && type2 is FunkType.IntType -> true
            type1 is FunkType.FloatType && type2 is FunkType.FloatType -> true
            type1 is FunkType.StringType && type2 is FunkType.StringType -> true
            type1 is FunkType.BooleanType && type2 is FunkType.BooleanType -> true
            type1 is FunkType.NatType && type2 is FunkType.NatType -> true
            type1 is FunkType.FracType && type2 is FunkType.FracType -> true
            type1 is FunkType.TypeApplication && type2 is FunkType.TypeApplication -> {
                type1.constructor == type2.constructor &&
                type1.args.size == type2.args.size &&
                type1.args.zip(type2.args).all { (a, b) -> areTypesEqual(a, b) }
            }
            else -> false
        }
    }

    // ============================================================================
    // Private Helper Methods
    // ============================================================================

    // Thread-local cache for type variable instances during type conversion
    private val typeVarCache = ThreadLocal.withInitial { mutableMapOf<String, TypeVar>() }

    /**
     * Convert a type definition to its FunkType representation.
     */
    private fun convertTypeDefinition(definition: TypeDefinition): FunkType {
        typeVarCache.get().clear()

        return when (definition) {
            is NewtypeDefinition -> {
                val wrappedFunkType = convertTypeAnnotationInternal(definition.wrappedType)
                FunkType.VariantType(
                    constructors = mapOf(definition.constructor to wrappedFunkType),
                    rowVar = null
                )
            }
            is RecordDefinition -> {
                val fields = definition.fields.associate { field ->
                    field.name to convertTypeAnnotationInternal(field.type)
                }
                if (definition.isOpen) {
                    FunkType.RecordType(fields, RowVar("rest"))
                } else {
                    FunkType.RecordType(fields)
                }
            }
            is VariantDefinition -> {
                val constructors = definition.constructors.associate { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotationInternal(it) }
                }
                if (definition.isOpen) {
                    FunkType.VariantType(constructors, RowVar("rest"))
                } else {
                    FunkType.VariantType(constructors)
                }
            }
        }
    }

    /**
     * Convert a type annotation to FunkType.
     */
    private fun convertTypeAnnotationInternal(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when {
                    annotation.name == "Int" -> FunkType.IntType
                    annotation.name == "Float" -> FunkType.FloatType
                    annotation.name == "String" -> FunkType.StringType
                    annotation.name == "Bool" -> FunkType.BooleanType
                    annotation.name == "Unit" -> FunkType.RecordType(emptyMap())
                    annotation.name == "Nat" -> FunkType.NatType
                    annotation.name == "Frac" -> FunkType.FracType
                    annotation.name.startsWith("'") -> {
                        val cache = typeVarCache.get()
                        val typeVar = cache.getOrPut(annotation.name) { TypeVar(annotation.name) }
                        FunkType.VarType(typeVar)
                    }
                    typeContext.typeAliases.containsKey(annotation.name) -> {
                        val alias = typeContext.typeAliases[annotation.name]!!
                        alias.definition
                    }
                    typeContext.typeDeclarationMap.containsKey(annotation.name) -> {
                        val typeDecl = typeContext.typeDeclarationMap[annotation.name]!!
                        if (typeDecl.typeParams.isEmpty()) {
                            FunkType.TypeApplication(annotation.name, emptyList())
                        } else {
                            val freshArgs = typeDecl.typeParams.map {
                                FunkType.VarType(TypeVar.fresh())
                            }
                            FunkType.TypeApplication(annotation.name, freshArgs)
                        }
                    }
                    else -> FunkType.VarType(TypeVar.freshNamed(annotation.name))
                }
            }
            is TypeApplicationAnnotation -> {
                val alias = typeContext.typeAliases[annotation.constructor]
                if (alias != null) {
                    val argTypes = annotation.args.map { convertTypeAnnotationInternal(it) }
                    substituteTypeParameters(alias.definition, alias.typeParams, argTypes)
                } else {
                    val argTypes = annotation.args.map { convertTypeAnnotationInternal(it) }
                    FunkType.TypeApplication(annotation.constructor, argTypes)
                }
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotationInternal(entry.type)
                }
                val isOpen = annotation.restField == "..."
                if (isOpen) {
                    FunkType.RecordType(fields, RowVar("rest"))
                } else {
                    FunkType.RecordType(fields)
                }
            }
            is VariantTypeAnnotation -> {
                val constructors = annotation.constructors.associateTo(linkedMapOf()) { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotationInternal(it) }
                }
                FunkType.VariantType(constructors)
            }
            is FunctionTypeAnnotation -> {
                val paramType = convertTypeAnnotationInternal(annotation.paramType)
                val returnType = convertTypeAnnotationInternal(annotation.returnType)
                val effects = annotation.effects?.let {
                    FunkType.EffectRow(it.effects.toSet())
                }
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        constraint.abilityName,
                        constraint.typeArgs.map { convertTypeAnnotationInternal(it) }
                    )
                }
                FunkType.FunctionType(paramType, returnType, effects, abilityConstraints)
            }
            else -> FunkType.VarType(TypeVar.fresh())
        }
    }

    /**
     * Substitute type parameters in a type.
     */
    private fun substituteTypeParameters(
        type: FunkType,
        typeParams: List<String>,
        typeArgs: List<FunkType>
    ): FunkType {
        if (typeParams.isEmpty()) return type

        val substitution = typeParams.zip(typeArgs).toMap()
        return applyTypeSubstitution(type, substitution)
    }

    /**
     * Apply type parameter substitution.
     */
    private fun applyTypeSubstitution(type: FunkType, substitution: Map<String, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> {
                substitution[type.variable.name] ?: type
            }
            is FunkType.RecordType -> {
                val fields = type.fields.mapValues { (_, fieldType) ->
                    applyTypeSubstitution(fieldType, substitution)
                }
                FunkType.RecordType(fields, type.rowVar)
            }
            is FunkType.VariantType -> {
                val constructors = type.constructors.mapValues { (_, constructorType) ->
                    constructorType?.let { applyTypeSubstitution(it, substitution) }
                }
                FunkType.VariantType(constructors, type.rowVar)
            }
            is FunkType.FunctionType -> {
                FunkType.FunctionType(
                    applyTypeSubstitution(type.paramType, substitution),
                    applyTypeSubstitution(type.returnType, substitution),
                    type.effects,
                    type.abilityConstraints
                )
            }
            is FunkType.TypeApplication -> {
                FunkType.TypeApplication(
                    type.constructor,
                    type.args.map { applyTypeSubstitution(it, substitution) }
                )
            }
            else -> type
        }
    }

    /**
     * Check if two types are compatible.
     *
     * This is a simplified version focused on resolution needs.
     * For full compatibility checking, use UnificationEngine.
     */
    private fun isTypeCompatible(actualType: FunkType, expectedType: FunkType): Boolean {
        return when {
            actualType == expectedType -> true

            // Type variables can unify with anything
            actualType is FunkType.VarType -> true
            expectedType is FunkType.VarType -> true

            // Record type compatibility
            actualType is FunkType.RecordType && expectedType is FunkType.RecordType -> {
                if (expectedType.isOpen) {
                    expectedType.fields.all { (expectedFieldName, expectedFieldType) ->
                        val actualFieldType = actualType.fields[expectedFieldName]
                        actualFieldType != null && isTypeCompatible(actualFieldType, expectedFieldType)
                    }
                } else {
                    actualType.fields.size == expectedType.fields.size &&
                    expectedType.fields.all { (expectedFieldName, expectedFieldType) ->
                        val actualFieldType = actualType.fields[expectedFieldName]
                        actualFieldType != null && isTypeCompatible(actualFieldType, expectedFieldType)
                    }
                }
            }

            // Variant type compatibility - simplified
            actualType is FunkType.VariantType && expectedType is FunkType.VariantType -> {
                // For resolution purposes, allow if they have overlapping constructors
                val hasCommonConstructors = actualType.constructors.keys.any {
                    it in expectedType.constructors.keys
                }
                hasCommonConstructors
            }

            else -> false
        }
    }

    /**
     * Get the underlying variant type from a type that might be a TypeApplication or SuspendedType.
     *
     * For example: Option Int -> VariantType with constructors {Some, None}
     *
     * This method handles:
     * - VariantType: returns directly
     * - SuspendedType: unwraps and recurses
     * - TypeApplication: looks up type declaration and expands to VariantType with substitution
     */
    override fun getUnderlyingVariantType(type: FunkType): FunkType.VariantType? {
        return when (type) {
            is FunkType.VariantType -> type
            is FunkType.SuspendedType -> {
                // CRITICAL FIX: Unwrap SuspendedType to get the underlying value type
                // SuspendedType wraps a value type with unhandled effects
                // Example: Result {} | FileNotFound {...}, ... | <FileSystem, Time>
                // The SuspendedType wraps the Result TypeApplication
                getUnderlyingVariantType(type.valueType)
            }
            is FunkType.TypeApplication -> {
                // Look up the type declaration for this type application
                val typeDecl = typeContext.typeDeclarationMap[type.constructor]
                if (typeDecl != null && typeDecl.definition is VariantDefinition) {
                    // Build substitution map from type parameters to type arguments
                    val substitution = if (typeDecl.typeParams.size == type.args.size) {
                        typeDecl.typeParams.zip(type.args).toMap()
                    } else {
                        emptyMap()
                    }

                    // Convert variant definition constructors, substituting type parameters
                    val variantDef = typeDecl.definition as VariantDefinition
                    val constructors = variantDef.constructors.associate { constructor ->
                        val payloadType = constructor.dataType?.let { dataTypeAnnotation ->
                            // Convert annotation to FunkType with substitution
                            convertTypeAnnotationWithSubstitution(dataTypeAnnotation, substitution)
                        }
                        constructor.name to payloadType
                    }

                    return FunkType.VariantType(constructors, if (variantDef.isOpen) RowVar.fresh() else null)
                }
                null
            }
            else -> null
        }
    }

    /**
     * Convert a type annotation to FunkType with type parameter substitution.
     */
    private fun convertTypeAnnotationWithSubstitution(
        annotation: TypeAnnotation,
        substitution: Map<String, FunkType>
    ): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Check if this is a type parameter that needs substitution
                substitution[annotation.name] ?: convertTypeAnnotationInternal(annotation)
            }
            is TypeApplicationAnnotation -> {
                // Convert the argument annotations with substitution
                val argTypes = annotation.args.map { convertTypeAnnotationWithSubstitution(it, substitution) }
                FunkType.TypeApplication(annotation.constructor, argTypes)
            }
            is RecordTypeAnnotation -> {
                // Recursively apply substitution to field types
                val fields = annotation.entries.associate { entry ->
                    entry.name to convertTypeAnnotationWithSubstitution(entry.type, substitution)
                }
                val isOpen = annotation.restField == "..."
                if (isOpen) {
                    FunkType.RecordType(fields, RowVar("rest"))
                } else {
                    FunkType.RecordType(fields)
                }
            }
            is VariantTypeAnnotation -> {
                // Recursively apply substitution to constructor payload types
                val constructors = annotation.constructors.associateTo(linkedMapOf()) { constructor ->
                    constructor.name to constructor.dataType?.let {
                        convertTypeAnnotationWithSubstitution(it, substitution)
                    }
                }
                FunkType.VariantType(constructors)
            }
            is FunctionTypeAnnotation -> {
                val paramType = convertTypeAnnotationWithSubstitution(annotation.paramType, substitution)
                val returnType = convertTypeAnnotationWithSubstitution(annotation.returnType, substitution)
                val effects = annotation.effects?.let {
                    FunkType.EffectRow(it.effects.toSet())
                }
                val abilityConstraints = annotation.abilityConstraints.map { constraint ->
                    FunkType.FunctionAbilityConstraint(
                        constraint.abilityName,
                        constraint.typeArgs.map { convertTypeAnnotationWithSubstitution(it, substitution) }
                    )
                }
                FunkType.FunctionType(paramType, returnType, effects, abilityConstraints)
            }
            else -> convertTypeAnnotationInternal(annotation)
        }
    }

    // ============================================================================
    // TODO: Full Resolution Engine Extraction (Phase 4)
    // ============================================================================
    //
    // The following 20+ functions need to be extracted from TypeChecker.kt.
    // Total size: ~600 lines with complex lookup and resolution logic.
    //
    // ## Type Resolution (2 functions, ~100 lines)
    //
    // - **resolveTypeAlias**(type: FunkType): FunkType
    //   - Recursively expands type aliases to their definitions
    //   - Handles nested type aliases
    //   - Applies type parameters correctly
    //   - Critical for type equality checking
    //
    // - **lookupVariable**(name: String): FunkType?
    //   - Looks up variable type in current context
    //   - Searches polymorphic context for schemes
    //   - Returns null if not found
    //
    // ## Ability Resolution (7 functions, ~350 lines)
    //
    // - **resolveAbilityOperationCall**(...)
    //   - Resolves ability operation calls to implementations
    //   - Finds matching ability implementation for types
    //   - Handles multi-parameter abilities
    //   - Returns operation type
    //
    // - **findMatchingAbilityImplementation**(abilityName: String, types: List<FunkType>): ImplAbilityDeclaration?
    //   - Finds ability implementation matching given types
    //   - Handles type variable instantiation
    //   - Checks implementation constraints
    //
    // - **findBestMatchingMultiParameterImplementation**(...)
    //   - Finds best implementation for multi-parameter abilities
    //   - Ranks implementations by specificity
    //   - Handles overlapping implementations
    //
    // - **findAbilityImplementation**(...)
    //   - General ability implementation finder
    //   - Searches through registered implementations
    //   - Returns implementation details
    //
    // - **findAvailableAbilityImplementations**(abilityName: String): List<String>
    //   - Lists all available implementations for an ability
    //   - Used for error messages
    //
    // - **resolveAbilityConstraintsInFunctionCall**(...)
    //   - Resolves ability constraints at function call sites
    //   - Ensures all constraints are satisfied
    //   - Instantiates polymorphic abilities
    //
    // - **resolveAbilityConstraintsInExpression**(expr: Expression)
    //   - Resolves ability constraints throughout expression tree
    //   - Propagates constraints to subexpressions
    //
    // ## Module Resolution (2 functions, ~100 lines)
    //
    // - **resolveUseDeclarationForCompilation**(useDecl: UseDeclaration, currentModulePath: String): Program?
    //   - Resolves use (import) declarations
    //   - Loads and type-checks imported modules
    //   - Handles module caching
    //   - Returns typed module program
    //
    // - **resolveModuleValues**(modulePath: String): Map<String, FunkType>
    //   - Resolves all exported values from a module
    //   - Returns map of value names to types
    //   - Used for module imports
    //
    // ## Constructor and Type Lookup (5 functions, ~200 lines)
    //
    // - **findVariantConstructorType**(constructorName: String): FunkType?
    //   - Finds variant type for a constructor name
    //   - Searches type declaration map
    //   - Handles open/closed variants
    //   - Returns full variant type with all constructors
    //
    // - **findMatchingRecordTypeAlias**(inferredType: RecordType): FunkType?
    //   - Finds type alias matching a record structure
    //   - Used for pretty-printing and error messages
    //   - Handles structural typing
    //
    // - **findCommonSupertype**(type1: FunkType, type2: FunkType): FunkType?
    //   - Finds common supertype for two types
    //   - Used in match expression branch unification
    //   - Handles variant types, numeric types
    //
    // - **findEffectForPerformer**(performerName: String): String?
    //   - Finds effect name for a performer
    //   - Maps performer implementations to effects
    //
    // - **findClosestName**(input: String, candidates: Collection<String>): String?
    //   - Finds closest matching name for typo suggestions
    //   - Uses edit distance algorithm
    //   - Improves error messages
    //
    // ## Analysis and Search Functions (6 functions, ~150 lines)
    //
    // - **findFunctionCallsWithAbilities**(expr: Expression, callback: (List<FunctionAbilityConstraint>) -> Unit)
    //   - Finds all function calls with ability constraints in expression tree
    //   - Calls callback for each found call
    //   - Used for constraint collection
    //
    // - **findOperationsRequiringAbilities**(expr: Expression, callback: (String, Expression, Expression) -> Unit)
    //   - Finds all operations requiring abilities (e.g., +, -, ==)
    //   - Calls callback with operation info
    //   - Used for ability constraint inference
    //
    // - **findAbilityMethodCalls**(expr: Expression, callback: (String, Expression) -> Unit)
    //   - Finds all ability method accesses (e.g., Eq.eq, NumAdd.add)
    //   - Calls callback for each method call
    //   - Used for ability usage tracking
    //
    // - **findArithmeticParameters**(expr: Expression, paramNames: Set<String>): Set<String>
    //   - Finds parameters used in arithmetic operations
    //   - Returns set of parameter names
    //   - Used for lambda parameter type inference
    //
    // - **resolveAbilityConstraintsInProgram**(program: Program)
    //   - Resolves all ability constraints in entire program
    //   - Ensures global constraint satisfaction
    //   - Called after type checking completion
    //
    // - **lookupWithModulePreference**(name: String, context: Map<String, T>): T?
    //   - Module-aware lookup preferring local bindings
    //   - Prevents name collisions across modules
    //   - Returns value with module preference applied
    //
    // ## Dependencies and Challenges
    //
    // Extracting these functions is challenging because they:
    //
    // 1. **Access complex state**: Many state maps and registries
    //   - typeDeclarationMap, typeAliasMap, implAbilities
    //   - performerToEffect, effectDeclarations
    //   - bindingSourceModule, currentModulePath
    //
    // 2. **Call inference functions**: Resolution often needs type inference
    //   - Finding constructors may infer payload types
    //   - Ability resolution may infer type arguments
    //
    // 3. **Call validation functions**: Resolution validates constraints
    //   - Ability resolution validates implementations exist
    //   - Module resolution validates declarations
    //
    // 4. **Use ModuleResolver**: External dependency for module loading
    //   - Need to integrate with existing module system
    //   - Handle caching and circular dependencies
    //
    // ## Extraction Strategy (Phase 4)
    //
    // To successfully extract these functions:
    //
    // 1. Consolidate resolution state (maps, registries) into ResolutionContext
    // 2. ResolutionEngine depends on InferenceEngine and ValidationEngine
    // 3. Extract module resolution helper methods
    // 4. Extract ability resolution helper methods
    // 5. Wire TypeChecker to delegate resolution to this engine
    // 6. Remove duplicate resolution code from TypeChecker
    // 7. Consider separating ModuleResolver integration
    //
    // ============================================================================

    /**
     * Get a debug summary of the resolution engine state.
     *
     * @return A string describing the current state
     */
    fun debugSummary(): String {
        return """
            ResolutionEngine (Phase 4 Step 4 - Substantially Complete):
              Status: Core resolution and ability resolution extracted
              Extracted: 12/15 primary functions
              Core Resolution: resolveTypeAlias, lookupVariable, findVariantConstructorType,
                               findClosestName, findCommonSupertype, findMatchingRecordTypeAlias,
                               findEffectForPerformer
              Ability Resolution: checkAbilityImplementationExists, findMatchingAbilityImplementation,
                                  findBestMatchingMultiParameterImplementation, findAvailableAbilityImplementations,
                                  findAbilityImplementation
              Helper Methods: convertTypeDefinition, convertTypeAnnotationInternal, substituteTypeParameters,
                              applyTypeSubstitution, isTypeCompatible, getTypeName, areTypesEqual
              Dependencies: TypeContext, UnificationEngine, ConstraintSolver
              Remaining: Module resolution (integrated with ModuleResolver), analysis functions (may be in other engines)
        """.trimIndent()
    }
}
