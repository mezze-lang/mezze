package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.FunkValue

/**
 * Exception thrown when fork is called within a delimiter context.
 *
 * Instead of returning an array to the caller, fork throws this exception
 * which propagates up to the delimiter boundary. The delimiter then handles
 * the forking by running the continuation for each value and collecting results.
 *
 * This enables proper nesting of multishot continuations:
 *
 * Example:
 *   perform AllPaths in do        // <- Delimiter boundary catches ForkedExecutionException
 *     let x = choose [1, 2]        // <- Throws ForkedExecutionException
 *     let y = choose [10, 20]      // <- Each path throws again
 *     x + y                        // <- Each path completes with single values
 *
 * Result: [11, 21, 12, 22] collected at delimiter
 */
class ForkedExecutionException(
    /**
     * The continuation to fork.
     * This represents "the rest of the computation" after the fork point.
     */
    val continuation: FunkValue,

    /**
     * The values to fork the continuation with.
     * The continuation will be resumed once for each value.
     */
    val values: List<FunkValue>
) : RuntimeException("Forked execution with ${values.size} branches") {

    init {
        // Don't fill in stack trace for performance
        // This exception is used for control flow, not error reporting
        stackTrace = emptyArray()
    }
}
