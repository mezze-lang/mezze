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
    private val constraintResults: Map<String, com.funk.language.typechecker.constraints.ConstraintResult> = emptyMap()
) {
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
        val updatedItems = program.items.map { item ->
            applyToProgramItem(item)
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
        val baseInferredType = if (binding.inferredType == null) {
            // Try to get the type from constraint results
            val bindingName = try {
                (binding.pattern as? VariablePattern)?.name ?: binding.pattern.extractBindings().firstOrNull()
            } catch (e: Exception) {
                null
            }
            bindingName?.let { name ->
                constraintResults[name]?.type
            }
        } else {
            binding.inferredType
        }

        // Apply substitution to get the final type
        val finalType = baseInferredType?.let { substitution.apply(it) }

        // CRITICAL: If the expression is a lambda, set its inferredType BEFORE processing it
        // This allows the lambda to extract parameter types and add them to identifierContext
        val expr = binding.expression
        val expressionWithType = if (expr is LambdaExpression && finalType != null) {
            expr.copy(inferredType = finalType)
        } else {
            expr
        }

        val updatedExpression = applyToExpression(expressionWithType)

        return binding.copy(
            expression = updatedExpression,
            inferredType = finalType
        )
    }

    /**
     * Apply substitution to a type declaration.
     */
    private fun applyToTypeDeclaration(typeDecl: TypeDeclaration): TypeDeclaration {
        val updatedInferredType = typeDecl.inferredType?.let { substitution.apply(it) }

        return typeDecl.copy(
            inferredType = updatedInferredType
        )
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
        val updatedElements = expr.elements.map { applyToExpression(it) }
        // CRITICAL FIX: Compute type based on expression structure
        // Array type is Array<elementType>, where elementType is the type of the first element
        val updatedType = if (updatedElements.isNotEmpty()) {
            val elementType = updatedElements.first().inferredType ?: FunkType.VarType(TypeVar.fresh())
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

        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(entries = updatedEntries, inferredType = updatedType)
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

            val functionType = FunkType.FunctionType(
                paramType = paramType,
                returnType = bodyType
            )
            substitution.apply(functionType)
        }
        return expr.copy(body = updatedBody, inferredType = updatedType)
    }

    private fun applyToFunctionCall(expr: FunctionCall): FunctionCall {
        val updatedFunction = applyToExpression(expr.function)
        val updatedArguments = applyToExpression(expr.arguments)
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(
            function = updatedFunction,
            arguments = updatedArguments,
            inferredType = updatedType
        )
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
        // PerformHandler has name and parameters, not operations
        // The handlers list stays as-is since they don't contain expressions to update
        val updatedType = expr.inferredType?.let { substitution.apply(it) }
        return expr.copy(
            expression = updatedExpression,
            inferredType = updatedType
        )
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
}
