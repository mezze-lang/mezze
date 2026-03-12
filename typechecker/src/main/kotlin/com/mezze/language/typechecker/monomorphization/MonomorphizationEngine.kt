package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.Program
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category

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
            val totalStartTime = System.nanoTime()

            // PHASE 1: Analyze program to find needed monomorphic instances
            val analysisStartTime = System.nanoTime()
            val tracker = analyzeProgram(program)
            val analysisTimeNanos = System.nanoTime() - analysisStartTime

            // Early exit if no monomorphization needed
            if (tracker.size() == 0) {
                return MonomorphizationResult.Success(
                    program = program,
                    monomorphicInstances = emptyMap(),
                    warnings = emptyList(),
                    stats = MonomorphizationStats(
                        totalInstancesRequested = 0,
                        analysisTimeNanos = analysisTimeNanos
                    )
                )
            }

            // PHASE 2: Generate specialized bindings (with timing and cache tracking)
            val generationStartTime = System.nanoTime()
            val generator = InstanceGenerator(program)
            val generationResult = generateAllWithStats(generator, tracker)
            val generationTimeNanos = System.nanoTime() - generationStartTime

            // PHASE 3: Update call sites with resolved names
            val updateStartTime = System.nanoTime()
            val updater = CallSiteUpdater(tracker)
            val updatedProgram = updater.updateProgram(program)
            val updateTimeNanos = System.nanoTime() - updateStartTime

            // Build statistics
            val stats = MonomorphizationStats(
                totalInstancesRequested = tracker.size(),
                instancesGenerated = generationResult.instances.size,
                cacheHits = generationResult.cacheHits,
                cacheMisses = generationResult.cacheMisses,
                totalGenerationTimeNanos = generationTimeNanos,
                analysisTimeNanos = analysisTimeNanos,
                callSiteUpdateTimeNanos = updateTimeNanos,
                instanceDetails = generationResult.instanceStats
            )

            // Log statistics if debug is enabled
            DebugLogger.debugSection(Category.Monomorphization, "Monomorphization complete") {
                listOf(
                    "Generated ${generationResult.instances.size} instances (${tracker.size()} requested)",
                    "Cache: ${(stats.cacheHitRate * 100).toInt()}% hit rate (${generationResult.cacheHits} hits, ${generationResult.cacheMisses} misses)",
                    "Time: ${stats.totalTimeNanos / 1_000_000}ms total (analysis: ${analysisTimeNanos / 1_000_000}ms, generation: ${generationTimeNanos / 1_000_000}ms, update: ${updateTimeNanos / 1_000_000}ms)"
                )
            }

            // PHASE 4: Return results
            return MonomorphizationResult.Success(
                program = updatedProgram,
                monomorphicInstances = generationResult.instances,
                warnings = buildWarnings(tracker, generationResult.instances),
                stats = stats
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
     * Result of generation with statistics.
     */
    private data class GenerationWithStatsResult(
        val instances: Map<String, com.mezze.language.ast.LetBinding>,
        val cacheHits: Int,
        val cacheMisses: Int,
        val instanceStats: List<InstanceStats>
    )

    /**
     * Generate all instances while tracking statistics.
     */
    private fun generateAllWithStats(
        generator: InstanceGenerator,
        tracker: InstanceTracker
    ): GenerationWithStatsResult {
        val instances = mutableMapOf<String, com.mezze.language.ast.LetBinding>()
        val instanceStats = mutableListOf<InstanceStats>()
        var cacheHits = 0
        var cacheMisses = 0

        // Count call sites per request for statistics
        val callCounts = tracker.getCallCounts()

        for (request in tracker.getRequests()) {
            val startTime = System.nanoTime()
            val wasCachedBefore = com.mezze.language.typechecker.cache.TypeCheckCache.getMonomorphicInstance(
                request.originalContentHash ?: "",
                com.mezze.language.typechecker.cache.TypeCheckCache.hashTypeArgs(request.typeArgs)
            ) != null

            when (val result = generator.generate(request)) {
                is GenerationResult.Success -> {
                    val endTime = System.nanoTime()
                    instances[result.instance.monomorphicName] = result.instance.specializedBinding

                    if (wasCachedBefore) {
                        cacheHits++
                    } else {
                        cacheMisses++
                    }

                    // Build type args summary
                    val typeArgsSummary = request.typeArgs.entries
                        .sortedBy { it.key }
                        .joinToString(", ") { (_, type) ->
                            com.mezze.language.typechecker.hashing.ContentHashComputer.mezzeTypeToString(type)
                        }

                    instanceStats.add(InstanceStats(
                        originalName = request.originalName,
                        monomorphicName = result.instance.monomorphicName,
                        typeArgsSummary = typeArgsSummary,
                        callCount = callCounts[request.originalName + typeArgsSummary] ?: 1,
                        generationTimeNanos = if (wasCachedBefore) 0L else (endTime - startTime),
                        wasCached = wasCachedBefore
                    ))
                }
                is GenerationResult.Failure -> {
                    // Skip failed generations (already logged by generator)
                    cacheMisses++
                }
            }
        }

        return GenerationWithStatsResult(instances, cacheHits, cacheMisses, instanceStats)
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
fun formatMonomorphizationResult(result: MonomorphizationResult, includeStats: Boolean = false): String {
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

            if (includeStats) {
                appendLine()
                append(result.stats.format())
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
