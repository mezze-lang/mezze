package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*
import java.util.concurrent.CompletableFuture

/**
 * Native implementations for Coop (cooperative) async.
 *
 * Provides the same API as Loom but uses cooperative scheduling internally.
 * All operations are single-threaded - "parallelism" is achieved by interleaving
 * at await points.
 *
 * Key difference from Loom:
 * - Loom: spawns actual virtual threads, await blocks the thread
 * - Coop: stores computation in promise, await executes it immediately
 *
 * This makes Coop suitable for:
 * - Single-threaded environments (JavaScript compile target)
 * - Testing with deterministic execution
 * - Workflow orchestration with serializable state
 */
object CoopImplementations {

    // Tag to identify Coop promises (vs Loom promises)
    private const val COOP_PROMISE_TAG = "__coop_promise__"

    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerSpawn(context)
        registerAwait(context)
        registerRace(context)
        registerAllPromises(context)
        registerTimeout(context)
        registerCancel(context)
        registerSleep(context)
        registerIsDone(context)
        registerIsCancelled(context)
        registerCheck(context)

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

        // Keep legacy functions for compatibility
        registerWrapPromise(context)
        registerUnwrapPromise(context)
    }

    /**
     * __native_coop_spawn - Store a computation in a promise.
     *
     * Unlike Loom which spawns a thread, Coop just stores the function.
     * The computation will be executed when awaited.
     */
    private fun registerSpawn(context: MutableMap<String, FunkValue>) {
        context["__native_coop_spawn"] = NativeFunkFunction("__native_coop_spawn") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_spawn", "expects FunkRecord")

            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_spawn", "expects 'f' field")

            // Wrap the function in a completed future with a tagged record
            val wrapper = FunkRecord(mapOf(
                "tag" to FunkString(COOP_PROMISE_TAG),
                "f" to f
            ))
            val future = CompletableFuture.completedFuture<FunkValue>(wrapper)
            FunkPromise(future)
        }
    }

    /**
     * __native_coop_await - Execute the promise's computation and return result.
     *
     * Unlike Loom which blocks a thread, Coop executes the computation
     * immediately and returns the result.
     */
    private fun registerAwait(context: MutableMap<String, FunkValue>) {
        context["__native_coop_await"] = NativeFunkFunction("__native_coop_await") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_await", "expects FunkRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_await", "expects 'p' field")

            executePromise(promise, "__native_coop_await")
        }
    }

    /**
     * __native_coop_race - Execute first promise and return its result.
     */
    private fun registerRace(context: MutableMap<String, FunkValue>) {
        context["__native_coop_race"] = NativeFunkFunction("__native_coop_race") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_race", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_race", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList()
                else -> throw NativeFunctionException(
                    "__native_coop_race",
                    "ps must be a list, got: ${ps::class.simpleName}"
                )
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_coop_race", "cannot race empty list")
            }

            // In Coop, just execute the first promise
            executePromise(promises.first(), "__native_coop_race")
        }
    }

    /**
     * __native_coop_all - Execute all promises and return results.
     */
    private fun registerAllPromises(context: MutableMap<String, FunkValue>) {
        context["__native_coop_all"] = NativeFunkFunction("__native_coop_all") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_all", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_all", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList()
                else -> throw NativeFunctionException(
                    "__native_coop_all",
                    "ps must be a list, got: ${ps::class.simpleName}"
                )
            }

            val results = promises.map { promise ->
                executePromise(promise, "__native_coop_all")
            }

            FunkArrayList(results)
        }
    }

    /**
     * __native_coop_timeout - Execute promise (timeout is simulated in Coop).
     */
    private fun registerTimeout(context: MutableMap<String, FunkValue>) {
        context["__native_coop_timeout"] = NativeFunkFunction("__native_coop_timeout") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_timeout", "expects FunkRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_timeout", "expects 'p' field")

            // In Coop, timeout always succeeds (single-threaded, no actual waiting)
            val result = executePromise(promise, "__native_coop_timeout")
            FunkVariant("Some", FunkRecord(mapOf("val" to result)))
        }
    }

    /**
     * __native_coop_sleep - Simulated sleep (no-op in single-threaded Coop).
     */
    private fun registerSleep(context: MutableMap<String, FunkValue>) {
        context["__native_coop_sleep"] = NativeFunkFunction("__native_coop_sleep") { args ->
            // In Coop, sleep is a no-op (single-threaded, cooperative)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_coop_is_done - Check if promise is done (always true in Coop).
     */
    private fun registerIsDone(context: MutableMap<String, FunkValue>) {
        context["__native_coop_is_done"] = NativeFunkFunction("__native_coop_is_done") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_is_done", "expects FunkRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_is_done", "expects 'p' field")

            // In Coop, promises are always "ready" (computation stored, not executed)
            FunkBool(true)
        }
    }

    /**
     * __native_coop_cancel - Cancel a promise (marks as cancelled in Coop).
     */
    private fun registerCancel(context: MutableMap<String, FunkValue>) {
        context["__native_coop_cancel"] = NativeFunkFunction("__native_coop_cancel") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_cancel", "expects FunkRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_cancel", "expects 'p' field")

            when (promise) {
                is FunkPromise -> {
                    promise.future.cancel(true)
                    FunkRecord(emptyMap())
                }
                else -> throw NativeFunctionException(
                    "__native_coop_cancel",
                    "p must be a FunkPromise"
                )
            }
        }
    }

    /**
     * __native_coop_is_cancelled - Check if current computation was cancelled.
     * In single-threaded Coop, this always returns false.
     */
    private fun registerIsCancelled(context: MutableMap<String, FunkValue>) {
        context["__native_coop_is_cancelled"] = NativeFunkFunction("__native_coop_is_cancelled") { args ->
            // In single-threaded Coop, cancellation is not applicable
            FunkBool(false)
        }
    }

    /**
     * __native_coop_check - Cancellation checkpoint.
     * In single-threaded Coop, this is a no-op.
     */
    private fun registerCheck(context: MutableMap<String, FunkValue>) {
        context["__native_coop_check"] = NativeFunkFunction("__native_coop_check") { args ->
            // In single-threaded Coop, no cancellation to check
            FunkRecord(emptyMap())
        }
    }

    /**
     * Helper: Execute a Coop promise's stored computation.
     */
    private fun executePromise(promise: FunkValue, functionName: String): FunkValue {
        when (promise) {
            is FunkPromise -> {
                val wrapper = promise.future.get()
                when (wrapper) {
                    is FunkRecord -> {
                        val tag = wrapper.fields["tag"]
                        if (tag is FunkString && tag.value == COOP_PROMISE_TAG) {
                            val f = wrapper.fields["f"]
                                ?: throw NativeFunctionException(
                                    functionName,
                                    "Coop promise missing 'f' field"
                                )
                            return executeFunction(f, functionName)
                        } else {
                            throw NativeFunctionException(
                                functionName,
                                "expected Coop promise, got different tag"
                            )
                        }
                    }
                    else -> throw NativeFunctionException(
                        functionName,
                        "expected Coop promise wrapper record"
                    )
                }
            }
            else -> throw NativeFunctionException(
                functionName,
                "p must be a FunkPromise, got: ${promise::class.simpleName}"
            )
        }
    }

    /**
     * Helper: Execute a Funk function with empty arguments.
     */
    private fun executeFunction(f: FunkValue, functionName: String): FunkValue {
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    FunkRecord(emptyMap()),
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> f.call(listOf(FunkRecord(emptyMap())))
            else -> throw NativeFunctionException(
                functionName,
                "computation must be a function, got: ${f::class.simpleName}"
            )
        }
    }

    // =========================================================================
    // Structured Concurrency Operations
    // =========================================================================

    /**
     * __native_coop_join - Run two computations sequentially (Coop is single-threaded).
     */
    private fun registerJoin(context: MutableMap<String, FunkValue>) {
        context["__native_coop_join"] = NativeFunkFunction("__native_coop_join") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_join", "expects FunkRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_coop_join", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_coop_join", "expects 'right' field")

            // In Coop, execute sequentially (single-threaded)
            val leftResult = executeFunction(leftFn, "__native_coop_join")
            val rightResult = executeFunction(rightFn, "__native_coop_join")

            FunkRecord(mapOf(
                "left" to leftResult,
                "right" to rightResult
            ))
        }
    }

    /**
     * __native_coop_join3 - Run three computations sequentially.
     */
    private fun registerJoin3(context: MutableMap<String, FunkValue>) {
        context["__native_coop_join3"] = NativeFunkFunction("__native_coop_join3") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_join3", "expects FunkRecord")

            val aFn = record.fields["a"]
                ?: throw NativeFunctionException("__native_coop_join3", "expects 'a' field")
            val bFn = record.fields["b"]
                ?: throw NativeFunctionException("__native_coop_join3", "expects 'b' field")
            val cFn = record.fields["c"]
                ?: throw NativeFunctionException("__native_coop_join3", "expects 'c' field")

            // In Coop, execute sequentially
            val aResult = executeFunction(aFn, "__native_coop_join3")
            val bResult = executeFunction(bFn, "__native_coop_join3")
            val cResult = executeFunction(cFn, "__native_coop_join3")

            FunkRecord(mapOf(
                "a" to aResult,
                "b" to bResult,
                "c" to cResult
            ))
        }
    }

    // =========================================================================
    // Collection Operations
    // =========================================================================

    /**
     * __native_coop_map - Apply function to each element sequentially.
     */
    private fun registerMap(context: MutableMap<String, FunkValue>) {
        context["__native_coop_map"] = NativeFunkFunction("__native_coop_map") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_map", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_coop_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_map", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_coop_map", "xs must be a list")
            }

            // In Coop, map sequentially
            val results = list.map { element ->
                executeFunctionWithArg(f, element, "__native_coop_map")
            }

            FunkArrayList(results)
        }
    }

    /**
     * __native_coop_for_each - Run function on each element for side effects.
     */
    private fun registerForEach(context: MutableMap<String, FunkValue>) {
        context["__native_coop_for_each"] = NativeFunkFunction("__native_coop_for_each") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_for_each", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_coop_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_for_each", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_coop_for_each", "xs must be a list")
            }

            // In Coop, execute sequentially
            for (element in list) {
                executeFunctionWithArg(f, element, "__native_coop_for_each")
            }

            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_coop_filter - Filter elements with predicate.
     */
    private fun registerFilter(context: MutableMap<String, FunkValue>) {
        context["__native_coop_filter"] = NativeFunkFunction("__native_coop_filter") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_filter", "expects FunkRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_coop_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_filter", "expects 'f' field")

            val list = when (xs) {
                is FunkArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_coop_filter", "xs must be a list")
            }

            // In Coop, filter sequentially
            val results = list.filter { element ->
                val result = executeFunctionWithArg(f, element, "__native_coop_filter")
                (result as? FunkBool)?.value ?: false
            }

            FunkArrayList(results)
        }
    }

    // =========================================================================
    // Error Handling Operations
    // =========================================================================

    /**
     * __native_coop_any - Return first successful result from promises.
     * In Coop, executes promises in order, returns first success.
     */
    private fun registerAny(context: MutableMap<String, FunkValue>) {
        context["__native_coop_any"] = NativeFunkFunction("__native_coop_any") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_any", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_any", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList()
                else -> throw NativeFunctionException("__native_coop_any", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_coop_any", "cannot get any from empty list")
            }

            // In Coop, try each promise in order, return first success
            val errors = mutableListOf<Exception>()
            for (promise in promises) {
                try {
                    return@NativeFunkFunction executePromise(promise, "__native_coop_any")
                } catch (e: Exception) {
                    errors.add(e)
                }
            }

            throw NativeFunctionException(
                "__native_coop_any",
                "All promises failed: ${errors.map { it.message }}"
            )
        }
    }

    /**
     * __native_coop_settle - Execute all promises, return Results.
     */
    private fun registerSettle(context: MutableMap<String, FunkValue>) {
        context["__native_coop_settle"] = NativeFunkFunction("__native_coop_settle") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_settle", "expects FunkRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_settle", "expects 'ps' field")

            val promises = when (ps) {
                is FunkArrayList -> ps.toList()
                else -> throw NativeFunctionException("__native_coop_settle", "ps must be a list")
            }

            // Execute all and collect as Results
            val results = promises.map { promise ->
                try {
                    val value = executePromise(promise, "__native_coop_settle")
                    FunkVariant("Ok", FunkRecord(mapOf("val" to value)))
                } catch (e: java.util.concurrent.CancellationException) {
                    FunkVariant("Err", FunkRecord(mapOf(
                        "error" to FunkVariant("AsyncCancelled", FunkRecord(emptyMap()))
                    )))
                } catch (e: Exception) {
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

    /**
     * Helper: Execute a Funk function with a single argument wrapped in a record {x = arg}.
     * This is the Funk convention for functions taking a single argument.
     */
    private fun executeFunctionWithArg(f: FunkValue, arg: FunkValue, functionName: String): FunkValue {
        // Wrap the argument in a record with field "x"
        val recordArg = FunkRecord(mapOf("x" to arg))
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    recordArg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> f.call(listOf(recordArg))
            else -> throw NativeFunctionException(
                functionName,
                "f must be a function, got: ${f::class.simpleName}"
            )
        }
    }

    // =========================================================================
    // Legacy functions (for compatibility)
    // =========================================================================

    private fun registerWrapPromise(context: MutableMap<String, FunkValue>) {
        context["__native_coop_wrap_promise"] = NativeFunkFunction("__native_coop_wrap_promise") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_wrap_promise", "expects FunkRecord")

            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_wrap_promise", "expects 'f' field")

            val wrapper = FunkRecord(mapOf(
                "tag" to FunkString(COOP_PROMISE_TAG),
                "f" to f
            ))
            val future = CompletableFuture.completedFuture<FunkValue>(wrapper)
            FunkPromise(future)
        }
    }

    private fun registerUnwrapPromise(context: MutableMap<String, FunkValue>) {
        context["__native_coop_unwrap_promise"] = NativeFunkFunction("__native_coop_unwrap_promise") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw NativeFunctionException("__native_coop_unwrap_promise", "expects FunkRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_unwrap_promise", "expects 'p' field")

            when (promise) {
                is FunkPromise -> {
                    val wrapper = promise.future.get()
                    when (wrapper) {
                        is FunkRecord -> {
                            val tag = wrapper.fields["tag"]
                            if (tag is FunkString && tag.value == COOP_PROMISE_TAG) {
                                val f = wrapper.fields["f"]
                                    ?: throw NativeFunctionException(
                                        "__native_coop_unwrap_promise",
                                        "Coop promise missing 'f' field"
                                    )
                                FunkRecord(mapOf("f" to f))
                            } else {
                                throw NativeFunctionException(
                                    "__native_coop_unwrap_promise",
                                    "expected Coop promise, got different tag"
                                )
                            }
                        }
                        else -> throw NativeFunctionException(
                            "__native_coop_unwrap_promise",
                            "expected Coop promise wrapper record"
                        )
                    }
                }
                else -> throw NativeFunctionException(
                    "__native_coop_unwrap_promise",
                    "p must be a FunkPromise, got: ${promise::class.simpleName}"
                )
            }
        }
    }
}
