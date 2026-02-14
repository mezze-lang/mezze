package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import com.oracle.truffle.api.TruffleContext
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.RecursiveTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * True ForkJoin parallel implementation with work-stealing.
 *
 * Uses a custom ForkJoinPool with worker threads that enter/leave the TruffleContext
 * in onStart()/onTermination(). This allows proper context access while benefiting
 * from ForkJoin's work-stealing algorithm.
 *
 * Based on the approach described in https://github.com/oracle/graal/issues/1379
 *
 * Key features:
 * - Work-stealing for load balancing across cores
 * - Recursive task decomposition (divide-and-conquer)
 * - Configurable threshold for switching to sequential execution
 * - Proper TruffleContext management for each worker thread
 */
object ForkJoinParallelImplementations {

    // Default threshold: below this size, execute sequentially
    private const val DEFAULT_THRESHOLD = 64

    /**
     * Custom ForkJoinWorkerThread that enters TruffleContext on start.
     */
    private class TruffleAwareForkJoinWorkerThread(
        pool: ForkJoinPool,
        private val truffleContext: TruffleContext
    ) : ForkJoinWorkerThread(pool) {

        private var contextHandle: Any? = null

        override fun onStart() {
            super.onStart()
            // Enter the TruffleContext for this worker thread
            contextHandle = truffleContext.enter(null)
        }

        override fun onTermination(exception: Throwable?) {
            // Leave the TruffleContext before thread terminates
            try {
                truffleContext.leave(null, contextHandle)
            } finally {
                super.onTermination(exception)
            }
        }
    }

    /**
     * Factory for creating TruffleAware ForkJoin worker threads.
     */
    private class TruffleAwareForkJoinWorkerThreadFactory(
        private val truffleContext: TruffleContext
    ) : ForkJoinPool.ForkJoinWorkerThreadFactory {
        override fun newThread(pool: ForkJoinPool): ForkJoinWorkerThread {
            return TruffleAwareForkJoinWorkerThread(pool, truffleContext)
        }
    }

    /**
     * Get or create a ForkJoinPool for the current TruffleContext.
     *
     * Note: In production, you'd want to cache these pools per context.
     * For simplicity, we create a new pool for each parallel operation.
     */
    private fun createForkJoinPool(parallelism: Int = 0): ForkJoinPool {
        val env = TruffleContextHolder.getEnv()
            ?: throw FunkException("No Truffle environment available for ForkJoin parallel")

        val truffleContext = env.context

        val effectiveParallelism = if (parallelism > 0) parallelism else Runtime.getRuntime().availableProcessors()

        return ForkJoinPool(
            effectiveParallelism,
            TruffleAwareForkJoinWorkerThreadFactory(truffleContext),
            null,  // No custom exception handler
            false  // LIFO mode (better for recursive tasks)
        )
    }

    /**
     * Register all ForkJoin parallel native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerForkJoinMap(context)
        registerForkJoinForEach(context)
        registerForkJoinFilter(context)
        registerForkJoinReduce(context)
        registerForkJoinFold(context)
        registerForkJoinBoth(context)
        registerForkJoinBoth3(context)
        registerForkJoinFind(context)
        registerForkJoinAny(context)
        registerForkJoinAll(context)
        registerForkJoinPartition(context)
        registerForkJoinGroupBy(context)
    }

    // =========================================================================
    // RecursiveTask implementations for work-stealing
    // =========================================================================

    /**
     * Recursive task for parallel map with work-stealing.
     */
    private class MapTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int
    ) : RecursiveTask<List<FunkValue>>() {

        override fun compute(): List<FunkValue> {
            val size = end - start

            if (size <= threshold) {
                // Sequential execution for small chunks
                return (start until end).map { i ->
                    callFunctionWithArg(f, list[i])
                }
            }

            // Fork: split in half
            val mid = start + size / 2
            val leftTask = MapTask(list, f, start, mid, threshold)
            val rightTask = MapTask(list, f, mid, end, threshold)

            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()

            return leftResult + rightResult
        }
    }

    /**
     * Recursive task for parallel filter with work-stealing.
     */
    private class FilterTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int
    ) : RecursiveTask<List<FunkValue>>() {

        override fun compute(): List<FunkValue> {
            val size = end - start

            if (size <= threshold) {
                return (start until end).mapNotNull { i ->
                    val element = list[i]
                    val result = callFunctionWithArg(f, element)
                    if ((result as? FunkBool)?.value == true) element else null
                }
            }

            val mid = start + size / 2
            val leftTask = FilterTask(list, f, start, mid, threshold)
            val rightTask = FilterTask(list, f, mid, end, threshold)

            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()

            return leftResult + rightResult
        }
    }

    /**
     * Recursive task for parallel reduce with work-stealing.
     */
    private class ReduceTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int
    ) : RecursiveTask<FunkValue>() {

        override fun compute(): FunkValue {
            val size = end - start

            if (size <= threshold) {
                // Sequential reduce for small chunks
                var acc = list[start]
                for (i in (start + 1) until end) {
                    acc = callBinaryFunction(f, acc, list[i])
                }
                return acc
            }

            val mid = start + size / 2
            val leftTask = ReduceTask(list, f, start, mid, threshold)
            val rightTask = ReduceTask(list, f, mid, end, threshold)

            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()

            // Combine left and right results
            return callBinaryFunction(f, leftResult, rightResult)
        }
    }

    /**
     * Recursive task for parallel fold with work-stealing.
     */
    private class FoldTask(
        private val list: List<FunkValue>,
        private val zero: FunkValue,
        private val mapFn: FunkValue,
        private val combine: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int
    ) : RecursiveTask<FunkValue>() {

        override fun compute(): FunkValue {
            val size = end - start

            if (size <= threshold) {
                var acc = zero
                for (i in start until end) {
                    val mapped = callFunctionWithArg(mapFn, list[i])
                    acc = callBinaryFunction(combine, acc, mapped)
                }
                return acc
            }

            val mid = start + size / 2
            val leftTask = FoldTask(list, zero, mapFn, combine, start, mid, threshold)
            val rightTask = FoldTask(list, zero, mapFn, combine, mid, end, threshold)

            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()

            return callBinaryFunction(combine, leftResult, rightResult)
        }
    }

    /**
     * Recursive task for parallel find with short-circuiting.
     */
    private class FindTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int,
        private val found: AtomicBoolean,
        private val result: AtomicReference<FunkValue?>
    ) : RecursiveTask<Unit>() {

        override fun compute() {
            if (found.get()) return

            val size = end - start

            if (size <= threshold) {
                for (i in start until end) {
                    if (found.get()) return
                    val element = list[i]
                    val check = callFunctionWithArg(f, element)
                    if ((check as? FunkBool)?.value == true) {
                        if (found.compareAndSet(false, true)) {
                            result.set(element)
                        }
                        return
                    }
                }
                return
            }

            val mid = start + size / 2
            val leftTask = FindTask(list, f, start, mid, threshold, found, result)
            val rightTask = FindTask(list, f, mid, end, threshold, found, result)

            leftTask.fork()
            rightTask.compute()
            leftTask.join()
        }
    }

    /**
     * Recursive task for parallel any with short-circuiting.
     */
    private class AnyTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int,
        private val found: AtomicBoolean
    ) : RecursiveTask<Unit>() {

        override fun compute() {
            if (found.get()) return

            val size = end - start

            if (size <= threshold) {
                for (i in start until end) {
                    if (found.get()) return
                    val element = list[i]
                    val check = callFunctionWithArg(f, element)
                    if ((check as? FunkBool)?.value == true) {
                        found.set(true)
                        return
                    }
                }
                return
            }

            val mid = start + size / 2
            val leftTask = AnyTask(list, f, start, mid, threshold, found)
            val rightTask = AnyTask(list, f, mid, end, threshold, found)

            leftTask.fork()
            rightTask.compute()
            leftTask.join()
        }
    }

    /**
     * Recursive task for parallel all with short-circuiting.
     */
    private class AllTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int,
        private val failed: AtomicBoolean
    ) : RecursiveTask<Unit>() {

        override fun compute() {
            if (failed.get()) return

            val size = end - start

            if (size <= threshold) {
                for (i in start until end) {
                    if (failed.get()) return
                    val element = list[i]
                    val check = callFunctionWithArg(f, element)
                    if ((check as? FunkBool)?.value != true) {
                        failed.set(true)
                        return
                    }
                }
                return
            }

            val mid = start + size / 2
            val leftTask = AllTask(list, f, start, mid, threshold, failed)
            val rightTask = AllTask(list, f, mid, end, threshold, failed)

            leftTask.fork()
            rightTask.compute()
            leftTask.join()
        }
    }

    /**
     * Recursive task for parallel partition.
     */
    private class PartitionTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int
    ) : RecursiveTask<Pair<List<FunkValue>, List<FunkValue>>>() {

        override fun compute(): Pair<List<FunkValue>, List<FunkValue>> {
            val size = end - start

            if (size <= threshold) {
                val matching = mutableListOf<FunkValue>()
                val notMatching = mutableListOf<FunkValue>()
                for (i in start until end) {
                    val element = list[i]
                    val check = callFunctionWithArg(f, element)
                    if ((check as? FunkBool)?.value == true) {
                        matching.add(element)
                    } else {
                        notMatching.add(element)
                    }
                }
                return Pair(matching, notMatching)
            }

            val mid = start + size / 2
            val leftTask = PartitionTask(list, f, start, mid, threshold)
            val rightTask = PartitionTask(list, f, mid, end, threshold)

            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()

            return Pair(
                leftResult.first + rightResult.first,
                leftResult.second + rightResult.second
            )
        }
    }

    /**
     * Recursive task for parallel group_by.
     */
    private class GroupByTask(
        private val list: List<FunkValue>,
        private val f: FunkValue,
        private val start: Int,
        private val end: Int,
        private val threshold: Int
    ) : RecursiveTask<List<Pair<FunkValue, FunkValue>>>() {

        override fun compute(): List<Pair<FunkValue, FunkValue>> {
            val size = end - start

            if (size <= threshold) {
                return (start until end).map { i ->
                    val element = list[i]
                    val key = callFunctionWithArg(f, element)
                    Pair(key, element)
                }
            }

            val mid = start + size / 2
            val leftTask = GroupByTask(list, f, start, mid, threshold)
            val rightTask = GroupByTask(list, f, mid, end, threshold)

            leftTask.fork()
            val rightResult = rightTask.compute()
            val leftResult = leftTask.join()

            return leftResult + rightResult
        }
    }

    // =========================================================================
    // Native function registrations
    // =========================================================================

    private fun registerForkJoinMap(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_map"] = NativeFunkFunction("__native_forkjoin_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_map", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_map", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_map", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val task = MapTask(list, f, 0, list.size, threshold)
                val results = pool.invoke(task)
                FunkArrayList(results)
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinForEach(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_for_each"] = NativeFunkFunction("__native_forkjoin_for_each") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_for_each", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_for_each", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_for_each", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(emptyMap())
            }

            val pool = createForkJoinPool(parallelism)
            try {
                // Use map task but ignore results
                val task = MapTask(list, f, 0, list.size, threshold)
                pool.invoke(task)
                FunkRecord(emptyMap())
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinFilter(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_filter"] = NativeFunkFunction("__native_forkjoin_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_filter", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_filter", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_filter", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val task = FilterTask(list, f, 0, list.size, threshold)
                val results = pool.invoke(task)
                FunkArrayList(results)
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinReduce(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_reduce"] = NativeFunkFunction("__native_forkjoin_reduce") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_reduce", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_reduce", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_reduce", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_reduce", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val task = ReduceTask(list, f, 0, list.size, threshold)
                val result = pool.invoke(task)
                FunkVariant("Some", FunkRecord(mapOf("val" to result)))
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinFold(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_fold"] = NativeFunkFunction("__native_forkjoin_fold") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_fold", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_fold", "expects 'xs' field")
            val zero = record.fields["zero"]
                ?: throw NativeFunctionException("__native_forkjoin_fold", "expects 'zero' field")
            val mapFn = record.fields["map_fn"]
                ?: throw NativeFunctionException("__native_forkjoin_fold", "expects 'map_fn' field")
            val combine = record.fields["combine"]
                ?: throw NativeFunctionException("__native_forkjoin_fold", "expects 'combine' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_fold", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction zero
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val task = FoldTask(list, zero, mapFn, combine, 0, list.size, threshold)
                pool.invoke(task)
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinBoth(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_both"] = NativeFunkFunction("__native_forkjoin_both") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_both", "expects FunkRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_forkjoin_both", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_forkjoin_both", "expects 'right' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val pool = createForkJoinPool(parallelism)
            try {
                // Submit both tasks to the pool and wait for results
                val leftFuture = pool.submit<FunkValue> { callFunction(leftFn) }
                val rightFuture = pool.submit<FunkValue> { callFunction(rightFn) }

                val leftResult = leftFuture.get()
                val rightResult = rightFuture.get()

                FunkRecord(mapOf("left" to leftResult, "right" to rightResult))
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinBoth3(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_both3"] = NativeFunkFunction("__native_forkjoin_both3") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_both3", "expects FunkRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_forkjoin_both3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_forkjoin_both3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_forkjoin_both3", "expects 'c' field")
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val pool = createForkJoinPool(parallelism)
            try {
                // Submit all three tasks to the pool and wait for results
                val aFuture = pool.submit<FunkValue> { callFunction(aFn) }
                val bFuture = pool.submit<FunkValue> { callFunction(bFn) }
                val cFuture = pool.submit<FunkValue> { callFunction(cFn) }

                val aResult = aFuture.get()
                val bResult = bFuture.get()
                val cResult = cFuture.get()

                FunkRecord(mapOf("a" to aResult, "b" to bResult, "c" to cResult))
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinFind(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_find"] = NativeFunkFunction("__native_forkjoin_find") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_find", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_find", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_find", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_find", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkVariant("None", FunkRecord(emptyMap()))
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val found = AtomicBoolean(false)
                val result = AtomicReference<FunkValue?>(null)
                val task = FindTask(list, f, 0, list.size, threshold, found, result)
                pool.invoke(task)

                val foundValue = result.get()
                if (foundValue != null) {
                    FunkVariant("Some", FunkRecord(mapOf("val" to foundValue)))
                } else {
                    FunkVariant("None", FunkRecord(emptyMap()))
                }
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinAny(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_any"] = NativeFunkFunction("__native_forkjoin_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_any", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_any", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_any", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_any", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkBool(false)
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val found = AtomicBoolean(false)
                val task = AnyTask(list, f, 0, list.size, threshold, found)
                pool.invoke(task)
                FunkBool(found.get())
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinAll(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_all"] = NativeFunkFunction("__native_forkjoin_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_all", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_all", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_all", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_all", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkBool(true)
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val failed = AtomicBoolean(false)
                val task = AllTask(list, f, 0, list.size, threshold, failed)
                pool.invoke(task)
                FunkBool(!failed.get())
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinPartition(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_partition"] = NativeFunkFunction("__native_forkjoin_partition") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_partition", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_partition", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_partition", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_partition", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkRecord(mapOf(
                    "matching" to FunkArrayList(emptyList()),
                    "not_matching" to FunkArrayList(emptyList())
                ))
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val task = PartitionTask(list, f, 0, list.size, threshold)
                val (matching, notMatching) = pool.invoke(task)
                FunkRecord(mapOf(
                    "matching" to FunkArrayList(matching),
                    "not_matching" to FunkArrayList(notMatching)
                ))
            } finally {
                pool.shutdown()
            }
        }
    }

    private fun registerForkJoinGroupBy(context: MutableMap<String, FunkValue>) {
        context["__native_forkjoin_group_by"] = NativeFunkFunction("__native_forkjoin_group_by") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_forkjoin_group_by", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_forkjoin_group_by", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_forkjoin_group_by", "expects 'f' field")
            val threshold = (record.fields["threshold"] as? FunkInt)?.value?.toInt() ?: DEFAULT_THRESHOLD
            val parallelism = (record.fields["parallelism"] as? FunkInt)?.value?.toInt() ?: 0

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_forkjoin_group_by", "xs must be a list")
            }

            if (list.isEmpty()) {
                return@NativeFunkFunction FunkArrayList(emptyList())
            }

            val pool = createForkJoinPool(parallelism)
            try {
                val task = GroupByTask(list, f, 0, list.size, threshold)
                val keyedElements = pool.invoke(task)

                // Group the keyed elements
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
            } finally {
                pool.shutdown()
            }
        }
    }

    // =========================================================================
    // Helper functions (same as ParallelImplementations)
    // =========================================================================

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
