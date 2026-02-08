package com.funk.language.typechecker.application

import com.funk.language.ast.*
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
 */
class SubstitutionApplier(
    private val substitution: Substitution,
    private val constraintResults: Map<String, com.funk.language.typechecker.constraints.ConstraintResult> = emptyMap(),
    private val performerToEffect: Map<String, String> = emptyMap(),
    private val mustBeOpenRowVars: Set<RowVar> = emptySet(),
    private val mustBeOpenBindings: Set<String> = emptySet(),
    private val mustBeOpenTypes: Set<TypeVar> = emptySet(),
    private val abilityImpls: Map<String, com.funk.language.typechecker.context.TypingContext.AbilityImplInfo> = emptyMap()
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
                val bindingName = try {
                    (updatedItem.pattern as? VariablePattern)?.name
                        ?: updatedItem.pattern.extractBindings().firstOrNull()
                } catch (e: Exception) {
                    null
                }
                updatedItem.inferredType?.let { type ->
                    if (bindingName != null) {
                        identifierContext[bindingName] = type
                    }
                }
            }
        }

        return Program(items = updatedItems)
    }

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

        // CRITICAL: If the expression is a lambda, set its inferredType BEFORE processing it
        // This allows the lambda to extract parameter types and add them to identifierContext
        val expr = binding.expression
        val expressionWithType = if (expr is LambdaExpression && preExpressionType != null) {
            expr.copy(inferredType = preExpressionType)
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
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(receiver = updatedReceiver, inferredType = updatedType)
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
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(value = updatedValue, inferredType = updatedType)
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
        val updatedType = expr.inferredType?.let { substitution.apply(it) }

        // CRITICAL: Resolve ability constraints for this function call
        // This matches OldTypeChecker.kt:16483-16581 (resolveAbilitiesForFunctionCall)
        var resolvedAbilities = resolveAbilitiesForCall(updatedFunction)

        // Also check if this is a direct ability method call (e.g., Show.show {val = 42})
        // In this case, we need to resolve based on the argument types, not the function type constraints
        if (resolvedAbilities.isEmpty() && updatedFunction is MemberAccess) {
            resolvedAbilities = resolveDirectAbilityMethodCall(updatedFunction, updatedArguments)
        }

        return expr.copy(
            function = updatedFunction,
            arguments = updatedArguments,
            inferredType = updatedType,
            resolvedAbilities = resolvedAbilities
        )
    }

    /**
     * Resolve a direct ability method call like Show.show {val = 42}.
     * The receiver (Show) is an ability name, and we need to find the implementation
     * based on the argument types.
     */
    private fun resolveDirectAbilityMethodCall(
        memberAccess: MemberAccess,
        arguments: Expression
    ): Map<String, ResolvedAbility> {
        val resolvedAbilities = mutableMapOf<String, ResolvedAbility>()

        // Get the ability name from the receiver
        val abilityName = when (val receiver = memberAccess.receiver) {
            is Identifier -> receiver.name
            is VariantConstruction -> receiver.constructor
            else -> return resolvedAbilities
        }


        // Check if this is actually an ability (not a regular variable)
        // Look for implementations of this ability
        val hasImpl = abilityImpls.values.any { it.abilityName == abilityName }
        if (!hasImpl && !BuiltinAbilities.isBuiltin(abilityName)) {
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
                    // No effects to remove
                    type
                } else {
                    // Remove the handled effects
                    val remainingEffects = effects.effects - effectsToRemove

                    if (remainingEffects.isEmpty()) {
                        // All effects handled - return pure function
                        FunkType.FunctionType(
                            paramType = type.paramType,
                            returnType = type.returnType,
                            effects = null,  // Pure function
                            abilityConstraints = type.abilityConstraints
                        )
                    } else {
                        // Some effects remain
                        FunkType.FunctionType(
                            paramType = type.paramType,
                            returnType = type.returnType,
                            effects = FunkType.EffectRow(remainingEffects),
                            abilityConstraints = type.abilityConstraints
                        )
                    }
                }
            }
            is FunkType.SuspendedType -> {
                // Remove effects from suspended type
                val remainingEffects = type.effects.effects - effectsToRemove

                if (remainingEffects.isEmpty()) {
                    // All effects handled - return pure value
                    type.valueType
                } else {
                    // Some effects remain - still suspended
                    FunkType.SuspendedType(
                        valueType = type.valueType,
                        effects = FunkType.EffectRow(remainingEffects)
                    )
                }
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
                        FunkType.EffectRow(effectRow.effects.toSet())
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
