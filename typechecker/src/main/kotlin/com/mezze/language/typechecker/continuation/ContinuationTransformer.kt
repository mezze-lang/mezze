package com.mezze.language.typechecker.continuation

import com.mezze.language.ast.*

/**
 * Transforms continuation-using code into explicit state machines.
 *
 * This transformer converts code that uses continuation operations
 * (capture, suspend, suspend_with) into StateMachineExpression nodes
 * that can be executed by the runtime's state machine infrastructure.
 *
 * ## Key Responsibilities
 *
 * 1. **Detect suspension points** - Find where code suspends
 * 2. **Analyze variable liveness** - Determine what needs to be captured
 * 3. **Transform to states** - Convert sequential code into explicit states
 * 4. **Generate transitions** - Wire up state machine transitions
 *
 * ## Design
 *
 * - Pure functional transformation
 * - Immutable state machine generation
 * - Liveness analysis for efficient variable capture
 * - Handles all expression types
 *
 * ## Example
 *
 * **Input**:
 * ```mezze
 * do {
 *   let x = compute()
 *   Continuation.suspend_with {value = x}
 *   let y = compute2()
 *   y
 * }
 * ```
 *
 * **Output**:
 * ```
 * StateMachine {
 *   states = [
 *     ComputeState(0, compute(), nextState = 1),
 *     YieldState(1, x, capturedVars = ["x"], nextState = 2),
 *     ComputeState(2, compute2(), nextState = 3),
 *     CompleteState(3, y)
 *   ]
 * }
 * ```
 */
class ContinuationTransformer {

    private var stateCounter = 0
    private val continuationDetector = ContinuationDetector()

    /**
     * Transform a DoExpression with continuations into a state machine.
     *
     * This is the main entry point for transformation. It takes a do-block
     * that uses continuation operations and converts it into an explicit
     * state machine.
     *
     * @param doExpr The do-expression to transform
     * @return StateMachineExpression representing the transformed code
     */
    fun transformToStateMachine(doExpr: DoExpression): StateMachineExpression {
        stateCounter = 0
        val states = mutableListOf<StateMachineState>()

        // Analyze which variables are live at each suspension point
        val liveVars = analyzeLiveness(doExpr)

        // Transform statements into states
        transformStatements(doExpr.statements, doExpr.result, states, liveVars)

        // Extract all captured variables - from yield states AND all live variables across states
        // This ensures all variables are available when the continuation is resumed
        val yieldCapturedVars = states.flatMap { state ->
            when (state) {
                is YieldState -> state.capturedVariables
                else -> emptyList()
            }
        }
        val allLiveVars = liveVars.values.flatten()

        // Also collect FREE variables - variables that are used but not defined in the do block
        // These are closure variables from the enclosing scope that need to be captured
        val definedVars = mutableSetOf<String>()
        for (stmt in doExpr.statements) {
            when (stmt) {
                is LetStatement -> {
                    if (stmt.isSimpleBinding) {
                        definedVars.add(stmt.variableName)
                    } else {
                        definedVars.addAll(stmt.pattern.extractBindings())
                    }
                }
                else -> {}
            }
        }
        val usedVars = mutableSetOf<String>()
        for (stmt in doExpr.statements) {
            when (stmt) {
                is LetStatement -> usedVars.addAll(collectUsedVariables(stmt.expression))
                is ExpressionStatement -> usedVars.addAll(collectUsedVariables(stmt.expression))
            }
        }
        usedVars.addAll(collectUsedVariables(doExpr.result))
        val freeVars = usedVars - definedVars

        val allCapturedVars = (yieldCapturedVars + allLiveVars + freeVars).distinct()

        return StateMachineExpression(
            states = states,
            initialState = 0,
            capturedVariables = allCapturedVars,
            inferredType = doExpr.inferredType,
            sourceLocation = doExpr.sourceLocation
        )
    }

    /**
     * Transform with result type (new API for integration with TypeChecker).
     *
     * @param doExpr The do-expression to transform
     * @return ContinuationTransformResult
     */
    fun transform(doExpr: DoExpression): ContinuationTransformResult {
        return try {
            val stateMachine = transformToStateMachine(doExpr)
            ContinuationTransformResult.Success(
                transformedExpression = stateMachine,
                warnings = emptyList()
            )
        } catch (e: Exception) {
            ContinuationTransformResult.Failure(
                errors = listOf(
                    ContinuationTransformError(
                        message = e.message ?: "Unknown error during continuation transformation",
                        expression = doExpr,
                        sourceLocation = doExpr.sourceLocation
                    )
                )
            )
        }
    }

    /**
     * Transform a list of statements and a result expression into state machine states.
     */
    private fun transformStatements(
        statements: List<Statement>,
        result: Expression,
        states: MutableList<StateMachineState>,
        liveVars: Map<Int, Set<String>>
    ) {
        val allItems = statements.map { StatementItem(it) } + ResultItem(result)

        for ((index, item) in allItems.withIndex()) {
            val isLast = index == allItems.size - 1
            val stateId = stateCounter++

            when (item) {
                is StatementItem -> {
                    val stmt = item.statement
                    if (isSuspensionPoint(stmt)) {
                        // Create a yield state for suspension points
                        val yieldState = createYieldStateFromStatement(
                            stmt,
                            stateId,
                            liveVars[index] ?: emptySet(),
                            if (isLast) -1 else stateCounter
                        )
                        states.add(yieldState)
                    } else if (stmt is LetStatement) {
                        // CRITICAL: Let statements must be split into TWO states:
                        // 1. Execute the expression
                        // 2. Bind the value (from input) to the variable
                        //
                        // This ensures that when capture() is called from within the expression,
                        // the captured continuation includes the variable binding step.

                        // State 1: Execute the expression
                        val executeState = ComputeState(
                            id = stateId,
                            computation = stmt.expression,  // Just the expression, no binding yet
                            nextStateId = stateCounter  // Points to binding state
                        )
                        states.add(executeState)

                        // State 2: Bind the value (from input) to the variable
                        val bindStateId = stateCounter++
                        val bindState = ComputeState(
                            id = bindStateId,
                            // Create a DoExpression that binds the variable from input
                            computation = createBindExpression(stmt.pattern, stmt.expression.inferredType),
                            nextStateId = if (isLast) -1 else stateCounter
                        )
                        states.add(bindState)
                    } else {
                        // Create a compute state for regular statements
                        val computeState = ComputeState(
                            id = stateId,
                            computation = statementToExpression(stmt),
                            nextStateId = if (isLast) -1 else stateCounter
                        )
                        states.add(computeState)
                    }
                }
                is ResultItem -> {
                    if (continuationDetector.usesContinuations(item.expression)) {
                        // Result expression uses continuations
                        if (isSuspensionExpression(item.expression)) {
                            val yieldState = createYieldStateFromExpression(
                                item.expression,
                                stateId,
                                liveVars[index] ?: emptySet(),
                                -1  // Terminal
                            )
                            states.add(yieldState)
                        } else {
                            val computeState = ComputeState(
                                id = stateId,
                                computation = item.expression,
                                nextStateId = -1
                            )
                            states.add(computeState)
                        }
                    } else {
                        // Final result - use CompleteState
                        val completeState = CompleteState(
                            id = stateId,
                            resultExpression = item.expression
                        )
                        states.add(completeState)
                    }
                }
            }
        }
    }

    /**
     * Check if a statement is a suspension point.
     */
    private fun isSuspensionPoint(stmt: Statement): Boolean {
        return when (stmt) {
            is LetStatement -> isSuspensionExpression(stmt.expression)
            is ExpressionStatement -> isSuspensionExpression(stmt.expression)
        }
    }

    /**
     * Check if an expression is a suspension operation.
     */
    private fun isSuspensionExpression(expr: Expression): Boolean {
        return when (expr) {
            is FunctionCall -> {
                isSuspensionPrimitive(expr.function)
            }
            else -> false
        }
    }

    /**
     * Check if a function expression is a suspension primitive.
     */
    private fun isSuspensionPrimitive(funcExpr: Expression): Boolean {
        return when (funcExpr) {
            is Identifier -> funcExpr.localName in suspensionPrimitives
            is MemberAccess -> {
                // Match method calls like cont.suspend_with or Continuation.suspend_with
                funcExpr.member in setOf("suspend", "suspend_with")
            }
            else -> false
        }
    }

    /**
     * Create a yield state from a statement.
     */
    private fun createYieldStateFromStatement(
        stmt: Statement,
        stateId: Int,
        capturedVars: Set<String>,
        nextStateId: Int
    ): YieldState {
        val valueExpr = when (stmt) {
            is LetStatement -> extractSuspensionValue(stmt.expression)
            is ExpressionStatement -> extractSuspensionValue(stmt.expression)
        }

        return YieldState(
            id = stateId,
            valueExpression = valueExpr,
            capturedVariables = capturedVars.toList(),
            nextStateId = nextStateId
        )
    }

    /**
     * Create a yield state from an expression.
     */
    private fun createYieldStateFromExpression(
        expr: Expression,
        stateId: Int,
        capturedVars: Set<String>,
        nextStateId: Int
    ): YieldState {
        return YieldState(
            id = stateId,
            valueExpression = extractSuspensionValue(expr),
            capturedVariables = capturedVars.toList(),
            nextStateId = nextStateId
        )
    }

    /**
     * Extract the value being suspended with from a suspension expression.
     */
    private fun extractSuspensionValue(expr: Expression): Expression {
        return when (expr) {
            is FunctionCall -> {
                // Check if this is a suspend_with call
                val funcName = when (val func = expr.function) {
                    is Identifier -> func.localName
                    is MemberAccess -> func.member
                    else -> null
                }

                if (funcName == "__native_continuation_suspend_with" || funcName == "suspend_with") {
                    // Extract 'value' field from the record argument
                    val argRecord = expr.arguments as? RecordLiteral
                    argRecord?.findField("value")?.value ?: IntLiteral(
                        value = 0,
                        inferredType = null,
                        sourceLocation = null
                    )
                } else {
                    // suspend without value - use unit-like value
                    RecordLiteral(
                        entries = emptyList(),
                        restField = null,
                        inferredType = null,
                        sourceLocation = null
                    )
                }
            }
            else -> expr
        }
    }

    /**
     * Create an expression that binds a pattern to the state machine input value.
     *
     * This is used when splitting LetStatements with continuation calls into two states.
     * The binding state receives the value as input and binds it to the pattern.
     *
     * For simple variable patterns like `result`, this creates:
     * ```
     * do
     *     let result = __input
     *     {}
     * ```
     * Where __input is a special identifier that the runtime will replace with the actual input value.
     */
    private fun createBindExpression(pattern: Pattern, valueType: MezzeType?): Expression {
        // Create a placeholder identifier for the input value
        // The runtime will need to handle this specially
        val inputIdentifier = Identifier(localName = "__state_machine_input__", inferredType = valueType)

        // Create a let statement that binds the pattern to the input
        val letStmt = LetStatement(
            pattern = pattern,
            typeAnnotation = null,
            expression = inputIdentifier
        )

        // Wrap in a DoExpression that binds and returns unit
        val unitExpr = RecordLiteral(
            entries = emptyList(),
            restField = null,
            inferredType = null,
            sourceLocation = null
        )
        return DoExpression(
            statements = listOf(letStmt),
            result = unitExpr,
            inferredType = unitExpr.inferredType,
            sourceLocation = null
        )
    }

    /**
     * Convert a statement to an expression for compute states.
     *
     * CRITICAL: For LetStatements, we must preserve the variable binding!
     * Otherwise variables assigned from effect calls will be undefined when
     * continuations are resumed.
     */
    private fun statementToExpression(stmt: Statement): Expression {
        return when (stmt) {
            is LetStatement -> {
                // Wrap the let statement in a DoExpression to preserve the binding
                // This ensures that when the state executes, the variable gets bound to the frame
                //
                // Example: `let x = foo()` becomes:
                //   do
                //       let x = foo()
                //       {}
                // This way, x is properly bound when the state executes
                val unitExpr = RecordLiteral(
                    entries = emptyList(),
                    restField = null,
                    inferredType = null,
                    sourceLocation = null
                )
                DoExpression(
                    statements = listOf(stmt),
                    result = unitExpr,  // Return unit
                    inferredType = unitExpr.inferredType,  // Unit type
                    sourceLocation = stmt.expression.sourceLocation  // Use expression's location
                )
            }
            is ExpressionStatement -> stmt.expression
        }
    }

    /**
     * Analyze variable liveness for each statement.
     * Returns a map from statement index to set of live variables at that point.
     */
    private fun analyzeLiveness(doExpr: DoExpression): Map<Int, Set<String>> {
        val liveVars = mutableMapOf<Int, MutableSet<String>>()
        val allItems = doExpr.statements.size + 1  // +1 for result expression

        // Initialize all positions with empty sets
        for (i in 0 until allItems) {
            liveVars[i] = mutableSetOf()
        }

        // Backward pass: collect variables used after each point
        var varsLiveAfter = mutableSetOf<String>()

        // Start with result expression
        varsLiveAfter.addAll(collectUsedVariables(doExpr.result))
        liveVars[doExpr.statements.size] = varsLiveAfter.toMutableSet()

        // Process statements in reverse order
        for (i in doExpr.statements.indices.reversed()) {
            val stmt = doExpr.statements[i]

            // Variables used in this statement
            val varsUsedHere = when (stmt) {
                is LetStatement -> collectUsedVariables(stmt.expression)
                is ExpressionStatement -> collectUsedVariables(stmt.expression)
            }

            // Variables defined by this statement
            val varsDefined = when (stmt) {
                is LetStatement -> {
                    if (stmt.isSimpleBinding) {
                        setOf(stmt.variableName)
                    } else {
                        stmt.pattern.extractBindings().toSet()
                    }
                }
                else -> emptySet()
            }

            // Update live variables
            varsLiveAfter.addAll(varsUsedHere)
            varsLiveAfter.removeAll(varsDefined)

            liveVars[i] = varsLiveAfter.toMutableSet()
        }

        return liveVars
    }

    /**
     * Collect all variable references in an expression.
     */
    private fun collectUsedVariables(expr: Expression): Set<String> {
        val variables = mutableSetOf<String>()
        collectUsedVariablesRecursive(expr, variables)
        return variables
    }

    private fun collectUsedVariablesRecursive(expr: Expression, collector: MutableSet<String>) {
        when (expr) {
            is Identifier -> collector.add(expr.localName)
            is FunctionCall -> {
                collectUsedVariablesRecursive(expr.function, collector)
                collectUsedVariablesRecursive(expr.arguments, collector)
            }
            is RecordLiteral -> {
                expr.getFieldEntries().forEach { entry ->
                    entry.value?.let { collectUsedVariablesRecursive(it, collector) }
                }
                expr.getSpreadEntries().forEach { entry ->
                    collectUsedVariablesRecursive(entry.expression, collector)
                }
            }
            is MemberAccess -> {
                collectUsedVariablesRecursive(expr.receiver, collector)
            }
            is DoExpression -> {
                expr.statements.forEach { stmt ->
                    when (stmt) {
                        is LetStatement -> collectUsedVariablesRecursive(stmt.expression, collector)
                        is ExpressionStatement -> collectUsedVariablesRecursive(stmt.expression, collector)
                    }
                }
                collectUsedVariablesRecursive(expr.result, collector)
            }
            is IfExpression -> {
                collectUsedVariablesRecursive(expr.condition, collector)
                collectUsedVariablesRecursive(expr.thenBranch, collector)
                collectUsedVariablesRecursive(expr.elseBranch, collector)
            }
            is MatchExpression -> {
                collectUsedVariablesRecursive(expr.expression, collector)
                expr.cases.forEach { case ->
                    collectUsedVariablesRecursive(case.expression, collector)
                    case.guard?.let { collectUsedVariablesRecursive(it, collector) }
                }
            }
            is LambdaExpression -> {
                // Lambda bodies encapsulate their own scope.
                // Only collect FREE variables of the lambda (used but not defined inside).
                val lambdaParams = when (val params = expr.parameters) {
                    is UntypedParameters -> params.names.toSet()
                    is TypedParameters -> params.recordType.entries.map { it.name }.toSet()
                }
                // Collect variables from lambda body
                val bodyVars = mutableSetOf<String>()
                collectUsedVariablesRecursive(expr.body, bodyVars)
                // Collect variables defined inside the lambda body (let bindings in do blocks)
                val definedInBody = collectDefinedVariables(expr.body)
                // Only include FREE variables (not lambda params, not defined inside)
                val freeVars = bodyVars - lambdaParams - definedInBody
                collector.addAll(freeVars)
            }
            is VariantConstruction -> {
                expr.value?.let { collectUsedVariablesRecursive(it, collector) }
            }
            is SeqLiteral -> {
                expr.elements.forEach { element ->
                    when (element) {
                        is SeqRegularElement -> collectUsedVariablesRecursive(element.expression, collector)
                        is SeqSpreadElement -> collectUsedVariablesRecursive(element.expression, collector)
                    }
                }
            }
            is SeqAccess -> {
                collectUsedVariablesRecursive(expr.seq, collector)
                collectUsedVariablesRecursive(expr.index, collector)
            }
            is RecordUpdate -> {
                collectUsedVariablesRecursive(expr.record, collector)
                expr.updates.forEach { update ->
                    update.value?.let { collectUsedVariablesRecursive(it, collector) }
                }
            }
            is RecordUpdateExpression -> {
                collectUsedVariablesRecursive(expr.baseRecord, collector)
                expr.updates.forEach { update ->
                    collectUsedVariablesRecursive(update.value, collector)
                }
            }
            is PerformExpression -> {
                collectUsedVariablesRecursive(expr.expression, collector)
            }
            is EffectCallExpression -> {
                collectUsedVariablesRecursive(expr.arguments, collector)
            }
            is StringLiteral -> {
                expr.parts?.forEach { part ->
                    if (part is ExpressionPart) {
                        collectUsedVariablesRecursive(part.expression, collector)
                    }
                }
            }
            // Literals don't use variables
            is IntLiteral, is NatLiteral, is FloatLiteral, is BooleanLiteral,
            is FractionLiteral, is PlaceholderExpression -> {}
            // Parenthesized - recurse into inner
            is ParenthesizedExpression -> collectUsedVariablesRecursive(expr.expression, collector)
            // State machines
            is StateMachineExpression -> {
                expr.states.forEach { state ->
                    when (state) {
                        is ComputeState -> collectUsedVariablesRecursive(state.computation, collector)
                        is YieldState -> collectUsedVariablesRecursive(state.valueExpression, collector)
                        is CompleteState -> collectUsedVariablesRecursive(state.resultExpression, collector)
                    }
                }
            }
        }
    }

    /**
     * Collect all variables defined (bound) within an expression.
     * This includes let bindings in do blocks, match pattern bindings, etc.
     */
    private fun collectDefinedVariables(expr: Expression): Set<String> {
        val defined = mutableSetOf<String>()
        collectDefinedVariablesRecursive(expr, defined)
        return defined
    }

    private fun collectDefinedVariablesRecursive(expr: Expression, collector: MutableSet<String>) {
        when (expr) {
            is DoExpression -> {
                for (stmt in expr.statements) {
                    when (stmt) {
                        is LetStatement -> {
                            // Add variables bound by this let
                            collector.addAll(stmt.pattern.extractBindings())
                            // Also recurse into the expression (might have nested do blocks)
                            collectDefinedVariablesRecursive(stmt.expression, collector)
                        }
                        is ExpressionStatement -> {
                            collectDefinedVariablesRecursive(stmt.expression, collector)
                        }
                    }
                }
                collectDefinedVariablesRecursive(expr.result, collector)
            }
            is IfExpression -> {
                collectDefinedVariablesRecursive(expr.thenBranch, collector)
                collectDefinedVariablesRecursive(expr.elseBranch, collector)
            }
            is MatchExpression -> {
                expr.cases.forEach { case ->
                    // Match patterns bind variables in each branch
                    case.patterns.forEach { pattern ->
                        collector.addAll(pattern.extractBindings())
                    }
                    collectDefinedVariablesRecursive(case.expression, collector)
                }
            }
            is LambdaExpression -> {
                // Don't recurse into nested lambdas - their bindings are their own scope
            }
            is FunctionCall -> {
                collectDefinedVariablesRecursive(expr.arguments, collector)
            }
            is RecordLiteral -> {
                expr.getFieldEntries().forEach { entry ->
                    entry.value?.let { collectDefinedVariablesRecursive(it, collector) }
                }
            }
            is SeqLiteral -> {
                expr.elements.forEach { element ->
                    when (element) {
                        is SeqRegularElement -> collectDefinedVariablesRecursive(element.expression, collector)
                        is SeqSpreadElement -> collectDefinedVariablesRecursive(element.expression, collector)
                    }
                }
            }
            else -> {} // Other expressions don't define variables
        }
    }

    companion object {
        /**
         * Suspension primitive function names.
         */
        private val suspensionPrimitives = setOf(
            "__native_continuation_suspend",
            "__native_continuation_suspend_with"
        )

        /**
         * Create a continuation transformer.
         */
        fun create(): ContinuationTransformer {
            return ContinuationTransformer()
        }
    }
}

/**
 * Helper sealed class to represent items in a do-block (statements + result).
 */
private sealed class DoBlockItem
private data class StatementItem(val statement: Statement) : DoBlockItem()
private data class ResultItem(val expression: Expression) : DoBlockItem()
