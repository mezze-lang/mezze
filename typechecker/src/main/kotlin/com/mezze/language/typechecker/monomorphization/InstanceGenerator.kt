package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.*
import com.mezze.language.typechecker.cache.TypeCheckCache
import com.mezze.language.typechecker.debug.DebugLogger
import com.mezze.language.typechecker.debug.DebugLogger.Category
import com.mezze.language.typechecker.ProgramIndex

/**
 * Instance generator - creates specialized versions of polymorphic functions.
 *
 * Takes an instance request (function name + type arguments) and generates
 * a monomorphic binding with all type variables substituted.
 *
 * Design:
 * - Pure functions (creates new bindings, doesn't mutate!)
 * - Handles TypeVar instances correctly (not just names)
 * - Applies substitution to entire expression AST
 * - Returns sealed Result type (Success or Failure)
 * - Uses ProgramIndex for O(1) binding lookup
 */
class InstanceGenerator(
    private val program: Program,
    private val index: ProgramIndex = ProgramIndex.build(program)
) {
    /**
     * Generate a monomorphic instance for a request.
     *
     * Returns a sealed Result indicating success or failure.
     */
    fun generate(request: InstanceRequest): GenerationResult {
        // Find the original polymorphic binding
        val originalBinding = findBinding(request.originalName)
            ?: return GenerationResult.Failure(
                error = "Original binding '${request.originalName}' not found",
                request = request
            )

        // Check if the binding is actually polymorphic
        val typeScheme = originalBinding.typeScheme
        if (typeScheme == null || typeScheme.quantifiedVars.isEmpty()) {
            return GenerationResult.Failure(
                error = "Function '${request.originalName}' is not polymorphic",
                request = request
            )
        }

        // Check L1/L2 cache for previously generated instance (fast path)
        // Use original binding's content hash + type args hash as cache key
        val originalHash = originalBinding.contentHash
        if (originalHash != null) {
            val typeArgsHash = TypeCheckCache.hashTypeArgs(request.typeArgs)
            TypeCheckCache.getMonomorphicInstance(originalHash, typeArgsHash)?.let { cachedBinding ->
                // Cache hit - return the cached monomorphic instance
                val monomorphicName = request.generateMonomorphicName()
                val contentHash = request.generateContentHash()
                val key = request.toMonomorphicKey()
                return GenerationResult.Success(
                    instance = MonomorphicInstance(
                        originalName = request.originalName,
                        monomorphicName = monomorphicName,
                        typeArguments = request.typeArgs,
                        specializedBinding = cachedBinding,
                        contentHash = contentHash,
                        monomorphicKey = key
                    )
                )
            }
        }

        // Get the original expression (should be a lambda for functions)
        val originalExpr = originalBinding.expression

        // Collect all TypeVar instances from the expression
        val typeVarsInExpression = collectTypeVarsFromExpression(originalExpr)

        // Build substitution map from TypeVar instances to concrete types
        val substitutionMap = buildSubstitutionMap(
            typeVarsInExpression = typeVarsInExpression,
            typeArgs = request.typeArgs
        )

        // Apply type substitution to the entire expression
        val substitutor = TypeSubstitutor(substitutionMap)
        val specializedExpr = substitutor.apply(originalExpr)

        // Generate the monomorphic name and content hash
        val monomorphicName = request.generateMonomorphicName()
        val contentHash = request.generateContentHash()

        // Create the specialized binding with content hash
        val specializedBinding = originalBinding.copy(
            pattern = VariablePattern(monomorphicName),
            expression = specializedExpr,
            typeScheme = null, // No longer polymorphic
            inferredType = specializedExpr.inferredType,
            isMonomorphicInstance = true,
            originalFunctionName = request.originalName,
            monomorphicTypeArgs = request.typeArgs,
            contentHash = contentHash  // Propagate content hash to binding
        )

        // Cache the generated instance for future lookups
        if (originalHash != null) {
            val typeArgsHash = TypeCheckCache.hashTypeArgs(request.typeArgs)
            TypeCheckCache.putMonomorphicInstance(originalHash, typeArgsHash, specializedBinding)
        }

        // Build structured MonomorphicKey
        val key = request.toMonomorphicKey()

        return GenerationResult.Success(
            instance = MonomorphicInstance(
                originalName = request.originalName,
                monomorphicName = monomorphicName,
                typeArguments = request.typeArgs,
                specializedBinding = specializedBinding,
                contentHash = contentHash,
                monomorphicKey = key
            )
        )
    }

    /**
     * Generate all instances from a tracker.
     *
     * Returns a map of monomorphic name -> LetBinding.
     */
    fun generateAll(tracker: InstanceTracker): Map<String, LetBinding> {
        val instances = mutableMapOf<String, LetBinding>()
        val errors = mutableListOf<String>()

        for (request in tracker.getRequests()) {
            when (val result = generate(request)) {
                is GenerationResult.Success -> {
                    instances[result.instance.monomorphicName] = result.instance.specializedBinding
                }
                is GenerationResult.Failure -> {
                    errors.add("${result.request.originalName}: ${result.error}")
                }
            }
        }

        if (errors.isNotEmpty()) {
            DebugLogger.debugSection(Category.Monomorphization, "Warning: Some monomorphic instances could not be generated") {
                errors.map { "- $it" }
            }
        }

        return instances
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Find a binding by name in the program.
     * Uses pre-built index for O(1) lookup.
     */
    private fun findBinding(name: String): LetBinding? {
        return index.letBindings[name]
    }

    /**
     * Build a substitution map from TypeVar instances to concrete types.
     *
     * CRITICAL: This maps the actual TypeVar object instances (not names)
     * to concrete types. This is necessary because type inference creates
     * multiple TypeVar instances that need to be substituted independently.
     */
    private fun buildSubstitutionMap(
        typeVarsInExpression: Set<TypeVar>,
        typeArgs: Map<String, MezzeType>
    ): Map<TypeVar, MezzeType> {
        val substitutionMap = mutableMapOf<TypeVar, MezzeType>()

        when {
            // Single type argument: Map ALL TypeVars to the same concrete type
            // This handles cases where type inference creates multiple TypeVar instances
            // for the same type parameter (e.g., 'a and 'b both represent the same polymorphic type)
            typeArgs.size == 1 -> {
                val concreteType = typeArgs.values.first()
                for (typeVar in typeVarsInExpression) {
                    substitutionMap[typeVar] = concreteType
                }
            }

            // Multiple type parameters: Match by position
            // Sort TypeVars by ID to ensure consistent mapping
            typeArgs.size > 1 -> {
                val sortedTypeVars = typeVarsInExpression.sortedBy { it.id }
                val concreteTypes = typeArgs.values.toList()

                // Zip sorted TypeVars with concrete types
                for ((index, typeVar) in sortedTypeVars.withIndex()) {
                    if (index < concreteTypes.size) {
                        substitutionMap[typeVar] = concreteTypes[index]
                    }
                }
            }

            // No type arguments: Nothing to substitute
            else -> {
                // Empty substitution map
            }
        }

        return substitutionMap
    }
}

/**
 * Result of generating a monomorphic instance.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class GenerationResult {
    /**
     * Generation succeeded.
     */
    data class Success(
        val instance: MonomorphicInstance
    ) : GenerationResult()

    /**
     * Generation failed.
     */
    data class Failure(
        val error: String,
        val request: InstanceRequest
    ) : GenerationResult()
}
