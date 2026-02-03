package com.funk.language.typechecker.continuation

import com.funk.language.ast.*

/**
 * Continuation transformation results and types.
 *
 * This module handles transformation of continuation-using code into
 * explicit state machines for the Funk runtime.
 *
 * ## What are Continuations?
 *
 * Continuations are a way to capture the "rest of the computation" at a
 * point in time. They enable powerful control flow features like:
 * - Delimited continuations
 * - Effect handlers
 * - Coroutines/generators
 * - Non-local control flow
 *
 * ## Continuation-Passing Style (CPS)
 *
 * In CPS, every function takes an extra argument: a continuation function
 * that represents what to do with the result.
 *
 * ## State Machine Transformation
 *
 * Rather than using actual CPS (which is hard to optimize), we transform
 * continuation-using code into explicit state machines that can be
 * efficiently executed by the runtime.
 *
 * ## Example Transformation
 *
 * **Input (continuation-using code)**:
 * ```funk
 * do {
 *   let x = compute()
 *   Continuation.suspend_with {value = x}
 *   let y = compute2()
 *   y
 * }
 * ```
 *
 * **Output (state machine)**:
 * ```
 * StateMachine {
 *   states = [
 *     ComputeState(0, compute(), nextState = 1),
 *     YieldState(1, x, capturedVars = ["x"], nextState = 2),
 *     ComputeState(2, compute2(), nextState = 3),
 *     CompleteState(3, y)
 *   ]
 * }
 * ```
 *
 * ## Design Principles
 *
 * - Pure functions (no mutation!)
 * - Immutable data structures
 * - Sealed result types (no nulls!)
 * - Clear error messages
 * - Efficient state machines
 */

/**
 * Result of continuation transformation.
 *
 * Sealed type - exhaustive pattern matching, no nulls!
 */
sealed class ContinuationTransformResult {
    /**
     * Transformation succeeded.
     */
    data class Success(
        val transformedExpression: Expression,  // The transformed expression (usually StateMachineExpression)
        val warnings: List<String> = emptyList()
    ) : ContinuationTransformResult()

    /**
     * Transformation failed.
     */
    data class Failure(
        val errors: List<ContinuationTransformError>
    ) : ContinuationTransformResult()
}

/**
 * Continuation transformation error.
 */
data class ContinuationTransformError(
    val message: String,
    val expression: Expression?,
    val sourceLocation: SourceLocation? = null
)

/**
 * Analysis result for liveness.
 *
 * Used internally to track which variables need to be captured
 * at each suspension point.
 */
data class LivenessAnalysis(
    val liveVarsAtPoint: Map<Int, Set<String>>
)

/**
 * Suspension point information.
 *
 * Represents a point where the computation can be suspended
 * (e.g., Continuation.suspend, effect perform, etc.)
 */
data class SuspensionPoint(
    val statementIndex: Int,
    val expression: Expression,
    val capturedVariables: Set<String>,
    val suspensionType: SuspensionType
)

/**
 * Type of suspension.
 */
enum class SuspensionType {
    /**
     * suspend - suspend without a value
     */
    SUSPEND,

    /**
     * suspend_with {value = x} - suspend with a value
     */
    SUSPEND_WITH_VALUE,

    /**
     * capture {k} -> ... - capture the continuation
     */
    CAPTURE,

    /**
     * perform Effect.operation - effect operation
     */
    EFFECT_PERFORM
}

/**
 * State machine build result.
 *
 * Internal result used during state machine construction.
 */
data class StateMachineBuildResult(
    val states: List<StateMachineState>,
    val capturedVariables: List<String>,
    val warnings: List<String> = emptyList()
)

/**
 * Transformation configuration.
 *
 * Options for controlling continuation transformation.
 */
data class ContinuationTransformConfig(
    /**
     * Whether to optimize state machines by merging consecutive compute states.
     */
    val optimizeStateMachines: Boolean = true,

    /**
     * Whether to validate that captured variables are actually live.
     */
    val validateLiveness: Boolean = true,

    /**
     * Maximum number of states in a single state machine (prevents infinite loops).
     */
    val maxStates: Int = 1000
)

/**
 * Format a suspension point for error messages.
 */
fun formatSuspensionPoint(point: SuspensionPoint): String {
    return buildString {
        append("Suspension at statement ${point.statementIndex}")
        append(" (${point.suspensionType})")
        if (point.capturedVariables.isNotEmpty()) {
            append(", captures: ${point.capturedVariables.joinToString()}")
        }
    }
}

/**
 * Format a state for debugging.
 */
fun formatState(state: StateMachineState): String {
    return when (state) {
        is ComputeState -> "State ${state.id}: Compute -> ${state.nextStateId}"
        is YieldState -> "State ${state.id}: Yield (${state.capturedVariables.size} vars) -> ${state.nextStateId}"
        is CompleteState -> "State ${state.id}: Complete"
    }
}
