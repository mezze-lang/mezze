package com.funk.language.typechecker.application

import com.funk.language.ast.*
import com.funk.language.typechecker.abilities.BuiltInAbilities
import com.funk.language.typechecker.codec.CodecGenerator
import com.funk.language.typechecker.solving.Substitution

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
    private val constraintResults: Map<String, com.funk.language.typechecker.constraints.ConstraintResult> = emptyMap(),
    private val performerToEffect: Map<String, String> = emptyMap(),
    private val mustBeOpenRowVars: Set<RowVar> = emptySet(),
    private val mustBeOpenBindings: Set<String> = emptySet(),
    private val mustBeOpenTypes: Set<TypeVar> = emptySet(),
    private val abilityImpls: Map<String, com.funk.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap(),
    private val abilityDeclarations: Map<String, AbilityDeclaration> = emptyMap(),
    private val effectDeclarations: Map<String, EffectDeclaration> = emptyMap(),
    private val typeDeclarations: Map<String, TypeDeclaration> = emptyMap(),
    // TCO: Source locations of self-recursive tail calls
    private val tailCallLocations: Set<SourceLocation> = emptySet()
) {
    // Compute variants that must stay open by resolving type vars with substitution
    // This handles the case where per-binding solving created different row vars than global solving
    private val effectiveMustBeOpenRowVars: Set<RowVar> by lazy {
        val result = mustBeOpenRowVars.toMutableSet()
        for (typeVar in mustBeOpenTypes) {
            val resolved = substitution.apply(FunkType.VarType(typeVar))
            if (resolved is FunkType.VariantType) {
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
    // Mutable context for tracking identifier types during traversal
    // This mimics OldTypeChecker's context map (OldTypeChecker.kt:1900-1910)
    // Maps identifier names to their types
    private val identifierContext = mutableMapOf<String, FunkType>()

    // CodecGenerator for auto-deriving Encode/Decode implementations
    private val codecGenerator = CodecGenerator()

    // Track generated codec implementations that need to be added to the program
    private val generatedCodecImpls = mutableListOf<ImplAbilityDeclaration>()

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

        // Check variant constructors - look through type declarations
        for ((_, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is VariantDefinition) {
                if (definition.constructors.any { it.name == name }) {
                    return ConstructorResolution.VariantConstructor
                }
            }
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
                        identifierContext[bindingName] = type
                    }
                }
            }
        }

        // Add any auto-generated codec implementations to the BEGINNING of the program
        // This ensures they're registered BEFORE any code that uses them
        val allItems = generatedCodecImpls + updatedItems
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
                is FunkType.SuspendedType -> substitutedType // Already suspended
                is FunkType.FunctionType -> substitutedType // Functions have effects in signature
                else -> FunkType.SuspendedType(
                    valueType = substitutedType,
                    effects = FunkType.EffectRow(effects)
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
                    typeParam = constraint.typeVar.name
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
     * Convert a TypeDefinition to a FunkType.
     *
     * This matches OldTypeChecker.kt:10808-10852.
     * CRITICAL: Preserves isOpen flag and rowVar for variants.
     */
    private fun convertTypeDefinition(definition: TypeDefinition): FunkType {
        return when (definition) {
            is NewtypeDefinition -> {
                // Convert the wrapped type
                val wrappedFunkType = convertTypeAnnotation(definition.wrappedType)
                // Newtypes are represented as single-constructor variants internally
                FunkType.VariantType(
                    constructors = mapOf(definition.constructor to wrappedFunkType),
                    rowVar = null  // Newtypes are always closed
                )
            }
            is RecordDefinition -> {
                val fields = definition.fields.associate { field ->
                    field.name to convertTypeAnnotation(field.type)
                }
                // CRITICAL: Preserve isOpen flag from RecordDefinition
                if (definition.isOpen) {
                    FunkType.RecordType(fields, RowVar("rest"))
                } else {
                    FunkType.RecordType(fields)
                }
            }
            is VariantDefinition -> {
                val constructors = definition.constructors.associate { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
                }
                // CRITICAL: Preserve isOpen flag from VariantDefinition
                if (definition.isOpen) {
                    // Create open variant with a row variable
                    FunkType.VariantType(constructors, RowVar("rest"))
                } else {
                    // Create closed variant
                    FunkType.VariantType(constructors)
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
        val effectRecordType = FunkType.RecordType(
            fields = effectDecl.operations.associate { op ->
                // Effect operations include effect rows, so we need to convert them properly
                val paramType = convertTypeAnnotation(op.paramType)
                val returnType = convertTypeAnnotation(op.returnType)

                // Create function type with effect row if effects are present
                val functionType = if (op.effects.isNotEmpty()) {
                    FunkType.FunctionType(
                        paramType = paramType,
                        returnType = returnType,
                        effects = FunkType.EffectRow(op.effects.toSet())
                    )
                } else {
                    FunkType.FunctionType(
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
        val abilityRecordType = FunkType.RecordType(
            fields = abilityDecl.operations.associate { op ->
                val functionType = FunkType.FunctionType(
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
        val performerRecordType = FunkType.RecordType(
            fields = implDecl.operations.associate { op ->
                // Use operation's type annotation if provided
                val paramType = op.paramType
                val returnType = op.returnType
                val functionType = if (paramType != null && returnType != null) {
                    FunkType.FunctionType(
                        paramType = convertTypeAnnotation(paramType),
                        returnType = convertTypeAnnotation(returnType)
                    )
                } else {
                    // If no type annotation, use fresh type variable
                    FunkType.VarType(TypeVar.fresh())
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
        val implRecordType = FunkType.RecordType(
            fields = implDecl.operations.associate { op ->
                // Use the operation's type annotation if available
                val paramType = op.paramType
                val returnType = op.returnType
                val functionType = if (paramType != null && returnType != null) {
                    FunkType.FunctionType(
                        paramType = convertTypeAnnotation(paramType),
                        returnType = convertTypeAnnotation(returnType)
                    )
                } else {
                    // If no type annotation, use fresh type variable
                    FunkType.VarType(TypeVar.fresh())
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
        val updatedType = substitution.apply(FunkType.IntType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToNatLiteral(expr: NatLiteral): NatLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(FunkType.NatType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToFloatLiteral(expr: FloatLiteral): FloatLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(FunkType.FloatType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToBooleanLiteral(expr: BooleanLiteral): BooleanLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        // Bool is a variant type: | True, False |
        val boolType = FunkType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
        val updatedType = substitution.apply(boolType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToFractionLiteral(expr: FractionLiteral): FractionLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(FunkType.FracType)
        return expr.copy(inferredType = updatedType)
    }

    private fun applyToStringLiteral(expr: StringLiteral): StringLiteral {
        // CRITICAL FIX: Compute type based on expression structure
        val updatedType = substitution.apply(FunkType.StringType)
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
                is ArrayRegularElement -> firstElement.expression.inferredType ?: FunkType.VarType(TypeVar.fresh())
                is ArraySpreadElement -> {
                    // For spread element, extract element type from the array type
                    val arrayType = firstElement.expression.inferredType
                    if (arrayType is FunkType.ArrayType) arrayType.elementType
                    else FunkType.VarType(TypeVar.fresh())
                }
            }
            substitution.apply(FunkType.ArrayType(elementType))
        } else {
            // Empty array: use existing type if available, otherwise fresh type variable
            expr.inferredType?.let { substitution.apply(it) }
                ?: FunkType.ArrayType(FunkType.VarType(TypeVar.fresh()))
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
        return expr.copy(inferredType = updatedType)
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
            val fields = mutableMapOf<String, FunkType>()
            for (entry in updatedEntries) {
                when (entry) {
                    is RecordFieldEntry -> {
                        val entryType = entry.value?.inferredType ?: FunkType.VarType(TypeVar.fresh())
                        fields[entry.name] = entryType
                    }
                    is RecordSpreadEntry -> {
                        // Spread entries add fields from the spread expression's type
                        val spreadType = entry.expression.inferredType
                        if (spreadType is FunkType.RecordType) {
                            fields.putAll(spreadType.fields)
                        }
                    }
                }
            }
            if (fields.isNotEmpty()) FunkType.RecordType(fields) else null
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
            if (abilityName != null) {
                val abilityDecl = abilityDeclarations[abilityName]
                if (abilityDecl != null) {
                    val operation = abilityDecl.operations.find { it.name == methodName }
                    if (operation != null) {
                        // Convert the param and return type annotations to FunkType and apply substitution
                        val paramType = typeAnnotationToFunkType(operation.paramType)
                        val returnType = typeAnnotationToFunkType(operation.returnType)
                        val methodType = FunkType.FunctionType(paramType, returnType)
                        updatedType = substitution.apply(methodType)
                    }
                }
            }
        }

        return expr.copy(receiver = updatedReceiver, inferredType = updatedType)
    }

    /**
     * Convert a TypeAnnotation to FunkType.
     * Used for looking up ability method types.
     */
    private fun typeAnnotationToFunkType(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Bool" -> FunkType.BooleanType
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    "Char" -> FunkType.CharType
                    "Bytes" -> FunkType.BytesType
                    else -> {
                        // Could be a type variable or type alias
                        if (annotation.name.startsWith("'") || annotation.name.all { it.isLowerCase() }) {
                            // Type variable - create a fresh one (will be unified later)
                            FunkType.VarType(TypeVar.fresh())
                        } else {
                            // Named type - use TypeApplication
                            FunkType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                val paramType = typeAnnotationToFunkType(annotation.paramType)
                val returnType = typeAnnotationToFunkType(annotation.returnType)
                FunkType.FunctionType(paramType, returnType)
            }
            is RecordTypeAnnotation -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to typeAnnotationToFunkType(entry.type)
                }
                FunkType.RecordType(fields)
            }
            is TypeApplicationAnnotation -> {
                val args = annotation.args.map { typeAnnotationToFunkType(it) }
                when (annotation.constructor) {
                    "List" -> if (args.isNotEmpty()) FunkType.ListType(args.first()) else FunkType.TypeApplication("List", args)
                    "Set" -> if (args.isNotEmpty()) FunkType.SetType(args.first()) else FunkType.TypeApplication("Set", args)
                    "Map" -> if (args.size >= 2) FunkType.MapType(args[0], args[1]) else FunkType.TypeApplication("Map", args)
                    "Option" -> FunkType.TypeApplication("Option", args)
                    "Result" -> FunkType.TypeApplication("Result", args)
                    else -> FunkType.TypeApplication(annotation.constructor, args)
                }
            }
            is VariantTypeAnnotation -> {
                val constructors = annotation.constructors.associate { ctor: VariantTypeConstructor ->
                    val payloadType = ctor.dataType?.let { typeAnnotationToFunkType(it) } ?: FunkType.RecordType(emptyMap())
                    ctor.name to payloadType
                }
                FunkType.VariantType(constructors)
            }
            else -> FunkType.VarType(TypeVar.fresh()) // Fallback for unknown annotations
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
                    FunkType.VariantType(
                        constructors = mapOf("True" to null, "False" to null),
                        rowVar = null
                    )
                }
                "None" -> {
                    // Option None: Option 'a = | Some { val: 'a }, None |
                    // We create a type application for Option with a fresh type var
                    FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar.fresh())))
                }
                "Some" -> {
                    // Option Some: Option 'a = | Some { val: 'a }, None |
                    // The type parameter comes from the payload
                    val innerType = updatedValue?.inferredType?.let {
                        when (it) {
                            is FunkType.RecordType -> it.fields["val"] ?: FunkType.VarType(TypeVar.fresh())
                            else -> FunkType.VarType(TypeVar.fresh())
                        }
                    } ?: FunkType.VarType(TypeVar.fresh())
                    FunkType.TypeApplication("Option", listOf(innerType))
                }
                "Nil" -> {
                    // List Nil constructor
                    FunkType.ListType(FunkType.VarType(TypeVar.fresh()))
                }
                "Cons" -> {
                    // List Cons constructor - extract element type from payload
                    val elemType = updatedValue?.inferredType?.let {
                        when (it) {
                            is FunkType.RecordType -> it.fields["head"] ?: FunkType.VarType(TypeVar.fresh())
                            else -> FunkType.VarType(TypeVar.fresh())
                        }
                    } ?: FunkType.VarType(TypeVar.fresh())
                    FunkType.ListType(elemType)
                }
                else -> {
                    // For other constructors, create an open variant with just this constructor
                    val payloadType = updatedValue?.inferredType
                    FunkType.VariantType(
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
        if (existingType is FunkType.FunctionType) {
            val paramType = existingType.paramType
            if (paramType is FunkType.RecordType) {
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
            val bodyType = updatedBody.inferredType ?: FunkType.VarType(TypeVar.fresh())

            // Extract parameter type from parameters
            val paramType: FunkType = when (val params = expr.parameters) {
                is UntypedParameters -> {
                    // Create record type from parameter names
                    val fields = params.names.associateWith { FunkType.VarType(TypeVar.fresh()) }
                    FunkType.RecordType(fields = fields, rowVar = null)
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
                    FunkType.FunctionAbilityConstraint(
                        abilityName = constraint.abilityName,
                        typeArgs = constraint.typeArgs.map { typeAnn ->
                            convertTypeAnnotation(typeAnn)
                        }
                    )
                }
            } else {
                emptyList()
            }

            val functionType = FunkType.FunctionType(
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
                is FunkType.FunctionType -> {
                    // CRITICAL: If the function has effects (including performed marker),
                    // wrap the return type in SuspendedType
                    // Calling an effectful function produces a suspended computation
                    val returnType = funcType.returnType
                    val effects = funcType.effects
                    // Check for: concrete effects, row variable (polymorphic), OR performed marker <*>
                    if (effects != null && (effects.effects.isNotEmpty() || effects.rowVar != null || effects.performed)) {
                        FunkType.SuspendedType(returnType, effects)
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

        // Also check if this is a direct ability method call (e.g., Show.show {val = 42})
        // In this case, we need to resolve based on the argument types, not the function type constraints
        if (resolvedAbilities.isEmpty() && updatedFunction is MemberAccess) {
            resolvedAbilities = resolveDirectAbilityMethodCall(updatedFunction, updatedArguments, updatedType)
        }

        // TCO: Check if this call is a self-recursive tail call
        val isTailCall = expr.sourceLocation != null && tailCallLocations.contains(expr.sourceLocation)

        return expr.copy(
            function = updatedFunction,
            arguments = updatedArguments,
            inferredType = updatedType,
            resolvedAbilities = resolvedAbilities,
            isTailCall = isTailCall
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
        returnType: FunkType?
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
        val hasImpl = abilityImpls.values.any { it.abilityName == abilityName }
        val isAutoDerivable = (abilityName == "Encode" || abilityName == "Decode")
        if (!hasImpl && !BuiltinAbilities.isBuiltin(abilityName) && !isAutoDerivable) {
            return resolvedAbilities
        }

        // Get the argument type to determine which implementation to use
        val argType = arguments.inferredType
        if (argType !is FunkType.RecordType) return resolvedAbilities

        // For single-parameter abilities like Show, use the first field's type
        // For multi-parameter abilities like Convert, we'd need more sophisticated matching
        val targetTypes = argType.fields.values.map { substitution.apply(it) }
        if (targetTypes.isEmpty()) return resolvedAbilities

        // Try to find an explicit implementation
        val primaryType = targetTypes.first()

        // First try exact match (for single-parameter abilities)
        var impl = findAbilityImplementation(abilityName, listOf(primaryType))

        // If no exact match, try partial match for multi-parameter abilities
        // e.g., for Convert 'a 'b, find impl where first type matches
        if (impl == null) {
            impl = findAbilityImplementationByFirstType(abilityName, primaryType)
        }

        // If no match found by argument types, try to find the only implementation
        // This handles format-parameterized abilities like EncodeFormat 'repr
        // where the type parameter doesn't come from the arguments
        if (impl == null) {
            val allImpls = abilityImpls.values.filter { it.abilityName == abilityName }
            if (allImpls.size == 1) {
                impl = allImpls.first()
            }
        }

        if (impl != null) {
            // Found explicit implementation
            // Reconstruct the full type args from the impl's target types
            val implTypeArgs = impl.targetTypes.map { typeName ->
                when (typeName) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Bool" -> FunkType.BooleanType
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    else -> FunkType.TypeApplication(typeName, emptyList())
                }
            }
            val key = "${abilityName}<${implTypeArgs.joinToString(", ") { prettyPrintType(it) }}>"
            val implRef = "${abilityName}\$${impl.targetTypes.joinToString("\$")}"
            resolvedAbilities[key] = ResolvedAbility(
                abilityName = abilityName,
                typeArgs = implTypeArgs,
                implementationRef = implRef,
                isBuiltin = false
            )
        } else if (BuiltinAbilities.isBuiltin(abilityName)) {
            // Check for built-in ability with automatic implementation
            if (BuiltinAbilities.typeHasAutomaticImplementation(primaryType, abilityName)) {
                val key = "${abilityName}<${prettyPrintType(primaryType)}>"
                val implRef = "builtin::${abilityName}::${getTypeNameForAbility(primaryType)}"
                resolvedAbilities[key] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = listOf(primaryType),
                    implementationRef = implRef,
                    isBuiltin = true
                )
            }
        } else if (abilityName == "Encode" || abilityName == "Decode") {
            // Auto-derive Encode/Decode for serializable types
            // First, find the format type from the EncodeFormat/DecodeFormat impl in scope
            val formatAbility = if (abilityName == "Encode") "EncodeFormat" else "DecodeFormat"
            val formatImpl = abilityImpls.values.find { it.abilityName == formatAbility }

            // For Encode: primaryType is the data type from the argument (correct)
            // For Decode: primaryType is the format type from the argument (wrong!)
            //             We need the return type, which is Result 'a DecodeError
            //             The 'a is what we need to decode to
            val dataType = if (abilityName == "Decode") {
                // The return type may be null on the AST node, but we can compute it
                // from the function's type. For Decode.decode, the function type is:
                //   {val: 'repr} -> Result 'a DecodeError
                // We need to extract 'a after applying the substitution.

                // First, try the passed returnType (may have been computed)
                val computedReturnType = returnType ?: run {
                    // Try to compute from the function's type
                    val funcType = memberAccess.inferredType?.let { substitution.apply(it) }
                    when (funcType) {
                        is FunkType.FunctionType -> funcType.returnType
                        else -> null
                    }
                }

                // Extract the target type from Result 'a DecodeError -> 'a
                val extractedType = when (computedReturnType) {
                    is FunkType.TypeApplication -> {
                        if (computedReturnType.constructor == "Result" && computedReturnType.args.isNotEmpty()) {
                            computedReturnType.args.first() // The 'a in Result 'a DecodeError
                        } else {
                            null
                        }
                    }
                    is FunkType.VariantType -> {
                        // Result is represented as a variant: | Ok {val: 'a} | Err {error: 'e} |
                        // Look for the Ok constructor's payload type
                        val okConstructor = computedReturnType.constructors["Ok"]
                        if (okConstructor is FunkType.RecordType) {
                            okConstructor.fields["val"]
                        } else {
                            null
                        }
                    }
                    else -> null
                }
                extractedType ?: primaryType
            } else {
                primaryType // For Encode, use the argument type
            }

            // For built-in parametric types (Option, Result), keep them as TypeApplication
            // so the codec generator can use the specialized encoders
            val resolvedPrimaryType = if (dataType is FunkType.TypeApplication &&
                (dataType.constructor == "Option" || dataType.constructor == "Result")) {
                dataType // Don't resolve Option/Result - use specialized encoder
            } else {
                resolveTypeAlias(dataType)
            }

            if (formatImpl != null && codecGenerator.isSerializable(resolvedPrimaryType) && !containsVarType(resolvedPrimaryType)) {
                // Get the format type (e.g., "Json")
                val formatTypeName = formatImpl.targetTypes.firstOrNull() ?: return resolvedAbilities
                val formatType = FunkType.TypeApplication(formatTypeName, emptyList())

                // Recursively generate implementations for all nested types
                // The record/variant encode calls Encode.encode for each field/variant
                // Filter out VarTypes since we can't generate code for unknown types
                val allTypesToGenerate = collectNestedTypes(resolvedPrimaryType)
                    .filter { !containsVarType(it) }
                for (nestedType in allTypesToGenerate) {
                    val nestedImpl = if (abilityName == "Encode") {
                        codecGenerator.generateEncodeImpl(formatType, nestedType)
                    } else {
                        codecGenerator.generateDecodeImpl(formatType, nestedType)
                    }
                    generatedCodecImpls.add(nestedImpl)
                }

                // Generate the main implementation using the resolved (structural) type
                val generatedImpl = if (abilityName == "Encode") {
                    codecGenerator.generateEncodeImpl(formatType, resolvedPrimaryType)
                } else {
                    codecGenerator.generateDecodeImpl(formatType, resolvedPrimaryType)
                }

                // Track the generated impl for later registration
                generatedCodecImpls.add(generatedImpl)

                // Create the resolved ability reference
                // Use a stable name that matches what ASTToTruffleConverter will generate
                val implRef = getCodecImplName(abilityName, formatTypeName, generatedImpl.targetTypes.drop(1).firstOrNull())
                val key = "${abilityName}<${formatTypeName}, ${prettyPrintType(primaryType)}>"

                resolvedAbilities[key] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = listOf(formatType, resolvedPrimaryType),
                    implementationRef = implRef,
                    isBuiltin = false
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
        if (funcType !is FunkType.FunctionType) return resolvedAbilities

        // Process each ability constraint
        for (constraint in funcType.abilityConstraints) {
            val abilityName = constraint.abilityName
            val typeArgs = constraint.typeArgs.map { substitution.apply(it) }

            // Try to find an explicit implementation
            val impl = findAbilityImplementation(abilityName, typeArgs)

            if (impl != null) {
                // Found explicit implementation
                val key = "${abilityName}<${typeArgs.joinToString(", ") { prettyPrintType(it) }}>"
                val implRef = "${abilityName}\$${impl.targetTypes.joinToString("\$")}"
                resolvedAbilities[key] = ResolvedAbility(
                    abilityName = abilityName,
                    typeArgs = typeArgs,
                    implementationRef = implRef,
                    isBuiltin = false
                )
            } else if (BuiltinAbilities.isBuiltin(abilityName) && typeArgs.size == 1) {
                // Check if it's a built-in ability with automatic implementation
                val targetType = typeArgs[0]
                if (BuiltinAbilities.typeHasAutomaticImplementation(targetType, abilityName)) {
                    val key = "${abilityName}<${prettyPrintType(targetType)}>"
                    val implRef = "builtin::${abilityName}::${getTypeNameForAbility(targetType)}"
                    resolvedAbilities[key] = ResolvedAbility(
                        abilityName = abilityName,
                        typeArgs = typeArgs,
                        implementationRef = implRef,
                        isBuiltin = true
                    )
                }
            }
        }

        return resolvedAbilities
    }

    /**
     * Find an ability implementation matching the given ability name and type arguments.
     */
    private fun findAbilityImplementation(
        abilityName: String,
        typeArgs: List<FunkType>
    ): com.funk.language.typechecker.context.TypingContext.AbilityImplInfo? {
        // Look for implementations of this ability
        for ((_, implInfo) in abilityImpls) {
            if (implInfo.abilityName != abilityName) continue
            if (implInfo.targetTypes.size != typeArgs.size) continue

            // Check if types match
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
        return null
    }

    /**
     * Find an ability implementation where the first type argument matches.
     * This is used for multi-parameter abilities like Convert 'a 'b where we only
     * know the input type from the arguments.
     */
    private fun findAbilityImplementationByFirstType(
        abilityName: String,
        firstType: FunkType
    ): com.funk.language.typechecker.context.TypingContext.AbilityImplInfo? {
        val firstTypeName = getTypeNameForAbility(firstType)
        val candidates = abilityImpls.values.filter { implInfo ->
            implInfo.abilityName == abilityName &&
            implInfo.targetTypes.isNotEmpty() &&
            implInfo.targetTypes.first() == firstTypeName
        }
        // If there's exactly one candidate, use it
        // If there are multiple, we can't disambiguate without more context
        return if (candidates.size == 1) candidates.first() else null
    }

    /**
     * Get a simple type name for ability resolution.
     */
    private fun getTypeNameForAbility(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.TypeApplication -> type.constructor
            is FunkType.ListType -> "List"
            is FunkType.ArrayType -> "Array"
            is FunkType.RecordType -> "Record"
            is FunkType.VariantType -> "Variant"
            else -> type.toString()
        }
    }

    /**
     * Pretty print a type for ability constraint keys.
     */
    private fun prettyPrintType(type: FunkType): String {
        return when (type) {
            is FunkType.IntType -> "Int"
            is FunkType.FloatType -> "Float"
            is FunkType.StringType -> "String"
            is FunkType.BooleanType -> "Bool"
            is FunkType.NatType -> "Nat"
            is FunkType.FracType -> "Frac"
            is FunkType.VarType -> "'${type.variable.name}"
            is FunkType.TypeApplication -> {
                if (type.args.isEmpty()) type.constructor
                else "${type.constructor}<${type.args.joinToString(", ") { prettyPrintType(it) }}>"
            }
            is FunkType.ListType -> "List<${prettyPrintType(type.elementType)}>"
            is FunkType.ArrayType -> "Array<${prettyPrintType(type.elementType)}>"
            else -> type.toString()
        }
    }

    /**
     * Get the impl name that will match what ASTToTruffleConverter generates.
     * This must be consistent with how the interpreter names ability implementations.
     */
    private fun getCodecImplName(abilityName: String, formatTypeName: String, dataTypeAnnotation: TypeAnnotation?): String {
        val dataTypeName = getTypeAnnotationNameForCodec(dataTypeAnnotation)
        return "${abilityName}\$${formatTypeName}\$${dataTypeName}"
    }

    /**
     * Get a deterministic name for a type annotation for codec impl naming.
     * This ensures consistent naming between the typechecker and interpreter.
     */
    private fun getTypeAnnotationNameForCodec(typeAnnotation: TypeAnnotation?): String {
        return when (typeAnnotation) {
            is IdentifierTypeAnnotation -> typeAnnotation.name
            is RecordTypeAnnotation -> {
                // Create a deterministic name for record types: "Record_field1_field2_..."
                val fieldNames = typeAnnotation.entries.map { it.name }.sorted().joinToString("_")
                "Record_$fieldNames"
            }
            is VariantTypeAnnotation -> {
                // Create a deterministic name for variant types: "Variant_Con1_Con2_..."
                val constructorNames = typeAnnotation.constructors.map { it.name }.sorted().joinToString("_")
                "Variant_$constructorNames"
            }
            is TypeApplicationAnnotation -> {
                // For type applications like List Int, Option String
                if (typeAnnotation.args.isEmpty()) {
                    typeAnnotation.constructor
                } else {
                    "${typeAnnotation.constructor}_${typeAnnotation.args.map { getTypeAnnotationNameForCodec(it) }.joinToString("_")}"
                }
            }
            null -> "Unknown"
            else -> typeAnnotation.toString()
        }
    }

    private fun getTypeAnnotationName(typeAnnotation: TypeAnnotation): String {
        return when (typeAnnotation) {
            is IdentifierTypeAnnotation -> typeAnnotation.name
            is TypeApplicationAnnotation -> {
                // For type applications like Option<Int> or Json (with no args)
                if (typeAnnotation.args.isEmpty()) {
                    typeAnnotation.constructor
                } else {
                    "${typeAnnotation.constructor}_${typeAnnotation.args.map { getTypeAnnotationName(it) }.joinToString("_")}"
                }
            }
            is RecordTypeAnnotation -> {
                val fieldNames = typeAnnotation.entries.map { it.name }.sorted().joinToString("_")
                "Record_$fieldNames"
            }
            is VariantTypeAnnotation -> {
                val constructorNames = typeAnnotation.constructors.map { it.name }.sorted().joinToString("_")
                "Variant_$constructorNames"
            }
            else -> typeAnnotation.toString()
        }
    }

    /**
     * Collect all nested types that need Encode/Decode implementations.
     * For a record type {name: String, age: Int}, this returns [String, Int].
     */
    private fun collectNestedTypes(type: FunkType): Set<FunkType> {
        val result = mutableSetOf<FunkType>()
        collectNestedTypesImpl(type, result)
        return result
    }

    private fun collectNestedTypesImpl(type: FunkType, result: MutableSet<FunkType>) {
        when (type) {
            is FunkType.RecordType -> {
                for ((_, fieldType) in type.fields) {
                    result.add(fieldType)
                    collectNestedTypesImpl(fieldType, result)
                }
            }
            is FunkType.VariantType -> {
                for ((_, payloadType) in type.constructors) {
                    if (payloadType != null) {
                        result.add(payloadType)
                        collectNestedTypesImpl(payloadType, result)
                    }
                }
            }
            is FunkType.ListType -> {
                result.add(type.elementType)
                collectNestedTypesImpl(type.elementType, result)
            }
            is FunkType.ArrayType -> {
                result.add(type.elementType)
                collectNestedTypesImpl(type.elementType, result)
                // Array encoder converts to List, so we need the List impl too
                val listType = FunkType.ListType(type.elementType)
                result.add(listType)
                collectNestedTypesImpl(listType, result)
            }
            is FunkType.SetType -> {
                result.add(type.elementType)
                collectNestedTypesImpl(type.elementType, result)
            }
            is FunkType.MapType -> {
                result.add(type.keyType)
                result.add(type.valueType)
                collectNestedTypesImpl(type.keyType, result)
                collectNestedTypesImpl(type.valueType, result)
            }
            is FunkType.TypeApplication -> {
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
     * Check if a type contains any unresolved type variables.
     */
    private fun containsVarType(type: FunkType): Boolean {
        return when (type) {
            is FunkType.VarType -> true
            is FunkType.RecordType -> type.fields.values.any { containsVarType(it) }
            is FunkType.VariantType -> type.constructors.values.any { it != null && containsVarType(it) }
            is FunkType.ListType -> containsVarType(type.elementType)
            is FunkType.ArrayType -> containsVarType(type.elementType)
            is FunkType.SetType -> containsVarType(type.elementType)
            is FunkType.SortedSetType -> containsVarType(type.elementType)
            is FunkType.MapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
            is FunkType.SortedMapType -> containsVarType(type.keyType) || containsVarType(type.valueType)
            is FunkType.TypeApplication -> type.args.any { containsVarType(it) }
            is FunkType.FunctionType -> containsVarType(type.paramType) || containsVarType(type.returnType)
            else -> false
        }
    }

    /**
     * Resolve a type alias to its underlying structural type.
     * For example, `Person` -> `{name: String, age: Int}`.
     */
    private fun resolveTypeAlias(type: FunkType): FunkType {
        return when (type) {
            is FunkType.TypeApplication -> {
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
            MatchCase(case.patterns, updatedGuard, updatedExpr)
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

        val updatedStatements = expr.statements.map { stmt ->
            when (stmt) {
                is LetStatement -> {
                    val updatedExpr = applyToExpression(stmt.expression)
                    // Add the let binding(s) to context for subsequent statements
                    // For simple patterns (let x = ...), this is just x -> type
                    // For complex patterns (let {a, b} = ...), extract all bindings
                    val exprType = updatedExpr.inferredType
                    if (exprType != null) {
                        // Extract all variable names from the pattern
                        val variableNames = stmt.pattern.extractBindings()
                        // For simple case: just bind the pattern variable to the expression type
                        for (varName in variableNames) {
                            identifierContext[varName] = exprType
                        }
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

        return expr.copy(
            expression = updatedExpression,
            inferredType = resultType
        )
    }

    /**
     * Remove specific effects from a type.
     *
     * For FunctionType with effects: Remove effects from effect row
     * For SuspendedType with effects: Remove effects from effect row, returning pure value if all removed
     */
    private fun removeEffectsFromType(type: FunkType, effectsToRemove: Set<String>): FunkType {
        return when (type) {
            is FunkType.FunctionType -> {
                val effects = type.effects
                if (effects == null) {
                    // No effects to remove, but mark as performed since we're in a perform block
                    FunkType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = FunkType.EffectRow(emptySet(), null, true),  // Mark as performed
                        abilityConstraints = type.abilityConstraints
                    )
                } else {
                    // Remove the handled effects, mark as performed
                    val remainingEffects = effects.effects - effectsToRemove

                    // Always set performed = true since we're handling effects
                    FunkType.FunctionType(
                        paramType = type.paramType,
                        returnType = type.returnType,
                        effects = FunkType.EffectRow(remainingEffects, effects.rowVar, true),
                        abilityConstraints = type.abilityConstraints
                    )
                }
            }
            is FunkType.SuspendedType -> {
                // Remove effects from suspended type, mark as performed
                val remainingEffects = type.effects.effects - effectsToRemove

                // Keep SuspendedType with performed=true even when all effects are handled
                // This allows top-level validation to reject values with <*> marker
                FunkType.SuspendedType(
                    valueType = type.valueType,
                    effects = FunkType.EffectRow(remainingEffects, type.effects.rowVar, true)
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
     * Convert TypeAnnotation to FunkType.
     * Reuses logic from ContextBuilder for consistency.
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                when (annotation.name) {
                    "Int" -> FunkType.IntType
                    "Float" -> FunkType.FloatType
                    "String" -> FunkType.StringType
                    "Bool" -> FunkType.BooleanType
                    "Nat" -> FunkType.NatType
                    "Frac" -> FunkType.FracType
                    "Ordering" -> FunkType.OrderingType
                    else -> {
                        if (annotation.name.first().isLowerCase() || annotation.name.startsWith("'")) {
                            FunkType.VarType(TypeVar(annotation.name.removePrefix("'")))
                        } else {
                            FunkType.TypeApplication(annotation.name, emptyList())
                        }
                    }
                }
            }
            is FunctionTypeAnnotation -> {
                FunkType.FunctionType(
                    paramType = convertTypeAnnotation(annotation.paramType),
                    returnType = convertTypeAnnotation(annotation.returnType),
                    effects = annotation.effects?.let { effectRow ->
                        FunkType.EffectRow(effectRow.effects.toSet(), null, effectRow.isPerformed)
                    },
                    abilityConstraints = annotation.abilityConstraints.map { constraint ->
                        FunkType.FunctionAbilityConstraint(
                            abilityName = constraint.abilityName,
                            typeArgs = constraint.typeArgs.map { convertTypeAnnotation(it) }
                        )
                    }
                )
            }
            is RecordTypeAnnotation -> {
                FunkType.RecordType(
                    fields = annotation.entries.associate { entry ->
                        entry.name to convertTypeAnnotation(entry.type)
                    },
                    rowVar = annotation.restField?.let { RowVar(it) }
                )
            }
            is VariantTypeAnnotation -> {
                FunkType.VariantType(
                    constructors = annotation.constructors.associate { ctor ->
                        ctor.name to ctor.dataType?.let { convertTypeAnnotation(it) }
                    }
                )
            }
            is TypeApplicationAnnotation -> {
                FunkType.TypeApplication(
                    constructor = annotation.constructor,
                    args = annotation.args.map { convertTypeAnnotation(it) }
                )
            }
            is ArrayTypeAnnotation -> {
                FunkType.ArrayType(
                    elementType = convertTypeAnnotation(annotation.elementType)
                )
            }
            is EffectRowAnnotation -> {
                FunkType.VarType(TypeVar.fresh())
            }
            is QualifiedTypeAnnotation -> {
                val qualifiedName = (annotation.modulePath + annotation.typeName).joinToString("::")
                FunkType.TypeApplication(
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
    private fun closeGeneratedRowVars(type: FunkType, bindingName: String? = null): FunkType {
        // Check if this binding should have open variants
        val bindingShouldBeOpen = bindingName != null && mustBeOpenBindings.contains(bindingName)

        return when (type) {
            is FunkType.RecordType -> {
                // Close the row variable if it's a generated one (starts with "row")
                val existingRowVar = type.rowVar
                val closedRowVar = if (existingRowVar != null && existingRowVar.name.startsWith("row")) {
                    null  // Close it
                } else {
                    existingRowVar  // Keep it (user annotation)
                }
                FunkType.RecordType(
                    fields = type.fields.mapValues { (_, fieldType) -> closeGeneratedRowVars(fieldType, bindingName) },
                    rowVar = closedRowVar
                )
            }
            is FunkType.VariantType -> {
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
                FunkType.VariantType(
                    constructors = type.constructors.mapValues { (_, payloadType) ->
                        payloadType?.let { closeGeneratedRowVars(it, bindingName) }
                    },
                    rowVar = closedRowVar
                )
            }
            is FunkType.FunctionType -> {
                FunkType.FunctionType(
                    paramType = closeGeneratedRowVars(type.paramType, bindingName),
                    returnType = closeGeneratedRowVars(type.returnType, bindingName),
                    effects = type.effects,
                    abilityConstraints = type.abilityConstraints
                )
            }
            is FunkType.ArrayType -> FunkType.ArrayType(closeGeneratedRowVars(type.elementType, bindingName))
            is FunkType.ListType -> FunkType.ListType(closeGeneratedRowVars(type.elementType, bindingName))
            is FunkType.SetType -> FunkType.SetType(closeGeneratedRowVars(type.elementType, bindingName))
            is FunkType.SortedMapType -> FunkType.SortedMapType(
                keyType = closeGeneratedRowVars(type.keyType, bindingName),
                valueType = closeGeneratedRowVars(type.valueType, bindingName)
            )
            is FunkType.SortedSetType -> FunkType.SortedSetType(closeGeneratedRowVars(type.elementType, bindingName))
            is FunkType.MapType -> FunkType.MapType(
                keyType = closeGeneratedRowVars(type.keyType, bindingName),
                valueType = closeGeneratedRowVars(type.valueType, bindingName)
            )
            is FunkType.TypeApplication -> FunkType.TypeApplication(
                constructor = type.constructor,
                args = type.args.map { closeGeneratedRowVars(it, bindingName) }
            )
            is FunkType.SuspendedType -> FunkType.SuspendedType(
                valueType = closeGeneratedRowVars(type.valueType, bindingName),
                effects = type.effects
            )
            // Types without row variables
            is FunkType.VarType,
            is FunkType.IntType,
            is FunkType.FloatType,
            is FunkType.StringType,
            is FunkType.BooleanType,
            is FunkType.NatType,
            is FunkType.FracType,
            is FunkType.OrderingType,
            is FunkType.BytesType,
            is FunkType.ByteBufferType,
            is FunkType.CharType,
            is FunkType.EffectRow -> type
        }
    }

    private fun debugType(type: FunkType?, depth: Int = 0): String {
        if (type == null) return "null"
        if (depth > 3) return "..."
        return when (type) {
            is FunkType.FunctionType -> "FunctionType(param=${debugType(type.paramType, depth + 1)}, return=${debugType(type.returnType, depth + 1)})"
            is FunkType.RecordType -> "RecordType(fields={${type.fields.map { (k, v) -> "$k: ${debugType(v, depth + 1)}" }.joinToString(", ")}}, rowVar=${type.rowVar})"
            is FunkType.VariantType -> "VariantType(constructors=${type.constructors.keys}, rowVar=${type.rowVar})"
            is FunkType.VarType -> "VarType(${type.variable})"
            else -> type::class.simpleName ?: type.toString()
        }
    }

}
