package com.mezze.language.ast.hashing

import com.mezze.language.ast.*

/**
 * Alpha-renames internal bindings (let/match) for content-addressable hashing.
 *
 * ## Mezze-Specific Design
 *
 * In Mezze, function parameters are record fields (named parameters):
 * - `let add = {x, y} -> x + y` is called with `add {x = 1, y = 2}`
 * - Parameter names ARE part of the API
 *
 * Therefore:
 * - `{x, y} -> x + y` and `{a, b} -> a + b` have DIFFERENT hashes (different API)
 * - Only INTERNAL bindings (let/match) are alpha-renamed
 *
 * ## What Gets Renamed
 *
 * Internal bindings that are renamed:
 * - `let x = ...` statements inside do blocks
 * - Match pattern bindings (case variables)
 * - Array rest pattern bindings (`[head, ...tail]`)
 *
 * What is NOT renamed (part of API):
 * - Lambda parameters (`{x, y} -> ...`)
 * - Record field names
 * - Top-level let binding names
 *
 * ## De Bruijn Indices
 *
 * Internal bindings are renamed to positional indices:
 * ```
 * {x, y} -> do {          // x, y: API names, kept
 *     let z = x + y       // z -> #0
 *     let w = z * 2       // w -> #1
 *     w                   // #1
 * }
 * ```
 *
 * ## Thread Safety
 *
 * Each call to `rename` creates a fresh environment. Thread-safe when
 * using different instances or calling `rename` from different threads.
 */
class InternalBindingRenamer {
    /**
     * Environment mapping original names to De Bruijn indices.
     * Only contains internal bindings, not parameters.
     */
    private val env = mutableMapOf<String, String>()

    /** Counter for generating De Bruijn indices */
    private var counter = 0

    /**
     * Names that are parameters (should NOT be renamed).
     * These are added when entering a lambda scope.
     */
    private val parameterNames = mutableSetOf<String>()

    /**
     * Rename internal bindings in an expression.
     *
     * @param expr The expression to rename
     * @return Expression with internal bindings renamed to De Bruijn indices
     */
    fun rename(expr: Expression): Expression {
        // Reset state for new rename operation
        env.clear()
        parameterNames.clear()
        counter = 0
        return renameExpr(expr)
    }

    private fun renameExpr(expr: Expression): Expression = when (expr) {
        // Literals - no bindings
        is IntLiteral -> expr
        is NatLiteral -> expr
        is FloatLiteral -> expr
        is StringLiteral -> expr.copy(
            parts = expr.parts?.map { renameStringPart(it) }
        )
        is BooleanLiteral -> expr
        is FractionLiteral -> expr

        // Identifiers - rename if internal binding
        is Identifier -> {
            val renamed = env[expr.name]
            if (renamed != null) {
                expr.copy(name = renamed)
            } else {
                expr  // External reference or parameter - keep as-is
            }
        }

        is PlaceholderExpression -> expr

        // Arrays
        is ArrayLiteral -> expr.copy(
            elements = expr.elements.map { renameArrayElement(it) }
        )

        is ArrayAccess -> expr.copy(
            array = renameExpr(expr.array),
            index = renameExpr(expr.index)
        )

        // Records
        is RecordLiteral -> expr.copy(
            entries = expr.entries.map { renameRecordEntry(it) }
        )

        is MemberAccess -> expr.copy(
            receiver = renameExpr(expr.receiver)
            // member name is structural, keep as-is
        )

        is RecordUpdate -> expr.copy(
            record = renameExpr(expr.record),
            updates = expr.updates.map { entry ->
                RecordEntry(entry.name, entry.value?.let { renameExpr(it) })
            }
        )

        is RecordUpdateExpression -> expr.copy(
            baseRecord = renameExpr(expr.baseRecord),
            updates = expr.updates.map { entry ->
                RecordUpdateEntry(entry.fieldPath, renameExpr(entry.value))
            }
        )

        // Variants
        is VariantConstruction -> expr.copy(
            value = expr.value?.let { renameExpr(it) }
        )

        // Lambdas - CRITICAL: Don't rename parameters!
        is LambdaExpression -> renameLambda(expr)

        // Function calls
        is FunctionCall -> expr.copy(
            function = renameExpr(expr.function),
            arguments = renameExpr(expr.arguments)
        )

        // Control flow
        is IfExpression -> expr.copy(
            condition = renameExpr(expr.condition),
            thenBranch = renameExpr(expr.thenBranch),
            elseBranch = renameExpr(expr.elseBranch)
        )

        is MatchExpression -> renameMatch(expr)

        // Blocks - where let bindings are renamed
        is DoExpression -> renameDoExpression(expr)

        // Effects
        is PerformExpression -> expr.copy(
            expression = renameExpr(expr.expression),
            handlers = expr.handlers.map { handler ->
                PerformHandler(
                    handler.name,
                    handler.parameters?.let { renameExpr(it) as RecordLiteral }
                )
            }
        )

        is EffectCallExpression -> expr.copy(
            arguments = renameExpr(expr.arguments)
        )

        // State machines
        is StateMachineExpression -> renameStateMachine(expr)
    }

    /**
     * Rename a lambda expression.
     * Parameters are NOT renamed - they're part of the API.
     */
    private fun renameLambda(lambda: LambdaExpression): LambdaExpression {
        // Save current environment
        val savedEnv = env.toMap()
        val savedParams = parameterNames.toSet()

        // Add parameter names to the "do not rename" set
        val params = extractParameterNames(lambda.parameters)
        parameterNames.addAll(params)

        // Rename body
        val newBody = renameExpr(lambda.body)

        // Restore environment
        env.clear()
        env.putAll(savedEnv)
        parameterNames.clear()
        parameterNames.addAll(savedParams)

        return lambda.copy(body = newBody)
    }

    /**
     * Extract parameter names from lambda parameters.
     */
    private fun extractParameterNames(params: LambdaParameters): List<String> = when (params) {
        is UntypedParameters -> params.names
        is TypedParameters -> params.recordType.entries.map { it.name }
    }

    /**
     * Rename a match expression.
     * Pattern bindings ARE renamed (internal bindings).
     */
    private fun renameMatch(match: MatchExpression): MatchExpression {
        val newExpr = renameExpr(match.expression)
        val newCases = match.cases.map { case ->
            // Save environment for each case
            val savedEnv = env.toMap()
            val savedCounter = counter

            // Add pattern bindings
            for (pattern in case.patterns) {
                addPatternBindings(pattern)
            }

            // Rename patterns while env still has the bindings
            val newPatterns = case.patterns.map { renamePattern(it) }

            // Rename guard and body
            val newGuard = case.guard?.let { renameExpr(it) }
            val newBody = renameExpr(case.expression)

            // Restore environment (but keep counter for next case)
            env.clear()
            env.putAll(savedEnv)

            MatchCase(
                patterns = newPatterns,
                guard = newGuard,
                expression = newBody
            )
        }

        return match.copy(
            expression = newExpr,
            cases = newCases
        )
    }

    /**
     * Rename a do expression.
     * Let bindings ARE renamed (internal bindings).
     */
    private fun renameDoExpression(doExpr: DoExpression): DoExpression {
        // Process statements sequentially, adding bindings
        val newStatements = doExpr.statements.map { stmt ->
            when (stmt) {
                is LetStatement -> {
                    // Rename the expression first (uses current env)
                    val newExpr = renameExpr(stmt.expression)

                    // Add the binding to environment
                    addPatternBindings(stmt.pattern)

                    // Rename the pattern
                    val newPattern = renamePattern(stmt.pattern)

                    LetStatement(newPattern, stmt.typeAnnotation, newExpr)
                }
                is ExpressionStatement -> {
                    ExpressionStatement(renameExpr(stmt.expression))
                }
            }
        }

        val newResult = renameExpr(doExpr.result)

        return doExpr.copy(
            statements = newStatements,
            result = newResult
        )
    }

    /**
     * Rename a state machine expression.
     */
    private fun renameStateMachine(sm: StateMachineExpression): StateMachineExpression {
        // Captured variables are references to existing bindings
        // States contain expressions that need renaming
        val newStates = sm.states.map { state ->
            when (state) {
                is ComputeState -> ComputeState(
                    id = state.id,
                    computation = renameExpr(state.computation),
                    nextStateId = state.nextStateId
                )
                is YieldState -> YieldState(
                    id = state.id,
                    valueExpression = renameExpr(state.valueExpression),
                    capturedVariables = state.capturedVariables.map { name ->
                        env[name] ?: name
                    },
                    nextStateId = state.nextStateId
                )
                is CompleteState -> CompleteState(
                    id = state.id,
                    resultExpression = renameExpr(state.resultExpression)
                )
            }
        }

        return sm.copy(
            states = newStates,
            capturedVariables = sm.capturedVariables.map { name ->
                env[name] ?: name
            }
        )
    }

    /**
     * Add bindings from a pattern to the environment.
     */
    private fun addPatternBindings(pattern: Pattern) {
        when (pattern) {
            is VariablePattern -> {
                // Only rename if not a parameter
                if (pattern.name !in parameterNames) {
                    val index = "#$counter"
                    env[pattern.name] = index
                    counter++
                }
            }
            is WildcardPattern -> {
                // No binding
            }
            is ConstructorPattern -> {
                pattern.recordPattern?.let { addPatternBindings(it) }
            }
            is RecordPattern -> {
                for (entry in pattern.entries) {
                    if (entry.pattern != null) {
                        addPatternBindings(entry.pattern)
                    } else {
                        // Shorthand {x} - the field name becomes a binding
                        if (entry.name !in parameterNames) {
                            val index = "#$counter"
                            env[entry.name] = index
                            counter++
                        }
                    }
                }
            }
            is LiteralPattern -> {
                // No binding
            }
            is ArrayPattern -> {
                for (elem in pattern.elements) {
                    addPatternBindings(elem)
                }
                pattern.restPattern?.let { name ->
                    if (name !in parameterNames) {
                        val index = "#$counter"
                        env[name] = index
                        counter++
                    }
                }
            }
            is StringInterpolationPattern -> {
                // Add bindings for capture variables
                for (part in pattern.parts) {
                    when (part) {
                        is StringPatternCapture -> {
                            if (part.variableName !in parameterNames) {
                                val index = "#$counter"
                                env[part.variableName] = index
                                counter++
                            }
                        }
                        is StringPatternLiteral -> {
                            // No binding
                        }
                    }
                }
            }
        }
    }

    /**
     * Rename pattern variable names to De Bruijn indices.
     */
    private fun renamePattern(pattern: Pattern): Pattern = when (pattern) {
        is VariablePattern -> {
            val renamed = env[pattern.name]
            if (renamed != null) {
                VariablePattern(renamed)
            } else {
                pattern  // Parameter or external - keep as-is
            }
        }
        is WildcardPattern -> pattern
        is ConstructorPattern -> ConstructorPattern(
            constructor = pattern.constructor,
            recordPattern = pattern.recordPattern?.let { renamePattern(it) as RecordPattern }
        )
        is RecordPattern -> RecordPattern(
            entries = pattern.entries.map { entry ->
                RecordPatternEntry(
                    name = entry.name,  // Field names are structural, keep as-is
                    pattern = entry.pattern?.let { renamePattern(it) }
                )
            },
            isOpen = pattern.isOpen
        )
        is LiteralPattern -> pattern
        is ArrayPattern -> ArrayPattern(
            elements = pattern.elements.map { renamePattern(it) },
            restPattern = pattern.restPattern?.let { name ->
                env[name] ?: name
            }
        )
        is StringInterpolationPattern -> StringInterpolationPattern(
            parts = pattern.parts.map { part ->
                when (part) {
                    is StringPatternCapture -> {
                        val renamed = env[part.variableName]
                        if (renamed != null) {
                            StringPatternCapture(renamed)
                        } else {
                            part  // Parameter or external - keep as-is
                        }
                    }
                    is StringPatternLiteral -> part
                }
            },
            sourceLocation = pattern.sourceLocation
        )
    }

    private fun renameStringPart(part: StringInterpolationPart): StringInterpolationPart = when (part) {
        is StringPart -> part
        is ExpressionPart -> ExpressionPart(renameExpr(part.expression))
    }

    private fun renameArrayElement(elem: ArrayElement): ArrayElement = when (elem) {
        is ArrayRegularElement -> ArrayRegularElement(renameExpr(elem.expression))
        is ArraySpreadElement -> ArraySpreadElement(renameExpr(elem.expression))
    }

    private fun renameRecordEntry(entry: RecordLiteralEntry): RecordLiteralEntry = when (entry) {
        is RecordFieldEntry -> RecordFieldEntry(
            name = entry.name,  // Field names are API - keep as-is
            value = entry.value?.let { renameExpr(it) }
        )
        is RecordSpreadEntry -> RecordSpreadEntry(renameExpr(entry.expression))
    }

    companion object {
        /**
         * Create a new renamer and rename the expression.
         * Convenience method for one-shot usage.
         */
        fun renameBindings(expr: Expression): Expression {
            return InternalBindingRenamer().rename(expr)
        }

        /**
         * Rename bindings in a let binding's expression.
         */
        fun renameLetBinding(binding: LetBinding): LetBinding {
            val renamer = InternalBindingRenamer()
            return binding.copy(
                expression = renamer.rename(binding.expression)
            )
        }

        /**
         * Rename bindings in an ability implementation operation.
         */
        fun renameImplOperation(op: ImplAbilityOperation): ImplAbilityOperation {
            return ImplAbilityOperation(
                name = op.name,
                paramType = op.paramType,
                returnType = op.returnType,
                implementation = renameBindings(op.implementation)
            )
        }

        /**
         * Rename bindings in an effect handler operation.
         */
        fun renamePerformerOperation(op: PerformerOperation): PerformerOperation {
            return PerformerOperation(
                name = op.name,
                paramType = op.paramType,
                returnType = op.returnType,
                implementation = renameBindings(op.implementation),
                effects = op.effects
            )
        }
    }
}
