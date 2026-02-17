package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.*

/**
 * Call site updater - updates function calls with monomorphic names.
 *
 * When a polymorphic function is called with concrete types, the call site
 * needs to be updated with the resolved monomorphic function name.
 *
 * Design:
 * - Pure functions (creates new AST, doesn't mutate!)
 * - Uses tracker to determine which calls need updating
 * - Sets resolvedFunctionName on FunctionCall nodes
 * - Recursively updates all nested expressions
 */
class CallSiteUpdater(
    private val tracker: InstanceTracker
) {
    /**
     * Update all function calls in an expression.
     *
     * Returns a new expression with resolved function names.
     */
    fun update(expr: Expression): Expression {
        return when (expr) {
            is FunctionCall -> updateFunctionCall(expr)

            is LambdaExpression -> expr.copy(
                body = update(expr.body)
            )

            is DoExpression -> expr.copy(
                statements = expr.statements.map { stmt ->
                    when (stmt) {
                        is LetStatement -> stmt.copy(
                            expression = update(stmt.expression)
                        )
                        is ExpressionStatement -> stmt.copy(
                            expression = update(stmt.expression)
                        )
                    }
                },
                result = update(expr.result)
            )

            is IfExpression -> expr.copy(
                condition = update(expr.condition),
                thenBranch = update(expr.thenBranch),
                elseBranch = update(expr.elseBranch)
            )

            is MatchExpression -> expr.copy(
                expression = update(expr.expression),
                cases = expr.cases.map { case ->
                    case.copy(
                        expression = update(case.expression),
                        guard = case.guard?.let { update(it) }
                    )
                }
            )

            is RecordLiteral -> expr.copy(
                entries = expr.entries.map { entry ->
                    when (entry) {
                        is RecordFieldEntry -> entry.copy(
                            value = entry.value?.let { update(it) }
                        )
                        is RecordSpreadEntry -> entry.copy(
                            expression = update(entry.expression)
                        )
                    }
                }
            )

            is MemberAccess -> expr.copy(
                receiver = update(expr.receiver)
            )

            is VariantConstruction -> expr.copy(
                value = expr.value?.let { update(it) }
            )

            is RecordUpdate -> expr.copy(
                record = update(expr.record),
                updates = expr.updates.map { recordUpdate ->
                    recordUpdate.copy(
                        value = recordUpdate.value?.let { update(it) }
                    )
                }
            )

            is RecordUpdateExpression -> expr.copy(
                baseRecord = update(expr.baseRecord),
                updates = expr.updates.map { recordUpdate ->
                    recordUpdate.copy(
                        value = update(recordUpdate.value)
                    )
                }
            )

            is ArrayLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is ArrayRegularElement -> ArrayRegularElement(update(element.expression))
                        is ArraySpreadElement -> ArraySpreadElement(update(element.expression))
                    }
                }
            )

            is ArrayAccess -> expr.copy(
                array = update(expr.array),
                index = update(expr.index)
            )

            is StringLiteral -> {
                val updatedParts = expr.parts?.map { part ->
                    when (part) {
                        is ExpressionPart -> ExpressionPart(update(part.expression))
                        else -> part
                    }
                }
                if (updatedParts != null) {
                    expr.copy(parts = updatedParts)
                } else {
                    expr
                }
            }

            is PerformExpression -> expr.copy(
                expression = update(expr.expression)
            )

            is EffectCallExpression -> expr.copy(
                arguments = update(expr.arguments)
            )

            is StateMachineExpression -> expr.copy(
                states = expr.states.map { state ->
                    when (state) {
                        is ComputeState -> state.copy(
                            computation = update(state.computation)
                        )
                        is YieldState -> state.copy(
                            valueExpression = update(state.valueExpression)
                        )
                        is CompleteState -> state.copy(
                            resultExpression = update(state.resultExpression)
                        )
                    }
                }
            )

            // Literals and identifiers don't need updating
            else -> expr
        }
    }

    /**
     * Update a function call with its resolved monomorphic name.
     */
    private fun updateFunctionCall(expr: FunctionCall): FunctionCall {
        // Get the function name (if it's a direct identifier)
        val functionName = when (val func = expr.function) {
            is Identifier -> func.name
            else -> null
        }

        // Check if this call needs a monomorphic name
        val monomorphicName = if (functionName != null && expr.typeSubstitution.isNotEmpty()) {
            tracker.getMonomorphicName(functionName, expr.typeSubstitution)
        } else {
            null
        }

        // Update the call with the resolved name
        val updatedCall = if (monomorphicName != null) {
            expr.copy(resolvedFunctionName = monomorphicName)
        } else {
            expr
        }

        // Recursively update nested expressions
        return updatedCall.copy(
            function = update(updatedCall.function),
            arguments = update(updatedCall.arguments)
        )
    }

    /**
     * Update all function calls in a program.
     *
     * Returns a new program with all call sites updated.
     */
    fun updateProgram(program: Program): Program {
        val updatedItems = program.items.map { item ->
            when (item) {
                is LetBinding -> item.copy(
                    expression = update(item.expression)
                )
                is TopLevelExpression -> item.copy(
                    expression = update(item.expression)
                )
                is ImplDeclaration -> item.copy(
                    operations = item.operations.map { op ->
                        op.copy(implementation = update(op.implementation))
                    }
                )
                is ImplAbilityDeclaration -> item.copy(
                    operations = item.operations.map { op ->
                        op.copy(implementation = update(op.implementation))
                    }
                )
                else -> item
            }
        }

        return program.copy(items = updatedItems)
    }
}
