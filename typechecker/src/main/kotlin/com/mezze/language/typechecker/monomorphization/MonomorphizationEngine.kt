package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.Program

/**
 * Monomorphization engine - generates specialized versions of polymorphic functions.
 *
 * This is PHASE 6 of the type checking pipeline (runs after Phase 5: Substitution Application):
 *
 * ## What is Monomorphization?
 *
 * Monomorphization is the process of generating specialized versions of polymorphic (generic)
 * functions for each set of concrete types they're called with.
 *
 * **Example:**
 * ```mezze
 * let map = {'a, 'b} -> {f: 'a -> 'b, list: ['a]} -> ...  // Polymorphic
 *
 * let result1 = map {f = {x} -> x + 1, list = [1, 2, 3]}  // Uses map$Int
 * let result2 = map {f = length, list = ["a", "bb"]}      // Uses map$String
 * ```
 *
 * After monomorphization:
 * ```mezze
 * let map$Int = {f: Int -> Int, list: [Int]} -> ...       // Specialized for Int
 * let map$String = {f: String -> Int, list: [String]} -> ... // Specialized for String
 * ```
 *
 * ## Why is Monomorphization Needed?
 *
 * 1. **Runtime Performance**: The interpreter needs concrete function implementations
 *    without type parameters for efficient execution.
 *
 * 2. **Ability Resolution**: Abilities (type classes) need concrete types to resolve
 *    which implementation to use (e.g., NumAdd for Int vs Float).
 *
 * 3. **Type Safety**: Ensures type variables are fully resolved before runtime.
 *
 * ## Algorithm
 *
 * **Phase 1: Track Needed Instances**
 * - Walk the entire program AST
 * - Find all function calls that use type substitution
 * - Track: (function name, type arguments) pairs
 *
 * **Phase 2: Generate Specialized Bindings**
 * - For each tracked instance request:
 *   - Find the original polymorphic binding
 *   - Collect all TypeVar instances from the expression
 *   - Build substitution map: TypeVar → concrete type
 *   - Apply substitution to entire expression AST
 *   - Create new binding with monomorphic name
 *
 * **Phase 3: Update Call Sites**
 * - Walk the program AST again
 * - Update FunctionCall nodes with resolvedFunctionName
 * - Interpreter uses resolvedFunctionName to call the right instance
 *
 * **Phase 4: Return Results**
 * - Return updated program (with resolved call sites)
 * - Return map of monomorphic instances (for interpreter)
 *
 * ## Design Principles:
 * - Pure functions (no mutation!)
 * - Immutable data structures
 * - Sealed result types
 * - Exhaustive pattern matching
 * - Clean separation of concerns
 *
 * ## Usage:
 * ```kotlin
 * val engine = MonomorphizationEngine()
 * val result = engine.monomorphize(typedProgram)
 *
 * when (result) {
 *     is MonomorphizationResult.Success -> {
 *         // result.program has updated call sites
 *         // result.monomorphicInstances for interpreter
 *     }
 *     is MonomorphizationResult.Failure -> {
 *         // Handle errors
 *     }
 * }
 * ```
 */
class MonomorphizationEngine {
    /**
     * Monomorphize a program.
     *
     * Returns a program with:
     * - Updated function calls (resolvedFunctionName set)
     * - Map of monomorphic instances (for interpreter)
     */
    fun monomorphize(program: Program): MonomorphizationResult {
        try {
            // PHASE 1: Analyze program to find needed monomorphic instances
            val tracker = analyzeProgram(program)

            // Early exit if no monomorphization needed
            if (tracker.size() == 0) {
                return MonomorphizationResult.Success(
                    program = program,
                    monomorphicInstances = emptyMap(),
                    warnings = emptyList()
                )
            }

            // PHASE 2: Generate specialized bindings
            val generator = InstanceGenerator(program)
            val monomorphicInstances = generator.generateAll(tracker)

            // PHASE 3: Update call sites with resolved names
            val updater = CallSiteUpdater(tracker)
            val updatedProgram = updater.updateProgram(program)

            // PHASE 4: Return results
            return MonomorphizationResult.Success(
                program = updatedProgram,
                monomorphicInstances = monomorphicInstances,
                warnings = buildWarnings(tracker, monomorphicInstances)
            )

        } catch (e: Exception) {
            // If monomorphization fails, return error
            return MonomorphizationResult.Failure(
                errors = listOf(
                    MonomorphizationError(
                        message = "Monomorphization failed: ${e.message}",
                        functionName = null,
                        typeArgs = emptyMap(),
                        sourceLocation = null
                    )
                )
            )
        }
    }

    /**
     * Build warnings for monomorphization results.
     *
     * Warns if any requested instances couldn't be generated.
     */
    private fun buildWarnings(
        tracker: InstanceTracker,
        instances: Map<String, *>
    ): List<String> {
        val warnings = mutableListOf<String>()

        // Check if all requested instances were generated
        val requestedCount = tracker.size()
        val generatedCount = instances.size

        if (generatedCount < requestedCount) {
            warnings.add(
                "Generated $generatedCount/$requestedCount monomorphic instances. " +
                "Some instances may not have been generated."
            )
        }

        return warnings
    }

    /**
     * Create a monomorphization engine.
     */
    companion object {
        fun create(): MonomorphizationEngine {
            return MonomorphizationEngine()
        }
    }
}

/**
 * Format a monomorphization result for display.
 */
fun formatMonomorphizationResult(result: MonomorphizationResult): String {
    return when (result) {
        is MonomorphizationResult.Success -> buildString {
            appendLine("✓ Monomorphization succeeded!")
            appendLine("  Generated ${result.monomorphicInstances.size} specialized function(s)")

            if (result.monomorphicInstances.isNotEmpty()) {
                appendLine("  Instances:")
                for (name in result.monomorphicInstances.keys.sorted()) {
                    appendLine("    - $name")
                }
            }

            if (result.warnings.isNotEmpty()) {
                appendLine("  Warnings:")
                for (warning in result.warnings) {
                    appendLine("    - $warning")
                }
            }
        }

        is MonomorphizationResult.Failure -> buildString {
            appendLine("✗ Monomorphization failed with ${result.errors.size} error(s):")
            for ((index, error) in result.errors.withIndex()) {
                appendLine("${index + 1}. ${error.message}")
                if (error.functionName != null) {
                    appendLine("   Function: ${error.functionName}")
                }
                if (error.typeArgs.isNotEmpty()) {
                    appendLine("   Type arguments: ${error.typeArgs}")
                }
            }
        }
    }
}
