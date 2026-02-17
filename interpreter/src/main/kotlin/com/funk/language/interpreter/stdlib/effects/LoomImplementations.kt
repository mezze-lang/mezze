package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import com.funk.language.interpreter.stdlib.concurrent.FunkCancellationToken
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

    // Semaphore pools for limiting concurrent tasks per max_concurrent value
    private val concurrencySemaphores = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.Semaphore>()

    private fun getSemaphore(maxConcurrent: Int): java.util.concurrent.Semaphore? {
        if (maxConcurrent <= 0) return null  // 0 means unbounded
        return concurrencySemaphores.computeIfAbsent(maxConcurrent) {
            java.util.concurrent.Semaphore(it)
        }
    }

    /**
     * Register all Loom-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerSpawn(context)
        registerSpawnCfg(context)
        registerAwait(context)
        registerRace(context)
        registerAll_(context)
        registerTimeout(context)
        registerCancel(context)
        registerSleep(context)
        registerIsDone(context)
        registerIsCancelled(context)
        registerCheck(context)
        registerCurrentThread(context)
        registerPark(context)
        registerUnpark(context)
        // New structured concurrency operations
        registerJoin(context)
        registerJoin3(context)
        // New collection operations
        registerMap(context)
        registerForEach(context)
        registerFilter(context)
        // New error handling operations
        registerAny(context)
        registerSettle(context)
        // Structured concurrency scopes
        registerScoped(context)
        registerScopeSpawn(context)
        registerScopeCancelAll(context)
        registerScopeGetToken(context)
        registerScopeIsCancelled(context)
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

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_spawn", "expects 'f' field")

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
     * __native_loom_spawn_cfg - Spawn with configuration.
     *
     * Takes: { f: {} -> 'a, max_concurrent: Int }
     * Returns: Promise 'a
     *
     * Like spawn but with configuration:
     * - max_concurrent: Maximum concurrent tasks (0 = unbounded)
     */
    private fun registerSpawnCfg(context: MutableMap<String, FunkValue>) {
        context["__native_loom_spawn_cfg"] = NativeFunkFunction("__native_loom_spawn_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_spawn_cfg", "expects FunkRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_spawn_cfg", "expects 'f' field")

            val maxConcurrent = (record.fields["max_concurrent"] as? FunkInt)?.value?.toInt() ?: 0

            when (computation) {
                is FunkTruffleFunction, is NativeFunkFunction -> {
                    val future = CompletableFuture<FunkValue>()
                    val semaphore = getSemaphore(maxConcurrent)

                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_loom_spawn_cfg", "No Truffle environment available")

                    val thread = env.newTruffleThreadBuilder {
                        try {
                            // Acquire semaphore permit if bounded
                            semaphore?.acquire()
                            try {
                                val result = when (computation) {
                                    is FunkTruffleFunction -> {
                                        val emptyArgs = FunkRecord(emptyMap())
                                        computation.callTarget.call(
                                            emptyArgs,
                                            computation.closureVariables,
                                            computation.lexicalScope,
                                            computation.lexicalScopeSlotMap
                                        ) as FunkValue
                                    }
                                    is NativeFunkFunction -> {
                                        computation.call(emptyList())
                                    }
                                    else -> throw FunkException("Unexpected function type")
                                }
                                future.complete(result)
                            } finally {
                                // Release semaphore permit
                                semaphore?.release()
                            }
                        } catch (e: Exception) {
                            semaphore?.release()
                            e.printStackTrace()
                            future.completeExceptionally(e)
                        }
                    }
                        .virtual(true)
                        .build()

                    thread.start()
                    FunkPromise(future)
                }
                else -> throw NativeFunctionException(
                    "__native_loom_spawn_cfg",
                    "f must be a function, got: ${computation::class.simpleName}"
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

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_await", "expects 'p' field")

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
     * __native_loom_race - Wait for first Promise to complete.
     */
    private fun registerRace(context: MutableMap<String, FunkValue>) {
        context["__native_loom_race"] = NativeFunkFunction("__native_loom_race") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_race", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_race", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList().map { it as FunkPromise }
                else -> throw NativeFunctionException("__native_loom_race", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_loom_race", "cannot race empty list")
            }

            // Use CompletableFuture.anyOf to wait for first completion
            val futures = promises.map { it.future }
            val firstFuture = CompletableFuture.anyOf(*futures.toTypedArray())
            firstFuture.get() as FunkValue
        }
    }

    /**
     * __native_loom_all - Wait for all Promises to complete.
     */
    private fun registerAll_(context: MutableMap<String, FunkValue>) {
        context["__native_loom_all"] = NativeFunkFunction("__native_loom_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_all", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_all", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList().map { it as FunkPromise }
                else -> throw NativeFunctionException("__native_loom_all", "ps must be a list")
            }

            // Wait for all and collect results
            val results = promises.map { it.await() }
            FunkArrayList(results)
        }
    }

    /**
     * __native_loom_timeout - Wait with timeout.
     */
    private fun registerTimeout(context: MutableMap<String, FunkValue>) {
        context["__native_loom_timeout"] = NativeFunkFunction("__native_loom_timeout") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_timeout", "expects FunkRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_loom_timeout", "expects 'ms' field")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_timeout", "expects 'p' field")

            val timeout = (ms as FunkInt).value.toLong()
            val promise = p as FunkPromise

            try {
                val result = promise.future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                FunkVariant("Some", FunkRecord(mapOf("val" to result)))
            } catch (e: java.util.concurrent.TimeoutException) {
                FunkVariant("None", FunkRecord(emptyMap()))
            }
        }
    }

    /**
     * __native_loom_cancel - Cancel a Promise.
     */
    private fun registerCancel(context: MutableMap<String, FunkValue>) {
        context["__native_loom_cancel"] = NativeFunkFunction("__native_loom_cancel") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_cancel", "expects FunkRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_cancel", "expects 'p' field")

            val promise = p as FunkPromise
            promise.future.cancel(true)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_loom_sleep - Sleep for specified milliseconds.
     */
    private fun registerSleep(context: MutableMap<String, FunkValue>) {
        context["__native_loom_sleep"] = NativeFunkFunction("__native_loom_sleep") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_sleep", "expects FunkRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_loom_sleep", "expects 'ms' field")

            val timeout = (ms as FunkInt).value.toLong()
            Thread.sleep(timeout)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_loom_is_done - Check if Promise is complete.
     */
    private fun registerIsDone(context: MutableMap<String, FunkValue>) {
        context["__native_loom_is_done"] = NativeFunkFunction("__native_loom_is_done") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_is_done", "expects FunkRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_is_done", "expects 'p' field")

            val promise = p as FunkPromise
            FunkBool(promise.future.isDone)
        }
    }

    /**
     * __native_loom_is_cancelled - Check if current computation was cancelled.
     */
    private fun registerIsCancelled(context: MutableMap<String, FunkValue>) {
        context["__native_loom_is_cancelled"] = NativeFunkFunction("__native_loom_is_cancelled") { args ->
            FunkBool(Thread.currentThread().isInterrupted)
        }
    }

    /**
     * __native_loom_check - Cancellation checkpoint.
     */
    private fun registerCheck(context: MutableMap<String, FunkValue>) {
        context["__native_loom_check"] = NativeFunkFunction("__native_loom_check") { args ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Computation was cancelled")
            }
            FunkRecord(emptyMap())
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

    // =========================================================================
    // Structured Concurrency Operations
    // =========================================================================

    /**
     * __native_loom_join - Run two computations concurrently, return both results.
     * If either fails, the error propagates.
     */
    private fun registerJoin(context: MutableMap<String, FunkValue>) {
        context["__native_loom_join"] = NativeFunkFunction("__native_loom_join") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_join", "expects FunkRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_loom_join", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_loom_join", "expects 'right' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_join", "No Truffle environment available")

            val leftFuture = CompletableFuture<FunkValue>()
            val rightFuture = CompletableFuture<FunkValue>()

            // Spawn left computation
            val leftThread = env.newTruffleThreadBuilder {
                try {
                    val result = callFunction(leftFn)
                    leftFuture.complete(result)
                } catch (e: Exception) {
                    leftFuture.completeExceptionally(e)
                }
            }.virtual(true).build()

            // Spawn right computation
            val rightThread = env.newTruffleThreadBuilder {
                try {
                    val result = callFunction(rightFn)
                    rightFuture.complete(result)
                } catch (e: Exception) {
                    rightFuture.completeExceptionally(e)
                }
            }.virtual(true).build()

            leftThread.start()
            rightThread.start()

            // Wait for both and return results
            val leftResult = leftFuture.get()
            val rightResult = rightFuture.get()

            FunkRecord(mapOf(
                "left" to leftResult,
                "right" to rightResult
            ))
        }
    }

    /**
     * __native_loom_join3 - Run three computations concurrently.
     */
    private fun registerJoin3(context: MutableMap<String, FunkValue>) {
        context["__native_loom_join3"] = NativeFunkFunction("__native_loom_join3") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_join3", "expects FunkRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_loom_join3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_loom_join3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_loom_join3", "expects 'c' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_join3", "No Truffle environment available")

            val aFuture = CompletableFuture<FunkValue>()
            val bFuture = CompletableFuture<FunkValue>()
            val cFuture = CompletableFuture<FunkValue>()

            val aThread = env.newTruffleThreadBuilder {
                try { aFuture.complete(callFunction(aFn)) }
                catch (e: Exception) { aFuture.completeExceptionally(e) }
            }.virtual(true).build()

            val bThread = env.newTruffleThreadBuilder {
                try { bFuture.complete(callFunction(bFn)) }
                catch (e: Exception) { bFuture.completeExceptionally(e) }
            }.virtual(true).build()

            val cThread = env.newTruffleThreadBuilder {
                try { cFuture.complete(callFunction(cFn)) }
                catch (e: Exception) { cFuture.completeExceptionally(e) }
            }.virtual(true).build()

            aThread.start()
            bThread.start()
            cThread.start()

            FunkRecord(mapOf(
                "a" to aFuture.get(),
                "b" to bFuture.get(),
                "c" to cFuture.get()
            ))
        }
    }

    // =========================================================================
    // Collection Operations
    // =========================================================================

    /**
     * __native_loom_map - Apply function to each element concurrently.
     * Results are returned in the same order as input.
     */
    private fun registerMap(context: MutableMap<String, FunkValue>) {
        context["__native_loom_map"] = NativeFunkFunction("__native_loom_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_map", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_loom_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_map", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_loom_map", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_map", "No Truffle environment available")

            // Create futures for each element
            val futures = list.map { element ->
                val future = CompletableFuture<FunkValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        val result = callFunctionWithArg(f, element)
                        future.complete(result)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Wait for all and collect results in order
            val results = futures.map { it.get() }
            FunkArrayList(results)
        }
    }

    /**
     * __native_loom_for_each - Run function on each element for side effects.
     */
    private fun registerForEach(context: MutableMap<String, FunkValue>) {
        context["__native_loom_for_each"] = NativeFunkFunction("__native_loom_for_each") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_for_each", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_loom_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_for_each", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_loom_for_each", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(emptyMap())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_for_each", "No Truffle environment available")

            val futures = list.map { element ->
                val future = CompletableFuture<FunkValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        callFunctionWithArg(f, element)
                        future.complete(FunkRecord(emptyMap()))
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Wait for all to complete
            futures.forEach { it.get() }
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_loom_filter - Filter elements with predicate evaluated concurrently.
     */
    private fun registerFilter(context: MutableMap<String, FunkValue>) {
        context["__native_loom_filter"] = NativeFunkFunction("__native_loom_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_filter", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_loom_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_filter", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_loom_filter", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_filter", "No Truffle environment available")

            // Evaluate predicate for each element concurrently
            val futures = list.map { element ->
                val future = CompletableFuture<Pair<FunkValue, Boolean>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        val result = callFunctionWithArg(f, element)
                        val keep = (result as? FunkBool)?.value ?: false
                        future.complete(Pair(element, keep))
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Collect results, keeping only elements where predicate returned true
            val results = futures.map { it.get() }
                .filter { it.second }
                .map { it.first }
            FunkArrayList(results)
        }
    }

    // =========================================================================
    // Error Handling Operations
    // =========================================================================

    /**
     * __native_loom_any - Return first successful result, fail only if ALL fail.
     */
    private fun registerAny(context: MutableMap<String, FunkValue>) {
        context["__native_loom_any"] = NativeFunkFunction("__native_loom_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_any", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_any", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList().map { it as FunkPromise }
                else -> throw NativeFunctionException("__native_loom_any", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_loom_any", "cannot get any from empty list")
            }

            // Try to get first successful result
            val errors = mutableListOf<Throwable>()

            // Create a future that completes with first success
            val resultFuture = CompletableFuture<FunkValue>()
            val remaining = java.util.concurrent.atomic.AtomicInteger(promises.size)

            for (promise in promises) {
                promise.future.whenComplete { result, error ->
                    if (error == null && !resultFuture.isDone) {
                        resultFuture.complete(result)
                    } else if (error != null) {
                        synchronized(errors) {
                            errors.add(error)
                        }
                        if (remaining.decrementAndGet() == 0 && !resultFuture.isDone) {
                            resultFuture.completeExceptionally(
                                RuntimeException("All promises failed: ${errors.map { it.message }}")
                            )
                        }
                    } else {
                        remaining.decrementAndGet()
                    }
                }
            }

            resultFuture.get()
        }
    }

    /**
     * __native_loom_settle - Wait for all promises, return list of Results.
     * Never throws - collects Ok/Err for each promise.
     */
    private fun registerSettle(context: MutableMap<String, FunkValue>) {
        context["__native_loom_settle"] = NativeFunkFunction("__native_loom_settle") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_settle", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_settle", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList().map { it as FunkPromise }
                else -> throw NativeFunctionException("__native_loom_settle", "ps must be a list")
            }

            // Collect results as Result variants
            val results = promises.map { promise ->
                try {
                    val value = promise.future.get()
                    // Ok {val = value}
                    FunkVariant("Ok", FunkRecord(mapOf("val" to value)))
                } catch (e: java.util.concurrent.CancellationException) {
                    // Err {error = AsyncCancelled}
                    FunkVariant("Err", FunkRecord(mapOf(
                        "error" to FunkVariant("AsyncCancelled", FunkRecord(emptyMap()))
                    )))
                } catch (e: Exception) {
                    // Err {error = AsyncFailed {msg = ...}}
                    val message = e.cause?.message ?: e.message ?: "Unknown error"
                    FunkVariant("Err", FunkRecord(mapOf(
                        "error" to FunkVariant("AsyncFailed", FunkRecord(mapOf(
                            "msg" to FunkString(message)
                        )))
                    )))
                }
            }

            FunkArrayList(results)
        }
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    /**
     * Call a function with no arguments.
     */
    private fun callFunction(fn: FunkValue): FunkValue {
        return when (fn) {
            is FunkTruffleFunction -> {
                val emptyArgs = FunkRecord(emptyMap())
                fn.callTarget.call(
                    emptyArgs,
                    fn.closureVariables,
                    fn.lexicalScope,
                    fn.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> fn.call(emptyList())
            else -> throw FunkException("Expected function, got: ${fn::class.simpleName}")
        }
    }

    /**
     * Call a function with a single argument wrapped in a record {x = arg}.
     * This is the Funk convention for functions taking a single argument.
     */
    private fun callFunctionWithArg(fn: FunkValue, arg: FunkValue): FunkValue {
        // Wrap the argument in a record with field "x"
        val recordArg = FunkRecord(mapOf("x" to arg))
        return when (fn) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(
                    recordArg,
                    fn.closureVariables,
                    fn.lexicalScope,
                    fn.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> fn.call(listOf(recordArg))
            else -> throw FunkException("Expected function, got: ${fn::class.simpleName}")
        }
    }

    // =========================================================================
    // Structured Concurrency Scopes
    // =========================================================================

    /**
     * __native_loom_scoped - Run a function with a structured concurrency scope.
     *
     * Takes: { f: {scope: Scope} -> 'a }
     * Returns: 'a
     *
     * Creates a scope, runs the function with it, waits for all spawned children
     * to complete, then returns the result. If the function fails, all children
     * are cancelled. If any child fails, the scope fails and other children are cancelled.
     *
     * This provides Kotlin/Java-style structured concurrency where:
     * - Parent always waits for all children to complete
     * - If parent fails, all children are cancelled
     * - If child fails, siblings are cancelled (fail-fast behavior)
     */
    private fun registerScoped(context: MutableMap<String, FunkValue>) {
        context["__native_loom_scoped"] = NativeFunkFunction("__native_loom_scoped") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_scoped", "expects FunkRecord")

            val fn = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_scoped", "expects 'f' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_scoped", "No Truffle environment available")

            // Create the scope
            val scope = FunkScope(env)

            try {
                // Call the function with the scope
                val result = callFunctionWithScopeArg(fn, scope)

                // Wait for all spawned children to complete
                scope.awaitAll()

                // Check if any child failed
                val error = scope.getFirstError()
                if (error != null) {
                    throw error
                }

                result
            } catch (e: Exception) {
                // Cancel all children on failure
                scope.cancelAll()
                throw e
            } finally {
                // Always wait for all children to finish (even if cancelled)
                scope.awaitAllQuietly()
            }
        }
    }

    /**
     * __native_loom_scope_spawn - Spawn a task within a scope.
     *
     * Takes: { scope: Scope, f: {} -> 'a }
     * Returns: Promise 'a
     *
     * Spawns a task that is tracked by the scope. The scope will wait for this
     * task to complete before returning.
     */
    private fun registerScopeSpawn(context: MutableMap<String, FunkValue>) {
        context["__native_loom_scope_spawn"] = NativeFunkFunction("__native_loom_scope_spawn") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "expects FunkRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "expects 'scope' field")

            val fn = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "expects 'f' field")

            val scope = scopeVal as? FunkScope
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "scope must be a Scope")

            // Spawn the task within the scope
            scope.spawn(fn)
        }
    }

    /**
     * __native_loom_scope_cancel_all - Cancel all tasks in a scope.
     *
     * Takes: { scope: Scope }
     * Returns: {}
     *
     * Requests cancellation of all tasks spawned within the scope.
     */
    private fun registerScopeCancelAll(context: MutableMap<String, FunkValue>) {
        context["__native_loom_scope_cancel_all"] = NativeFunkFunction("__native_loom_scope_cancel_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_scope_cancel_all", "expects FunkRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_cancel_all", "expects 'scope' field")

            val scope = scopeVal as? FunkScope
                ?: throw NativeFunctionException("__native_loom_scope_cancel_all", "scope must be a Scope")

            scope.cancelAll()
            FunkUnit
        }
    }

    /**
     * __native_loom_scope_get_token - Get the CancellationToken for a scope.
     *
     * Takes: { scope: Scope }
     * Returns: CancellationToken
     *
     * Returns the CancellationToken associated with this scope.
     * Tasks can use this to check for cancellation or register callbacks.
     */
    private fun registerScopeGetToken(context: MutableMap<String, FunkValue>) {
        context["__native_loom_scope_get_token"] = NativeFunkFunction("__native_loom_scope_get_token") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_scope_get_token", "expects FunkRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_get_token", "expects 'scope' field")

            val scope = scopeVal as? FunkScope
                ?: throw NativeFunctionException("__native_loom_scope_get_token", "scope must be a Scope")

            FunkCancellationToken(scope.cancellationToken)
        }
    }

    /**
     * __native_loom_scope_is_cancelled - Check if a scope is cancelled.
     *
     * Takes: { scope: Scope }
     * Returns: Bool
     *
     * Returns true if the scope has been cancelled.
     */
    private fun registerScopeIsCancelled(context: MutableMap<String, FunkValue>) {
        context["__native_loom_scope_is_cancelled"] = NativeFunkFunction("__native_loom_scope_is_cancelled") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_loom_scope_is_cancelled", "expects FunkRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_is_cancelled", "expects 'scope' field")

            val scope = scopeVal as? FunkScope
                ?: throw NativeFunctionException("__native_loom_scope_is_cancelled", "scope must be a Scope")

            FunkBool(scope.isCancelled())
        }
    }

    /**
     * Call a function with a scope argument wrapped in a record {scope = arg}.
     */
    private fun callFunctionWithScopeArg(fn: FunkValue, scope: FunkScope): FunkValue {
        val recordArg = FunkRecord(mapOf("scope" to scope))
        return when (fn) {
            is FunkTruffleFunction -> {
                fn.callTarget.call(
                    recordArg,
                    fn.closureVariables,
                    fn.lexicalScope,
                    fn.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> fn.call(listOf(recordArg))
            else -> throw FunkException("Expected function, got: ${fn::class.simpleName}")
        }
    }
}

/**
 * FunkScope - Tracks spawned tasks for structured concurrency.
 *
 * A scope maintains a list of all tasks spawned within it and ensures:
 * - All tasks complete before the scope exits
 * - If any task fails, all others are cancelled
 * - If the scope function fails, all tasks are cancelled
 *
 * The scope has an associated CancellationToken that:
 * - Is cancelled when the scope fails or is explicitly cancelled
 * - Can be used by child tasks to check for cancellation
 * - Can have parent token for hierarchical cancellation
 */
class FunkScope(
    private val env: com.oracle.truffle.api.TruffleLanguage.Env,
    parentToken: com.funk.language.interpreter.effects.CancellationToken? = null
) : FunkValue() {

    private val tasks = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<FunkValue>>()
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    // CancellationToken for this scope - child of parent if provided
    val cancellationToken: com.funk.language.interpreter.effects.CancellationToken =
        parentToken?.createChild() ?: com.funk.language.interpreter.effects.CancellationToken()

    /**
     * Spawn a new task within this scope.
     */
    fun spawn(fn: FunkValue): FunkPromise {
        val future = CompletableFuture<FunkValue>()
        tasks.add(future)

        val thread = env.newTruffleThreadBuilder {
            try {
                // Check if scope is cancelled before starting
                if (cancelled.get() || cancellationToken.isCancelled()) {
                    future.cancel(true)
                    return@newTruffleThreadBuilder
                }

                val result = when (fn) {
                    is FunkTruffleFunction -> {
                        val emptyArgs = FunkRecord(emptyMap())
                        fn.callTarget.call(
                            emptyArgs,
                            fn.closureVariables,
                            fn.lexicalScope,
                            fn.lexicalScopeSlotMap
                        ) as FunkValue
                    }
                    is NativeFunkFunction -> fn.call(emptyList())
                    else -> throw FunkException("Expected function, got: ${fn::class.simpleName}")
                }
                future.complete(result)
            } catch (e: com.funk.language.interpreter.effects.EffectCancellationException) {
                // Task was cancelled via CancellationToken - don't treat as error
                future.cancel(true)
            } catch (e: Exception) {
                // Record first error for fail-fast behavior
                firstError.compareAndSet(null, e)
                future.completeExceptionally(e)
                // Cancel siblings on failure (fail-fast)
                cancelAll()
            }
        }.virtual(true).build()

        threads.add(thread)
        thread.start()

        return FunkPromise(future)
    }

    /**
     * Cancel all tasks in this scope.
     */
    fun cancelAll() {
        cancelled.set(true)
        // Cancel the CancellationToken so tasks can detect cancellation
        cancellationToken.cancel("Scope cancelled")
        for (task in tasks) {
            task.cancel(true)
        }
        for (thread in threads) {
            thread.interrupt()
        }
    }

    /**
     * Wait for all tasks to complete.
     * Throws the first error if any task failed.
     */
    fun awaitAll() {
        for (task in tasks) {
            try {
                task.get()
            } catch (e: java.util.concurrent.CancellationException) {
                // Task was cancelled, that's ok
            } catch (e: java.util.concurrent.ExecutionException) {
                // Don't throw here, we'll check firstError after
            }
        }
    }

    /**
     * Wait for all tasks to complete, ignoring all errors.
     * Used in finally blocks for cleanup.
     */
    fun awaitAllQuietly() {
        for (task in tasks) {
            try {
                task.get()
            } catch (e: Exception) {
                // Ignore all errors
            }
        }
    }

    /**
     * Get the first error that occurred, or null if none.
     */
    fun getFirstError(): Throwable? = firstError.get()

    /**
     * Check if the scope is cancelled.
     */
    fun isCancelled(): Boolean = cancelled.get() || cancellationToken.isCancelled()

    override fun getDisplayString(): String = "<scope:${tasks.size} tasks>"
}

/**
 * Exception thrown by native functions when arguments are invalid.
 */
class NativeFunctionException(functionName: String, message: String) :
    RuntimeException("Native function $functionName error: $message")
