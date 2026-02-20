package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
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

    fun registerAll(context: MutableMap<String, MezzeValue>) {
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
    }

    /**
     * __native_os_spawn - Spawn a computation on an OS platform thread.
     */
    private fun registerSpawn(context: MutableMap<String, MezzeValue>) {
        context["__native_os_spawn"] = NativeMezzeFunction("__native_os_spawn") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_spawn", "expects MezzeRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_spawn", "expects 'f' field")

            when (computation) {
                is MezzeTruffleFunction, is NativeMezzeFunction -> {
                    val future = CompletableFuture<MezzeValue>()

                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_os_spawn", "No Truffle environment available")

                    // Use platform threads (not virtual)
                    val thread = env.newTruffleThreadBuilder {
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
                        } catch (e: Exception) {
                            e.printStackTrace()
                            future.completeExceptionally(e)
                        }
                    }
                        .virtual(false)  // Use OS platform threads
                        .build()

                    thread.start()
                    MezzePromise(future)
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
     * Takes: { f: {} -> 'a, pool_size: Int }
     * Returns: Promise 'a
     *
     * Like spawn but with configuration:
     * - pool_size: Maximum concurrent OS threads (0 = unbounded)
     */
    private fun registerSpawnCfg(context: MutableMap<String, MezzeValue>) {
        context["__native_os_spawn_cfg"] = NativeMezzeFunction("__native_os_spawn_cfg") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_spawn_cfg", "expects MezzeRecord")

            val computation = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_spawn_cfg", "expects 'f' field")

            val poolSize = (record.fields["pool_size"] as? MezzeInt)?.value?.toInt() ?: 0
            val semaphore = getSemaphore(poolSize)

            when (computation) {
                is MezzeTruffleFunction, is NativeMezzeFunction -> {
                    val future = CompletableFuture<MezzeValue>()

                    val env = TruffleContextHolder.getEnv()
                        ?: throw NativeFunctionException("__native_os_spawn_cfg", "No Truffle environment available")

                    // Use Truffle's thread builder for proper context management
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
                    MezzePromise(future)
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
    private fun registerAwait(context: MutableMap<String, MezzeValue>) {
        context["__native_os_await"] = NativeMezzeFunction("__native_os_await") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_await", "expects MezzeRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_await", "expects 'p' field")

            when (promise) {
                is MezzePromise -> promise.await()
                else -> throw NativeFunctionException(
                    "__native_os_await",
                    "p must be a MezzePromise"
                )
            }
        }
    }

    /**
     * __native_os_race - Wait for first Promise to complete.
     */
    private fun registerRace(context: MutableMap<String, MezzeValue>) {
        context["__native_os_race"] = NativeMezzeFunction("__native_os_race") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_race", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_os_race", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_os_race", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_os_race", "cannot race empty list")
            }

            val futures = promises.map { it.future }
            val firstFuture = CompletableFuture.anyOf(*futures.toTypedArray())
            firstFuture.get() as MezzeValue
        }
    }

    /**
     * __native_os_all - Wait for all Promises to complete.
     */
    private fun registerAllPromises(context: MutableMap<String, MezzeValue>) {
        context["__native_os_all"] = NativeMezzeFunction("__native_os_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_all", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_os_all", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_os_all", "ps must be a list")
            }

            val results = promises.map { it.await() }
            MezzeArrayList(results)
        }
    }

    /**
     * __native_os_timeout - Wait with timeout.
     */
    private fun registerTimeout(context: MutableMap<String, MezzeValue>) {
        context["__native_os_timeout"] = NativeMezzeFunction("__native_os_timeout") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_timeout", "expects MezzeRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_os_timeout", "expects 'ms' field")
            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_timeout", "expects 'p' field")

            val timeout = (ms as MezzeInt).value.toLong()
            val promise = p as MezzePromise

            try {
                val result = promise.future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
                mezzeOptionSome(result)
            } catch (e: java.util.concurrent.TimeoutException) {
                mezzeOptionNone()
            }
        }
    }

    /**
     * __native_os_cancel - Cancel a Promise.
     */
    private fun registerCancel(context: MutableMap<String, MezzeValue>) {
        context["__native_os_cancel"] = NativeMezzeFunction("__native_os_cancel") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_cancel", "expects MezzeRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_cancel", "expects 'p' field")

            val promise = p as MezzePromise
            promise.future.cancel(true)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_os_sleep - Sleep for specified milliseconds.
     */
    private fun registerSleep(context: MutableMap<String, MezzeValue>) {
        context["__native_os_sleep"] = NativeMezzeFunction("__native_os_sleep") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_sleep", "expects MezzeRecord")

            val ms = record.fields["ms"]
                ?: throw NativeFunctionException("__native_os_sleep", "expects 'ms' field")

            val timeout = (ms as MezzeInt).value.toLong()
            Thread.sleep(timeout)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_os_is_done - Check if Promise is complete.
     */
    private fun registerIsDone(context: MutableMap<String, MezzeValue>) {
        context["__native_os_is_done"] = NativeMezzeFunction("__native_os_is_done") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_is_done", "expects MezzeRecord")

            val p = record.fields["p"]
                ?: throw NativeFunctionException("__native_os_is_done", "expects 'p' field")

            val promise = p as MezzePromise
            MezzeBool(promise.future.isDone)
        }
    }

    /**
     * __native_os_is_cancelled - Check if current computation was cancelled.
     */
    private fun registerIsCancelled(context: MutableMap<String, MezzeValue>) {
        context["__native_os_is_cancelled"] = NativeMezzeFunction("__native_os_is_cancelled") { args ->
            MezzeBool(Thread.currentThread().isInterrupted)
        }
    }

    /**
     * __native_os_check - Cancellation checkpoint.
     */
    private fun registerCheck(context: MutableMap<String, MezzeValue>) {
        context["__native_os_check"] = NativeMezzeFunction("__native_os_check") { args ->
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("Computation was cancelled")
            }
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_os_current_thread - Get a handle to the current thread.
     */
    private fun registerCurrentThread(context: MutableMap<String, MezzeValue>) {
        context["__native_os_current_thread"] = NativeMezzeFunction("__native_os_current_thread") { args ->
            MezzeThread(Thread.currentThread())
        }
    }

    /**
     * __native_os_park - Park (suspend) the current thread.
     */
    private fun registerPark(context: MutableMap<String, MezzeValue>) {
        context["__native_os_park"] = NativeMezzeFunction("__native_os_park") { args ->
            java.util.concurrent.locks.LockSupport.park()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_os_unpark - Unpark (resume) a parked thread.
     */
    private fun registerUnpark(context: MutableMap<String, MezzeValue>) {
        context["__native_os_unpark"] = NativeMezzeFunction("__native_os_unpark") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_unpark", "expects MezzeRecord")

            val threadValue = record.fields["thread"]
                ?: throw NativeFunctionException("__native_os_unpark", "expects 'thread' field")

            when (threadValue) {
                is MezzeThread -> {
                    java.util.concurrent.locks.LockSupport.unpark(threadValue.thread)
                    MezzeRecord(emptyMap())
                }
                else -> throw NativeFunctionException(
                    "__native_os_unpark",
                    "thread must be a MezzeThread, got: ${threadValue::class.simpleName}"
                )
            }
        }
    }

    // =========================================================================
    // Structured Concurrency Operations
    // =========================================================================

    /**
     * __native_os_join - Run two computations concurrently, return both results.
     */
    private fun registerJoin(context: MutableMap<String, MezzeValue>) {
        context["__native_os_join"] = NativeMezzeFunction("__native_os_join") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_join", "expects MezzeRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_os_join", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_os_join", "expects 'right' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_os_join", "No Truffle environment available")

            val leftFuture = CompletableFuture<MezzeValue>()
            val rightFuture = CompletableFuture<MezzeValue>()

            val leftThread = env.newTruffleThreadBuilder {
                try {
                    leftFuture.complete(callFunction(leftFn))
                } catch (e: Exception) {
                    leftFuture.completeExceptionally(e)
                }
            }.virtual(false).build()

            val rightThread = env.newTruffleThreadBuilder {
                try {
                    rightFuture.complete(callFunction(rightFn))
                } catch (e: Exception) {
                    rightFuture.completeExceptionally(e)
                }
            }.virtual(false).build()

            leftThread.start()
            rightThread.start()

            MezzeRecord(mapOf(
                "left" to leftFuture.get(),
                "right" to rightFuture.get()
            ))
        }
    }

    /**
     * __native_os_join3 - Run three computations concurrently.
     */
    private fun registerJoin3(context: MutableMap<String, MezzeValue>) {
        context["__native_os_join3"] = NativeMezzeFunction("__native_os_join3") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_join3", "expects MezzeRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_os_join3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_os_join3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_os_join3", "expects 'c' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_os_join3", "No Truffle environment available")

            val aFuture = CompletableFuture<MezzeValue>()
            val bFuture = CompletableFuture<MezzeValue>()
            val cFuture = CompletableFuture<MezzeValue>()

            val aThread = env.newTruffleThreadBuilder {
                try { aFuture.complete(callFunction(aFn)) }
                catch (e: Exception) { aFuture.completeExceptionally(e) }
            }.virtual(false).build()

            val bThread = env.newTruffleThreadBuilder {
                try { bFuture.complete(callFunction(bFn)) }
                catch (e: Exception) { bFuture.completeExceptionally(e) }
            }.virtual(false).build()

            val cThread = env.newTruffleThreadBuilder {
                try { cFuture.complete(callFunction(cFn)) }
                catch (e: Exception) { cFuture.completeExceptionally(e) }
            }.virtual(false).build()

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
     * __native_os_map - Apply function to each element concurrently.
     */
    private fun registerMap(context: MutableMap<String, MezzeValue>) {
        context["__native_os_map"] = NativeMezzeFunction("__native_os_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_map", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_os_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_map", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_os_map", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeMezzeFunction MezzeArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_os_map", "No Truffle environment available")

            val futures = list.map { element ->
                val future = CompletableFuture<MezzeValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        future.complete(callFunctionWithArg(f, element))
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(false).build()
                thread.start()
                future
            }

            MezzeArrayList(futures.map { it.get() })
        }
    }

    /**
     * __native_os_for_each - Run function on each element for side effects.
     */
    private fun registerForEach(context: MutableMap<String, MezzeValue>) {
        context["__native_os_for_each"] = NativeMezzeFunction("__native_os_for_each") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_for_each", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_os_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_for_each", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_os_for_each", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeMezzeFunction MezzeRecord(emptyMap())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_os_for_each", "No Truffle environment available")

            val futures = list.map { element ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        callFunctionWithArg(f, element)
                        future.complete(Unit)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(false).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_os_filter - Filter elements with predicate evaluated concurrently.
     */
    private fun registerFilter(context: MutableMap<String, MezzeValue>) {
        context["__native_os_filter"] = NativeMezzeFunction("__native_os_filter") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_filter", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_os_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_filter", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_os_filter", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeMezzeFunction MezzeArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_os_filter", "No Truffle environment available")

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
                }.virtual(false).build()
                thread.start()
                future
            }

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
     * __native_os_any - Return first successful result, fail only if ALL fail.
     */
    private fun registerAny(context: MutableMap<String, MezzeValue>) {
        context["__native_os_any"] = NativeMezzeFunction("__native_os_any") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_any", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_os_any", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_os_any", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_os_any", "cannot get any from empty list")
            }

            val errors = mutableListOf<Throwable>()
            val resultFuture = CompletableFuture<MezzeValue>()
            val remaining = java.util.concurrent.atomic.AtomicInteger(promises.size)

            for (promise in promises) {
                promise.future.whenComplete { result, error ->
                    if (error == null && !resultFuture.isDone) {
                        resultFuture.complete(result)
                    } else if (error != null) {
                        synchronized(errors) { errors.add(error) }
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
     * __native_os_settle - Wait for all promises, return list of Results.
     */
    private fun registerSettle(context: MutableMap<String, MezzeValue>) {
        context["__native_os_settle"] = NativeMezzeFunction("__native_os_settle") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_settle", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_os_settle", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList().map { it as MezzePromise }
                else -> throw NativeFunctionException("__native_os_settle", "ps must be a list")
            }

            val results = promises.map { promise ->
                try {
                    val value = promise.future.get()
                    mezzeResultOk(value)
                } catch (e: java.util.concurrent.CancellationException) {
                    MezzeVariant("Err", MezzeRecord(mapOf(
                        "error" to MezzeVariant("AsyncCancelled", MezzeRecord(emptyMap()))
                    )))
                } catch (e: Exception) {
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
     * __native_os_scoped - Run a function with a structured concurrency scope.
     * Uses OS platform threads instead of virtual threads.
     */
    private fun registerScoped(context: MutableMap<String, MezzeValue>) {
        context["__native_os_scoped"] = NativeMezzeFunction("__native_os_scoped") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_scoped", "expects MezzeRecord")

            val fn = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_scoped", "expects 'f' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_os_scoped", "No Truffle environment available")

            // Create the scope (uses OS threads)
            val scope = OSScope(env)

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
     * __native_os_scope_spawn - Spawn a task within a scope using OS threads.
     */
    private fun registerScopeSpawn(context: MutableMap<String, MezzeValue>) {
        context["__native_os_scope_spawn"] = NativeMezzeFunction("__native_os_scope_spawn") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_scope_spawn", "expects MezzeRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_os_scope_spawn", "expects 'scope' field")

            val fn = record.fields["f"]
                ?: throw NativeFunctionException("__native_os_scope_spawn", "expects 'f' field")

            val scope = scopeVal as? OSScope
                ?: throw NativeFunctionException("__native_os_scope_spawn", "scope must be an OSScope")

            scope.spawn(fn)
        }
    }

    /**
     * __native_os_scope_cancel_all - Cancel all tasks in a scope.
     */
    private fun registerScopeCancelAll(context: MutableMap<String, MezzeValue>) {
        context["__native_os_scope_cancel_all"] = NativeMezzeFunction("__native_os_scope_cancel_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_os_scope_cancel_all", "expects MezzeRecord")

            val scopeVal = record.fields["scope"]
                ?: throw NativeFunctionException("__native_os_scope_cancel_all", "expects 'scope' field")

            val scope = scopeVal as? OSScope
                ?: throw NativeFunctionException("__native_os_scope_cancel_all", "scope must be an OSScope")

            scope.cancelAll()
            MezzeUnit
        }
    }

    /**
     * Call a function with a scope argument wrapped in a record {scope = arg}.
     */
    private fun callFunctionWithScopeArg(fn: MezzeValue, scope: OSScope): MezzeValue {
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
 * OSScope - Tracks spawned tasks for structured concurrency using OS threads.
 */
class OSScope(
    private val env: com.oracle.truffle.api.TruffleLanguage.Env
) : MezzeValue() {

    private val tasks = java.util.concurrent.CopyOnWriteArrayList<java.util.concurrent.CompletableFuture<MezzeValue>>()
    private val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Spawn a new task within this scope using OS threads.
     */
    fun spawn(fn: MezzeValue): MezzePromise {
        val future = java.util.concurrent.CompletableFuture<MezzeValue>()
        tasks.add(future)

        val thread = env.newTruffleThreadBuilder {
            try {
                // Check if scope is cancelled before starting
                if (cancelled.get()) {
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
            } catch (e: Exception) {
                // Record first error for fail-fast behavior
                firstError.compareAndSet(null, e)
                future.completeExceptionally(e)
                // Cancel siblings on failure (fail-fast)
                cancelAll()
            }
        }.virtual(false).build()  // Use OS platform threads

        threads.add(thread)
        thread.start()

        return MezzePromise(future)
    }

    /**
     * Cancel all tasks in this scope.
     */
    fun cancelAll() {
        cancelled.set(true)
        for (task in tasks) {
            task.cancel(true)
        }
        for (thread in threads) {
            thread.interrupt()
        }
    }

    /**
     * Wait for all tasks to complete.
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

    override fun getDisplayString(): String = "<os-scope:${tasks.size} tasks>"
}
