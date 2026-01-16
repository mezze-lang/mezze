package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import java.util.concurrent.CompletableFuture
import com.oracle.truffle.api.CallTarget

/**
 * Truffle-optimized implementations for Project Loom virtual thread concurrency.
 *
 * Phase 8 of Truffle migration: Effect system optimized for JIT performance.
 * Provides native functions for lightweight concurrency with virtual threads.
 *
 * Optimizations:
 * - TruffleThreadBuilder for proper Truffle context sharing across threads
 * - CompletableFuture-based promises for async result handling
 * - Virtual threads (Project Loom) for efficient concurrency
 * - LockSupport.park/unpark for low-level thread coordination
 * - Proper Truffle function calling (4 arguments: args, closure, scope, slot map)
 * - Thread-local state management via TruffleContextHolder
 *
 * CRITICAL: Uses TruffleThreadBuilder to avoid "Multi threaded access not allowed" errors.
 * Each spawned thread has proper access to the TruffleContext via newTruffleThreadBuilder.
 *
 * Operations: spawn, await, current_thread, park, unpark
 *
 * These implementations allow Funk programs to:
 * - Spawn computations on virtual threads (lightweight concurrency)
 * - Wait for concurrent computations to complete (structured concurrency)
 * - Coordinate threads with park/unpark primitives
 *
 * Total: 5 Loom functions
 */
object LoomImplementations {

    /**
     * Register all Loom-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerSpawn(context)
        registerAwait(context)
        registerCurrentThread(context)
        registerPark(context)
        registerUnpark(context)
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

                    // Capture the current Truffle environment for thread creation
                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_loom_spawn", "No Truffle environment available")

                    // CRITICAL: Use TruffleThreadBuilder to create threads properly
                    // This ensures the thread has proper access to the TruffleContext
                    // and avoids "Multi threaded access not allowed" errors
                    val thread = env.newTruffleThreadBuilder {

                        try {
                            val result = when (computation) {
                                is FunkTruffleFunction -> {
                                    // The TruffleThreadBuilder automatically enters the context
                                    // so we can call CallTargets directly
                                    val emptyArgs = FunkRecord(emptyMap())
                                    // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                                    // [0] = args record, [1] = closure variables map (legacy),
                                    // [2] = MaterializedFrame (lexical scope), [3] = slot mapping

                                    val value = computation.callTarget.call(
                                        emptyArgs,
                                        computation.closureVariables,
                                        computation.lexicalScope,
                                        computation.lexicalScopeSlotMap
                                    ) as FunkValue

                                    value
                                }
                                is NativeFunkFunction -> {
                                    computation.call(emptyList())
                                }
                                else -> throw FunkException("Unexpected function type: ${computation::class.simpleName}")
                            }

                            future.complete(result)
                        } catch (e: Exception) {

                            e.printStackTrace()
                            future.completeExceptionally(e)
                        }
                    }
                        .virtual(true)  // Use Java virtual threads (Project Loom)
                        .build()

                    // Start the thread
                    thread.start()

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

    /**
     * __native_loom_current_thread - Get a handle to the current thread.
     *
     * Takes: {}
     * Returns: Thread
     *
     * Returns an opaque handle to the current Java thread (typically a virtual thread).
     * This handle can be passed to other threads for coordination via unpark.
     *
     * Example usage in Funk:
     *   let receiver_thread = current_thread {}
     *   # Pass this handle to other threads for signaling
     */
    private fun registerCurrentThread(context: MutableMap<String, FunkValue>) {
        context["__native_loom_current_thread"] = NativeFunkFunction("__native_loom_current_thread") { args ->
            // Get current thread and wrap it in FunkThread
            val currentThread = Thread.currentThread()
            FunkThread(currentThread)
        }
    }

    /**
     * __native_loom_park - Park (suspend) the current virtual thread.
     *
     * Takes: {}
     * Returns: {}
     *
     * Parks (suspends) the current virtual thread until it is unparked by another thread.
     * While parked, the thread does not consume CPU resources. Uses LockSupport.park()
     * internally for efficient blocking.
     *
     * CRITICAL: This is a low-level synchronization primitive. The thread will remain
     * parked until another thread calls unpark on this thread's handle.
     *
     * Example usage in Funk:
     *   park {}  # Suspends current thread until unparked
     */
    private fun registerPark(context: MutableMap<String, FunkValue>) {
        context["__native_loom_park"] = NativeFunkFunction("__native_loom_park") { args ->

            // Park the current thread using LockSupport
            java.util.concurrent.locks.LockSupport.park()

            FunkRecord(emptyMap())  // Return empty record (unit type)
        }
    }

    /**
     * __native_loom_unpark - Unpark (resume) a parked virtual thread.
     *
     * Takes: { thread: Thread }
     * Returns: {}
     *
     * Unparks the given thread, allowing it to resume execution if it was parked.
     * If the thread is not currently parked, the next park() call will return immediately.
     * Uses LockSupport.unpark() internally.
     *
     * CRITICAL: This is a low-level synchronization primitive for custom coordination
     * patterns between threads.
     *
     * Example usage in Funk:
     *   unpark {thread = receiver_thread}  # Wakes up the parked thread
     */
    private fun registerUnpark(context: MutableMap<String, FunkValue>) {
        context["__native_loom_unpark"] = NativeFunkFunction("__native_loom_unpark") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_unpark", "expects FunkRecord")

            val threadValue = record.fields["thread"]
                ?: throw NativeFunctionException("__native_loom_unpark", "expects 'thread' field")

            when (threadValue) {
                is FunkThread -> {

                    // Unpark the thread using LockSupport
                    java.util.concurrent.locks.LockSupport.unpark(threadValue.thread)
                    FunkRecord(emptyMap())  // Return empty record (unit type)
                }
                else -> throw NativeFunctionException(
                    "__native_loom_unpark",
                    "thread must be a FunkThread, got: ${threadValue::class.simpleName}"
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
