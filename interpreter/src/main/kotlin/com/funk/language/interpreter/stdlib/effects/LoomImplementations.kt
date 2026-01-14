package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import java.util.concurrent.CompletableFuture
import com.oracle.truffle.api.CallTarget

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

                    // WORKAROUND: Truffle functions cannot be called across thread boundaries
                    // due to context sharing restrictions. We execute them synchronously
                    // on the current thread but wrap the result in a CompletableFuture
                    // to maintain the async API contract.
                    //
                    // This means "spawn" is effectively synchronous for Truffle functions,
                    // but still allows the Promise/await pattern to work.
                    //
                    // TODO: Implement proper multi-context support using TruffleContext API
                    // for true concurrent execution.
                    when (computation) {
                        is FunkTruffleFunction -> {
                            // Execute synchronously on current thread (Truffle requirement)
                            try {
                                val emptyArgs = FunkRecord(emptyMap())
                                // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                                // [0] = args record, [1] = closure variables map (legacy),
                                // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                                val result = computation.callTarget.call(
                                    emptyArgs,
                                    computation.closureVariables,
                                    computation.lexicalScope,
                                    computation.lexicalScopeSlotMap
                                ) as FunkValue
                                future.complete(result)
                            } catch (e: Exception) {
                                future.completeExceptionally(e)
                            }
                        }
                        is NativeFunkFunction -> {
                            // Native functions CAN run on virtual threads safely
                            val loomManager = LoomConcurrencyManager()
                            loomManager.spawnVirtualThread("funk-spawn-${System.nanoTime()}") {
                                try {
                                    val result = computation.call(emptyList())
                                    future.complete(result)
                                } catch (e: Exception) {
                                    future.completeExceptionally(e)
                                }
                            }
                        }
                        else -> {
                            future.completeExceptionally(
                                FunkException("Unexpected function type: ${computation::class.simpleName}")
                            )
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
