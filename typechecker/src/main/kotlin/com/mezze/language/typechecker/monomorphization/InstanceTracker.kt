package com.mezze.language.typechecker.monomorphization

import com.mezze.language.ast.*

/**
 * Instance tracker - tracks needed monomorphic instances.
 *
 * During type inference, when a polymorphic function is called
 * with concrete types, we track it so we can generate a specialized
 * version later.
 *
 * Design:
 * - Immutable tracking (returns new tracker with updated state)
 * - Deduplicates requests (same function + same types = one instance)
 * - Pure functions (no side effects!)
 * - Uses MonomorphicKey as PRIMARY key for type-safe tracking (no string parsing)
 * - String-based names are derived from keys when needed for backward compatibility
 */
class InstanceTracker private constructor(
    /** PRIMARY: Structured key index for O(1) type-directed lookup */
    private val keyIndex: Map<MonomorphicKey, InstanceRequest>,
    /** Call counts per MonomorphicKey */
    private val callCounts: Map<MonomorphicKey, Int> = emptyMap()
) {
    companion object {
        /**
         * Create an empty instance tracker.
         */
        fun empty(): InstanceTracker {
            return InstanceTracker(emptyMap(), emptyMap())
        }
    }

    /**
     * Track a needed monomorphic instance.
     *
     * Returns a new tracker with the instance added.
     * Uses MonomorphicKey for deduplication (no string parsing).
     */
    fun track(request: InstanceRequest): InstanceTracker {
        val key = request.toMonomorphicKey()

        // Update call count even for duplicates
        val newCallCounts = callCounts + (key to (callCounts[key] ?: 0) + 1)

        // Deduplicate using structured key (no string generation needed)
        if (keyIndex.containsKey(key)) {
            return InstanceTracker(keyIndex, newCallCounts)
        }

        // Return new tracker with this request added
        val newKeyIndex = keyIndex + (key to request)
        return InstanceTracker(newKeyIndex, newCallCounts)
    }

    /**
     * Get call counts for all tracked instances.
     * Returns map of "functionName + typeArgsSummary" -> count for compatibility.
     */
    fun getCallCounts(): Map<String, Int> {
        return callCounts.entries.associate { (key, count) ->
            computeCallCountKey(key) to count
        }
    }

    /**
     * Compute a string key for call counting (for stats display).
     */
    private fun computeCallCountKey(key: MonomorphicKey): String {
        val typeArgsSummary = key.typeArgs.joinToString(", ") { it.toCanonicalString() }
        return key.originalName + typeArgsSummary
    }

    /**
     * Track a needed instance from a function call.
     *
     * Convenience method for tracking from a FunctionCall node.
     */
    fun trackFromCall(
        functionName: String,
        typeSubstitution: Map<String, MezzeType>,
        sourceLocation: SourceLocation? = null,
        originalContentHash: String? = null
    ): InstanceTracker {
        // Only track if there are actual type substitutions
        if (typeSubstitution.isEmpty()) {
            return this
        }

        val request = InstanceRequest(
            originalName = functionName,
            typeArgs = typeSubstitution,
            sourceLocation = sourceLocation,
            originalContentHash = originalContentHash
        )

        return track(request)
    }

    /**
     * Get all tracked instance requests.
     */
    fun getRequests(): List<InstanceRequest> {
        return keyIndex.values.toList()
    }

    /**
     * Get the monomorphic name for a function call, if tracked.
     *
     * Returns null if this call doesn't need monomorphization.
     *
     * @deprecated Use getMonomorphicKey() for structured access without string generation.
     */
    fun getMonomorphicName(
        functionName: String,
        typeSubstitution: Map<String, MezzeType>
    ): String? {
        if (typeSubstitution.isEmpty()) {
            return null
        }

        // Use structured key lookup (no string generation for deduplication)
        val key = MonomorphicKey.create(
            originalName = functionName,
            originalContentHash = null,
            typeArgs = typeSubstitution
        )

        // Only generate string if key exists
        return if (keyIndex.containsKey(key)) {
            key.toLegacyName()
        } else {
            null
        }
    }

    /**
     * Check if an instance is tracked by MonomorphicKey.
     */
    fun hasInstance(key: MonomorphicKey): Boolean {
        return keyIndex.containsKey(key)
    }

    /**
     * Check if an instance is tracked by legacy name.
     *
     * @deprecated Use hasInstance(MonomorphicKey) for structured lookup.
     */
    fun hasInstance(monomorphicName: String): Boolean {
        // Parse the legacy name back into a key for lookup
        // This is slow but maintains backward compatibility
        return keyIndex.keys.any { it.toLegacyName() == monomorphicName }
    }

    /**
     * Get the content hash for a monomorphic call, if tracked.
     *
     * Returns null if the call doesn't need monomorphization or
     * if the original function doesn't have a content hash.
     */
    fun getContentHash(
        functionName: String,
        typeSubstitution: Map<String, MezzeType>
    ): String? {
        if (typeSubstitution.isEmpty()) {
            return null
        }

        // Use structured key lookup
        val key = MonomorphicKey.create(
            originalName = functionName,
            originalContentHash = null,
            typeArgs = typeSubstitution
        )

        // Get the original tracked request (has the originalContentHash)
        val originalRequest = keyIndex[key] ?: return null

        // Generate the content hash for this monomorphic instance
        return originalRequest.generateContentHash()
    }

    /**
     * Get the number of tracked instances.
     */
    fun size(): Int {
        return keyIndex.size
    }

    /**
     * Merge two trackers.
     *
     * Combines all unique instance requests from both trackers.
     */
    fun merge(other: InstanceTracker): InstanceTracker {
        val mergedKeyIndex = keyIndex + other.keyIndex
        // Merge call counts by summing
        val mergedCallCounts = (callCounts.keys + other.callCounts.keys).associateWith { key ->
            (callCounts[key] ?: 0) + (other.callCounts[key] ?: 0)
        }
        return InstanceTracker(mergedKeyIndex, mergedCallCounts)
    }

    /**
     * Get the MonomorphicKey for a function call, if tracked.
     *
     * Returns null if this call doesn't need monomorphization.
     * This is the PREFERRED method for structured access.
     */
    fun getMonomorphicKey(
        functionName: String,
        typeSubstitution: Map<String, MezzeType>,
        originalContentHash: String? = null
    ): MonomorphicKey? {
        if (typeSubstitution.isEmpty()) {
            return null
        }

        val key = MonomorphicKey.create(
            originalName = functionName,
            originalContentHash = originalContentHash,
            typeArgs = typeSubstitution
        )

        return if (keyIndex.containsKey(key)) key else null
    }

    /**
     * Get instance request by MonomorphicKey.
     */
    fun getByKey(key: MonomorphicKey): InstanceRequest? {
        return keyIndex[key]
    }

    /**
     * Get all MonomorphicKeys tracked.
     */
    fun getKeys(): Set<MonomorphicKey> {
        return keyIndex.keys
    }
}

/**
 * Analyze an expression to find all polymorphic function calls.
 *
 * This walks the expression tree and tracks any calls that need
 * monomorphization based on their type substitutions.
 */
fun analyzeExpression(expr: Expression, tracker: InstanceTracker): InstanceTracker {
    return when (expr) {
        is FunctionCall -> {
            var currentTracker = tracker

            // Track this call if it has type substitutions
            val func = expr.function
            val functionName = when (func) {
                is Identifier -> func.localName
                else -> null
            }
            val contentHash = when (func) {
                is Identifier -> func.resolvedContentHash
                else -> null
            }

            if (functionName != null && expr.typeSubstitution.isNotEmpty()) {
                currentTracker = currentTracker.trackFromCall(
                    functionName,
                    expr.typeSubstitution,
                    expr.sourceLocation,
                    contentHash
                )
            }

            // Recursively analyze nested expressions
            currentTracker = analyzeExpression(expr.function, currentTracker)
            currentTracker = analyzeExpression(expr.arguments, currentTracker)

            currentTracker
        }

        is LambdaExpression -> {
            analyzeExpression(expr.body, tracker)
        }

        is DoExpression -> {
            var currentTracker = tracker
            for (stmt in expr.statements) {
                currentTracker = when (stmt) {
                    is LetStatement -> analyzeExpression(stmt.expression, currentTracker)
                    is ExpressionStatement -> analyzeExpression(stmt.expression, currentTracker)
                }
            }
            analyzeExpression(expr.result, currentTracker)
        }

        is IfExpression -> {
            var currentTracker = tracker
            currentTracker = analyzeExpression(expr.condition, currentTracker)
            currentTracker = analyzeExpression(expr.thenBranch, currentTracker)
            currentTracker = analyzeExpression(expr.elseBranch, currentTracker)
            currentTracker
        }

        is MatchExpression -> {
            var currentTracker = analyzeExpression(expr.expression, tracker)
            for (case in expr.cases) {
                currentTracker = analyzeExpression(case.expression, currentTracker)
                case.guard?.let { currentTracker = analyzeExpression(it, currentTracker) }
            }
            currentTracker
        }

        is RecordLiteral -> {
            var currentTracker = tracker
            for (entry in expr.entries) {
                currentTracker = when (entry) {
                    is RecordFieldEntry -> {
                        entry.value?.let { analyzeExpression(it, currentTracker) } ?: currentTracker
                    }
                    is RecordSpreadEntry -> analyzeExpression(entry.expression, currentTracker)
                }
            }
            currentTracker
        }

        is MemberAccess -> {
            analyzeExpression(expr.receiver, tracker)
        }

        is VariantConstruction -> {
            expr.value?.let { analyzeExpression(it, tracker) } ?: tracker
        }

        is SeqLiteral -> {
            var currentTracker = tracker
            for (element in expr.elements) {
                val elementExpr = when (element) {
                    is SeqRegularElement -> element.expression
                    is SeqSpreadElement -> element.expression
                }
                currentTracker = analyzeExpression(elementExpr, currentTracker)
            }
            currentTracker
        }

        is StringLiteral -> {
            var currentTracker = tracker
            expr.parts?.let { parts ->
                for (part in parts) {
                    if (part is ExpressionPart) {
                        currentTracker = analyzeExpression(part.expression, currentTracker)
                    }
                }
            }
            currentTracker
        }

        is PerformExpression -> {
            analyzeExpression(expr.expression, tracker)
        }

        is EffectCallExpression -> {
            analyzeExpression(expr.arguments, tracker)
        }

        is StateMachineExpression -> {
            var currentTracker = tracker
            for (state in expr.states) {
                currentTracker = when (state) {
                    is ComputeState -> analyzeExpression(state.computation, currentTracker)
                    is YieldState -> analyzeExpression(state.valueExpression, currentTracker)
                    is CompleteState -> analyzeExpression(state.resultExpression, currentTracker)
                }
            }
            currentTracker
        }

        // Literals and identifiers don't contain function calls
        else -> tracker
    }
}

/**
 * Analyze a program to find all needed monomorphic instances.
 *
 * Walks the entire program AST and tracks all polymorphic function calls.
 */
fun analyzeProgram(program: Program): InstanceTracker {
    var tracker = InstanceTracker.empty()

    for (item in program.items) {
        tracker = when (item) {
            is LetBinding -> analyzeExpression(item.expression, tracker)
            is TopLevelExpression -> analyzeExpression(item.expression, tracker)
            is ImplDeclaration -> {
                var currentTracker = tracker
                for (op in item.operations) {
                    currentTracker = analyzeExpression(op.implementation, currentTracker)
                }
                currentTracker
            }
            is ImplAbilityDeclaration -> {
                var currentTracker = tracker
                for (op in item.operations) {
                    currentTracker = analyzeExpression(op.implementation, currentTracker)
                }
                currentTracker
            }
            else -> tracker
        }
    }

    return tracker
}
