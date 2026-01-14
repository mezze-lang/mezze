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
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_loom_spawn: {computation: {} -> 'a} -> Promise
        // Spawns a computation on a new virtual thread, returns a Promise
        // The Promise newtype wraps the result which can be awaited later
        context["__native_loom_spawn"] = FunkType.FunctionType(
            // Parameter type: {computation: {} -> 'a}
            FunkType.RecordType(mapOf(
                "computation" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),  // {} parameter
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))  // 'a result
                )
            )),
            // Return type: Promise (opaque native promise type)
            // Note: In practice this is wrapped in a Promise newtype variant
            FunkType.VarType(com.funk.language.ast.TypeVar("Promise"))
        )

        // __native_loom_await: {promise: Promise} -> 'a
        // Blocks the current virtual thread until the promise completes, returns the result
        // Uses LockSupport.park() internally for efficient blocking
        context["__native_loom_await"] = FunkType.FunctionType(
            // Parameter type: {promise: Promise}
            FunkType.RecordType(mapOf(
                "promise" to FunkType.VarType(com.funk.language.ast.TypeVar("Promise"))
            )),
            // Return type: 'a (the unwrapped promise value)
            FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        )

        // __native_loom_park: {} -> {}
        // Parks (suspends) the current virtual thread until unparked
        // Thread will not consume CPU resources while parked
        context["__native_loom_park"] = FunkType.FunctionType(
            // Parameter type: {}
            FunkType.RecordType(emptyMap()),
            // Return type: {} (unit)
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_unpark: {thread: Thread} -> {}
        // Unparks the given thread, allowing it to resume execution
        // If thread is not parked, next park() will return immediately
        context["__native_loom_unpark"] = FunkType.FunctionType(
            // Parameter type: {thread: Thread}
            FunkType.RecordType(mapOf(
                "thread" to FunkType.VarType(com.funk.language.ast.TypeVar("Thread"))
            )),
            // Return type: {} (unit)
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_current_thread: {} -> Thread
        // Returns a handle to the current virtual thread
        // This handle can be passed to other threads for coordination via unpark
        context["__native_loom_current_thread"] = FunkType.FunctionType(
            // Parameter type: {}
            FunkType.RecordType(emptyMap()),
            // Return type: Thread (opaque thread handle)
            // Note: In practice this is wrapped in a Thread newtype variant
            FunkType.VarType(com.funk.language.ast.TypeVar("Thread"))
        )

        // __native_promise_is_complete: {promise: Promise} -> Bool
        // Checks if a promise has completed without blocking
        context["__native_promise_is_complete"] = FunkType.FunctionType(
            // Parameter type: {promise: Promise}
            FunkType.RecordType(mapOf(
                "promise" to FunkType.VarType(com.funk.language.ast.TypeVar("Promise"))
            )),
            // Return type: Bool
            FunkType.BooleanType
        )
    }
}
