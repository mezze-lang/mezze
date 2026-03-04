package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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

    fun registerAll(context: MutableMap<String, MezzeValue>) {
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

        // Structured concurrency scopes
        registerScoped(context)

    }

    /**
     * __native_coop_spawn - Store a computation in a promise.
     *
     * Unlike Loom which spawns a thread, Coop just stores the function.
     * The computation will be executed when awaited.
     */
    private fun registerSpawn(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_spawn"] = NativeMezzeFunction("__native_coop_spawn") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_spawn", "expects MezzeRecord")

            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_spawn", "expects 'f' field")

            // Wrap the function in a completed future with a tagged record
            val wrapper = MezzeRecord(mapOf(
                "tag" to MezzeString(COOP_PROMISE_TAG),
                "f" to f
            ))
            val future = CompletableFuture.completedFuture<MezzeValue>(wrapper)
            MezzePromise(future)
        }
    }

    /**
     * __native_coop_await - Execute the promise's computation and return result.
     *
     * Unlike Loom which blocks a thread, Coop executes the computation
     * immediately and returns the result.
     */
    private fun registerAwait(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_await"] = NativeMezzeFunction("__native_coop_await") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_await", "expects MezzeRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_await", "expects 'p' field")

            executePromise(promise, "__native_coop_await")
        }
    }

    /**
     * __native_coop_race - Execute first promise and return its result.
     */
    private fun registerRace(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_race"] = NativeMezzeFunction("__native_coop_race") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_race", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_race", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList()
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
    private fun registerAllPromises(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_all"] = NativeMezzeFunction("__native_coop_all") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_all", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_all", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList()
                else -> throw NativeFunctionException(
                    "__native_coop_all",
                    "ps must be a list, got: ${ps::class.simpleName}"
                )
            }

            val results = promises.map { promise ->
                executePromise(promise, "__native_coop_all")
            }

            MezzeArrayList(results)
        }
    }

    /**
     * __native_coop_timeout - Execute promise (timeout is simulated in Coop).
     */
    private fun registerTimeout(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_timeout"] = NativeMezzeFunction("__native_coop_timeout") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_timeout", "expects MezzeRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_timeout", "expects 'p' field")

            // In Coop, timeout always succeeds (single-threaded, no actual waiting)
            val result = executePromise(promise, "__native_coop_timeout")
            mezzeOptionSome(result)
        }
    }

    /**
     * __native_coop_sleep - Simulated sleep (no-op in single-threaded Coop).
     */
    private fun registerSleep(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_sleep"] = NativeMezzeFunction("__native_coop_sleep") { args ->
            // In Coop, sleep is a no-op (single-threaded, cooperative)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_coop_is_done - Check if promise is done (always true in Coop).
     */
    private fun registerIsDone(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_is_done"] = NativeMezzeFunction("__native_coop_is_done") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_is_done", "expects MezzeRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_is_done", "expects 'p' field")

            // In Coop, promises are always "ready" (computation stored, not executed)
            MezzeBool(true)
        }
    }

    /**
     * __native_coop_cancel - Cancel a promise (marks as cancelled in Coop).
     */
    private fun registerCancel(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_cancel"] = NativeMezzeFunction("__native_coop_cancel") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_cancel", "expects MezzeRecord")

            val promise = record.fields["p"]
                ?: throw NativeFunctionException("__native_coop_cancel", "expects 'p' field")

            when (promise) {
                is MezzePromise -> {
                    promise.future.cancel(true)
                    MezzeRecord(emptyMap())
                }
                else -> throw NativeFunctionException(
                    "__native_coop_cancel",
                    "p must be a MezzePromise"
                )
            }
        }
    }

    /**
     * __native_coop_is_cancelled - Check if current computation was cancelled.
     * In single-threaded Coop, this always returns false.
     */
    private fun registerIsCancelled(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_is_cancelled"] = NativeMezzeFunction("__native_coop_is_cancelled") { args ->
            // In single-threaded Coop, cancellation is not applicable
            MezzeBool(false)
        }
    }

    /**
     * __native_coop_check - Cancellation checkpoint.
     * In single-threaded Coop, this is a no-op.
     */
    private fun registerCheck(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_check"] = NativeMezzeFunction("__native_coop_check") { args ->
            // In single-threaded Coop, no cancellation to check
            MezzeRecord(emptyMap())
        }
    }

    /**
     * Helper: Execute a Coop promise's stored computation.
     */
    private fun executePromise(promise: MezzeValue, functionName: String): MezzeValue {
        when (promise) {
            is MezzePromise -> {
                val wrapper = promise.future.get()
                when (wrapper) {
                    is MezzeRecord -> {
                        val tag = wrapper.fields["tag"]
                        if (tag is MezzeString && tag.value == COOP_PROMISE_TAG) {
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
                "p must be a MezzePromise, got: ${promise::class.simpleName}"
            )
        }
    }

    /**
     * Helper: Execute a Mezze function with empty arguments.
     */
    private fun executeFunction(f: MezzeValue, functionName: String): MezzeValue {
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    MezzeRecord(emptyMap()),
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> f.call(listOf(MezzeRecord(emptyMap())))
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
    private fun registerJoin(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_join"] = NativeMezzeFunction("__native_coop_join") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_join", "expects MezzeRecord")

            val leftFn = record.fields["left"]
                ?: throw NativeFunctionException("__native_coop_join", "expects 'left' field")
            val rightFn = record.fields["right"]
                ?: throw NativeFunctionException("__native_coop_join", "expects 'right' field")

            // In Coop, execute sequentially (single-threaded)
            val leftResult = executeFunction(leftFn, "__native_coop_join")
            val rightResult = executeFunction(rightFn, "__native_coop_join")

            MezzeRecord(mapOf(
                "left" to leftResult,
                "right" to rightResult
            ))
        }
    }

    /**
     * __native_coop_join3 - Run three computations sequentially.
     */
    private fun registerJoin3(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_join3"] = NativeMezzeFunction("__native_coop_join3") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_join3", "expects MezzeRecord")

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

            MezzeRecord(mapOf(
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
    private fun registerMap(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_map"] = NativeMezzeFunction("__native_coop_map") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_map", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_coop_map", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_map", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_coop_map", "xs must be a list")
            }

            // In Coop, map sequentially
            val results = list.map { element ->
                executeFunctionWithArg(f, element, "__native_coop_map")
            }

            MezzeArrayList(results)
        }
    }

    /**
     * __native_coop_for_each - Run function on each element for side effects.
     */
    private fun registerForEach(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_for_each"] = NativeMezzeFunction("__native_coop_for_each") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_for_each", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_coop_for_each", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_for_each", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_coop_for_each", "xs must be a list")
            }

            // In Coop, execute sequentially
            for (element in list) {
                executeFunctionWithArg(f, element, "__native_coop_for_each")
            }

            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_coop_filter - Filter elements with predicate.
     */
    private fun registerFilter(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_filter"] = NativeMezzeFunction("__native_coop_filter") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_filter", "expects MezzeRecord")

            val xs = record.fields["xs"]
                ?: throw NativeFunctionException("__native_coop_filter", "expects 'xs' field")
            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_filter", "expects 'f' field")

            val list = when (xs) {
                is MezzeArrayList -> xs.toList()
                else -> throw NativeFunctionException("__native_coop_filter", "xs must be a list")
            }

            // In Coop, filter sequentially
            val results = list.filter { element ->
                val result = executeFunctionWithArg(f, element, "__native_coop_filter")
                (result as? MezzeBool)?.value ?: false
            }

            MezzeArrayList(results)
        }
    }

    // =========================================================================
    // Error Handling Operations
    // =========================================================================

    /**
     * __native_coop_any - Return first successful result from promises.
     * In Coop, executes promises in order, returns first success.
     */
    private fun registerAny(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_any"] = NativeMezzeFunction("__native_coop_any") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_any", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_any", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList()
                else -> throw NativeFunctionException("__native_coop_any", "ps must be a list")
            }

            if (promises.isEmpty()) {
                throw NativeFunctionException("__native_coop_any", "cannot get any from empty list")
            }

            // In Coop, try each promise in order, return first success
            val errors = mutableListOf<Exception>()
            for (promise in promises) {
                try {
                    return@NativeMezzeFunction executePromise(promise, "__native_coop_any")
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
    private fun registerSettle(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_settle"] = NativeMezzeFunction("__native_coop_settle") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_settle", "expects MezzeRecord")

            val ps = record.fields["ps"]
                ?: throw NativeFunctionException("__native_coop_settle", "expects 'ps' field")

            val promises = when (ps) {
                is MezzeArrayList -> ps.toList()
                else -> throw NativeFunctionException("__native_coop_settle", "ps must be a list")
            }

            // Execute all and collect as Results
            val results = promises.map { promise ->
                try {
                    val value = executePromise(promise, "__native_coop_settle")
                    mezzeResultOk(value)
                } catch (e: java.util.concurrent.CancellationException) {
                    MezzeVariant("Err", MezzeRecord(mapOf(
                        "error" to MezzeVariant("AsyncCancelled", MezzeRecord(emptyMap()))
                    )))
                } catch (e: Exception) {
                    val message = safeGetExceptionMessage(e)
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

    /**
     * Helper: Execute a Mezze function with a single argument wrapped in a record {x = arg}.
     * This is the Mezze convention for functions taking a single argument.
     */
    private fun executeFunctionWithArg(f: MezzeValue, arg: MezzeValue, functionName: String): MezzeValue {
        // Wrap the argument in a record with field "x"
        val recordArg = MezzeRecord(mapOf("x" to arg))
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    recordArg,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> f.call(listOf(recordArg))
            else -> throw NativeFunctionException(
                functionName,
                "f must be a function, got: ${f::class.simpleName}"
            )
        }
    }

    // =========================================================================
    // Structured Concurrency Scopes
    // =========================================================================

    /**
     * __native_coop_scoped - Run a function with a structured concurrency scope.
     *
     * In Coop, there's no actual parallelism, so scoped just runs the function
     * with a dummy scope that tracks promises for sequential execution.
     */
    private fun registerScoped(context: MutableMap<String, MezzeValue>) {
        context["__native_coop_scoped"] = NativeMezzeFunction("__native_coop_scoped") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_coop_scoped", "expects MezzeRecord")

            val f = record.fields["f"]
                ?: throw NativeFunctionException("__native_coop_scoped", "expects 'f' field")

            // Create a dummy Coop scope
            val scope = CoopScope()

            // Call the function with the scope
            val scopeArg = MezzeRecord(mapOf("sc" to scope))
            val result = when (f) {
                is MezzeTruffleFunction -> {
                    f.callTarget.call(
                        scopeArg,
                        f.closureVariables,
                        f.lexicalScope,
                        f.lexicalScopeSlotMap
                    ) as MezzeValue
                }
                is NativeMezzeFunction -> f.call(listOf(scopeArg))
                else -> throw NativeFunctionException(
                    "__native_coop_scoped",
                    "f must be a function, got: ${f::class.simpleName}"
                )
            }

            // In Coop, we don't need to await anything since there's no parallelism
            // The spawned tasks are already executed when awaited
            result
        }
    }

}

/**
 * Helper to safely extract exception messages without triggering JIT compilation issues.
 * Some exception types (like UnsupportedSpecializationException) have getMessage() methods
 * that are marked as neverPartOfCompilation.
 */
@TruffleBoundary
fun safeGetExceptionMessage(e: Exception): String {
    return e.cause?.message ?: e.message ?: "Unknown error"
}

/**
 * CoopScope - A dummy scope for cooperative async.
 *
 * In Coop, there's no actual parallelism, so the scope is just a placeholder.
 * Tasks "spawned" in the scope are immediately available as promises that
 * execute when awaited.
 */
class CoopScope : MezzeValue() {
    override fun computeDisplayString(): String = "<coop-scope>"
}
