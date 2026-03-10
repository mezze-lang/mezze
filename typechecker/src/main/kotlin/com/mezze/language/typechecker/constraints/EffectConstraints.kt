package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*

/**
 * Effect constraint generator - generates effect usage constraints.
 *
 * Effects appear in:
 * - Effect operations: Log.write {msg = "hello"}
 * - Function types: {x: Int} -> Int <Log, IO>
 * - Perform blocks: perform expr with Log, IO
 * - Effect handlers: handle expr with Log {write = ...}
 *
 * For each effect usage, we:
 * 1. Track which effects are used
 * 2. Generate EffectUsed constraints
 * 3. Ensure effects are handled or propagated
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Tracks effect usage through ConstraintResult.effects
 * - Works with validation to ensure effects are handled
 */
class EffectConstraintGenerator {

    /**
     * Generate effect constraints from an expression.
     *
     * Returns the set of effects used by this expression.
     */
    fun generateFromExpression(
        expr: Expression,
        context: ConstraintContext
    ): Set<String> {
        return when (expr) {
            // Effect operations directly use effects
            is EffectCallExpression -> setOf(expr.operation.qualifier?.fullPath ?: "")

            // Perform expressions use effects from inner expression
            is PerformExpression -> generateFromPerform(expr, context)

            // Function calls might use effects from the function
            is FunctionCall -> generateFromFunctionCall(expr, context)

            // Other expressions don't directly use effects
            else -> emptySet()
        }
    }

    /**
     * Generate effect constraints from perform expression.
     *
     * perform expr with handlers
     *
     * The handlers specify which effects are being handled,
     * so those effects don't propagate up.
     */
    private fun generateFromPerform(
        expr: PerformExpression,
        context: ConstraintContext
    ): Set<String> {
        // Effects from inner expression
        val innerEffects = generateFromExpression(expr.expression, context)

        // Effects being handled
        val handledEffects = expr.handlers.map { it.performer.localName }.toSet()

        // Unhandled effects propagate up
        return innerEffects - handledEffects
    }

    /**
     * Generate effect constraints from function call.
     *
     * If the function being called has effects in its type,
     * those effects are used by the call.
     */
    private fun generateFromFunctionCall(
        expr: FunctionCall,
        context: ConstraintContext
    ): Set<String> {
        val functionType = expr.function.inferredType

        if (functionType is MezzeType.FunctionType) {
            val effectRow = functionType.effects
            if (effectRow != null) {
                return effectRow.effects
            }
        }

        return emptySet()
    }

    /**
     * Generate effect row from function type.
     *
     * Extracts the effect row from a function type.
     */
    fun extractEffectRow(functionType: MezzeType.FunctionType): MezzeType.EffectRow? {
        return functionType.effects
    }

    /**
     * Merge effect sets.
     *
     * When combining results from multiple sub-expressions,
     * we need to merge their effects.
     */
    fun mergeEffects(effects: List<Set<String>>): Set<String> {
        return effects.flatten().toSet()
    }

    /**
     * Check if effect is allowed in context.
     *
     * Inside a perform block with handlers, certain effects are allowed.
     */
    fun isEffectAllowed(
        effect: String,
        context: ConstraintContext
    ): Boolean {
        return effect in context.allowedEffects
    }

    /**
     * Generate constraint for effect usage.
     *
     * This creates an EffectUsed constraint for validation.
     */
    fun generateConstraint(
        effect: String,
        expr: Expression? = null,
        context: String? = null
    ): Constraint.EffectUsed {
        return Constraint.EffectUsed(
            effect = effect,
            expression = expr,
            context = context
        )
    }

    /**
     * Common built-in effects.
     */
    object BuiltInEffects {
        const val IO = "IO"
        const val STATE = "State"
        const val ERROR = "Error"
        const val LOG = "Log"
        const val RANDOM = "Random"
        const val TIME = "Time"
        const val ASYNC = "Async"

        /**
         * Check if effect is a built-in effect.
         */
        fun isBuiltIn(effectName: String): Boolean {
            return when (effectName) {
                IO, STATE, ERROR, LOG, RANDOM, TIME, ASYNC -> true
                else -> false
            }
        }

        /**
         * Get effect operations for built-in effects.
         *
         * This is used during validation to check if operations exist.
         */
        fun getOperations(effectName: String): List<String> {
            return when (effectName) {
                IO -> listOf("read", "write", "readFile", "writeFile")
                STATE -> listOf("get", "set", "modify")
                ERROR -> listOf("throw", "catch")
                LOG -> listOf("log", "debug", "info", "warn", "error")
                RANDOM -> listOf("random", "randomInt", "randomFloat")
                TIME -> listOf("now", "sleep")
                ASYNC -> listOf("async", "await")
                else -> emptyList()
            }
        }
    }
}
