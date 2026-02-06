package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType

/**
 * Type definitions for Loom virtual thread concurrency primitives.
 *
 * These native functions provide Project Loom virtual thread support:
 * - Spawning computations on virtual threads
 * - Awaiting promises
 * - Park/unpark for thread coordination
 * - Current thread handle
 */
object LoomTypes {

    /**
     * Register all Loom native function types.
     *
     * Each function gets its own fresh type variables for Promise, Thread, and result type.
     * This is important because these types are registered as polymorphic and get
     * fresh instantiation on each use. If we shared type variables across functions,
     * unifying one would affect all others.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_loom_spawn: {computation: {} -> 'a} -> Promise
        // Each function gets its own type variables
        val spawnPromise = FunkType.VarType(com.funk.language.ast.TypeVar("Promise"))
        val spawnResult = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        context["__native_loom_spawn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "computation" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    spawnResult
                )
            )),
            spawnPromise
        )

        // __native_loom_await: {promise: Promise} -> 'a
        val awaitPromise = FunkType.VarType(com.funk.language.ast.TypeVar("Promise"))
        val awaitResult = FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        context["__native_loom_await"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "promise" to awaitPromise
            )),
            awaitResult
        )

        // __native_loom_park: {} -> {}
        context["__native_loom_park"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_unpark: {thread: Thread} -> {}
        val unparkThread = FunkType.VarType(com.funk.language.ast.TypeVar("Thread"))
        context["__native_loom_unpark"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "thread" to unparkThread
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_current_thread: {} -> Thread
        val currentThread = FunkType.VarType(com.funk.language.ast.TypeVar("Thread"))
        context["__native_loom_current_thread"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            currentThread
        )

        // __native_promise_is_complete: {promise: Promise} -> Bool
        val isCompletePromise = FunkType.VarType(com.funk.language.ast.TypeVar("Promise"))
        context["__native_promise_is_complete"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "promise" to isCompletePromise
            )),
            FunkType.BooleanType
        )
    }
}
