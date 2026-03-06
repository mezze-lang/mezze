package com.mezze.language.typechecker.application

import com.mezze.language.ast.*
import com.mezze.language.ast.hashing.ASTHasher
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.codec.CodecGenerator
import com.mezze.language.typechecker.hashing.ContentHashComputer
import com.mezze.language.typechecker.scope.detectScopedEffectMembers
import com.mezze.language.typechecker.solving.Substitution

/**
 * Substitution applier - applies type substitution to AST.
 *
 * This is PHASE 5 of the new architecture:
 * - PHASE 1: Build context (ContextBuilder)
 * - PHASE 2: Generate constraints (ConstraintGenerator)
 * - PHASE 3: Solve constraints (ConstraintSolver)
 * - PHASE 4: Validate types (TypeValidator)
 * - PHASE 5: Apply substitution (SubstitutionApplier) ← WE ARE HERE
 *
 * This phase takes the final substitution from constraint solving
 * and updates all Expression nodes in the AST with their inferred types.
 *
 * Design:
 * - Pure functions (creates new AST, doesn't mutate!)
 * - Exhaustive pattern matching on all Expression types
 * - Applies substitution recursively to all sub-expressions
 * - Updates inferredType field on each expression
 * - Sets resolution field on VariantConstruction nodes for proper interpreter handling
 */
class SubstitutionApplier(
    private val substitution: Substitution,
    private val constraintResults: Map<String, com.mezze.language.typechecker.constraints.ConstraintResult> = emptyMap(),
    private val performerToEffect: Map<String, String> = emptyMap(),
    private val mustBeOpenRowVars: Set<RowVar> = emptySet(),
    private val mustBeOpenBindings: Set<String> = emptySet(),
    private val mustBeOpenTypes: Set<TypeVar> = emptySet(),
    private val abilityImpls: Map<String, com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap(),
    private val abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap(),
    private val effectDeclarations: Map<String, EffectDeclaration> = emptyMap(),
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
    // TCO: Source locations of self-recursive tail calls
    private val tailCallLocations: Set<SourceLocation> = emptySet(),
    // ImplDeclarations for populating resolvedHandlers with content hashes
    private val implDeclarations: Map<String, ImplDeclaration> = emptyMap(),
    // Content hashes for identifier resolution
    private val identifierContentHashes: Map<String, String> = emptyMap()
) {
    // Compute variants that must stay open by resolving type vars with substitution
    // This handles the case where per-binding solving created different row vars than global solving
    private val effectiveMustBeOpenRowVars: Set<RowVar> by lazy {
        val result = mustBeOpenRowVars.toMutableSet()
        for (typeVar in mustBeOpenTypes) {
            val resolved = substitution.apply(MezzeType.VarType(typeVar))
            if (resolved is MezzeType.VariantType) {
                resolved.rowVar?.let { result.add(it) }
            }
        }
        result
    }

    // Map binding names to the constructor sets that should stay open in that binding
    private val mustBeOpenBindingConstructors: Map<String, Set<Set<String>>> by lazy {
        // TODO: We need the MustBeOpen constraints to build this properly
        // For now, we use a simpler approach
        emptyMap<String, Set<Set<String>>>()
    }

    // Pre-built index of variant constructor names for O(1) lookup
    // Built once at initialization time from typeDeclarations
    private val variantConstructorIndex: Set<String> by lazy {
        typeDeclarations.values.asSequence()
            .mapNotNull { it.definition as? VariantDefinition }
            .flatMap { it.constructors.asSequence() }
            .map { it.name }
            .toSet()
    }

    // Mutable context for tracking identifier types during traversal
    // This mimics OldTypeChecker's context map (OldTypeChecker.kt:1900-1910)
    // Maps identifier names to their types
    private val identifierContext = mutableMapOf<String, MezzeType>()

    // Track polymorphic function types for let-polymorphism support
    // Maps identifier name to the polymorphic function type (with ability constraints)
    // This allows resolving abilities when calling polymorphic functions
    private val polymorphicFunctionTypes = mutableMapOf<String, MezzeType.FunctionType>()

    // CodecGenerator for auto-deriving Encode/Decode implementations
    private val codecGenerator = CodecGenerator()

    // Track generated codec implementations that need to be added to the program
    private val generatedCodecImpls = mutableListOf<ImplAbilityDeclaration>()

    // Mutable version of abilityImpls that includes generated codec impls
    // This allows nested Encode/Decode calls to find impls generated in the same pass
    private val mutableAbilityImpls = abilityImpls.toMutableMap()

    // Secondary index: abilityName -> list of content hashes for O(1) lookup
    // Mirrors TypingContext.abilityImplsByName pattern
    private val mutableAbilityImplsByName: MutableMap<String, MutableList<String>> = run {
        val index = mutableMapOf<String, MutableList<String>>()
        for ((hash, info) in abilityImpls) {
            index.getOrPut(info.abilityName) { mutableListOf() }.add(hash)
        }
        index
    }

    /**
     * Register a generated codec impl so it's available for nested calls.
     * This is called when we generate an Encode/Decode impl, so subsequent
     * Encode.encode/Decode.decode calls can resolve to it.
     *
     * IMPORTANT: We compute the content hash IMMEDIATELY so that nested calls
     * can reference this impl by hash. This is critical for content-addressable
     * semantics - we don't want to use mangled names.
     */
    private fun registerGeneratedCodecImpl(impl: ImplAbilityDeclaration): ImplAbilityDeclaration {
        // Compute content hash immediately
        val hash = ASTHasher.hashImplAbilityDeclaration(impl)
        val implWithHash = impl.copy(contentHash = hash.toFullHexString())

        generatedCodecImpls.add(implWithHash)

        // Also add to mutableAbilityImpls so nested calls can find it
        // Use content hash as the key (same key format as TypingContext)
        val abilityName = impl.abilityName
        val targetTypes = impl.targetTypes.map { com.mezze.language.typechecker.hashing.ContentHashComputer.typeAnnotationToString(it) }
        val contentHashKey = hash.toFullHexString()

        mutableAbilityImpls[contentHashKey] = com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo(
            abilityName = abilityName,
            targetTypes = targetTypes,
            targetTypeAnnotations = impl.targetTypes,  // Store original annotations for structural matching
            associatedTypeBindings = impl.associatedTypeBindings,  // Store associated type bindings
            sourceModule = null,
            contentHash = contentHashKey
        )

        // Update secondary index for O(1) lookup by ability name
        mutableAbilityImplsByName.getOrPut(abilityName) { mutableListOf() }.add(contentHashKey)

        return implWithHash
    }

    /**
     * Resolve what an uppercase identifier represents.
     * The parser creates VariantConstruction for ALL uppercase identifiers.
     * This function determines if it's actually an ability, effect, handler, or variant.
     *
     * @param name The constructor/identifier name to resolve
     * @return The appropriate ConstructorResolution
     */
    private fun resolveConstructor(name: String): ConstructorResolution {
        // Check built-in abilities first
        if (BuiltInAbilities.isBuiltInAbility(name)) {
            return ConstructorResolution.AbilityReference(name)
        }

        // Check user-defined abilities
        if (abilityDeclarations.containsKey(name)) {
            return ConstructorResolution.AbilityReference(name)
        }

        // Check effects
        if (effectDeclarations.containsKey(name)) {
            return ConstructorResolution.EffectReference(name)
        }

        // Check handlers (performers) - reverse lookup from performerToEffect
        val effectName = performerToEffect[name]
        if (effectName != null) {
            return ConstructorResolution.HandlerReference(name, effectName)
        }

        // Check variant constructors using O(1) index lookup
        if (variantConstructorIndex.contains(name)) {
            return ConstructorResolution.VariantConstructor
        }

        // Cannot determine - leave unresolved
        return ConstructorResolution.Unresolved
    }

    /**
     * Apply substitution to a program.
     *
     * Returns a new Program with all types resolved.
     */
    fun applyToProgram(program: Program): Program {
        // Process items sequentially, updating identifierContext after each let binding
        // This allows later bindings to reference earlier bindings' types (including SuspendedType)
        val updatedItems = mutableListOf<ProgramItem>()

        for (item in program.items) {
            val updatedItem = applyToProgramItem(item)
            updatedItems.add(updatedItem)

            // After processing a let binding, add its final type to identifierContext
            // This allows subsequent bindings to see the correct type (including SuspendedType)
            if (updatedItem is LetBinding) {
                val bindingName = (updatedItem.pattern as? VariablePattern)?.name
                    ?: updatedItem.pattern.extractBindings().firstOrNull()
                updatedItem.inferredType?.let { type ->
                    if (bindingName != null) {
                        // Check if this is a polymorphic function with ability constraints
                        val isPolymorphicWithAbilities = when (type) {
                            is MezzeType.FunctionType -> type.abilityConstraints.isNotEmpty()
                            else -> false
                        }
                        if (isPolymorphicWithAbilities && type is MezzeType.FunctionType) {
                            // Track polymorphic functions for ability resolution at call sites
                            polymorphicFunctionTypes[bindingName] = type
                        } else {
                            identifierContext[bindingName] = type
                        }
                    }
                }
            }
        }

        // Add any auto-generated codec implementations to the BEGINNING of the program
        // This ensures they're registered BEFORE any code that uses them
        // IMPORTANT: Process generated impls through applyToImplAbilityDeclaration so their
        // internal expressions get ability resolution (resolvedAbilities populated)
        val processedCodecImpls = generatedCodecImpls.map { applyToImplAbilityDeclaration(it) }
        val allItems = processedCodecImpls + updatedItems
        return Program(items = allItems)
    }

    /**
     * Get the list of auto-generated codec implementations.
     * These need to be registered in the runtime context.
     */
    fun getGeneratedCodecImpls(): List<ImplAbilityDeclaration> = generatedCodecImpls.toList()

    /**
     * Apply substitution to a program item.
     *
     * Exhaustive when on ProgramItem sealed interface.
     */
    private fun applyToProgramItem(item: ProgramItem): ProgramItem {
        return when (item) {
            is LetBinding -> applyToLetBinding(item)
            is TypeDeclaration -> applyToTypeDeclaration(item)
            is EffectDeclaration -> applyToEffectDeclaration(item)
            is ImplDeclaration -> applyToImplDeclaration(item)
            is AbilityDeclaration -> applyToAbilityDeclaration(item)
            is ImplAbilityDeclaration -> applyToImplAbilityDeclaration(item)
            is TopLevelExpression -> applyToTopLevelExpression(item)
            is UseDeclaration -> item  // No types to update
            is PubUseDeclaration -> item  // No types to update
        }
    }

    /**
     * Apply substitution to a let binding.
     */
    private fun applyToLetBinding(binding: LetBinding): LetBinding {
        // CRITICAL FIX: Get the binding's type FIRST, then set it on the expression before processing
        // This allows nested expressions (especially lambdas) to know their types
        val bindingName = try {
            (binding.pattern as? VariablePattern)?.name ?: binding.pattern.extractBindings().firstOrNull()
        } catch (e: Exception) {
            null
        }

        val constraintResult = bindingName?.let { constraintResults[it] }

        val baseInferredType = if (binding.inferredType == null) {
            constraintResult?.type
        } else {
            binding.inferredType
        }

        // Apply substitution to get the final type
        val substitutedType = baseInferredType?.let { substitution.apply(it) }


        // Get the effects from constraint results
        val effects = constraintResult?.effects ?: emptySet()

        // Wrap type in SuspendedType if there are unhandled effects
        // Only wrap if:
        // 1. There are effects
        // 2. The type is not already a SuspendedType
        // 3. The type is not a FunctionType (functions declare their effects in their signature)
        val preExpressionType = if (effects.isNotEmpty() && substitutedType != null) {
            when (substitutedType) {
                is MezzeType.SuspendedType -> substitutedType // Already suspended
                is MezzeType.FunctionType -> substitutedType // Functions have effects in signature
                else -> MezzeType.SuspendedType(
                    valueType = substitutedType,
                    effects = MezzeType.EffectRow.simple(effects)
                )
            }
        } else {
            substitutedType
        }

        // CRITICAL: Set inferredType on the expression BEFORE processing it
        // This allows nested expressions to know their types:
        // - Lambda: extract parameter types and add them to identifierContext
        // - FunctionCall: know the return type for Decode and other abilities
        val expr = binding.expression
        val expressionWithType = if (preExpressionType != null) {
            when (expr) {
                is LambdaExpression -> expr.copy(inferredType = preExpressionType)
                is FunctionCall -> expr.copy(inferredType = preExpressionType)
                else -> expr
            }
        } else {
            expr
        }

        val updatedExpression = applyToExpression(expressionWithType)

        // CRITICAL: For perform expressions, use the expression's computed type
        // because perform expressions compute effect removal which isn't in constraint results
        val finalType = if (updatedExpression is PerformExpression && updatedExpression.inferredType != null) {
            updatedExpression.inferredType
        } else {
            preExpressionType
        }

        // Close generated row variables in the final type
        // This matches old TypeChecker behavior where records inferred from field access are closed by default
        // Pass the binding name so we can check if this binding should have open variants
        val closedType = finalType?.let { closeGeneratedRowVars(it, bindingName) }

        // Create TypeScheme for polymorphic bindings (those with type variables)
        // This is needed for pretty printing and monomorphization
        val typeScheme = closedType?.let { type ->
            // Extract ability constraints from constraint result
            val abilityConstraints = constraintResult?.abilityConstraints?.map { constraint ->
                AbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeParam = constraint.typeVar.name,
                    typeArgs = constraint.typeArgs  // Preserve type args for multi-parameter abilities
                )
            } ?: emptyList()

            TypeScheme.generalize(type, abilityConstraints)
        }

        return binding.copy(
            expression = updatedExpression,
            inferredType = closedType,
            typeScheme = typeScheme
        )
    }

    /**
     * Apply substitution to a type declaration.
     *
     * CRITICAL: If inferredType is null, compute it from the definition.
     * This matches OldTypeChecker.kt:1569 which does `item.copy(inferredType = definitionType)`
     */
    private fun applyToTypeDeclaration(typeDecl: TypeDeclaration): TypeDeclaration {
        val computedType = typeDecl.inferredType ?: convertTypeDefinition(typeDecl.definition)
        val updatedInferredType = substitution.apply(computedType)

        return typeDecl.copy(
            inferredType = updatedInferredType
        )
    }

    /**
     * Convert a TypeDefinition to a MezzeType.
     *
     * This matches OldTypeChecker.kt:10808-10852.
     * CRITICAL: Preserves isOpen flag and rowVar for variants.
     */
    private fun convertTypeDefinition(definition: TypeDefinition): MezzeType {
        return when (definition) {
            is NewtypeDefinition -> {
                // Convert the wrapped type
                val wrappedMezzeType = convertTypeAnnotation(definition.wrappedType)
                // Newtypes are represented as single-constructor variants internally
                MezzeType.VariantType(
                    constructors = mapOf(definition.constructor to wrappedMezzeType),
                    rowVar = null  // Newtypes are always closed
                )
            }
            is RecordDefinition -> {
                val fields = definition.fields.associate { field ->
                    field.name to convertTypeAnnotation(field.type)
                }
                // CRITICAL: Preserve isOpen flag from RecordDefinition
                if (definition.isOpen) {
                    MezzeType.RecordType(fields, RowVar("rest"))
                } else {
                    MezzeType.RecordType(fields)
                }
            }
            is VariantDefinition -> {
                val constructors = definition.constructors.associate { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
                }
                // CRITICAL: Preserve isOpen flag from VariantDefinition
                if (definition.isOpen) {
                    // Create open variant with a row variable
                    MezzeType.VariantType(constructors, RowVar("rest"))
                } else {
                    // Create closed variant
                    MezzeType.VariantType(constructors)
                }
            }
        }
    }

    /**
     * Apply substitution to an effect declaration.
     *
     * Creates a RecordType from the effect's operations, matching old typechecker behavior.
     */
    private fun applyToEffectDeclaration(effectDecl: EffectDeclaration): EffectDeclaration {
        // Create a RecordType from the effect operations
        // This matches the old typechecker behavior (OldTypeChecker.kt:1594-1635)
        val effectRecordType = MezzeType.RecordType(
            fields = effectDecl.operations.associate { op ->
                // Effect operations include effect rows, so we need to convert them properly
                val paramType = convertTypeAnnotation(op.paramType)
                val returnType = convertTypeAnnotation(op.returnType)

                // Create function type with effect row if effects are present
                val functionType = if (op.effects.isNotEmpty()) {
                    MezzeType.FunctionType(
                        paramType = paramType,
                        returnType = returnType,
                        effects = MezzeType.EffectRow(op.effects.toSet())
                    )
                } else {
                    MezzeType.FunctionType(
                        paramType = paramType,
                        returnType = returnType
                    )
                }
                op.name to functionType
            }
        )

        // Apply substitution to the record type
        val updatedInferredType = substitution.apply(effectRecordType)

        return effectDecl.copy(inferredType = updatedInferredType)
    }

    /**
     * Apply substitution to an ability declaration.
     *
     * Creates a RecordType from the ability's operations, matching old typechecker behavior.
     */
    private fun applyToAbilityDeclaration(abilityDecl: AbilityDeclaration): AbilityDeclaration {
        // Create a RecordType from the ability operations
        // This matches the old typechecker behavior (OldTypeChecker.kt:1718-1724)
        val abilityRecordType = MezzeType.RecordType(
            fields = abilityDecl.operations.associate { op ->
                val functionType = MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(op.paramType),
                    returnType = convertTypeAnnotation(op.returnType)
                )
                op.name to functionType
            }
        )

        // Apply substitution to the record type
        val updatedInferredType = substitution.apply(abilityRecordType)

        return abilityDecl.copy(inferredType = updatedInferredType)
    }

    /**
     * Apply substitution to an effect implementation (performer).
     *
     * Creates a RecordType from the impl's operations, matching old typechecker behavior.
     */
    private fun applyToImplDeclaration(implDecl: ImplDeclaration): ImplDeclaration {
        val updatedOperations = implDecl.operations.map { op ->
            val updatedImpl = applyToExpression(op.implementation)
            op.copy(implementation = updatedImpl)
        }

        // Create a RecordType from the performer operations
        // This matches the old typechecker behavior (OldTypeChecker.kt:1682-1696)
        val performerRecordType = MezzeType.RecordType(
            fields = implDecl.operations.associate { op ->
                // Use operation's type annotation if provided
                val paramType = op.paramType
                val returnType = op.returnType
                val functionType = if (paramType != null && returnType != null) {
                    MezzeType.FunctionType(
                        paramType = convertTypeAnnotation(paramType),
                        returnType = convertTypeAnnotation(returnType)
                    )
                } else {
                    // If no type annotation, use fresh type variable
                    MezzeType.VarType(TypeVar.fresh())
                }
                op.name to functionType
            }
        )

        // Apply substitution to the record type
        val updatedInferredType = substitution.apply(performerRecordType)

        return implDecl.copy(
            operations = updatedOperations,
            inferredType = updatedInferredType
        )
    }

    /**
     * Apply substitution to an ability implementation.
     *
     * Creates a RecordType from the impl's operations, matching old typechecker behavior.
     */
    private fun applyToImplAbilityDeclaration(implDecl: ImplAbilityDeclaration): ImplAbilityDeclaration {
        val updatedOperations = implDecl.operations.map { op ->
            val updatedImpl = applyToExpression(op.implementation)
            op.copy(implementation = updatedImpl)
        }

        // Create a RecordType from the impl operations (old typechecker pattern)
        // This matches OldTypeChecker.kt:1744-1766
        val implRecordType = MezzeType.RecordType(
            fields = implDecl.operations.associate { op ->
                // Use the operation's type annotation if available
                val paramType = op.paramType
                val returnType = op.returnType
                val functionType = if (paramType != null && returnType != null) {
                    MezzeType.FunctionType(
                        paramType = convertTypeAnnotation(paramType),
                        returnType = convertTypeAnnotation(returnType)
                    )
                } else {
                    // If no type annotation, use fresh type variable
                    MezzeType.VarType(TypeVar.fresh())
                }
                op.name to functionType
            }
        )

        // Apply substitution to the record type
        val updatedInferredType = substitution.apply(implRecordType)

        return implDecl.copy(
            operations = updatedOperations,
            inferredType = updatedInferredType
        )
    }

    /**
     * Apply substitution to a top-level expression.
     */
    private fun applyToTopLevelExpression(topLevel: TopLevelExpression): TopLevelExpression {
        val updatedExpression = applyToExpression(topLevel.expression)

        return topLevel.copy(
            expression = updatedExpression
        )
    }

    /**
     * Apply substitution to an expression.
     *
     * Exhaustive when on all Expression types.
     * Returns a new Expression with updated inferredType.
     */
    fun applyToExpression(expr: Expression): Expression {
        return when (expr) {
            // Literals
            is IntLiteral -> applyToIntLiteral(expr)
            is NatLiteral -> applyToNatLiteral(expr)
            is FloatLiteral -> applyToFloatLiteral(expr)
            is BooleanLiteral -> applyToBooleanLiteral(expr)
            is FractionLiteral -> applyToFractionLiteral(expr)
            is StringLiteral -> applyToStringLiteral(expr)
            is ArrayLiteral -> applyToArrayLiteral(expr)

            // Variables
            is Identifier -> applyToIdentifier(expr)
            is PlaceholderExpression -> applyToPlaceholder(expr)

            // Arrays
            is ArrayAccess -> applyToArrayAccess(expr)

            // Records
            is RecordLiteral -> applyToRecordLiteral(expr)
            is MemberAccess -> applyToMemberAccess(expr)
            is RecordUpdate -> applyToRecordUpdate(expr)
            is RecordUpdateExpression -> applyToRecordUpdateExpression(expr)

            // Variants
            is VariantConstruction -> applyToVariantConstruction(expr)

            // Functions
            is LambdaExpression -> applyToLambda(expr)
            is FunctionCall -> applyToFunctionCall(expr)

            // Control flow
            is IfExpression -> applyToIf(expr)
            is MatchExpression -> applyToMatch(expr)

            // Blocks
            is DoExpression -> applyToDo(expr)

            // Effects
            is PerformExpression -> applyToPerform(expr)
            is EffectCallExpression -> applyToEffectCall(expr)

            // State machines
            is StateMachineExpression -> applyToStateMachine(expr)
        }
    }

    // ========================================================================
    // LITERALS
    // ========================================================================

    private fun applyToIntLiteral(expr: IntLiteral): IntLiteral {
        // CRITICAL FIX: Compute type based on expression structure, don't rely on inferredType being set
        // Int literals always have type Int
        val updatedType = substitution.apply(MezzeType.IntType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToNatLiteral(expr: NatLiteral): NatLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(MezzeType.NatType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToFloatLiteral(expr: FloatLiteral): FloatLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(MezzeType.FloatType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToBooleanLiteral(expr: BooleanLiteral): BooleanLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        // Bool is a variant type: | True, False |
        val boolType = MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
        val updatedType = substitution.apply(boolType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToFractionLiteral(expr: FractionLiteral): FractionLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(MezzeType.FracType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToStringLiteral(expr: StringLiteral): StringLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(MezzeType.StringType)
        val updatedParts = expr.parts?.map { part ->
            when (part) {
                is StringPart -> part
                is ExpressionPart -> {
                    val updatedExpr = applyToExpression(part.expression)
                    ExpressionPart(updatedExpr)
                }
            }
        }
        return expr.copy(parts = updatedParts, inferredType = updatedType)
    }

    private fun applyToArrayLiteral(expr: ArrayLiteral): ArrayLiteral {
        val updatedElements = expr.elements.map { element ->
            when (element) {
                is ArrayRegularElement -> ArrayRegularElement(applyToExpression(element.expression))
                is ArraySpreadElement -> ArraySpreadElement(applyToExpression(element.expression))
            }
        }
        // CRITICAL FIX: Compute type based on expression structure
        // Array type is Array<elementType>, where elementType is the type of the first element
        val updatedType = if (updatedElements.isNotEmpty()) {
            val firstElement = updatedElements.first()
            val elementType = when (firstElement) {
                is ArrayRegularElement -> firstElement.expression.inferredType ?: MezzeType.VarType(TypeVar.fresh())
                is ArraySpreadElement -> {
                    // For spread element, extract element type from the array type
                    val arrayType = firstElement.expression.inferredType
                    if (arrayType is MezzeType.ArrayType) arrayType.elementType
                    else MezzeType.VarType(TypeVar.fresh())
                }
            }
            substitution.apply(MezzeType.ArrayType(elementType))
        } else {
            // Empty array: use existing type if available, otherwise fresh type variable
            expr.inferredType?.let { substitution.apply(it) }
                ?: MezzeType.ArrayType(MezzeType.VarType(TypeVar.fresh()))
        }
        return expr.copy(elements = updatedElements, inferredType = updatedType)
    }

    // ========================================================================
    // VARIABLES
    // ========================================================================

    private fun applyToIdentifier(expr: Identifier): Identifier {
        // CRITICAL FIX: Look up identifier type from context, like OldTypeChecker does
        // This allows identifiers in nested expressions (like lambda bodies) to get their types
        val contextType = identifierContext[expr.name]
        val updatedType = if (contextType != null) {
            substitution.apply(contextType)
        } else {
            expr.inferredType?.let { substitution.apply(it) }
        }
        // Look up content hash for identifier (from imports or local declarations)
        val contentHash = identifierContentHashes[expr.name]
        return expr.copy(
            inferredType = updatedType,
            resolvedContentHash = contentHash
        )
    }

    private fun applyToPlaceholder(expr: PlaceholderExpression): PlaceholderExpression {
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(inferredType = updatedType)
    }

    // ========================================================================
    // ARRAYS
    // ========================================================================

    private fun applyToArrayAccess(expr: ArrayAccess): ArrayAccess {
        val updatedArray = applyToExpression(expr.array)
        val updatedIndex = applyToExpression(expr.index)
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(
            array = updatedArray,
            index = updatedIndex,
            inferredType = updatedType
        )
    }

    // ========================================================================
    // RECORDS
    // ========================================================================

    private fun applyToRecordLiteral(expr: RecordLiteral): RecordLiteral {
        val updatedEntries = expr.entries.map { entry ->
            when (entry) {
                is RecordFieldEntry -> {
                    val updatedValue = entry.value?.let { applyToExpression(it) }
                    RecordFieldEntry(entry.name, updatedValue)
                }
                is RecordSpreadEntry -> {
                    val updatedExpr = applyToExpression(entry.expression)
                    RecordSpreadEntry(updatedExpr)
                }
            }
        }

        // Compute the record type from its entries if not already set
        // This is needed for ability resolution where argument types must be known
        val computedType = expr.inferredType?.let { substitution.apply(it) } ?: run {
            // Build record type from entries
            val fields = mutableMapOf<String, MezzeType>()
            for (entry in updatedEntries) {
                when (entry) {
                    is RecordFieldEntry -> {
                        val entryType = entry.value?.inferredType ?: MezzeType.VarType(TypeVar.fresh())
                        fields[entry.name] = entryType
                    }
                    is RecordSpreadEntry -> {
                        // Spread entries add fields from the spread expression's type
                        val spreadType = entry.expression.inferredType
                        if (spreadType is MezzeType.RecordType) {
                            fields.putAll(spreadType.fields)
                        }
                    }
                }
            }
            if (fields.isNotEmpty()) MezzeType.RecordType(fields) else null
        }
        return expr.copy(entries = updatedEntries, inferredType = computedType)
    }

    private fun applyToMemberAccess(expr: MemberAccess): MemberAccess {
        val updatedReceiver = applyToExpression(expr.receiver)

        // First try to use existing inferred type
        var updatedType = expr.inferredType?.let { substitution.apply(it) }

        // If no type yet and this is an ability method access, compute it
        if (updatedType == null) {
            val abilityName = when (val receiver = expr.receiver) {
                is Identifier -> receiver.name
                is VariantConstruction -> receiver.constructor
                else -> null
            }
            val methodName = expr.member

            // Look up the ability declaration to get the method type
            // NOTE: For Encode/Decode, the type created here may have fresh type variables
            // that won't be resolved by the substitution. But for local bindings in do blocks,
            // we still need to compute the method type so applyToFunctionCall can extract
            // the return type structure (even if inner type vars aren't fully resolved).
            if (abilityName != null) {
                val abilityDecl = abilityDeclarations[abilityName]
                if (abilityDecl != null) {
                    val operation = abilityDecl.operations.find { it.name == methodName }
                    if (operation != null) {
                        // Clear the type variable cache before converting method type
                        // This ensures each method type conversion gets fresh but consistent type variables
                        typeVarCache.clear()
                        // Convert the param and return type annotations to MezzeType and apply substitution
                        val paramType = typeAnnotationToMezzeType(operation.paramType)
                        val returnType = typeAnnotationToMezzeType(operation.returnType)
                        val methodType = MezzeType.FunctionType(paramType, returnType)
                        updatedType = substitution.apply(methodType)
                    }
                }
            }
        }

        return expr.copy(receiver = updatedReceiver, inferredType = updatedType)
    }

    /**
     * Convert a TypeAnnotation to MezzeType.
     * Used for looking up ability method types.
     */
    // Track type variables created during typeAnnotationToMezzeType to ensure consistency
    // within a single method type conversion
    private val typeVarCache = mutableMapOf<String, TypeVar>()

    private fun typeAnnotationToMezzeType(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> MezzeType.IntType
                    "Float" -> MezzeType.FloatType
                    "String" -> MezzeType.StringType
                    "Bool" -> MezzeType.BoolType
                    "Nat" -> MezzeType.NatType
                    "Frac" -> MezzeType.FracType
                    "Char" -> MezzeType.CharType
                    "Bytes" -> MezzeType.BytesType
                    else -> {
                        // Could be a type variable or type alias
                        if (annotation.name.startsWith("'") || annotation.name.all { it.isLowerCase() }) {
                            // Type variable - reuse from cache or create a fresh one
                            // This ensures the same type parameter name maps to the same type variable
                            // within a single method type conversion
                            val normalizedName = annotation.name.removePrefix("'")
                            val typeVar = typeVarCache.getOrPut(normalizedName) {
                                TypeVar.fresh()
                            }
                            MezzeType.VarType(typeVar)
                        } else {
                            // Named type - use TypeApplication
                            MezzeType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                val paramType = typeAnnotationToMezzeType(annotation.paramType)
                val returnType = typeAnnotationToMezzeType(annotation.returnType)
                MezzeType.FunctionType(paramType, returnType)
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to typeAnnotationToMezzeType(entry.type)
                }
                MezzeType.RecordType(fields)
            }
            is TypeApplicationAnnotation -> {
                val args = annotation.args.map { typeAnnotationToMezzeType(it) }
                when (annotation.constructor) {
                    "List" -> if (args.isNotEmpty()) MezzeType.ListType(args.first()) else MezzeType.TypeApplication("List", args)
                    "Set" -> if (args.isNotEmpty()) MezzeType.SetType(args.first()) else MezzeType.TypeApplication("Set", args)
                    "Map" -> if (args.size >= 2) MezzeType.MapType(args[0], args[1]) else MezzeType.TypeApplication("Map", args)
                    "Option" -> MezzeType.TypeApplication("Option", args)
                    "Result" -> MezzeType.TypeApplication("Result", args)
                    else -> MezzeType.TypeApplication(annotation.constructor, args)
                }
            }
            is VariantTypeAnnotation -> {
                val constructors = annotation.constructors.associate { ctor: VariantTypeConstructor ->
                    val payloadType = ctor.dataType?.let { typeAnnotationToMezzeType(it) } ?: MezzeType.RecordType(emptyMap())
                    ctor.name to payloadType
                }
                MezzeType.VariantType(constructors)
            }
            else -> MezzeType.VarType(TypeVar.fresh()) // Fallback for unknown annotations
        }
    }

    private fun applyToRecordUpdate(expr: RecordUpdate): RecordUpdate {
        val updatedRecord = applyToExpression(expr.record)
        val updatedUpdates = expr.updates.map { update ->
            val updatedValue = update.value?.let { applyToExpression(it) }
            RecordEntry(update.name, updatedValue)
        }
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(
            record = updatedRecord,
            updates = updatedUpdates,
            inferredType = updatedType
        )
    }

    private fun applyToRecordUpdateExpression(expr: RecordUpdateExpression): RecordUpdateExpression {
        val updatedBase = applyToExpression(expr.baseRecord)
        val updatedUpdates = expr.updates.map { update ->
            val updatedValue = applyToExpression(update.value)
            RecordUpdateEntry(update.fieldPath, updatedValue)
        }
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(baseRecord = updatedBase, updates = updatedUpdates, inferredType = updatedType)
    }

    // ========================================================================
    // VARIANTS
    // ========================================================================

    private fun applyToVariantConstruction(expr: VariantConstruction): VariantConstruction {
        val updatedValue = expr.value?.let { applyToExpression(it) }

        // CRITICAL FIX: Compute type for variant constructors when inferredType is null
        // This is similar to how we handle BooleanLiteral in applyToBooleanLiteral
        // Without this, True/False constructors would have null inferredType which breaks codec generation
        val existingType = expr.inferredType
        val updatedType = if (existingType != null) {
            substitution.apply(existingType)
        } else {
            // Try to compute the type based on the constructor name
            when (expr.constructor) {
                "True", "False" -> {
                    // Bool constructor: | True, False |
                    MezzeType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                }
                "None" -> {
                    // Option None: Option 'a = | Some { val: 'a }, None |
                    // We create a type application for Option with a fresh type var
                    MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar.fresh())))
                }
                "Some" -> {
                    // Option Some: Option 'a = | Some { val: 'a }, None |
                    // The type parameter comes from the payload
                    val innerType = updatedValue?.inferredType?.let {
                        when (it) {
                            is MezzeType.RecordType -> it.fields["val"] ?: MezzeType.VarType(TypeVar.fresh())
                            else -> MezzeType.VarType(TypeVar.fresh())
                        }
                    } ?: MezzeType.VarType(TypeVar.fresh())
                    MezzeType.TypeApplication("Option", listOf(innerType))
                }
                "Nil" -> {
                    // List Nil constructor
                    MezzeType.ListType(MezzeType.VarType(TypeVar.fresh()))
                }
                "Cons" -> {
                    // List Cons constructor - extract element type from payload
                    val elemType = updatedValue?.inferredType?.let {
                        when (it) {
                            is MezzeType.RecordType -> it.fields["head"] ?: MezzeType.VarType(TypeVar.fresh())
                            else -> MezzeType.VarType(TypeVar.fresh())
                        }
                    } ?: MezzeType.VarType(TypeVar.fresh())
                    MezzeType.ListType(elemType)
                }
                else -> {
                    // For other constructors, create an open variant with just this constructor
                    val payloadType = updatedValue?.inferredType
                    MezzeType.VariantType(
                        constructors = mapOf(expr.constructor to payloadType),
                        rowVar = RowVar.fresh()
                    )
                }
            }
        }

        // CRITICAL: Resolve what this uppercase identifier actually represents
        // The parser creates VariantConstruction for ALL uppercase identifiers
        // (abilities like Inspect, effects like Log, handlers like Console, variants like Some)
        // Set the resolution so the interpreter knows how to handle it
        val resolution = resolveConstructor(expr.constructor)

        return expr.copy(value = updatedValue, inferredType = updatedType, resolution = resolution)
    }

    // ========================================================================
    // FUNCTIONS
    // ========================================================================

    private fun applyToLambda(expr: LambdaExpression): LambdaExpression {
        // CRITICAL FIX: Add lambda parameters to context before processing body
        // This matches OldTypeChecker.kt:1898-1923
        val originalContext = identifierContext.toMap()

        // Extract parameter types from the lambda's inferred type
        val existingType = expr.inferredType
        if (existingType is MezzeType.FunctionType) {
            val paramType = existingType.paramType
            if (paramType is MezzeType.RecordType) {
                // Add lambda parameters to context
                paramType.fields.forEach { (name, type) ->
                    identifierContext[name] = type
                }
            }
        }

        val updatedBody = applyToExpression(expr.body)

        // Restore original context
        identifierContext.clear()
        identifierContext.putAll(originalContext)

        val updatedType = if (existingType != null) {
            substitution.apply(existingType)
        } else {
            // Fallback: compute function type based on expression structure
            // Function type is paramType -> returnType, where returnType comes from the body
            val bodyType = updatedBody.inferredType ?: MezzeType.VarType(TypeVar.fresh())

            // Extract parameter type from parameters
            val paramType: MezzeType = when (val params = expr.parameters) {
                is UntypedParameters -> {
                    // Create record type from parameter names
                    val fields = params.names.associateWith { MezzeType.VarType(TypeVar.fresh()) }
                    MezzeType.RecordType(fields = fields, rowVar = null)
                }
                is TypedParameters -> {
                    // Use annotated type
                    convertTypeAnnotation(params.recordType)
                }
            }

            // CRITICAL: Include ability constraints from the lambda expression
            // The parser attaches ability constraints to LambdaExpression when parsing
            // `type with Ability = lambda` syntax. We must preserve these constraints!
            val abilityConstraints = if (expr.abilityConstraints.isNotEmpty()) {
                expr.abilityConstraints.map { constraint ->
                    MezzeType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { typeAnn ->
                            convertTypeAnnotation(typeAnn)
                        }
                    )
                }
            } else {
                emptyList()
            }

            val functionType = MezzeType.FunctionType(
                paramType = paramType,
                returnType = bodyType,
                abilityConstraints = abilityConstraints
            )
            substitution.apply(functionType)
        }
        return expr.copy(body = updatedBody, inferredType = updatedType)
    }

    private fun applyToFunctionCall(expr: FunctionCall): FunctionCall {
        val updatedFunction = applyToExpression(expr.function)
        val updatedArguments = applyToExpression(expr.arguments)

        // Compute the return type from the function's type if not already set on the expression
        // This is necessary because the constraint generator creates a fresh type variable
        // for the return type, but doesn't set it on the AST node
        val updatedType = expr.inferredType?.let { substitution.apply(it) } ?: run {
            // Extract return type from the function's type
            val funcType = updatedFunction.inferredType?.let { substitution.apply(it) }
            when (funcType) {
                is MezzeType.FunctionType -> {
                    // CRITICAL: If the function has effects (including performed marker),
                    // wrap the return type in SuspendedType
                    // Calling an effectful function produces a suspended computation
                    val returnType = funcType.returnType
                    val effects = funcType.effects
                    // Check for: concrete effects, row variable (polymorphic), OR performed marker <*>
                    if (effects != null && (effects.effects.isNotEmpty() || effects.rowVar != null || effects.performed)) {
                        MezzeType.SuspendedType(returnType, effects)
                    } else {
                        returnType
                    }
                }
                else -> null
            }
        }

        // CRITICAL: Resolve ability constraints for this function call
        // This matches OldTypeChecker.kt:16483-16581 (resolveAbilitiesForFunctionCall)
        var resolvedAbilities = resolveAbilitiesForCall(updatedFunction)

        // LET-POLYMORPHISM: If function is a polymorphic identifier with ability constraints,
        // resolve abilities based on the argument types
        if (resolvedAbilities.isEmpty() && updatedFunction is Identifier) {
            val funcName = updatedFunction.name
            val polymorphicType = polymorphicFunctionTypes[funcName]
            if (polymorphicType != null && polymorphicType.abilityConstraints.isNotEmpty()) {
                resolvedAbilities = resolvePolymorphicAbilities(polymorphicType, updatedArguments)
            }
        }

        // Also check if this is a direct ability method call (e.g., Show.show {val = 42})
        // In this case, we need to resolve based on the argument types, not the function type constraints
        if (resolvedAbilities.isEmpty() && updatedFunction is MemberAccess) {
            resolvedAbilities = resolveDirectAbilityMethodCall(updatedFunction, updatedArguments, updatedType)
        }

        // TCO: Check if this call is a self-recursive tail call
        val isTailCall = expr.sourceLocation != null && tailCallLocations.contains(expr.sourceLocation)

        // Get content hash for cross-module function references
        // This allows the interpreter to lookup by hash instead of string
        val functionContentHash = when (val func = expr.function) {
            is Identifier -> identifierContentHashes[func.name]
            else -> null
        }

        return expr.copy(
            function = updatedFunction,
            arguments = updatedArguments,
            inferredType = updatedType,
            resolvedAbilities = resolvedAbilities,
            isTailCall = isTailCall,
            resolvedFunctionContentHash = functionContentHash
        )
    }

    /**
     * Resolve a direct ability method call like Show.show {val = 42}.
     * The receiver (Show) is an ability name, and we need to find the implementation
     * based on the argument types.
     *
     * @param returnType The inferred return type of the call (for Decode, contains the target type)
     */
    private fun resolveDirectAbilityMethodCall(
        memberAccess: MemberAccess,
        arguments: Expression,
        returnType: MezzeType?
    ): Map<String, ResolvedAbility> {
        val resolvedAbilities = mutableMapOf<String, ResolvedAbility>()

        // Get the ability name from the receiver
        val abilityName = when (val receiver = memberAccess.receiver) {
            is Identifier -> receiver.name
            is VariantConstruction -> receiver.constructor
            else -> return resolvedAbilities
        }


        // Check if this is actually an ability (not a regular variable)
        // Look for implementations of this ability, or check if it's auto-derivable
        val hasImpl = mutableAbilityImpls.values.any { it.abilityName == abilityName }
        val isCodecAbility = abilityName in setOf(
            "Encode", "Decode",
            "EncodeFormat", "DecodeFormat",
            "StreamEncodeFormat", "StreamDecodeFormat"
        )
        if (!hasImpl && !BuiltinAbilities.isBuiltin(abilityName) && !isCodecAbility) {
            return resolvedAbilities
        }

        // Get the argument type to determine which implementation to use
        val argType = arguments.inferredType
        if (argType !is MezzeType.RecordType) return resolvedAbilities

        // For Encode/Decode abilities, the type we need is in the "val" field specifically
        // For other abilities like Show, use the first field's type
        val primaryType = if (abilityName == "Encode" || abilityName == "Decode") {
            // Encode/Decode use {val: 'a, config: 'cfg} where 'a is the data type
            val valFieldType = argType.fields["val"]
            if (valFieldType != null) {
                substitution.apply(valFieldType)
            } else {
                // Fall back to first field if "val" not found
                argType.fields.values.firstOrNull()?.let { substitution.apply(it) }
            }
        } else {
            // For other abilities, use the first field's type
            argType.fields.values.firstOrNull()?.let { substitution.apply(it) }
        }

        if (primaryType == null) return resolvedAbilities

        // For Encode/Decode, we need to search with both format type AND data type
        // The format type comes from the EncodeFormat/DecodeFormat impl in scope
        var impl: com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo? = null

        if (abilityName == "Encode" || abilityName == "Decode") {
            // Find the format type from EncodeFormat/DecodeFormat impl
            val formatAbility = if (abilityName == "Encode") "EncodeFormat" else "DecodeFormat"
            val formatImpl = mutableAbilityImpls.values.find { it.abilityName == formatAbility }
            val formatTypeName = formatImpl?.targetTypes?.firstOrNull()

            if (formatTypeName != null) {
                val formatType = MezzeType.TypeApplication(formatTypeName, emptyList())
                // Search with both format type and data type
                impl = findAbilityImplementation(abilityName, listOf(formatType, primaryType))
            }
        } else {
            // First try exact match (for single-parameter abilities)
            impl = findAbilityImplementation(abilityName, listOf(primaryType))

            // If no exact match, try partial match for multi-parameter abilities
            // e.g., for Convert 'a 'b, find impl where first type matches
            if (impl == null) {
                impl = findAbilityImplementationByFirstType(abilityName, primaryType)
            }
        }

        // If no match found by argument types, try to find the only implementation
        // This handles format-parameterized abilities like EncodeFormat 'repr
        // where the type parameter doesn't come from the arguments
        if (impl == null) {
            // O(1) lookup via secondary index instead of O(N) filter
            val implHashes = mutableAbilityImplsByName[abilityName] ?: emptyList()
            if (implHashes.size == 1) {
                impl = mutableAbilityImpls[implHashes.first()]
            }
        }

        if (impl != null) {
            // Found explicit implementation
            // Reconstruct the full type args from the impl's target types
            val implTypeArgs = impl.targetTypes.map { typeName ->
                when (typeName) {
                    "Int" -> MezzeType.IntType
                    "Float" -> MezzeType.FloatType
                    "String" -> MezzeType.StringType
                    "Bool" -> MezzeType.BoolType
                    "Nat" -> MezzeType.NatType
                    "Frac" -> MezzeType.FracType
                    "Char" -> MezzeType.CharType
                    "Bytes" -> MezzeType.BytesType
                    "ByteBuffer" -> MezzeType.ByteBufferType
                    "Ordering" -> MezzeType.OrderingType
                    else -> MezzeType.TypeApplication(typeName, emptyList())
                }
            }
            // Content hash is now computed early (Phase 0.5) so it's available here
            val contentHash = impl.contentHash
                ?: throw IllegalStateException("Content hash not computed for impl: ${impl.abilityName} ${impl.targetTypes}")
            // Use content hash as key for O(1) lookup and collision-free identity
            resolvedAbilities[contentHash] = ResolvedAbility(
                abilityName = abilityName,
                typeArgs = implTypeArgs,
                isBuiltin = false,
                implementationHash = contentHash
            )
        } else if (BuiltinAbilities.isBuiltin(abilityName)) {
            // Check for built-in ability with automatic implementation
            if (BuiltinAbilities.typeHasAutomaticImplementation(primaryType, abilityName)) {
                // For builtins, use a deterministic synthetic key based on ability + type
                val builtinKey = "builtin:$abilityName:${getTypeNameForAbility(primaryType)}"
                resolvedAbilities[builtinKey] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = listOf(primaryType),
                    isBuiltin = true
                    // No implementationHash for builtins - they're handled specially by interpreter
                )
            }
        } else if (abilityName == "Encode" || abilityName == "Decode") {
            // Auto-derive Encode/Decode for serializable types
            // First, find the format type from the EncodeFormat/DecodeFormat impl in scope
            val formatAbility = if (abilityName == "Encode") "EncodeFormat" else "DecodeFormat"
            val formatImpl = mutableAbilityImpls.values.find { it.abilityName == formatAbility }

            // For Encode: primaryType is the data type from the argument (correct)
            // For Decode: primaryType is the format type from the argument (wrong!)
            //             We need the return type, which is Result 'a DecodeError
            //             The 'a is what we need to decode to
            val dataType = if (abilityName == "Decode") {
                // BIDIRECTIONAL INFERENCE: For Decode without type annotation, the target type
                // is inferred from usage (e.g., field access like val.name). This creates
                // HasField constraints that bind type variables to record types in the substitution.
                //
                // Strategy 1: Try to extract from returnType if it's concrete
                val extractedFromReturnType = extractDecodeTargetType(returnType)

                // Strategy 2: If returnType has unbound VarTypes, look through substitution
                // for record types that could be the decode target.
                val extractedType = if (extractedFromReturnType != null && !containsVarType(extractedFromReturnType)) {
                    extractedFromReturnType
                } else {
                    // Look for record types in the substitution that match the expected pattern
                    // The decode target type is bound via HasField constraints from field access
                    findDecodeTargetFromSubstitution() ?: extractedFromReturnType
                }

                extractedType ?: primaryType
            } else {
                primaryType // For Encode, use the argument type
            }

            // For built-in parametric types (Option, Result), keep them as TypeApplication
            // so the codec generator can use the specialized encoders
            val resolvedPrimaryType = if (dataType is MezzeType.TypeApplication &&
                (dataType.constructor == "Option" || dataType.constructor == "Result")) {
                dataType // Don't resolve Option/Result - use specialized encoder
            } else {
                resolveTypeAlias(dataType)
            }

            // BIDIRECTIONAL INFERENCE: Close open records for codec generation
            // When types are inferred from field access (e.g., val.name), we get open records
            // with row variables like {name: String, ...}. For codec generation, we close
            // these to {name: String} so they're treated as concrete serializable types.
            val closedType = closeOpenRecords(resolvedPrimaryType)


            if (formatImpl != null && codecGenerator.isSerializable(closedType) && !containsVarType(closedType)) {
                // Get the format type (e.g., "Json")
                val formatTypeName = formatImpl.targetTypes.firstOrNull() ?: return resolvedAbilities
                val formatType = MezzeType.TypeApplication(formatTypeName, emptyList())

                // Recursively generate implementations for all nested types
                // The record/variant encode calls Encode.encode for each field/variant
                // Filter out VarTypes since we can't generate code for unknown types
                // Use closedType to ensure nested open records are also closed
                val allTypesToGenerate = collectNestedTypes(closedType)
                    .filter { !containsVarType(it) }
                for (nestedType in allTypesToGenerate) {
                    val nestedImpl = if (abilityName == "Encode") {
                        codecGenerator.generateEncodeImpl(formatType, nestedType)
                    } else {
                        codecGenerator.generateDecodeImpl(formatType, nestedType)
                    }
                    registerGeneratedCodecImpl(nestedImpl)
                }

                // Generate the main implementation using the closed type
                val generatedImpl = if (abilityName == "Encode") {
                    codecGenerator.generateEncodeImpl(formatType, closedType)
                } else {
                    codecGenerator.generateDecodeImpl(formatType, closedType)
                }

                // Track the generated impl for later registration
                // The returned impl has its content hash computed
                val implWithHash = registerGeneratedCodecImpl(generatedImpl)

                // Create the resolved ability reference using content hash as key
                val implHash = implWithHash.contentHash
                    ?: throw IllegalStateException("Content hash not computed for generated codec impl: $abilityName")

                resolvedAbilities[implHash] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = listOf(formatType, closedType),
                    isBuiltin = false,
                    implementationHash = implHash
                )
            }
        }

        return resolvedAbilities
    }

    /**
     * Resolve ability constraints for a function call.
     * This is critical for the interpreter to know which ability implementations to use.
     *
     * Matches OldTypeChecker.kt:16483-16581
     */
    private fun resolveAbilitiesForCall(function: Expression): Map<String, ResolvedAbility> {
        val resolvedAbilities = mutableMapOf<String, ResolvedAbility>()

        // Get the function's type to access ability constraints
        val funcType = function.inferredType
        if (funcType !is MezzeType.FunctionType) return resolvedAbilities

        // Process each ability constraint
        for (constraint in funcType.abilityConstraints) {
            val abilityName = constraint.abilityName
            val typeArgs = constraint.typeArgs.map { substitution.apply(it) }

            // Try to find an explicit implementation
            val impl = findAbilityImplementation(abilityName, typeArgs)

            if (impl != null) {
                // Found explicit implementation - use content hash as key
                val contentHash = impl.contentHash
                    ?: throw IllegalStateException("Content hash not computed for impl: ${impl.abilityName} ${impl.targetTypes}")
                resolvedAbilities[contentHash] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = typeArgs,
                    isBuiltin = false,
                    implementationHash = contentHash
                )
            } else if (BuiltinAbilities.isBuiltin(abilityName) && typeArgs.size == 1) {
                // Check if it's a built-in ability with automatic implementation
                val targetType = typeArgs[0]
                if (BuiltinAbilities.typeHasAutomaticImplementation(targetType, abilityName)) {
                    // For builtins, use a deterministic synthetic key
                    val builtinKey = "builtin:$abilityName:${getTypeNameForAbility(targetType)}"
                    resolvedAbilities[builtinKey] = ResolvedAbility(
                        abilityName = abilityName,
                        typeArgs = typeArgs,
                        isBuiltin = true
                        // No implementationHash for builtins
                    )
                }
            }
        }

        return resolvedAbilities
    }

    /**
     * Resolve abilities for a polymorphic function call.
     *
     * Given a polymorphic function type like `{x: 'a} -> String with Encoder 'a`
     * and arguments like `{x: Int}`, we:
     * 1. Unify the parameter type with the argument type to find type variable bindings
     * 2. Apply those bindings to the ability constraints
     * 3. Look up implementations for the resolved ability constraints
     */
    private fun resolvePolymorphicAbilities(
        polymorphicType: MezzeType.FunctionType,
        arguments: Expression
    ): Map<String, ResolvedAbility> {
        val resolvedAbilities = mutableMapOf<String, ResolvedAbility>()

        // Get the argument type
        val argType = arguments.inferredType ?: return resolvedAbilities

        // Build a mapping from type variables to concrete types
        // by matching the parameter type with the argument type
        val typeVarBindings = mutableMapOf<TypeVar, MezzeType>()

        fun collectBindings(paramType: MezzeType, argType: MezzeType) {
            when {
                paramType is MezzeType.VarType -> {
                    // Bind type variable to argument type
                    typeVarBindings[paramType.variable] = argType
                }
                paramType is MezzeType.RecordType && argType is MezzeType.RecordType -> {
                    // Recursively collect bindings from record fields
                    for ((fieldName, fieldType) in paramType.fields) {
                        val argFieldType = argType.fields[fieldName]
                        if (argFieldType != null) {
                            collectBindings(fieldType, argFieldType)
                        }
                    }
                }
                // Add more cases as needed (arrays, variants, etc.)
            }
        }

        collectBindings(polymorphicType.paramType, argType)

        // Now resolve each ability constraint using the type variable bindings
        for (constraint in polymorphicType.abilityConstraints) {
            val abilityName = constraint.abilityName
            // Apply type variable bindings to the constraint's type args
            val typeArgs = constraint.typeArgs.map { typeArg ->
                when (typeArg) {
                    is MezzeType.VarType -> typeVarBindings[typeArg.variable] ?: typeArg
                    else -> typeArg
                }
            }

            // Try to find an explicit implementation
            val impl = findAbilityImplementation(abilityName, typeArgs)

            if (impl != null) {
                // Found explicit implementation - use content hash as key
                val contentHash = impl.contentHash
                    ?: throw IllegalStateException("Content hash not computed for impl: ${impl.abilityName} ${impl.targetTypes}")
                resolvedAbilities[contentHash] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = typeArgs,
                    isBuiltin = false,
                    implementationHash = contentHash
                )
            } else if (BuiltinAbilities.isBuiltin(abilityName) && typeArgs.size == 1) {
                // Check if it's a built-in ability with automatic implementation
                val targetType = typeArgs[0]
                if (BuiltinAbilities.typeHasAutomaticImplementation(targetType, abilityName)) {
                    // For builtins, use a deterministic synthetic key
                    val builtinKey = "builtin:$abilityName:${getTypeNameForAbility(targetType)}"
                    resolvedAbilities[builtinKey] = ResolvedAbility(
                        abilityName = abilityName,
                        typeArgs = typeArgs,
                        isBuiltin = true
                    )
                }
            }
        }

        return resolvedAbilities
    }

    /**
     * Find an ability implementation matching the given ability name and type arguments.
     * Uses structural matching via targetTypeAnnotations, falling back to string matching.
     * OPTIMIZATION: Uses secondary index for O(1) ability name lookup.
     */
    private fun findAbilityImplementation(
        abilityName: String,
        typeArgs: List<MezzeType>
    ): com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo? {
        // O(1) lookup via secondary index instead of O(n) iteration
        val implHashes = mutableAbilityImplsByName[abilityName] ?: return null

        for (hash in implHashes) {
            val implInfo = mutableAbilityImpls[hash] ?: continue

            // PRIMARY: Use structural matching via targetTypeAnnotations
            val annotations = implInfo.targetTypeAnnotations
            if (annotations != null && annotations.size == typeArgs.size) {
                val matches = typeArgs.zip(annotations).all { (type, annotation) ->
                    typeMatchesAnnotation(type, annotation)
                }
                if (matches) return implInfo
            } else if (implInfo.targetTypes.size == typeArgs.size) {
                // FALLBACK: String-based matching when annotations not available
                var matches = true
                for (i in typeArgs.indices) {
                    val implTypeName = implInfo.targetTypes[i]
                    val argTypeName = getTypeNameForAbility(typeArgs[i])
                    if (implTypeName != argTypeName) {
                        matches = false
                        break
                    }
                }
                if (matches) return implInfo
            }
        }
        return null
    }

    /**
     * Find an ability implementation where the first type argument matches.
     * This is used for multi-parameter abilities like Convert 'a 'b where we only
     * know the input type from the arguments.
     */
    private fun findAbilityImplementationByFirstType(
        abilityName: String,
        firstType: MezzeType
    ): com.mezze.language.typechecker.context.TypingContext.AbilityImplInfo? {
        // O(1) lookup via secondary index, then filter the smaller list
        val implHashes = mutableAbilityImplsByName[abilityName] ?: return null
        val candidates = implHashes.mapNotNull { hash ->
            mutableAbilityImpls[hash]?.takeIf { implInfo ->
                // PRIMARY: Structural matching via targetTypeAnnotations
                val annotations = implInfo.targetTypeAnnotations
                if (annotations != null && annotations.isNotEmpty()) {
                    typeMatchesAnnotation(firstType, annotations.first())
                } else {
                    // FALLBACK: String matching
                    implInfo.targetTypes.isNotEmpty() &&
                    implInfo.targetTypes.first() == getTypeNameForAbility(firstType)
                }
            }
        }
        // If there's exactly one candidate, use it
        // If there are multiple, we can't disambiguate without more context
        return if (candidates.size == 1) candidates.first() else null
    }

    /**
     * Check if a MezzeType matches a TypeAnnotation structurally.
     */
    private fun typeMatchesAnnotation(type: MezzeType, annotation: TypeAnnotation): Boolean {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> type is MezzeType.IntType
                    "Float" -> type is MezzeType.FloatType
                    "String" -> type is MezzeType.StringType
                    "Bool" -> type is MezzeType.BoolType
                    "Nat" -> type is MezzeType.NatType
                    "Frac" -> type is MezzeType.FracType
                    "Char" -> type is MezzeType.CharType
                    "Bytes" -> type is MezzeType.BytesType
                    "ByteBuffer" -> type is MezzeType.ByteBufferType
                    "Ordering" -> type is MezzeType.OrderingType
                    else -> {
                        // Type variable in annotation - matches any type
                        if (annotation.name.startsWith("'") || annotation.name.first().isLowerCase()) {
                            true
                        } else {
                            // Type constructor match
                            when (type) {
                                is MezzeType.TypeApplication -> type.constructor == annotation.name && type.args.isEmpty()
                                is MezzeType.RecordType -> type.typeName == annotation.name
                                is MezzeType.VariantType -> type.typeName == annotation.name
                                else -> false
                            }
                        }
                    }
                }
            }
            is TypeApplicationAnnotation -> {
                when (type) {
                    is MezzeType.TypeApplication -> {
                        type.constructor == annotation.constructor &&
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotation(argType, argAnnotation)
                        }
                    }
                    is MezzeType.ArrayType -> {
                        annotation.constructor == "Array" &&
                        annotation.args.size == 1 &&
                        typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    is MezzeType.ListType -> {
                        annotation.constructor == "List" &&
                        annotation.args.size == 1 &&
                        typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    is MezzeType.SetType -> {
                        annotation.constructor == "Set" &&
                        annotation.args.size == 1 &&
                        typeMatchesAnnotation(type.elementType, annotation.args[0])
                    }
                    is MezzeType.MapType -> {
                        annotation.constructor == "Map" &&
                        annotation.args.size == 2 &&
                        typeMatchesAnnotation(type.keyType, annotation.args[0]) &&
                        typeMatchesAnnotation(type.valueType, annotation.args[1])
                    }
                    else -> false
                }
            }
            is RecordTypeAnnotation -> {
                if (type !is MezzeType.RecordType) return false
                annotation.entries.all { entry ->
                    val fieldType = type.fields[entry.name]
                    fieldType != null && typeMatchesAnnotation(fieldType, entry.type)
                }
            }
            is VariantTypeAnnotation -> {
                if (type !is MezzeType.VariantType) return false
                annotation.constructors.all { ctor ->
                    val payload = type.constructors[ctor.name]
                    val dataType = ctor.dataType
                    when {
                        dataType == null -> payload == null
                        payload == null -> false
                        else -> typeMatchesAnnotation(payload, dataType)
                    }
                }
            }
            is ArrayTypeAnnotation -> {
                type is MezzeType.ArrayType &&
                typeMatchesAnnotation(type.elementType, annotation.elementType)
            }
            is FunctionTypeAnnotation -> {
                type is MezzeType.FunctionType &&
                typeMatchesAnnotation(type.paramType, annotation.paramType) &&
                typeMatchesAnnotation(type.returnType, annotation.returnType)
            }
            is QualifiedTypeAnnotation -> {
                when (type) {
                    is MezzeType.TypeApplication -> {
                        val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                        type.constructor == qualifiedName &&
                        type.args.size == annotation.args.size &&
                        type.args.zip(annotation.args).all { (argType, argAnnotation) ->
                            typeMatchesAnnotation(argType, argAnnotation)
                        }
                    }
                    else -> false
                }
            }
            is EffectRowAnnotation -> false // Effect rows don't match concrete types
        }
    }

    /**
     * Get a type name for ability resolution.
     * Delegates to ContentHashComputer for consistency with content hash keys.
     */
    private fun getTypeNameForAbility(type: MezzeType): String {
        return ContentHashComputer.mezzeTypeToString(type)
    }

    /**
     * Pretty print a type for ability constraint keys.
     */
    private fun prettyPrintType(type: MezzeType): String {
        return when (type) {
            is MezzeType.IntType -> "Int"
            is MezzeType.FloatType -> "Float"
            is MezzeType.StringType -> "String"
            is MezzeType.BoolType -> "Bool"
            is MezzeType.NatType -> "Nat"
            is MezzeType.FracType -> "Frac"
            is MezzeType.VarType -> "'${type.variable.name}"
            is MezzeType.TypeApplication -> {
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor}<${type.args.joinToString(", ") { prettyPrintType(it) }}>"
            }
            is MezzeType.ListType -> "List<${prettyPrintType(type.elementType)}>"
            is MezzeType.ArrayType -> "Array<${prettyPrintType(type.elementType)}>"
            else -> type.toString()
        }
    }

    /**
     * Collect all nested types that need Encode/Decode implementations.
     * For a record type {name: String, age: Int}, this returns [String, Int].
     */
    private fun collectNestedTypes(type: MezzeType): Set<MezzeType> {
        val result = mutableSetOf<MezzeType>()
        collectNestedTypesImpl(type, result)
        return result
    }

    private fun collectNestedTypesImpl(type: MezzeType, result: MutableSet<MezzeType>) {
        when (type) {
            is MezzeType.RecordType -> {
                for ((_, fieldType) in type.fields) {
                    result.add(fieldType)
                    collectNestedTypesImpl(fieldType, result)
                }
            }
            is MezzeType.VariantType -> {
                for ((_, payloadType) in type.constructors) {
                    if (payloadType != null) {
                        result.add(payloadType)
                        collectNestedTypesImpl(payloadType, result)
                    }
                }
            }
            is MezzeType.ListType -> {
                result.add(type.elementType)
                collectNestedTypesImpl(type.elementType, result)
            }
            is MezzeType.ArrayType -> {
                result.add(type.elementType)
                collectNestedTypesImpl(type.elementType, result)
                // Array encoder converts to List, so we need the List impl too
                val listType = MezzeType.ListType(type.elementType)
                result.add(listType)
                collectNestedTypesImpl(listType, result)
            }
            is MezzeType.SetType -> {
                result.add(type.elementType)
                collectNestedTypesImpl(type.elementType, result)
            }
            is MezzeType.MapType -> {
                result.add(type.keyType)
                result.add(type.valueType)
                collectNestedTypesImpl(type.keyType, result)
                collectNestedTypesImpl(type.valueType, result)
            }
            is MezzeType.TypeApplication -> {
                // For Option, Result, etc.
                for (arg in type.args) {
                    result.add(arg)
                    collectNestedTypesImpl(arg, result)
                }
            }
            // Primitives don't need further recursion
            else -> {}
        }
    }

    /**
     * Close open record types for codec generation.
     *
     * When types are inferred from field access (e.g., val.name), we get open records
     * with row variables like {name: String, ...rowVar}. For codec generation, we close
     * these records (remove the row variable) so they can be treated as concrete types.
     *
     * This enables bidirectional type inference for Decode without explicit annotation.
     */
    private fun closeOpenRecords(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.RecordType -> {
                // Close the record (remove row variable) and recursively close nested types
                val closedFields = type.fields.mapValues { (_, fieldType) ->
                    closeOpenRecords(fieldType)
                }
                MezzeType.RecordType(fields = closedFields, rowVar = null, typeName = type.typeName)
            }
            is MezzeType.TypeApplication -> {
                // For Result {name: String} DecodeError, close nested records in args
                val closedArgs = type.args.map { closeOpenRecords(it) }
                MezzeType.TypeApplication(type.constructor, closedArgs)
            }
            is MezzeType.VariantType -> {
                // Close records in variant payloads
                val closedConstructors = type.constructors.mapValues { (_, payload) ->
                    payload?.let { closeOpenRecords(it) }
                }
                MezzeType.VariantType(closedConstructors, rowVar = null, typeName = type.typeName)
            }
            is MezzeType.ListType -> MezzeType.ListType(closeOpenRecords(type.elementType))
            is MezzeType.ArrayType -> MezzeType.ArrayType(closeOpenRecords(type.elementType))
            is MezzeType.SetType -> MezzeType.SetType(closeOpenRecords(type.elementType))
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(closeOpenRecords(type.elementType))
            is MezzeType.MapType -> MezzeType.MapType(
                closeOpenRecords(type.keyType),
                closeOpenRecords(type.valueType)
            )
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                closeOpenRecords(type.keyType),
                closeOpenRecords(type.valueType)
            )
            else -> type
        }
    }

    /**
     * Check if a type contains any unresolved type variables.
     */
    private fun containsVarType(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.VarType -> true
            is MezzeType.RecordType -> type.fields.values.any { containsVarType(it) }
            is MezzeType.VariantType -> type.constructors.values.any { it != null && containsVarType(it) }
            is MezzeType.ListType -> containsVarType(type.elementType)
            is MezzeType.ArrayType -> containsVarType(type.elementType)
            is MezzeType.SetType -> containsVarType(type.elementType)
            is MezzeType.SortedSetType -> containsVarType(type.elementType)
            is MezzeType.MapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
            is MezzeType.SortedMapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
            is MezzeType.TypeApplication -> type.args.any { containsVarType(it) }
            is MezzeType.FunctionType -> containsVarType(type.paramType) || containsVarType(type.returnType)
            else -> false
        }
    }

    /**
     * Extract the Decode target type from a return type.
     * For Result 'a DecodeError, extracts 'a.
     * For | Ok {val: 'a}, Err {...} |, extracts 'a.
     */
    private fun extractDecodeTargetType(returnType: MezzeType?): MezzeType? {
        if (returnType == null) return null
        return when (returnType) {
            is MezzeType.TypeApplication -> {
                if (returnType.constructor == "Result" && returnType.args.isNotEmpty()) {
                    returnType.args.first() // The 'a in Result 'a DecodeError
                } else {
                    null
                }
            }
            is MezzeType.VariantType -> {
                // Result is represented as a variant: | Ok {val: 'a} | Err {error: 'e} |
                val okConstructor = returnType.constructors["Ok"]
                if (okConstructor is MezzeType.RecordType) {
                    okConstructor.fields["val"]
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Find the Decode target type by looking through the substitution.
     *
     * For bidirectional type inference, when Decode.decode is called without a type annotation,
     * the target type is inferred from usage sites (e.g., field access like val.name).
     * This creates HasField constraints that bind type variables to record types.
     *
     * This function looks through the substitution for record types that could be
     * the decode target, filtering out known format types (like Json types).
     */
    private fun findDecodeTargetFromSubstitution(): MezzeType? {
        // Known format types that should NOT be the decode target
        val formatTypes = setOf("Json", "JString", "JInt", "JBool", "JArray", "JObject", "JNull", "JFloat")
        // Known pattern fields that indicate format types, not data types
        val formatFields = setOf("fields", "elements", "key", "value")

        // Look for record types in the substitution that could be the decode target
        for ((_, boundType) in substitution.mappings) {
            val resolvedType = resolveVarChain(boundType)
            when (resolvedType) {
                is MezzeType.RecordType -> {
                    // Found a record type - check if it's a plausible decode target
                    val fieldNames = resolvedType.fields.keys
                    // Skip format-related types (they have specific field patterns)
                    val isFormatType = fieldNames.all { it in formatFields } && fieldNames.size <= 2
                    // Skip records that only have 'val' field (these are usually Ok/Some payloads)
                    val isPayloadWrapper = fieldNames == setOf("val")
                    // Skip records that only have 'error' field (these are usually Err payloads)
                    val isErrorWrapper = fieldNames == setOf("error")
                    // Apply substitution to field types to resolve VarTypes
                    // This is necessary because HasField creates records with type variable fields
                    // that need to be resolved to their concrete types
                    val fullyResolvedType = MezzeType.RecordType(
                        fields = resolvedType.fields.mapValues { (_, fieldType) -> substitution.apply(fieldType) },
                        rowVar = resolvedType.rowVar
                    )
                    if (!isFormatType && !isPayloadWrapper && !isErrorWrapper && !containsVarType(fullyResolvedType)) {
                        return fullyResolvedType
                    }
                }
                is MezzeType.TypeApplication -> {
                    // Skip known format types
                    if (resolvedType.constructor !in formatTypes && !containsVarType(resolvedType)) {
                        // Could be a valid target type (e.g., custom types)
                    }
                }
                else -> {
                    // Skip other types
                }
            }
        }
        return null
    }

    /**
     * Resolve chains of VarType -> VarType -> ... to get the final concrete type.
     */
    private fun resolveVarChain(type: MezzeType, visited: Set<TypeVar> = emptySet()): MezzeType {
        return when (type) {
            is MezzeType.VarType -> {
                // Prevent infinite loops
                if (type.variable in visited) return type
                // Look up in substitution
                val bound = substitution.mappings[type.variable]
                if (bound != null) {
                    resolveVarChain(bound, visited + type.variable)
                } else {
                    type
                }
            }
            else -> type
        }
    }

    /**
     * Resolve a type alias to its underlying structural type.
     * For example, `Person` -> `{name: String, age: Int}`.
     */
    private fun resolveTypeAlias(type: MezzeType): MezzeType {
        return when (type) {
            is MezzeType.TypeApplication -> {
                // Look up the type declaration
                val typeDecl = typeDeclarations[type.constructor]
                if (typeDecl != null) {
                    // Use the existing convertTypeDefinition method
                    val definitionType = convertTypeDefinition(typeDecl.definition)

                    // CRITICAL: Substitute type parameters with actual args
                    // e.g., Option Int should substitute 'a with Int in the variant definition
                    val typeParams = typeDecl.typeParams
                    val typeArgs = type.args

                    // Create a substitution mapping type params to args
                    val typeParamSubstitution = if (typeParams.size == typeArgs.size) {
                        val mapping = typeParams.zip(typeArgs).associate { (param, arg) ->
                            TypeVar(param) to arg
                        }
                        Substitution(mapping)
                    } else {
                        Substitution.empty
                    }

                    // Apply the parameter substitution to the definition type
                    val substitutedType = typeParamSubstitution.apply(definitionType)

                    // Recursively resolve if the result is also a type application
                    resolveTypeAlias(substitutedType)
                } else {
                    // No resolution available, keep as is
                    type
                }
            }
            else -> type
        }
    }

    // ========================================================================
    // CONTROL FLOW
    // ========================================================================

    private fun applyToIf(expr: IfExpression): IfExpression {
        val updatedCondition = applyToExpression(expr.condition)
        val updatedThen = applyToExpression(expr.thenBranch)
        val updatedElse = applyToExpression(expr.elseBranch)
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(
            condition = updatedCondition,
            thenBranch = updatedThen,
            elseBranch = updatedElse,
            inferredType = updatedType
        )
    }

    private fun applyToMatch(expr: MatchExpression): MatchExpression {
        val updatedExpression = applyToExpression(expr.expression)
        val updatedCases = expr.cases.map { case ->
            val updatedGuard = case.guard?.let { applyToExpression(it) }
            val updatedExpr = applyToExpression(case.expression)
            // Also apply substitution to if-let bindings
            val updatedIfLetBindings = case.ifLetBindings.map { binding ->
                binding.copy(expression = applyToExpression(binding.expression))
            }
            MatchCase(case.patterns, updatedGuard, updatedExpr, updatedIfLetBindings)
        }
        // CRITICAL FIX: Compute type based on match result, don't rely on expr.inferredType being set
        // All match case expressions must have the same type, so use the first case's type
        val updatedType = if (updatedCases.isNotEmpty()) {
            updatedCases.first().expression.inferredType
        } else {
            expr.inferredType?.let { substitution.apply(it) }
        }
        return expr.copy(
            expression = updatedExpression,
            cases = updatedCases,
            inferredType = updatedType
        )
    }

    // ========================================================================
    // BLOCKS
    // ========================================================================

    private fun applyToDo(expr: DoExpression): DoExpression {
        // CRITICAL FIX: Add let bindings to context sequentially
        // This matches OldTypeChecker.kt:1925-1953
        val originalContext = identifierContext.toMap()
        val originalPolymorphicTypes = polymorphicFunctionTypes.toMap()

        val updatedStatements = expr.statements.map { stmt ->
            when (stmt) {
                is LetStatement -> {
                    // CRITICAL FIX: If there's a type annotation, use it to set the expression type BEFORE applying
                    // This ensures ability calls like Decode.decode get the correct type from the annotation
                    val annotatedType = stmt.typeAnnotation?.let { annotation ->
                        val contextBuilder = com.mezze.language.typechecker.context.ContextBuilder()
                        contextBuilder.convertTypeAnnotation(annotation)
                    }

                    // Set inferredType on the expression before processing if we have an annotation
                    val exprWithType = if (annotatedType != null) {
                        when (val e = stmt.expression) {
                            is FunctionCall -> e.copy(inferredType = annotatedType)
                            is LambdaExpression -> e.copy(inferredType = annotatedType)
                            else -> e
                        }
                    } else {
                        stmt.expression
                    }

                    val updatedExpr = applyToExpression(exprWithType)

                    // Add the let binding(s) to context for subsequent statements
                    // For simple patterns (let x = ...), this is just x -> type
                    // For complex patterns (let {a, b} = ...), extract all bindings
                    val exprType = annotatedType?.let { substitution.apply(it) } ?: updatedExpr.inferredType
                    if (exprType != null) {
                        // POLYMORPHISM FIX: Don't store polymorphic types in identifierContext.
                        // Polymorphic bindings (with ability constraints or free type variables)
                        // should rely on per-use instantiated types from expr.inferredType.
                        // If we stored the polymorphic type here, all uses would share it,
                        // breaking let-polymorphism where each use can have different type args.
                        val isPolymorphic = when (exprType) {
                            is MezzeType.FunctionType -> {
                                exprType.abilityConstraints.isNotEmpty() ||
                                com.mezze.language.typechecker.solving.collectFreeTypeVars(exprType).isNotEmpty()
                            }
                            else -> com.mezze.language.typechecker.solving.collectFreeTypeVars(exprType).isNotEmpty()
                        }

                        if (!isPolymorphic) {
                            // Only add monomorphic types to context
                            val variableNames = stmt.pattern.extractBindings()
                            for (varName in variableNames) {
                                identifierContext[varName] = exprType
                            }
                        } else if (exprType is MezzeType.FunctionType && exprType.abilityConstraints.isNotEmpty()) {
                            // POLYMORPHISM: Track polymorphic functions with ability constraints
                            // This allows resolveAbilitiesForCall to resolve abilities at each call site
                            val variableNames = stmt.pattern.extractBindings()
                            for (varName in variableNames) {
                                polymorphicFunctionTypes[varName] = exprType
                            }
                        }
                        // For other polymorphic bindings, let applyToIdentifier use expr.inferredType
                    }
                    LetStatement(stmt.pattern, stmt.typeAnnotation, updatedExpr)
                }
                is ExpressionStatement -> {
                    val updatedExpr = applyToExpression(stmt.expression)
                    ExpressionStatement(updatedExpr)
                }
            }
        }
        val updatedResult = applyToExpression(expr.result)

        // Restore original context
        identifierContext.clear()
        identifierContext.putAll(originalContext)
        polymorphicFunctionTypes.clear()
        polymorphicFunctionTypes.putAll(originalPolymorphicTypes)

        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(statements = updatedStatements, result = updatedResult, inferredType = updatedType)
    }

    // ========================================================================
    // EFFECTS
    // ========================================================================

    private fun applyToPerform(expr: PerformExpression): PerformExpression {
        val updatedExpression = applyToExpression(expr.expression)

        // Get the inner expression's type
        val innerType = updatedExpression.inferredType

        // Map each handler name to the effect it handles
        val handledEffects = expr.handlers.mapNotNull { handler ->
            performerToEffect[handler.name]
        }.toSet()

        // Compute the result type by removing handled effects
        val resultType = if (innerType != null && handledEffects.isNotEmpty()) {
            removeEffectsFromType(innerType, handledEffects)
        } else {
            // Fallback to existing inferred type if we can't compute
            expr.inferredType?.let { substitution.apply(it) }
        }

        // Build resolvedHandlers map for O(1) runtime dispatch
        // Maps effect name -> ResolvedEffectHandler with content hash
        val resolvedHandlers = expr.handlers.mapNotNull { handler ->
            val performerName = handler.name
            val effectName = performerToEffect[performerName] ?: return@mapNotNull null
            val implDecl = implDeclarations[performerName] ?: return@mapNotNull null
            val implHash = implDecl.contentHash ?: return@mapNotNull null

            // Detect scoped effect info
            val scopedInfo = detectScopedEffectMembers(implDecl)

            effectName to ResolvedEffectHandler(
                effectName = effectName,
                performerName = performerName,
                implDeclarationHash = implHash,
                isScopedEffect = scopedInfo.isScopedEffect,
                initializeScopeExpr = scopedInfo.initializeScope?.implementation,
                finalizeScopeExpr = scopedInfo.finalizeScope?.implementation
            )
        }.toMap()

        return expr.copy(
            expression = updatedExpression,
            inferredType = resultType,
            resolvedHandlers = resolvedHandlers
        )
    }

    /**
     * Remove specific effects from a type.
     *
     * For FunctionType with effects: Remove effects from effect row
     * For SuspendedType with effects: Remove effects from effect row, returning pure value if all removed
     */
    private fun removeEffectsFromType(type: MezzeType, effectsToRemove: Set<String>): MezzeType {
        return when (type) {
            is MezzeType.FunctionType -> {
                val effects = type.effects
                if (effects == null) {
                    // No effects to remove, but mark as performed since we're in a perform block
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(emptySet(), null, true),  // Mark as performed
                        abilityConstraints = type.abilityConstraints
                    )
                } else {
                    // Remove the handled effects, mark as performed
                    val remainingEffects = effects.effects - effectsToRemove

                    // Always set performed = true since we're handling effects
                    MezzeType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = MezzeType.EffectRow.simple(remainingEffects, effects.rowVar, true),
                        abilityConstraints = type.abilityConstraints
                    )
                }
            }
            is MezzeType.SuspendedType -> {
                // Remove effects from suspended type, mark as performed
                val remainingEffects = type.effects.effects - effectsToRemove

                // Keep SuspendedType with performed=true even when all effects are handled
                // This allows top-level validation to reject values with <*> marker
                MezzeType.SuspendedType(
                    valueType = type.valueType,
                    effects = MezzeType.EffectRow.simple(remainingEffects, type.effects.rowVar, true)
                )
            }
            else -> {
                // Other types don't have effects
                type
            }
        }
    }

    private fun applyToEffectCall(expr: EffectCallExpression): EffectCallExpression {
        val updatedArguments = applyToExpression(expr.arguments)
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(arguments = updatedArguments, inferredType = updatedType)
    }

    // ========================================================================
    // STATE MACHINES
    // ========================================================================

    private fun applyToStateMachine(expr: StateMachineExpression): StateMachineExpression {
        val updatedStates = expr.states.map { state ->
            when (state) {
                is ComputeState -> {
                    val updatedComputation = applyToExpression(state.computation)
                    ComputeState(state.id, updatedComputation, state.nextStateId)
                }
                is YieldState -> {
                    val updatedValue = applyToExpression(state.valueExpression)
                    YieldState(state.id, updatedValue, state.capturedVariables, state.nextStateId)
                }
                is CompleteState -> {
                    val updatedResult = applyToExpression(state.resultExpression)
                    CompleteState(state.id, updatedResult)
                }
            }
        }
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(states = updatedStates, inferredType = updatedType)
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Convert TypeAnnotation to MezzeType.
     * Reuses logic from ContextBuilder for consistency.
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): MezzeType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> MezzeType.IntType
                    "Float" -> MezzeType.FloatType
                    "String" -> MezzeType.StringType
                    "Bool" -> MezzeType.BoolType
                    "Nat" -> MezzeType.NatType
                    "Frac" -> MezzeType.FracType
                    "Ordering" -> MezzeType.OrderingType
                    "Char" -> MezzeType.CharType
                    "Bytes" -> MezzeType.BytesType
                    "ByteBuffer" -> MezzeType.ByteBufferType
                    else -> {
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            MezzeType.VarType(TypeVar(annotation.name.removePrefix("'")))
                        } else {
                            MezzeType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                MezzeType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType),
                    returnType = convertTypeAnnotation(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        MezzeType.EffectRow.simple(effectRow.effects.toSet(), null, effectRow.isPerformed)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        MezzeType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertTypeAnnotation(it) }
                        )
                    }
                )
            }
            is RecordTypeAnnotation -> {
                MezzeType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotation(entry.type)
                    },
                    rowVar = annotation.restField?.let { RowVar(it) }
                )
            }
            is VariantTypeAnnotation -> {
                MezzeType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotation(it) }
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                MezzeType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotation(it) }
                )
            }
            is ArrayTypeAnnotation -> {
                MezzeType.ArrayType(
                    elementType = convertTypeAnnotation(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                MezzeType.VarType(TypeVar.fresh())
            }
            is QualifiedTypeAnnotation -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                MezzeType.TypeApplication(
                    constructor = qualifiedName,
                    args = annotation.args.map { convertTypeAnnotation(it) }
                )
            }
        }
    }

    // ========================================================================
    // ROW VARIABLE CLOSING
    // ========================================================================

    /**
     * Close generated row variables in a type.
     *
     * Row variables created by the constraint solver (named "row0", "row1", etc.)
     * should be closed by default. Only row variables from user annotations
     * (named "rest" or other user-specified names) should remain open.
     *
     * This matches the old TypeChecker behavior where records inferred from
     * field access are closed by default.
     */
    private fun closeGeneratedRowVars(type: MezzeType, bindingName: String? = null): MezzeType {
        // Check if this binding should have open variants
        val bindingShouldBeOpen = bindingName != null && mustBeOpenBindings.contains(bindingName)

        return when (type) {
            is MezzeType.RecordType -> {
                // Close the row variable if it's a generated one (starts with "row")
                val existingRowVar = type.rowVar
                val closedRowVar = if (existingRowVar != null && existingRowVar.name.startsWith("row")) {
                    null  // Close it
                } else {
                    existingRowVar  // Keep it (user annotation)
                }
                MezzeType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) -> closeGeneratedRowVars(fieldType, bindingName) },
                    rowVar = closedRowVar
                )
            }
            is MezzeType.VariantType -> {
                // Check if this variant type should stay open
                // It must stay open ONLY if its row variable is in the effectiveMustBeOpenRowVars set
                //
                // NOTE: We do NOT use binding-level openness as a fallback anymore!
                // The old fallback (`mustBeOpenBindings.contains(bindingName)`) caused ALL variants
                // in a binding to stay open when only the scrutinee should be open.
                // This led to return types from variant constructions being incorrectly open.
                //
                // The proper solution requires tracking the per-binding substitutions and merging
                // them with the global substitution so MustBeOpen constraints can be resolved.
                // But since per-binding solving doesn't propagate substitutions back, we just
                // close all generated row vars unless they're explicitly in effectiveMustBeOpenRowVars.
                val existingRowVar = type.rowVar
                val shouldStayOpen = existingRowVar != null && effectiveMustBeOpenRowVars.contains(existingRowVar)

                // Close the row variable only if:
                // 1. It's a generated one (starts with "row")
                // 2. AND the type doesn't need to stay open
                val closedRowVar = if (existingRowVar != null && existingRowVar.name.startsWith("row") && !shouldStayOpen) {
                    null  // Close it
                } else {
                    existingRowVar  // Keep it (user annotation or must stay open)
                }
                MezzeType.VariantType(
                    constructors = type.constructors.mapValues { (_, payloadType) ->
                        payloadType?.let { closeGeneratedRowVars(it, bindingName) }
                    },
                    rowVar = closedRowVar
                )
            }
            is MezzeType.FunctionType -> {
                MezzeType.FunctionType(
                    paramType = closeGeneratedRowVars(type.paramType, bindingName),
                    returnType = closeGeneratedRowVars(type.returnType, bindingName),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is MezzeType.ArrayType -> MezzeType.ArrayType(closeGeneratedRowVars(type.elementType, bindingName))
            is MezzeType.ListType -> MezzeType.ListType(closeGeneratedRowVars(type.elementType, bindingName))
            is MezzeType.SetType -> MezzeType.SetType(closeGeneratedRowVars(type.elementType, bindingName))
            is MezzeType.SortedMapType -> MezzeType.SortedMapType(
                keyType = closeGeneratedRowVars(type.keyType, bindingName),
                valueType = closeGeneratedRowVars(type.valueType, bindingName)
            )
            is MezzeType.SortedSetType -> MezzeType.SortedSetType(closeGeneratedRowVars(type.elementType, bindingName))
            is MezzeType.MapType -> MezzeType.MapType(
                keyType = closeGeneratedRowVars(type.keyType, bindingName),
                valueType = closeGeneratedRowVars(type.valueType, bindingName)
            )
            is MezzeType.TypeApplication -> MezzeType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { closeGeneratedRowVars(it, bindingName) }
            )
            is MezzeType.SuspendedType -> MezzeType.SuspendedType(
                valueType = closeGeneratedRowVars(type.valueType, bindingName),
                effects = type.effects
            )
            // Types without row variables
            is MezzeType.VarType,
            is MezzeType.IntType,
            is MezzeType.FloatType,
            is MezzeType.StringType,
            is MezzeType.BoolType,
            is MezzeType.NatType,
            is MezzeType.FracType,
            is MezzeType.OrderingType,
            is MezzeType.BytesType,
            is MezzeType.ByteBufferType,
            is MezzeType.CharType,
            is MezzeType.EffectRow -> type
        }
    }

    private fun debugType(type: MezzeType?, depth: Int = 0): String {
        if (type == null) return "null"
        if (depth > 3) return "..."
        return when (type) {
            is MezzeType.FunctionType -> "FunctionType(param=${debugType(type.paramType, depth + 1)}, return=${debugType(type.returnType, depth + 1)})"
            is MezzeType.RecordType -> "RecordType(fields={${type.fields.map { (k, v) -> "$k: ${debugType(v, depth + 1)}" }.joinToString(", ")}}, rowVar=${type.rowVar})"
            is MezzeType.VariantType -> "VariantType(constructors=${type.constructors.keys}, rowVar=${type.rowVar})"
            is MezzeType.VarType -> "VarType(${type.variable})"
            else -> type::class.simpleName ?: type.toString()
        }
    }

}
