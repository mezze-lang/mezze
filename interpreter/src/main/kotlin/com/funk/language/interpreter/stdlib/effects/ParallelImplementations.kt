package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementations for the Parallel effect - data parallelism operations.
 *
 * Unlike Async (task parallelism), Parallel is designed for CPU-bound work
 * on collections. Uses virtual threads via TruffleThreadBuilder for execution.
 *
 * Provides:
 * - ForkJoinParallel: Default parallel implementation using virtual threads
 * - ForkJoinParallelWith: Configurable parallelism (same behavior, config reserved for future)
 * - Sequential: Single-threaded for testing/debugging
 *
 * Operations:
 * - map, for_each, filter: Collection operations
 * - reduce, fold: Aggregation operations
 * - both, both3: Tuple operations
 * - find, any, all: Search operations with short-circuiting
 * - partition, group_by: Partitioning operations
 */
object ParallelImplementations {

    /**
     * Register all Parallel-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        // Core parallel operations (uses virtual threads)
        registerParallelMap(context)
        registerParallelForEach(context)
        registerParallelFilter(context)
        registerParallelReduce(context)
        registerParallelFold(context)
        registerParallelBoth(context)
        registerParallelBoth3(context)
        registerParallelFind(context)
        registerParallelAny(context)
        registerParallelAll(context)
        registerParallelPartition(context)
        registerParallelGroupBy(context)

        // Configurable parallel operations (same as core for now)
        registerParallelMapCfg(context)
        registerParallelForEachCfg(context)
        registerParallelFilterCfg(context)
        registerParallelReduceCfg(context)
        registerParallelFoldCfg(context)
        registerParallelFindCfg(context)
        registerParallelAnyCfg(context)
        registerParallelAllCfg(context)
        registerParallelPartitionCfg(context)
        registerParallelGroupByCfg(context)

        // Sequential operations
        registerSequentialMap(context)
        registerSequentialForEach(context)
        registerSequentialFilter(context)
        registerSequentialReduce(context)
        registerSequentialFold(context)
        registerSequentialBoth(context)
        registerSequentialBoth3(context)
        registerSequentialFind(context)
        registerSequentialAny(context)
        registerSequentialAll(context)
        registerSequentialPartition(context)
        registerSequentialGroupBy(context)
    }

    // =========================================================================
    // Core Parallel Operations (ForkJoinParallel)
    // =========================================================================

    private fun registerParallelMap(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_map"] = NativeFunkFunction("__native_parallel_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_map", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_map", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_map", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_map", "No Truffle environment available")

            // Create a future for each element and spawn virtual threads
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

    private fun registerParallelForEach(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_for_each"] = NativeFunkFunction("__native_parallel_for_each") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_for_each", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_for_each", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_for_each", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(emptyMap())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_for_each", "No Truffle environment available")

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

    private fun registerParallelFilter(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_filter"] = NativeFunkFunction("__native_parallel_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_filter", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_filter", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_filter", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_filter", "No Truffle environment available")

            // Evaluate predicate for each element in parallel
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

    private fun registerParallelReduce(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_reduce"] = NativeFunkFunction("__native_parallel_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_reduce", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_reduce", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_reduce", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_reduce", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            // For reduce, we do parallel reduction in stages
            val result = parallelReduceInternal(list, f)
            FunkVariant("Some", FunkRecord(mapOf("val" to result)))
        }
    }

    private fun registerParallelFold(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_fold"] = NativeFunkFunction("__native_parallel_fold") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_fold", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_fold", "expects 'xs' field")
            val zero = record.fields["zero"]
                ?: throw NativeFunctionException("__native_parallel_fold", "expects 'zero' field")
            val mapFn = record.fields["map_fn"]
                ?: throw NativeFunctionException("__native_parallel_fold", "expects 'map_fn' field")
            val combine = record.fields["combine"]
                ?: throw NativeFunctionException("__native_parallel_fold", "expects 'combine' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_fold", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction zero
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_fold", "No Truffle environment available")

            // First, apply mapFn to all elements in parallel
            val futures = list.map { element ->
                val future = CompletableFuture<FunkValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        val result = callFunctionWithArg(mapFn, element)
                        future.complete(result)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val mapped = futures.map { it.get() }

            // Then reduce with combine (sequential for correctness with associative combine)
            var acc = zero
            for (element in mapped) {
                acc = callBinaryFunction(combine, acc, element)
            }
            acc
        }
    }

    private fun registerParallelBoth(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_both"] = NativeFunkFunction("__native_parallel_both") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_both", "expects FunkRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_parallel_both", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_parallel_both", "expects 'right' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_both", "No Truffle environment available")

            val leftFuture = CompletableFuture<FunkValue>()
            val rightFuture = CompletableFuture<FunkValue>()

            val leftThread = env.newTruffleThreadBuilder {
                try {
                    val result = callFunction(leftFn)
                    leftFuture.complete(result)
                } catch (e: Exception) {
                    leftFuture.completeExceptionally(e)
                }
            }.virtual(true).build()

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

            FunkRecord(mapOf(
                "left" to leftFuture.get(),
                "right" to rightFuture.get()
            ))
        }
    }

    private fun registerParallelBoth3(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_both3"] = NativeFunkFunction("__native_parallel_both3") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_both3", "expects FunkRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_parallel_both3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_parallel_both3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_parallel_both3", "expects 'c' field")

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_both3", "No Truffle environment available")

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

    private fun registerParallelFind(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_find"] = NativeFunkFunction("__native_parallel_find") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_find", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_find", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_find", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_find", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_find", "No Truffle environment available")

            val found = AtomicBoolean(false)
            val result = AtomicReference<FunkValue?>(null)

            val futures = list.map { element ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        if (found.get()) {
                            future.complete(Unit)
                            return@newTruffleThreadBuilder
                        }
                        val check = callFunctionWithArg(f, element)
                        if ((check as? FunkBool)?.value == true) {
                            if (found.compareAndSet(false, true)) {
                                result.set(element)
                            }
                        }
                        future.complete(Unit)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Wait for all threads
            futures.forEach { it.get() }

            val foundValue = result.get()
            if (foundValue != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to foundValue)))
            } else {
                FunkVariant("None", FunkRecord(emptyMap()))
            }
        }
    }

    private fun registerParallelAny(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_any"] = NativeFunkFunction("__native_parallel_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_any", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_any", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_any", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_any", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkBool(false)
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_any", "No Truffle environment available")

            val found = AtomicBoolean(false)

            val futures = list.map { element ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        if (found.get()) {
                            future.complete(Unit)
                            return@newTruffleThreadBuilder
                        }
                        val check = callFunctionWithArg(f, element)
                        if ((check as? FunkBool)?.value == true) {
                            found.set(true)
                        }
                        future.complete(Unit)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }
            FunkBool(found.get())
        }
    }

    private fun registerParallelAll(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_all"] = NativeFunkFunction("__native_parallel_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_all", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_all", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_all", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_all", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkBool(true)
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_all", "No Truffle environment available")

            val failed = AtomicBoolean(false)

            val futures = list.map { element ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        if (failed.get()) {
                            future.complete(Unit)
                            return@newTruffleThreadBuilder
                        }
                        val check = callFunctionWithArg(f, element)
                        if ((check as? FunkBool)?.value != true) {
                            failed.set(true)
                        }
                        future.complete(Unit)
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }
            FunkBool(!failed.get())
        }
    }

    private fun registerParallelPartition(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_partition"] = NativeFunkFunction("__native_parallel_partition") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_partition", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_partition", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_partition", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_partition", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(mapOf(
                    "matching" to FunkArrayList(emptyList()),
                    "not_matching" to FunkArrayList(emptyList())
                ))
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_partition", "No Truffle environment available")

            val futures = list.map { element ->
                val future = CompletableFuture<Pair<FunkValue, Boolean>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        val check = callFunctionWithArg(f, element)
                        val matches = (check as? FunkBool)?.value ?: false
                        future.complete(Pair(element, matches))
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val results = futures.map { it.get() }
            val matching = results.filter { it.second }.map { it.first }
            val notMatching = results.filter { !it.second }.map { it.first }

            FunkRecord(mapOf(
                "matching" to FunkArrayList(matching),
                "not_matching" to FunkArrayList(notMatching)
            ))
        }
    }

    private fun registerParallelGroupBy(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_group_by"] = NativeFunkFunction("__native_parallel_group_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_group_by", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_group_by", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_group_by", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_group_by", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_group_by", "No Truffle environment available")

            val futures = list.map { element ->
                val future = CompletableFuture<Pair<FunkValue, FunkValue>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        val key = callFunctionWithArg(f, element)
                        future.complete(Pair(key, element))
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val keyedElements = futures.map { it.get() }
            val groups = mutableMapOf<FunkValue, MutableList<FunkValue>>()

            for ((key, element) in keyedElements) {
                groups.getOrPut(key) { mutableListOf() }.add(element)
            }

            val result = groups.map { (key, values) ->
                FunkRecord(mapOf(
                    "key" to key,
                    "values" to FunkArrayList(values)
                ))
            }
            FunkArrayList(result)
        }
    }

    // =========================================================================
    // Configurable Parallel Operations (VirtualParallelWith)
    // Uses parallelism limit (semaphore) and chunk_size for batching
    // =========================================================================

    private fun registerParallelMapCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_map_cfg"] = NativeFunkFunction("__native_parallel_map_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_map_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_map_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_map_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_map_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_map_cfg", "No Truffle environment available")

            // Chunk the list
            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)

            // Use semaphore if parallelism > 0
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<List<FunkValue>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            val results = chunk.map { element -> callFunctionWithArg(f, element) }
                            future.complete(results)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val results = futures.flatMap { it.get() }
            FunkArrayList(results)
        }
    }

    private fun registerParallelForEachCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_for_each_cfg"] = NativeFunkFunction("__native_parallel_for_each_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_for_each_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_for_each_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_for_each_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_for_each_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(emptyMap())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_for_each_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            chunk.forEach { element -> callFunctionWithArg(f, element) }
                            future.complete(Unit)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }
            FunkRecord(emptyMap())
        }
    }

    private fun registerParallelFilterCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_filter_cfg"] = NativeFunkFunction("__native_parallel_filter_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_filter_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_filter_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_filter_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_filter_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_filter_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<List<FunkValue>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            val results = chunk.filter { element ->
                                val result = callFunctionWithArg(f, element)
                                (result as? FunkBool)?.value ?: false
                            }
                            future.complete(results)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val results = futures.flatMap { it.get() }
            FunkArrayList(results)
        }
    }

    private fun registerParallelReduceCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_reduce_cfg"] = NativeFunkFunction("__native_parallel_reduce_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_reduce_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_reduce_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_reduce_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_reduce_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_reduce_cfg", "No Truffle environment available")

            // For reduce, we chunk and reduce each chunk in parallel, then combine results
            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<FunkValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            var acc = chunk.first()
                            for (i in 1 until chunk.size) {
                                acc = callBinaryFunction(f, acc, chunk[i])
                            }
                            future.complete(acc)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Combine chunk results sequentially
            val chunkResults = futures.map { it.get() }
            var acc = chunkResults.first()
            for (i in 1 until chunkResults.size) {
                acc = callBinaryFunction(f, acc, chunkResults[i])
            }

            FunkVariant("Some", FunkRecord(mapOf("val" to acc)))
        }
    }

    private fun registerParallelFoldCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_fold_cfg"] = NativeFunkFunction("__native_parallel_fold_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_fold_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_fold_cfg", "expects 'xs' field")
            val zero = record.fields["zero"]
                ?: throw NativeFunctionException("__native_parallel_fold_cfg", "expects 'zero' field")
            val mapFn = record.fields["map_fn"]
                ?: throw NativeFunctionException("__native_parallel_fold_cfg", "expects 'map_fn' field")
            val combine = record.fields["combine"]
                ?: throw NativeFunctionException("__native_parallel_fold_cfg", "expects 'combine' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_fold_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction zero
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_fold_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            // Map and fold each chunk in parallel
            val futures = chunks.map { chunk ->
                val future = CompletableFuture<FunkValue>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            var acc = zero
                            for (element in chunk) {
                                val mapped = callFunctionWithArg(mapFn, element)
                                acc = callBinaryFunction(combine, acc, mapped)
                            }
                            future.complete(acc)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            // Combine chunk results
            val chunkResults = futures.map { it.get() }
            var acc = zero
            for (result in chunkResults) {
                acc = callBinaryFunction(combine, acc, result)
            }
            acc
        }
    }

    private fun registerParallelFindCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_find_cfg"] = NativeFunkFunction("__native_parallel_find_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_find_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_find_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_find_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_find_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_find_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val found = AtomicBoolean(false)
            val result = AtomicReference<FunkValue?>(null)

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            for (element in chunk) {
                                if (found.get()) break
                                val check = callFunctionWithArg(f, element)
                                if ((check as? FunkBool)?.value == true) {
                                    if (found.compareAndSet(false, true)) {
                                        result.set(element)
                                    }
                                    break
                                }
                            }
                            future.complete(Unit)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }

            val foundValue = result.get()
            if (foundValue != null) {
                FunkVariant("Some", FunkRecord(mapOf("val" to foundValue)))
            } else {
                FunkVariant("None", FunkRecord(emptyMap()))
            }
        }
    }

    private fun registerParallelAnyCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_any_cfg"] = NativeFunkFunction("__native_parallel_any_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_any_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_any_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_any_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_any_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkBool(false)
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_any_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val found = AtomicBoolean(false)

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            for (element in chunk) {
                                if (found.get()) break
                                val check = callFunctionWithArg(f, element)
                                if ((check as? FunkBool)?.value == true) {
                                    found.set(true)
                                    break
                                }
                            }
                            future.complete(Unit)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }
            FunkBool(found.get())
        }
    }

    private fun registerParallelAllCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_all_cfg"] = NativeFunkFunction("__native_parallel_all_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_all_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_all_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_all_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_all_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkBool(true)
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_all_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val failed = AtomicBoolean(false)

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<Unit>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            for (element in chunk) {
                                if (failed.get()) break
                                val check = callFunctionWithArg(f, element)
                                if ((check as? FunkBool)?.value != true) {
                                    failed.set(true)
                                    break
                                }
                            }
                            future.complete(Unit)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            futures.forEach { it.get() }
            FunkBool(!failed.get())
        }
    }

    private fun registerParallelPartitionCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_partition_cfg"] = NativeFunkFunction("__native_parallel_partition_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_partition_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_partition_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_partition_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_partition_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(mapOf(
                    "matching" to FunkArrayList(emptyList()),
                    "not_matching" to FunkArrayList(emptyList())
                ))
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_partition_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<Pair<List<FunkValue>, List<FunkValue>>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            val matching = mutableListOf<FunkValue>()
                            val notMatching = mutableListOf<FunkValue>()
                            for (element in chunk) {
                                val check = callFunctionWithArg(f, element)
                                if ((check as? FunkBool)?.value == true) {
                                    matching.add(element)
                                } else {
                                    notMatching.add(element)
                                }
                            }
                            future.complete(Pair(matching, notMatching))
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val chunkResults = futures.map { it.get() }
            val matching = chunkResults.flatMap { it.first }
            val notMatching = chunkResults.flatMap { it.second }

            FunkRecord(mapOf(
                "matching" to FunkArrayList(matching),
                "not_matching" to FunkArrayList(notMatching)
            ))
        }
    }

    private fun registerParallelGroupByCfg(context: MutableMap<String, FunkValue>) {
        context["__native_parallel_group_by_cfg"] = NativeFunkFunction("__native_parallel_group_by_cfg") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_parallel_group_by_cfg", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_parallel_group_by_cfg", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_parallel_group_by_cfg", "expects 'f' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0
            val chunkSize = (record.fields["chunk_size"] as? FunkInt)?.value?.toInt() ?: 1

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_parallel_group_by_cfg", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val env = TruffleContextHolder.getEnv()
                ?: throw NativeFunctionException("__native_parallel_group_by_cfg", "No Truffle environment available")

            val effectiveChunkSize = if (chunkSize <= 0) 1 else chunkSize
            val chunks = list.chunked(effectiveChunkSize)
            val semaphore = if (parallelism > 0) Semaphore(parallelism) else null

            val futures = chunks.map { chunk ->
                val future = CompletableFuture<List<Pair<FunkValue, FunkValue>>>()
                val thread = env.newTruffleThreadBuilder {
                    try {
                        semaphore?.acquire()
                        try {
                            val keyedElements = chunk.map { element ->
                                val key = callFunctionWithArg(f, element)
                                Pair(key, element)
                            }
                            future.complete(keyedElements)
                        } finally {
                            semaphore?.release()
                        }
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }.virtual(true).build()
                thread.start()
                future
            }

            val allKeyedElements = futures.flatMap { it.get() }
            val groups = mutableMapOf<FunkValue, MutableList<FunkValue>>()

            for ((key, element) in allKeyedElements) {
                groups.getOrPut(key) { mutableListOf() }.add(element)
            }

            val result = groups.map { (key, values) ->
                FunkRecord(mapOf(
                    "key" to key,
                    "values" to FunkArrayList(values)
                ))
            }
            FunkArrayList(result)
        }
    }

    // =========================================================================
    // Sequential Operations
    // =========================================================================

    private fun registerSequentialMap(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_map"] = NativeFunkFunction("__native_sequential_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_map", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_map", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_map", "xs must be a list")
            }

            val results = list.map { callFunctionWithArg(f, it) }
            FunkArrayList(results)
        }
    }

    private fun registerSequentialForEach(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_for_each"] = NativeFunkFunction("__native_sequential_for_each") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_for_each", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_for_each", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_for_each", "xs must be a list")
            }

            list.forEach { callFunctionWithArg(f, it) }
            FunkRecord(emptyMap())
        }
    }

    private fun registerSequentialFilter(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_filter"] = NativeFunkFunction("__native_sequential_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_filter", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_filter", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_filter", "xs must be a list")
            }

            val results = list.filter {
                val result = callFunctionWithArg(f, it)
                (result as? FunkBool)?.value ?: false
            }
            FunkArrayList(results)
        }
    }

    private fun registerSequentialReduce(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_reduce"] = NativeFunkFunction("__native_sequential_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_reduce", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_reduce", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_reduce", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_reduce", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            var acc = list.first()
            for (i in 1 until list.size) {
                acc = callBinaryFunction(f, acc, list[i])
            }
            FunkVariant("Some", FunkRecord(mapOf("val" to acc)))
        }
    }

    private fun registerSequentialFold(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_fold"] = NativeFunkFunction("__native_sequential_fold") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_fold", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_fold", "expects 'xs' field")
            val zero = record.fields["zero"]
                ?: throw NativeFunctionException("__native_sequential_fold", "expects 'zero' field")
            val mapFn = record.fields["map_fn"]
                ?: throw NativeFunctionException("__native_sequential_fold", "expects 'map_fn' field")
            val combine = record.fields["combine"]
                ?: throw NativeFunctionException("__native_sequential_fold", "expects 'combine' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_fold", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction zero
            }

            var acc = zero
            for (element in list) {
                val mapped = callFunctionWithArg(mapFn, element)
                acc = callBinaryFunction(combine, acc, mapped)
            }
            acc
        }
    }

    private fun registerSequentialBoth(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_both"] = NativeFunkFunction("__native_sequential_both") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_both", "expects FunkRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_sequential_both", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_sequential_both", "expects 'right' field")

            val leftResult = callFunction(leftFn)
            val rightResult = callFunction(rightFn)

            FunkRecord(mapOf(
                "left" to leftResult,
                "right" to rightResult
            ))
        }
    }

    private fun registerSequentialBoth3(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_both3"] = NativeFunkFunction("__native_sequential_both3") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_both3", "expects FunkRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_sequential_both3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_sequential_both3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_sequential_both3", "expects 'c' field")

            FunkRecord(mapOf(
                "a" to callFunction(aFn),
                "b" to callFunction(bFn),
                "c" to callFunction(cFn)
            ))
        }
    }

    private fun registerSequentialFind(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_find"] = NativeFunkFunction("__native_sequential_find") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_find", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_find", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_find", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_find", "xs must be a list")
            }

            for (element in list) {
                val result = callFunctionWithArg(f, element)
                if ((result as? FunkBool)?.value == true) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf("val" to element)))
                }
            }
            FunkVariant("None", FunkRecord(emptyMap()))
        }
    }

    private fun registerSequentialAny(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_any"] = NativeFunkFunction("__native_sequential_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_any", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_any", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_any", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_any", "xs must be a list")
            }

            for (element in list) {
                val result = callFunctionWithArg(f, element)
                if ((result as? FunkBool)?.value == true) {
                    return@NativeFunkFunction FunkBool(true)
                }
            }
            FunkBool(false)
        }
    }

    private fun registerSequentialAll(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_all"] = NativeFunkFunction("__native_sequential_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_all", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_all", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_all", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_all", "xs must be a list")
            }

            for (element in list) {
                val result = callFunctionWithArg(f, element)
                if ((result as? FunkBool)?.value != true) {
                    return@NativeFunkFunction FunkBool(false)
                }
            }
            FunkBool(true)
        }
    }

    private fun registerSequentialPartition(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_partition"] = NativeFunkFunction("__native_sequential_partition") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_partition", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_partition", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_partition", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_partition", "xs must be a list")
            }

            val matching = mutableListOf<FunkValue>()
            val notMatching = mutableListOf<FunkValue>()

            for (element in list) {
                val result = callFunctionWithArg(f, element)
                if ((result as? FunkBool)?.value == true) {
                    matching.add(element)
                } else {
                    notMatching.add(element)
                }
            }

            FunkRecord(mapOf(
                "matching" to FunkArrayList(matching),
                "not_matching" to FunkArrayList(notMatching)
            ))
        }
    }

    private fun registerSequentialGroupBy(context: MutableMap<String, FunkValue>) {
        context["__native_sequential_group_by"] = NativeFunkFunction("__native_sequential_group_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_sequential_group_by", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_sequential_group_by", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_sequential_group_by", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_sequential_group_by", "xs must be a list")
            }

            val groups = mutableMapOf<FunkValue, MutableList<FunkValue>>()

            for (element in list) {
                val key = callFunctionWithArg(f, element)
                groups.getOrPut(key) { mutableListOf() }.add(element)
            }

            val result = groups.map { (key, values) ->
                FunkRecord(mapOf(
                    "key" to key,
                    "values" to FunkArrayList(values)
                ))
            }
            FunkArrayList(result)
        }
    }

    // =========================================================================
    // Helper Functions
    // =========================================================================

    /**
     * Internal parallel reduce using pairwise reduction.
     */
    private fun parallelReduceInternal(list: List<FunkValue>, f: FunkValue): FunkValue {
        if (list.size == 1) {
            return list.first()
        }

        // Sequential reduction for simplicity - could be parallelized with pairwise reduction
        var acc = list.first()
        for (i in 1 until list.size) {
            acc = callBinaryFunction(f, acc, list[i])
        }
        return acc
    }

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
     */
    private fun callFunctionWithArg(fn: FunkValue, arg: FunkValue): FunkValue {
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

    /**
     * Call a binary function with two arguments wrapped in a record {left = a, right = b}.
     */
    private fun callBinaryFunction(fn: FunkValue, left: FunkValue, right: FunkValue): FunkValue {
        val recordArg = FunkRecord(mapOf("left" to left, "right" to right))
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
