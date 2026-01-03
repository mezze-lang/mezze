package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import java.util.concurrent.CompletableFuture

/**
 * Native implementations for Project Loom virtual thread concurrency.
 *
 * Provides native functions that bridge Funk's Concurrency effect to Kotlin's
 * LoomConcurrencyManager, enabling lightweight concurrency with virtual threads.
 *
 * These implementations allow Funk programs to:
 * - Spawn computations on virtual threads (`__native_loom_spawn`)
 * - Wait for concurrent computations to complete (`__native_loom_await`)
 * - Execute multiple operations in parallel with structured concurrency
 */
object LoomImplementations {

    /**
     * Register all Loom-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerSpawn(context)
        registerAwait(context)
    }

    /**
     * __native_loom_spawn - Spawn a computation on a virtual thread.
     *
     * Takes: { computation: {} -> 'a }
     * Returns: Promise 'a
     *
     * Creates a new Project Loom virtual thread and executes the computation
     * on it. Returns immediately with a Promise that will eventually contain
     * the result.
     *
     * Example usage in Funk:
     *   perform Loom in do
     *     let promise = Concurrency.spawn {computation = {
     *       expensive_operation {}
     *     }}
     *     # Do other work while computation runs
     *     let result = Concurrency.await {promise = promise}
     */
    private fun registerSpawn(context: MutableMap<String, FunkValue>) {
        context["__native_loom_spawn"] = NativeFunkFunction("__native_loom_spawn") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_spawn", "expects FunkRecord")

            val computation = record.fields["computation"]
                ?: throw NativeFunctionException("__native_loom_spawn", "expects 'computation' field")

            // Ensure computation is a callable function
            when (computation) {
                is FunkTruffleFunction, is NativeFunkFunction -> {
                    // Create a CompletableFuture to represent the Promise
                    val future = CompletableFuture<FunkValue>()

                    // Create Loom concurrency manager
                    val loomManager = LoomConcurrencyManager()

                    // Spawn virtual thread to execute the computation
                    loomManager.spawnVirtualThread("funk-spawn-${System.nanoTime()}") {
                        try {
                            // Call the computation with empty arguments
                            val result = when (computation) {
                                is FunkTruffleFunction -> {
                                    // Zero-parameter function - pass empty record
                                    val emptyArgs = FunkRecord(emptyMap())
                                    computation.callTarget.call(emptyArgs, computation.closureVariables) as FunkValue
                                }
                                is NativeFunkFunction -> {
                                    // Call with empty list
                                    computation.call(emptyList())
                                }
                                else -> throw FunkException("Unexpected function type")
                            }

                            // Complete the future with the result
                            future.complete(result)
                        } catch (e: Exception) {
                            // Complete exceptionally on error
                            future.completeExceptionally(e)
                        }
                    }

                    // Return the Promise wrapping the future
                    FunkPromise(future)
                }
                else -> throw NativeFunctionException(
                    "__native_loom_spawn",
                    "computation must be a function, got: ${computation::class.simpleName}"
                )
            }
        }
    }

    /**
     * __native_loom_await - Wait for a Promise to complete and return its value.
     *
     * Takes: { promise: Promise 'a }
     * Returns: 'a
     *
     * Blocks the current thread (which should be a virtual thread) until the
     * Promise completes, then returns the result. If the Promise was rejected,
     * throws an exception.
     *
     * Example usage in Funk:
     *   perform Loom in do
     *     let promise = Concurrency.spawn {computation = {operation {}}}
     *     let result = Concurrency.await {promise = promise}
     *     result
     */
    private fun registerAwait(context: MutableMap<String, FunkValue>) {
        context["__native_loom_await"] = NativeFunkFunction("__native_loom_await") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_await", "expects FunkRecord")

            val promise = record.fields["promise"]
                ?: throw NativeFunctionException("__native_loom_await", "expects 'promise' field")

            when (promise) {
                is FunkPromise -> {
                    // Block until the promise completes and return the result
                    promise.await()
                }
                else -> throw NativeFunctionException(
                    "__native_loom_await",
                    "promise must be a FunkPromise, got: ${promise::class.simpleName}"
                )
            }
        }
    }
}

/**
 * Exception thrown by native functions when arguments are invalid.
 */
class NativeFunctionException(functionName: String, message: String) :
    RuntimeException("Native function $functionName error: $message")
