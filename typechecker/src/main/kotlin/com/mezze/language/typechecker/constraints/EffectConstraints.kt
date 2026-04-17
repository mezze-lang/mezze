package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType

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
        context: String? = null
    ): Constraint.EffectUsed {
        return Constraint.EffectUsed(
            effect = effect,
            context = context
        )
    }
}
