package com.mezze.language.typechecker.validation

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.scope.ScopedEffectConstants

/**
 * Effect validator - checks that effects are properly handled.
 *
 * Effects represent computational side effects (IO, State, exceptions, etc.).
 * The validator ensures:
 * 1. Effects are either handled or propagated in function signature
 * 2. Effect handlers exist for used effects
 * 3. Effect rows are consistent
 *
 * Design:
 * - Pure functions (no mutation!)
 * - Returns sealed EffectCheckResult
 * - Exhaustive pattern matching
 * - Clear error messages
 */
class EffectValidator(
    private val context: TypingContext
) {
    /**
     * Result of effect validation.
     *
     * Sealed type - exhaustive pattern matching, no nulls!
     */
    sealed class EffectCheckResult {
        /**
         * Effect is properly handled.
         */
        data class Handled(
            val handlerSource: String? = null
        ) : EffectCheckResult()

        /**
         * Effect is not handled.
         */
        data class Unhandled(
            val reason: String,
            val suggestions: List<String> = emptyList()
        ) : EffectCheckResult()
    }

    /**
     * Check if an effect is handled in the current context.
     *
     * An effect is handled if:
     * - There's a handler in scope
     * - The effect is in the function's effect row
     * - The effect is pure (no effects)
     */
    fun checkEffect(
        effectName: String,
        allowedEffects: Set<String>,
        functionEffects: Set<String>
    ): EffectCheckResult {
        return when {
            // Effect is allowed in current scope (handler in scope)
            effectName in allowedEffects -> EffectCheckResult.Handled(
                handlerSource = "Handler in scope"
            )

            // Effect is in function signature (propagated)
            effectName in functionEffects -> EffectCheckResult.Handled(
                handlerSource = "Propagated in function signature"
            )

            // Effect is a built-in that's always allowed (e.g., Inspect for debugging)
            isUniversalEffect(effectName) -> EffectCheckResult.Handled(
                handlerSource = "Universal effect"
            )

            // Effect not handled
            else -> EffectCheckResult.Unhandled(
                reason = "Effect '$effectName' is not handled and not in function signature",
                suggestions = buildSuggestions(effectName, allowedEffects, functionEffects)
            )
        }
    }

    /**
     * Check if effect rows are compatible.
     *
     * Used row must be a subset of available row.
     */
    fun checkEffectRow(
        usedEffects: MezzeType.EffectRow,
        availableEffects: MezzeType.EffectRow
    ): EffectCheckResult {
        val used = usedEffects.effects
        val available = availableEffects.effects

        // All used effects must be in available effects
        val missingEffects = used - available

        return if (missingEffects.isEmpty()) {
            EffectCheckResult.Handled("All effects are available")
        } else {
            EffectCheckResult.Unhandled(
                reason = "Missing effect handlers for: ${missingEffects.joinToString()}",
                suggestions = missingEffects.map { "Add handler for $it or add to function signature" }
            )
        }
    }

    // validatePerformBlock removed — was dead code using v1 PerformExpression

    /**
     * Check if an effect is universal (always allowed).
     *
     * Universal effects don't need handlers.
     * Example: Inspect for debugging
     */
    private fun isUniversalEffect(effectName: String): Boolean {
        return when (effectName) {
            "Inspect" -> true  // Debugging/introspection is always allowed
            else -> false
        }
    }

    /**
     * Build suggestions for fixing unhandled effects.
     */
    private fun buildSuggestions(
        effectName: String,
        allowedEffects: Set<String>,
        functionEffects: Set<String>
    ): List<String> {
        val suggestions = mutableListOf<String>()

        // Suggest adding handler
        suggestions.add("Add a 'perform ... with $effectName {...}' block")

        // Suggest propagating in function signature
        if (functionEffects.isEmpty()) {
            suggestions.add("Add effect to function signature: {...} -> ... <$effectName>")
        } else {
            suggestions.add("Add effect to function signature: {...} -> ... <${(functionEffects + effectName).joinToString()}>")
        }

        // Show available handlers
        if (allowedEffects.isNotEmpty()) {
            suggestions.add("Available effects in scope: ${allowedEffects.joinToString()}")
        }

        return suggestions
    }

    /**
     * Extract effects from a type.
     *
     * Returns the set of effects used by a type.
     */
    fun extractEffects(type: MezzeType): Set<String> {
        return when (type) {
            is MezzeType.FunctionType -> type.effects?.effects ?: emptySet()
            is MezzeType.SuspendedType -> type.effects.effects
            else -> emptySet()
        }
    }

    /**
     * Merge effect sets.
     *
     * When combining results from multiple expressions, merge their effects.
     */
    fun mergeEffects(effects: List<Set<String>>): Set<String> {
        return effects.flatten().toSet()
    }

    /**
     * Check if an expression can have side effects.
     *
     * Pure expressions don't use effects.
     */
    fun isPure(type: MezzeType): Boolean {
        return when (type) {
            is MezzeType.FunctionType -> type.effects == null
            is MezzeType.SuspendedType -> false  // Suspended types always have effects
            else -> true  // Other types are pure values
        }
    }

    /**
     * Validate effect declaration.
     *
     * Ensures effect operations are well-formed.
     */
    fun validateEffectDeclaration(effectDecl: ParsedEffectDecl): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // Check for duplicate operation names
        val operationNames = effectDecl.operations.map { it.name }
        val duplicates = operationNames.groupingBy { it }.eachCount().filter { it.value > 1 }

        for ((name, count) in duplicates) {
            errors.add(
                ValidationError.TypeMismatch(
                    expected = MezzeType.IntType,  // Placeholder
                    actual = MezzeType.IntType,     // Placeholder
                    context = "Operation '$name' is defined $count times in effect '${effectDecl.name}'. Each operation must have a unique name.",
                    sourceLocation = null
                )
            )
        }

        return errors
    }

    /**
     * Validate effect implementation (performer).
     *
     * Ensures all operations are implemented and associated types are properly bound.
     * Matches OldTypeChecker.kt lines 12740-12779
     */
    fun validateEffectImpl(
        implDecl: ParsedPerformerDecl,
        effectDecl: ParsedEffectDecl
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        // CRITICAL: Validate associated types (from OldTypeChecker.kt lines 12746-12778)
        val effectAssociatedTypes = effectDecl.associatedTypes
        val implAssociatedTypeBindings = implDecl.associatedTypeBindings

        // Check if effect has associated types but impl doesn't provide bindings
        if (effectAssociatedTypes.isNotEmpty() && implAssociatedTypeBindings.isEmpty()) {
            val typesList = effectAssociatedTypes.joinToString(", ")
            errors.add(
                ValidationError.TypeMismatch(
                    expected = MezzeType.IntType,  // Placeholder
                    actual = MezzeType.IntType,     // Placeholder
                    context = "Effect '${implDecl.effect.localName}' declares associated types [$typesList] " +
                            "but implementation '${implDecl.performerName}' does not provide bindings. " +
                            "Use: impl ${implDecl.effect.localName} for ${implDecl.performerName} having ${effectAssociatedTypes.joinToString(", ") { "$it = <Type>" }} where { ... }",
                    sourceLocation = null
                )
            )
        }

        // Check that all declared associated types have bindings
        val missingBindings = effectAssociatedTypes.filter { it !in implAssociatedTypeBindings.keys }
        if (missingBindings.isNotEmpty()) {
            errors.add(
                ValidationError.TypeMismatch(
                    expected = MezzeType.IntType,  // Placeholder
                    actual = MezzeType.IntType,     // Placeholder
                    context = "Effect '${implDecl.effect.localName}' declares associated types ${effectAssociatedTypes.joinToString(", ")} " +
                            "but implementation '${implDecl.performerName}' is missing bindings for: ${missingBindings.joinToString(", ")}. " +
                            "All associated types must be bound in the implementation.",
                    sourceLocation = null
                )
            )
        }

        // Check that impl doesn't provide extra bindings not declared in effect
        val extraBindings = implAssociatedTypeBindings.keys.filter { it !in effectAssociatedTypes }
        if (extraBindings.isNotEmpty()) {
            val declaredPart = if (effectAssociatedTypes.isEmpty()) {
                "does not declare any associated types"
            } else {
                "only declares: ${effectAssociatedTypes.joinToString(", ")}"
            }
            errors.add(
                ValidationError.TypeMismatch(
                    expected = MezzeType.IntType,  // Placeholder
                    actual = MezzeType.IntType,     // Placeholder
                    context = "Implementation '${implDecl.performerName}' provides bindings for ${extraBindings.joinToString(", ")} " +
                            "but effect '${implDecl.effect.localName}' $declaredPart. " +
                            "Remove the extra bindings or add them to the effect declaration.",
                    sourceLocation = null
                )
            )
        }

        // Get implemented operations
        val implementedOps = implDecl.operations.map { it.name }.toSet()

        // Get required operations
        val requiredOps = effectDecl.operations.map { it.name }.toSet()

        // Check for missing operations
        val missingOps = requiredOps - implementedOps
        if (missingOps.isNotEmpty()) {
            val missing = missingOps.joinToString(", ")
            val example = missingOps.first()
            errors.add(
                ValidationError.TypeMismatch(
                    expected = MezzeType.IntType,  // Placeholder
                    actual = MezzeType.IntType,     // Placeholder
                    context = "Implementation '${implDecl.performerName}' for effect '${implDecl.effect.localName}' " +
                            "is missing required ${if (missingOps.size == 1) "operation" else "operations"}: $missing. " +
                            "Add: $example = {args} -> ...",
                    sourceLocation = null
                )
            )
        }

        // Check for extra operations (not in effect declaration)
        // Note: initialize_scope and finalize_scope are special scoped effect members, not regular operations
        val scopedEffectNames = setOf(ScopedEffectConstants.INITIALIZE_SCOPE, ScopedEffectConstants.FINALIZE_SCOPE)
        val extraOps = (implementedOps - requiredOps) - scopedEffectNames
        if (extraOps.isNotEmpty()) {
            val extra = extraOps.joinToString(", ")
            errors.add(
                ValidationError.TypeMismatch(
                    expected = MezzeType.IntType,  // Placeholder
                    actual = MezzeType.IntType,     // Placeholder
                    context = "Implementation '${implDecl.performerName}' for effect '${implDecl.effect.localName}' " +
                            "has extra ${if (extraOps.size == 1) "operation" else "operations"} not in effect: $extra. " +
                            "Remove ${if (extraOps.size == 1) "it" else "them"} or add to the effect declaration.",
                    sourceLocation = null
                )
            )
        }

        return errors
    }
}
