package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkCont

/**
 * Tracks the active delimiter context for delimited continuations.
 *
 * When fork is called within a delimiter context, instead of immediately
 * executing and returning an array, we accumulate fork requests and execute
 * them when the delimiter exits. This enables proper nesting of multishot
 * continuations (like nested Choice effects).
 *
 * Example:
 *   perform AllPaths in do
 *     let x = Choice.choose { options = [1, 2] }  // Fork request 1
 *     let y = Choice.choose { options = [10, 20] } // Fork request 2
 *     x + y
 *
 * Instead of:
 *   - First fork returns [1, 2], x = array
 *   - Second fork returns [10, 20], y = array
 *   - x + y fails (can't add arrays)
 *
 * With delimiter context:
 *   - First fork registers request, continues with single value
 *   - Second fork registers request, continues with single value
 *   - At delimiter exit, execute all combinations: [11, 21, 12, 22]
 */
class DelimiterContext(
    val delimiter: FunkCont
) {
    companion object {
        /**
         * Thread-local storage for the current delimiter context.
         * Each virtual thread has its own delimiter stack.
         */
        private val currentContext = ThreadLocal<DelimiterContext?>()

        /**
         * Enter a delimiter context.
         * Returns the new context that was created.
         */
        fun enter(delimiter: FunkCont): DelimiterContext {
            val context = DelimiterContext(delimiter)
            currentContext.set(context)
            return context
        }

        /**
         * Exit the current delimiter context.
         */
        fun exit() {
            currentContext.set(null)
        }

        /**
         * Get the current delimiter context, if any.
         */
        fun current(): DelimiterContext? = currentContext.get()

        /**
         * Check if we're currently inside a delimiter context.
         */
        fun isActive(): Boolean = currentContext.get() != null
    }

    /**
     * List of fork requests accumulated in this delimiter.
     * Each request contains the continuation and the values to fork with.
     */
    private val forkRequests = mutableListOf<ForkRequest>()

    /**
     * Register a fork request within this delimiter.
     * Instead of executing immediately, we save it for later.
     */
    fun registerFork(continuation: FunkValue, values: List<FunkValue>) {
        forkRequests.add(ForkRequest(continuation, values))
    }

    /**
     * Get all accumulated fork requests.
     */
    fun getForkRequests(): List<ForkRequest> = forkRequests.toList()

    /**
     * Check if any fork requests have been registered.
     */
    fun hasForkRequests(): Boolean = forkRequests.isNotEmpty()
}

/**
 * Represents a fork request - a continuation and the values to fork it with.
 */
data class ForkRequest(
    val continuation: FunkValue,
    val values: List<FunkValue>
)
