package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import java.util.concurrent.CompletableFuture

/**
 * Implementations for OS platform thread concurrency.
 *
 * Same API as Loom but uses OS-level platform threads instead of virtual threads.
 * Use when:
 * - You need true parallelism for CPU-bound work
 * - Thread-locals must work correctly
 * - Limited scale is acceptable (thousands, not millions)
 */
object OSThreadImplementations {

    // Semaphore for bounded concurrency (controls how many OS threads can run simultaneously)
    private val concurrencySemaphores = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.Semaphore>()

    private fun getSemaphore(poolSize: Int): java.util.concurrent.Semaphore? {
        if (poolSize <= 0) return null  // 0 means unbounded
        return concurrencySemaphores.computeIfAbsent(poolSize) {
            java.util.concurrent.Semaphore(it)
        }
    }

    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerSpawn(context)
        registerSpawnCfg(context)
        registerAwait(context)
        registerRace(context)
        registerAllPromises(context)
        registerTimeout(context)
        registerCancel(context)
        registerSleep(context)
        registerIsDone(context)
        registerIsCancelled(context)
        registerCheck(context)
        registerCurrentThread(context)
        registerPark(context)
        registerUnpark(context)
    }

    /**
     * __native_os_spawn - Spawn a computation on an OS platform thread.
     */
    private fun registerSpawn(context: MutableMap<String, FunkValue>) {
        context["__native_os_spawn"] = NativeFunkFunction("__native_os_spawn") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_spawn", "expects FunkRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_spawn", "expects 'f' field")

            when (computation) {
                is FunkTruffleFunction, is NativeFunkFunction -> {
                    val future = CompletableFuture<FunkValue>()

                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_os_spawn", "No Truffle environment available")

                    // Use platform threads (not virtual)
                    val thread = env.newTruffleThreadBuilder {
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
                        } catch (e: Exception) {
                            e.printStackTrace()
                            future.completeExceptionally(e)
                        }
                    }
                        .virtual(false)  // Use OS platform threads
                        .build()

                    thread.start()
                    FunkPromise(future)
                }
                else -> throw NativeFunctionException(
                    "__native_os_spawn",
                    "computation must be a function"
                )
            }
        }
    }

    /**
     * __native_os_spawn_cfg - Spawn with configuration.
     *
     * Takes: { f: {} -> 'a, pool_size: Int, timeout_ms: Int }
     * Returns: Promise 'a
     *
     * Like spawn but with configuration:
     * - pool_size: Maximum concurrent OS threads (0 = unbounded)
     * - timeout_ms: Default timeout in milliseconds (0 = no timeout, currently unused at spawn time)
     */
    private fun registerSpawnCfg(context: MutableMap<String, FunkValue>) {
        context["__native_os_spawn_cfg"] = NativeFunkFunction("__native_os_spawn_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_spawn_cfg", "expects FunkRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_spawn_cfg", "expects 'f' field")

            val poolSize = (record.fields["pool_size"] as? FunkInt)?.value?.toInt() ?: 0
            val semaphore = getSemaphore(poolSize)

            when (computation) {
                is FunkTruffleFunction, is NativeFunkFunction -> {
                    val future = CompletableFuture<FunkValue>()

                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_os_spawn_cfg", "No Truffle environment available")

                    // Use Truffle's thread builder for proper context management
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
                                semaphore?.release()
                            }
                        } catch (e: Exception) {
                            semaphore?.release()
                            e.printStackTrace()
                            future.completeExceptionally(e)
                        }
                    }
                        .virtual(false)  // OS platform threads
                        .build()

                    thread.start()
                    FunkPromise(future)
                }
                else -> throw NativeFunctionException(
                    "__native_os_spawn_cfg",
                    "f must be a function"
                )
            }
        }
    }

    /**
     * __native_os_await - Wait for a Promise to complete.
     */
    private fun registerAwait(context: MutableMap<String, FunkValue>) {
        context["__native_os_await"] = NativeFunkFunction("__native_os_await") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_await", "expects FunkRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_await", "expects 'p' field")

            when (promise) {
                is FunkPromise -> promise.await()
                else -> throw NativeFunctionException(
                    "__native_os_await",
                    "p must be a FunkPromise"
                )
            }
        }
    }

    /**
     * __native_os_race - Wait for first Promise to complete.
     */
    private fun registerRace(context: MutableMap<String, FunkValue>) {
        context["__native_os_race"] = NativeFunkFunction("__native_os_race") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_race", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_os_race", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList().map { it as FunkPromise }
                else -> throw NativeFunctionException("__native_os_race", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_os_race", "cannot race empty list")
            }

            val futures = promises.map { it.future }
            val firstFuture = CompletableFuture.anyOf(*futures.toTypedArray())
            firstFuture.get() as FunkValue
        }
    }

    /**
     * __native_os_all - Wait for all Promises to complete.
     */
    private fun registerAllPromises(context: MutableMap<String, FunkValue>) {
        context["__native_os_all"] = NativeFunkFunction("__native_os_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_all", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_os_all", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList().map { it as FunkPromise }
                else -> throw NativeFunctionException("__native_os_all", "ps must be a list")
            }

            val results = promises.map { it.await() }
            FunkArrayList(results)
        }
    }

    /**
     * __native_os_timeout - Wait with timeout.
     */
    private fun registerTimeout(context: MutableMap<String, FunkValue>) {
        context["__native_os_timeout"] = NativeFunkFunction("__native_os_timeout") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_timeout", "expects FunkRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_os_timeout", "expects 'ms' field")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_timeout", "expects 'p' field")

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
     * __native_os_cancel - Cancel a Promise.
     */
    private fun registerCancel(context: MutableMap<String, FunkValue>) {
        context["__native_os_cancel"] = NativeFunkFunction("__native_os_cancel") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_cancel", "expects FunkRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_cancel", "expects 'p' field")

            val promise = p as FunkPromise
            promise.future.cancel(true)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_os_sleep - Sleep for specified milliseconds.
     */
    private fun registerSleep(context: MutableMap<String, FunkValue>) {
        context["__native_os_sleep"] = NativeFunkFunction("__native_os_sleep") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_sleep", "expects FunkRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_os_sleep", "expects 'ms' field")

            val timeout = (ms as FunkInt).value.toLong()
            Thread.sleep(timeout)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_os_is_done - Check if Promise is complete.
     */
    private fun registerIsDone(context: MutableMap<String, FunkValue>) {
        context["__native_os_is_done"] = NativeFunkFunction("__native_os_is_done") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_is_done", "expects FunkRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_is_done", "expects 'p' field")

            val promise = p as FunkPromise
            FunkBool(promise.future.isDone)
        }
    }

    /**
     * __native_os_is_cancelled - Check if current computation was cancelled.
     */
    private fun registerIsCancelled(context: MutableMap<String, FunkValue>) {
        context["__native_os_is_cancelled"] = NativeFunkFunction("__native_os_is_cancelled") { args ->
            FunkBool(Thread.currentThread().isInterrupted)
        }
    }

    /**
     * __native_os_check - Cancellation checkpoint.
     */
    private fun registerCheck(context: MutableMap<String, FunkValue>) {
        context["__native_os_check"] = NativeFunkFunction("__native_os_check") { args ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Computation was cancelled")
            }
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_os_current_thread - Get a handle to the current thread.
     */
    private fun registerCurrentThread(context: MutableMap<String, FunkValue>) {
        context["__native_os_current_thread"] = NativeFunkFunction("__native_os_current_thread") { args ->
            FunkThread(Thread.currentThread())
        }
    }

    /**
     * __native_os_park - Park (suspend) the current thread.
     */
    private fun registerPark(context: MutableMap<String, FunkValue>) {
        context["__native_os_park"] = NativeFunkFunction("__native_os_park") { args ->
            java.util.concurrent.locks.LockSupport.park()
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_os_unpark - Unpark (resume) a parked thread.
     */
    private fun registerUnpark(context: MutableMap<String, FunkValue>) {
        context["__native_os_unpark"] = NativeFunkFunction("__native_os_unpark") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_os_unpark", "expects FunkRecord")

            val threadValue = record.fields["thread"]
                ?: throw NativeFunctionException("__native_os_unpark", "expects 'thread' field")

            when (threadValue) {
                is FunkThread -> {
                    java.util.concurrent.locks.LockSupport.unpark(threadValue.thread)
                    FunkRecord(emptyMap())
                }
                else -> throw NativeFunctionException(
                    "__native_os_unpark",
                    "thread must be a FunkThread, got: ${threadValue::class.simpleName}"
                )
            }
        }
    }
}
