package com.mezze.language.typechecker.continuation

import com.mezze.language.ast.*

/**
 * Detects code blocks that use continuations and need transformation.
 *
 * This detector identifies expressions that use continuation operations
 * (capture, suspend, suspend_with) or delimited effect handlers. Code
 * containing these operations needs to be transformed into explicit
 * state machines by the ContinuationTransformer.
 *
 * ## Design
 * - Pure recursive traversal
 * - No mutation
 * - Exhaustive pattern matching
 * - Fast detection (stops on first continuation found)
 *
 * ## Usage
 * ```kotlin
 * val detector = ContinuationDetector()
 * if (detector.usesContinuations(expression)) {
 *     // Transform to state machine
 * }
 * ```
 */
class ContinuationDetector {

    /**
     * Check if an expression uses continuation operations.
     * Returns true if the expression or any of its sub-expressions
     * uses continuation primitives or delimited performers.
     */
    fun usesContinuations(expr: Expression): Boolean {
        return when (expr) {
            is PerformExpression -> {
                // Check if this perform uses continuations
                usesContinuations(expr.expression)
            }
            is FunctionCall -> {
                // Check if calling continuation primitives directly
                isContinuationPrimitive(expr.function) ||
                    usesContinuations(expr.function) ||
                    usesContinuations(expr.arguments)
            }
            is DoExpression -> {
                // Check all statements and the result expression
                expr.statements.any { usesContinuations(it) } ||
                    usesContinuations(expr.result)
            }
            is IfExpression -> {
                usesContinuations(expr.condition) ||
                    usesContinuations(expr.thenBranch) ||
                    usesContinuations(expr.elseBranch)
            }
            is MatchExpression -> {
                usesContinuations(expr.expression) ||
                    expr.cases.any { usesContinuations(it.expression) ||
                        (it.guard?.let { usesContinuations(it) } ?: false) }
            }
            is LambdaExpression -> {
                // Lambda bodies may contain continuations
                usesContinuations(expr.body)
            }
            is RecordLiteral -> {
                // Check all field values
                expr.getFieldEntries().any { entry ->
                    entry.value?.let { usesContinuations(it) } ?: false
                } || expr.getSpreadEntries().any { usesContinuations(it.expression) }
            }
            is MemberAccess -> {
                usesContinuations(expr.receiver)
            }
            is RecordUpdate -> {
                usesContinuations(expr.record) ||
                    expr.updates.any { it.value?.let { usesContinuations(it) } ?: false }
            }
            is RecordUpdateExpression -> {
                usesContinuations(expr.baseRecord) ||
                    expr.updates.any { usesContinuations(it.value) }
            }
            is VariantConstruction -> {
                expr.value?.let { usesContinuations(it) } ?: false
            }
            is SeqLiteral -> {
                expr.elements.any { element ->
                    when (element) {
                        is SeqRegularElement -> usesContinuations(element.expression)
                        is SeqSpreadElement -> usesContinuations(element.expression)
                    }
                }
            }
            is SeqAccess -> {
                usesContinuations(expr.seq) || usesContinuations(expr.index)
            }
            is StringLiteral -> {
                // Check interpolated expressions
                expr.parts?.any { part ->
                    when (part) {
                        is ExpressionPart -> usesContinuations(part.expression)
                        else -> false
                    }
                } ?: false
            }
            is EffectCallExpression -> {
                // Effect calls don't use continuations unless their arguments do
                usesContinuations(expr.arguments)
            }
            is StateMachineExpression -> {
                // State machines already represent transformed continuation code
                // Check if any of the state expressions use continuations
                expr.states.any { state ->
                    when (state) {
                        is ComputeState -> usesContinuations(state.computation)
                        is YieldState -> usesContinuations(state.valueExpression)
                        is CompleteState -> usesContinuations(state.resultExpression)
                    }
                }
            }
            // Literals and identifiers don't use continuations
            is IntLiteral, is NatLiteral, is FloatLiteral, is BooleanLiteral,
            is FractionLiteral, is Identifier, is PlaceholderExpression -> false

            // Parenthesized - check inner expression
            is ParenthesizedExpression -> usesContinuations(expr.expression)
        }
    }

    /**
     * Check if a statement uses continuations.
     */
    private fun usesContinuations(stmt: Statement): Boolean {
        return when (stmt) {
            is LetStatement -> usesContinuations(stmt.expression)
            is ExpressionStatement -> usesContinuations(stmt.expression)
        }
    }

    /**
     * Check if a function call is to a continuation primitive.
     */
    private fun isContinuationPrimitive(funcExpr: Expression): Boolean {
        return when (funcExpr) {
            is Identifier -> funcExpr.localName in continuationPrimitives
            is MemberAccess -> {
                // Handle qualified calls like Continuation.capture
                funcExpr.member in setOf("capture", "suspend", "suspend_with") &&
                    (funcExpr.receiver as? Identifier)?.localName == "Continuation"
            }
            else -> false
        }
    }

    companion object {
        /**
         * Names of continuation primitive functions.
         */
        private val continuationPrimitives = setOf(
            "__native_continuation_capture",
            "__native_continuation_suspend",
            "__native_continuation_suspend_with",
            "__native_continuation_fork",
            "__native_continuation_abort"
        )

        /**
         * Create a continuation detector.
         */
        fun create(): ContinuationDetector {
            return ContinuationDetector()
        }
    }
}
