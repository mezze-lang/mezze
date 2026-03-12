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

            is SeqLiteral -> expr.copy(
                elements = expr.elements.map { element ->
                    when (element) {
                        is SeqRegularElement -> SeqRegularElement(update(element.expression))
                        is SeqSpreadElement -> SeqSpreadElement(update(element.expression))
                    }
                }
            )

            is SeqAccess -> expr.copy(
                seq = update(expr.seq),
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
     * Update a function call with its resolved content hash.
     *
     * Uses structured MonomorphicKey lookup (no string parsing).
     * The content hash is the authoritative identifier for the monomorphic instance.
     */
    private fun updateFunctionCall(expr: FunctionCall): FunctionCall {
        // Get the function name and original content hash (if it's a direct identifier)
        val (functionName, originalContentHash) = when (val func = expr.function) {
            is Identifier -> func.localName to func.resolvedContentHash
            else -> null to null
        }

        // Get the content hash using structured key lookup (no string generation)
        val contentHash = if (functionName != null && expr.typeSubstitution.isNotEmpty()) {
            tracker.getContentHash(functionName, expr.typeSubstitution)
        } else {
            null
        }

        // Update the call with the resolved content hash
        // Use content hash as the resolvedFunctionName too (for backward compatibility)
        val updatedCall = if (contentHash != null) {
            expr.copy(
                resolvedFunctionName = contentHash,  // Use hash as name (no $-delimited strings)
                resolvedFunctionContentHash = contentHash
            )
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
