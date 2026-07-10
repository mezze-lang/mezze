package com.mezze.language.typechecker.printing

import com.mezze.language.typechecker.solving.ConstraintSolver

/**
 * Generates helpful suggestions for constraint solver errors.
 */
object SuggestionGenerator {
    fun suggestForSolverError(error: ConstraintSolver.ConstraintError): List<String> {
        val suggestions = mutableListOf<String>()

        // Parse the error message for context
        val message = error.message.lowercase()

        when {
            "type mismatch" in message || "not compatible" in message -> {
                suggestions.add("Ensure both sides of the expression have compatible types")
                suggestions.add("Add explicit type annotations to clarify intent")
            }

            "arity" in message -> {
                suggestions.add("Check that the function is called with the correct number of arguments")
                suggestions.add("Use a record for multiple arguments: func {x = 1, y = 2}")
            }

            "row" in message && "field" in message -> {
                suggestions.add("Ensure all required record fields are present")
                suggestions.add("Check field names for typos")
            }

            "effect" in message -> {
                suggestions.add("Handle the effect with a handler")
                suggestions.add("Propagate the effect in the function signature")
            }

            else -> {
                suggestions.add("Add explicit type annotations")
                suggestions.add("Check the types involved in the constraint")
            }
        }

        return suggestions
    }
}
