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
 * - Uses MonomorphicKey for structured type-aware tracking
 */
class InstanceTracker private constructor(
    private val requests: Map<String, InstanceRequest>,
    /** Structured key index for O(1) type-directed lookup */
    private val keyIndex: Map<MonomorphicKey, InstanceRequest> = emptyMap()
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
     */
    fun track(request: InstanceRequest): InstanceTracker {
        val monomorphicName = request.generateMonomorphicName()
        val key = request.toMonomorphicKey()

        // Deduplicate: if we already have this instance, don't add it again
        if (requests.containsKey(monomorphicName)) {
            return this
        }

        // Return new tracker with this request added
        val newRequests = requests + (monomorphicName to request)
        val newKeyIndex = keyIndex + (key to request)
        return InstanceTracker(newRequests, newKeyIndex)
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
        return requests.values.toList()
    }

    /**
     * Get the monomorphic name for a function call, if tracked.
     *
     * Returns null if this call doesn't need monomorphization.
     */
    fun getMonomorphicName(
        functionName: String,
        typeSubstitution: Map<String, MezzeType>
    ): String? {
        if (typeSubstitution.isEmpty()) {
            return null
        }

        // Create a temporary request to compute the name
        val tempRequest = InstanceRequest(
            originalName = functionName,
            typeArgs = typeSubstitution
        )

        val monomorphicName = tempRequest.generateMonomorphicName()

        // Return the name only if we've tracked this instance
        return if (requests.containsKey(monomorphicName)) {
            monomorphicName
        } else {
            null
        }
    }

    /**
     * Check if an instance is tracked.
     */
    fun hasInstance(monomorphicName: String): Boolean {
        return requests.containsKey(monomorphicName)
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

        // Create a temporary request to compute the name
        val tempRequest = InstanceRequest(
            originalName = functionName,
            typeArgs = typeSubstitution
        )

        val monomorphicName = tempRequest.generateMonomorphicName()

        // Get the original tracked request (has the originalContentHash)
        val originalRequest = requests[monomorphicName] ?: return null

        // Generate the content hash for this monomorphic instance
        return originalRequest.generateContentHash()
    }

    /**
     * Get the number of tracked instances.
     */
    fun size(): Int {
        return requests.size
    }

    /**
     * Merge two trackers.
     *
     * Combines all unique instance requests from both trackers.
     */
    fun merge(other: InstanceTracker): InstanceTracker {
        val mergedRequests = requests + other.requests
        val mergedKeyIndex = keyIndex + other.keyIndex
        return InstanceTracker(mergedRequests, mergedKeyIndex)
    }

    /**
     * Get the MonomorphicKey for a function call, if tracked.
     *
     * Returns null if this call doesn't need monomorphization.
     * Preferred over getMonomorphicName() for structured access.
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

        is RecordUpdate -> {
            var currentTracker = analyzeExpression(expr.record, tracker)
            for (update in expr.updates) {
                currentTracker = update.value?.let { analyzeExpression(it, currentTracker) } ?: currentTracker
            }
            currentTracker
        }

        is RecordUpdateExpression -> {
            var currentTracker = analyzeExpression(expr.baseRecord, tracker)
            for (update in expr.updates) {
                currentTracker = analyzeExpression(update.value, currentTracker)
            }
            currentTracker
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

        is SeqAccess -> {
            var currentTracker = analyzeExpression(expr.seq, tracker)
            currentTracker = analyzeExpression(expr.index, currentTracker)
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
