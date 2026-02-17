package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.concurrency.LoomConcurrencyManager
import com.mezze.language.interpreter.stdlib.concurrent.MezzeCancellationToken
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
 * These implementations allow Mezze programs to:
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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
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
     * Example usage in Mezze:
     *   perform Loom in do
     *     let promise = Concurrency.spawn {computation = {
     *       expensive_operation {}
     *     }}
     *     # Do other work while computation runs
     *     let result = Concurrency.await {promise = promise}
     */
    private fun registerSpawn(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_spawn"] = NativeMezzeFunction("__native_loom_spawn") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_spawn", "expects MezzeRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_spawn", "expects 'f' field")

            // Ensure computation is a callable function
            when (computation) {
                is MezzeTruffleFunction, is NativeMezzeFunction -> {
                    // Create a CompletableFuture to represent the Promise
                    val future = CompletableFuture<MezzeValue>()

                    // Capture the current Truffle environment for thread creation
                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_loom_spawn", "No Truffle environment available")

                    // CRITICAL: Use TruffleThreadBuilder to create threads properly
                    // This ensures the thread has proper access to the TruffleContext
                    // and avoids "Multi threaded access not allowed" errors
                    val thread = env.newTruffleThreadBuilder {

                        try {
                            val result = when (computation) {
                                is MezzeTruffleFunction -> {
                                    // The TruffleThreadBuilder automatically enters the context
                                    // so we can call CallTargets directly
                                    val emptyArgs = MezzeRecord(emptyMap())
                                    // CRITICAL: MezzeFunctionRootNode expects 4 arguments:
                                    // [0] = args record, [1] = closure variables map (legacy),
                                    // [2] = MaterializedFrame (lexical scope), [3] = slot mapping

                                    val value = computation.callTarget.call(
                                        emptyArgs,
                                        computation.closureVariables,
                                        computation.lexicalScope,
                                        computation.lexicalScopeSlotMap
                                    ) as MezzeValue

                                    value
                                }
                                is NativeMezzeFunction -> {
                                    computation.call(emptyList())
                                }
                                else -> throw MezzeException("Unexpected function type: ${computation::class.simpleName}")
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
                    MezzePromise(future)
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
    private fun registerSpawnCfg(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_spawn_cfg"] = NativeMezzeFunction("__native_loom_spawn_cfg") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_spawn_cfg", "expects MezzeRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_spawn_cfg", "expects 'f' field")

            val maxConcurrent = (record.fields["max_concurrent"] as? MezzeInt)?.value?.toInt() ?: 0

            when (computation) {
                is MezzeTruffleFunction, is NativeMezzeFunction -> {
                    val future = CompletableFuture<MezzeValue>()
                    val semaphore = getSemaphore(maxConcurrent)

                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_loom_spawn_cfg", "No Truffle environment available")

                    val thread = env.newTruffleThreadBuilder {
                        try {
                            // Acquire semaphore permit if bounded
                            semaphore?.acquire()
                            try {
                                val result = when (computation) {
                                    is MezzeTruffleFunction -> {
                                        val emptyArgs = MezzeRecord(emptyMap())
                                        computation.callTarget.call(
                                            emptyArgs,
                                            computation.closureVariables,
                                            computation.lexicalScope,
                                            computation.lexicalScopeSlotMap
                                        ) as MezzeValue
                                    }
                                    is NativeMezzeFunction -> {
                                        computation.call(emptyList())
                                    }
                                    else -> throw MezzeException("Unexpected function type")
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
                    MezzePromise(future)
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
     * Example usage in Mezze:
     *   perform Loom in do
     *     let promise = Concurrency.spawn {computation = {operation {}}}
     *     let result = Concurrency.await {promise = promise}
     *     result
     */
    private fun registerAwait(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_await"] = NativeMezzeFunction("__native_loom_await") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_await", "expects MezzeRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_await", "expects 'p' field")

            when (promise) {
                is MezzePromise -> {
                    // Block until the promise completes and return the result
                    promise.await()
                }
                else -> throw NativeFunctionException(
                    "__native_loom_await",
                    "promise must be a MezzePromise, got: ${promise::class.simpleName}"
                )
            }
        }
    }

    /**
     * __native_loom_race - Wait for first Promise to complete.
     */
    private fun registerRace(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_race"] = NativeMezzeFunction("__native_loom_race") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_race", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_race", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_loom_race", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_loom_race", "cannot race empty list")
            }

            // Use CompletableFuture.anyOf to wait for first completion
            val futures = promises.map { it.future }
            val firstFuture = CompletableFuture.anyOf(*futures.toTypedArray())
            firstFuture.get() as MezzeValue
        }
    }

    /**
     * __native_loom_all - Wait for all Promises to complete.
     */
    private fun registerAll_(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_all"] = NativeMezzeFunction("__native_loom_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_all", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_all", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_loom_all", "ps must be a list")
            }

            // Wait for all and collect results
            val results = promises.map { it.await() }
            MezzeArrayList(results)
        }
    }

    /**
     * __native_loom_timeout - Wait with timeout.
     */
    private fun registerTimeout(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_timeout"] = NativeMezzeFunction("__native_loom_timeout") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_timeout", "expects MezzeRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_loom_timeout", "expects 'ms' field")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_timeout", "expects 'p' field")

            val timeout = (ms as MezzeInt).value.toLong()
            val promise = p as MezzePromise

            try {
                val result = promise.future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                MezzeVariant("Some", MezzeRecord(mapOf("val" to result)))
            } catch (e: java.util.concurrent.TimeoutException) {
                MezzeVariant("None", MezzeRecord(emptyMap()))
            }
        }
    }

    /**
     * __native_loom_cancel - Cancel a Promise.
     */
    private fun registerCancel(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_cancel"] = NativeMezzeFunction("__native_loom_cancel") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_cancel", "expects MezzeRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_cancel", "expects 'p' field")

            val promise = p as MezzePromise
            promise.future.cancel(true)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_loom_sleep - Sleep for specified milliseconds.
     */
    private fun registerSleep(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_sleep"] = NativeMezzeFunction("__native_loom_sleep") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_sleep", "expects MezzeRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_loom_sleep", "expects 'ms' field")

            val timeout = (ms as MezzeInt).value.toLong()
            Thread.sleep(timeout)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_loom_is_done - Check if Promise is complete.
     */
    private fun registerIsDone(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_is_done"] = NativeMezzeFunction("__native_loom_is_done") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_is_done", "expects MezzeRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_loom_is_done", "expects 'p' field")

            val promise = p as MezzePromise
            MezzeBool(promise.future.isDone)
        }
    }

    /**
     * __native_loom_is_cancelled - Check if current computation was cancelled.
     */
    private fun registerIsCancelled(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_is_cancelled"] = NativeMezzeFunction("__native_loom_is_cancelled") { args ->
            MezzeBool(Thread.currentThread().isInterrupted)
        }
    }

    /**
     * __native_loom_check - Cancellation checkpoint.
     */
    private fun registerCheck(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_check"] = NativeMezzeFunction("__native_loom_check") { args ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Computation was cancelled")
            }
            MezzeRecord(emptyMap())
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
     * Example usage in Mezze:
     *   let receiver_thread = current_thread {}
     *   # Pass this handle to other threads for signaling
     */
    private fun registerCurrentThread(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_current_thread"] = NativeMezzeFunction("__native_loom_current_thread") { args ->
            // Get current thread and wrap it in MezzeThread
            val currentThread = Thread.currentThread()
            MezzeThread(currentThread)
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
     * Example usage in Mezze:
     *   park {}  # Suspends current thread until unparked
     */
    private fun registerPark(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_park"] = NativeMezzeFunction("__native_loom_park") { args ->

            // Park the current thread using LockSupport
            java.util.concurrent.locks.LockSupport.park()

            MezzeRecord(emptyMap())  // Return empty record (unit type)
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
     * Example usage in Mezze:
     *   unpark {thread = receiver_thread}  # Wakes up the parked thread
     */
    private fun registerUnpark(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_unpark"] = NativeMezzeFunction("__native_loom_unpark") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_unpark", "expects MezzeRecord")

            val threadValue = record.fields["thread"]
                ?: throw NativeFunctionException("__native_loom_unpark", "expects 'thread' field")

            when (threadValue) {
                is MezzeThread -> {

                    // Unpark the thread using LockSupport
                    java.util.concurrent.locks.LockSupport.unpark(threadValue.thread)
                    MezzeRecord(emptyMap())  // Return empty record (unit type)
                }
                else -> throw NativeFunctionException(
                    "__native_loom_unpark",
                    "thread must be a MezzeThread, got: ${threadValue::class.simpleName}"
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
    private fun registerJoin(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_join"] = NativeMezzeFunction("__native_loom_join") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_join", "expects MezzeRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_loom_join", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_loom_join", "expects 'right' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_join", "No Truffle environment available")

            val leftFuture = CompletableFuture<MezzeValue>()
            val rightFuture = CompletableFuture<MezzeValue>()

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

            MezzeRecord(mapOf(
                "left" to leftResult,
                "right" to rightResult
            ))
        }
    }

    /**
     * __native_loom_join3 - Run three computations concurrently.
     */
    private fun registerJoin3(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_join3"] = NativeMezzeFunction("__native_loom_join3") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_join3", "expects MezzeRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_loom_join3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_loom_join3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_loom_join3", "expects 'c' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_join3", "No Truffle environment available")

            val aFuture = CompletableFuture<MezzeValue>()
            val bFuture = CompletableFuture<MezzeValue>()
            val cFuture = CompletableFuture<MezzeValue>()

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

            MezzeRecord(mapOf(
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
    private fun registerMap(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_map"] = NativeMezzeFunction("__native_loom_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_map", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_loom_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_map", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_loom_map", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeMezzeFunction MezzeArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_map", "No Truffle environment available")

            // Create futures for each element
            val futures = list.map { element ->
                val future = CompletableFuture<MezzeValue>()
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
            MezzeArrayList(results)
        }
    }

    /**
     * __native_loom_for_each - Run function on each element for side effects.
     */
    private fun registerForEach(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_for_each"] = NativeMezzeFunction("__native_loom_for_each") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_for_each", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_loom_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_for_each", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_loom_for_each", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeMezzeFunction MezzeRecord(emptyMap())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_for_each", "No Truffle environment available")

            val futures = list.map { element ->
                val future = CompletableFuture<MezzeValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        callFunctionWithArg(f, element)
                        future.complete(MezzeRecord(emptyMap()))
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Wait for all to complete
            futures.forEach { it.get() }
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_loom_filter - Filter elements with predicate evaluated concurrently.
     */
    private fun registerFilter(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_filter"] = NativeMezzeFunction("__native_loom_filter") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_filter", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_loom_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_filter", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_loom_filter", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeMezzeFunction MezzeArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_filter", "No Truffle environment available")

            // Evaluate predicate for each element concurrently
            val futures = list.map { element ->
                val future = CompletableFuture<Pair<MezzeValue, Boolean>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        val result = callFunctionWithArg(f, element)
                        val keep = (result as? MezzeBool)?.value ?: false
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
            MezzeArrayList(results)
        }
    }

    // =========================================================================
    // Error Handling Operations
    // =========================================================================

    /**
     * __native_loom_any - Return first successful result, fail only if ALL fail.
     */
    private fun registerAny(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_any"] = NativeMezzeFunction("__native_loom_any") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_any", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_any", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_loom_any", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_loom_any", "cannot get any from empty list")
            }

            // Try to get first successful result
            val errors = mutableListOf<Throwable>()

            // Create a future that completes with first success
            val resultFuture = CompletableFuture<MezzeValue>()
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
    private fun registerSettle(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_settle"] = NativeMezzeFunction("__native_loom_settle") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_settle", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_loom_settle", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_loom_settle", "ps must be a list")
            }

            // Collect results as Result variants
            val results = promises.map { promise ->
                try {
                    val value = promise.future.get()
                    // Ok {val = value}
                    MezzeVariant("Ok", MezzeRecord(mapOf("val" to value)))
                } catch (e: java.util.concurrent.CancellationException) {
                    // Err {error = AsyncCancelled}
                    MezzeVariant("Err", MezzeRecord(mapOf(
                        "error" to MezzeVariant("AsyncCancelled", MezzeRecord(emptyMap()))
                    )))
                } catch (e: Exception) {
                    // Err {error = AsyncFailed {msg = ...}}
                    val message = e.cause?.message ?: e.message ?: "Unknown error"
                    MezzeVariant("Err", MezzeRecord(mapOf(
                        "error" to MezzeVariant("AsyncFailed", MezzeRecord(mapOf(
                            "msg" to MezzeString(message)
                        )))
                    )))
                }
            }

            MezzeArrayList(results)
        }
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    /**
     * Call a function with no arguments.
     */
    private fun callFunction(fn: MezzeValue): MezzeValue {
        return when (fn) {
            is MezzeTruffleFunction -> {
                val emptyArgs = MezzeRecord(emptyMap())
                fn.callTarget.call(
                    emptyArgs,
                    fn.closureVariables,
                    fn.lexicalScope,
                    fn.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> fn.call(emptyList())
            else -> throw MezzeException("Expected function, got: ${fn::class.simpleName}")
        }
    }

    /**
     * Call a function with a single argument wrapped in a record {x = arg}.
     * This is the Mezze convention for functions taking a single argument.
     */
    private fun callFunctionWithArg(fn: MezzeValue, arg: MezzeValue): MezzeValue {
        // Wrap the argument in a record with field "x"
        val recordArg = MezzeRecord(mapOf("x" to arg))
        return when (fn) {
            is MezzeTruffleFunction -> {
                fn.callTarget.call(
                    recordArg,
                    fn.closureVariables,
                    fn.lexicalScope,
                    fn.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> fn.call(listOf(recordArg))
            else -> throw MezzeException("Expected function, got: ${fn::class.simpleName}")
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
    private fun registerScoped(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_scoped"] = NativeMezzeFunction("__native_loom_scoped") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_scoped", "expects MezzeRecord")

            val fn = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_scoped", "expects 'f' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_loom_scoped", "No Truffle environment available")

            // Create the scope
            val scope = MezzeScope(env)

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
    private fun registerScopeSpawn(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_scope_spawn"] = NativeMezzeFunction("__native_loom_scope_spawn") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "expects MezzeRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "expects 'scope' field")

            val fn = record.fields["f"]
                ?: throw NativeFunctionException("__native_loom_scope_spawn", "expects 'f' field")

            val scope = scopeVal as? MezzeScope
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
    private fun registerScopeCancelAll(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_scope_cancel_all"] = NativeMezzeFunction("__native_loom_scope_cancel_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_scope_cancel_all", "expects MezzeRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_cancel_all", "expects 'scope' field")

            val scope = scopeVal as? MezzeScope
                ?: throw NativeFunctionException("__native_loom_scope_cancel_all", "scope must be a Scope")

            scope.cancelAll()
            MezzeUnit
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
    private fun registerScopeGetToken(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_scope_get_token"] = NativeMezzeFunction("__native_loom_scope_get_token") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_scope_get_token", "expects MezzeRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_get_token", "expects 'scope' field")

            val scope = scopeVal as? MezzeScope
                ?: throw NativeFunctionException("__native_loom_scope_get_token", "scope must be a Scope")

            MezzeCancellationToken(scope.cancellationToken)
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
    private fun registerScopeIsCancelled(context: MutableMap<String, MezzeValue>) {
        context["__native_loom_scope_is_cancelled"] = NativeMezzeFunction("__native_loom_scope_is_cancelled") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_loom_scope_is_cancelled", "expects MezzeRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_loom_scope_is_cancelled", "expects 'scope' field")

            val scope = scopeVal as? MezzeScope
                ?: throw NativeFunctionException("__native_loom_scope_is_cancelled", "scope must be a Scope")

            MezzeBool(scope.isCancelled())
        }
    }

    /**
     * Call a function with a scope argument wrapped in a record {scope = arg}.
     */
    private fun callFunctionWithScopeArg(fn: MezzeValue, scope: MezzeScope): MezzeValue {
        val recordArg = MezzeRecord(mapOf("scope" to scope))
        return when (fn) {
            is MezzeTruffleFunction -> {
                fn.callTarget.call(
                    recordArg,
                    fn.closureVariables,
                    fn.lexicalScope,
                    fn.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> fn.call(listOf(recordArg))
            else -> throw MezzeException("Expected function, got: ${fn::class.simpleName}")
        }
    }
}

/**
 * MezzeScope - Tracks spawned tasks for structured concurrency.
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
class MezzeScope(
    private val env: com.oracle.truffle.api.TruffleLanguage.Env,
    parentToken: com.mezze.language.interpreter.effects.CancellationToken? = null
) : MezzeValue() {

    private val tasks = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<MezzeValue>>()
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    // CancellationToken for this scope - child of parent if provided
    val cancellationToken: com.mezze.language.interpreter.effects.CancellationToken =
        parentToken?.createChild() ?: com.mezze.language.interpreter.effects.CancellationToken()

    /**
     * Spawn a new task within this scope.
     */
    fun spawn(fn: MezzeValue): MezzePromise {
        val future = CompletableFuture<MezzeValue>()
        tasks.add(future)

        val thread = env.newTruffleThreadBuilder {
            try {
                // Check if scope is cancelled before starting
                if (cancelled.get() || cancellationToken.isCancelled()) {
                    future.cancel(true)
                    return@newTruffleThreadBuilder
                }

                val result = when (fn) {
                    is MezzeTruffleFunction -> {
                        val emptyArgs = MezzeRecord(emptyMap())
                        fn.callTarget.call(
                            emptyArgs,
                            fn.closureVariables,
                            fn.lexicalScope,
                            fn.lexicalScopeSlotMap
                        ) as MezzeValue
                    }
                    is NativeMezzeFunction -> fn.call(emptyList())
                    else -> throw MezzeException("Expected function, got: ${fn::class.simpleName}")
                }
                future.complete(result)
            } catch (e: com.mezze.language.interpreter.effects.EffectCancellationException) {
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

        return MezzePromise(future)
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
