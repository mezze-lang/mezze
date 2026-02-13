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
        val promiseType = FunkType.TypeApplication("Promise", listOf(FunkType.VarType(com.funk.language.ast.TypeVar("a"))))

        // __native_loom_spawn: {f: {} -> 'a} -> Promise 'a
        context["__native_loom_spawn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                )
            )),
            promiseType
        )

        // __native_loom_spawn_cfg: {f: {} -> 'a, max_concurrent: Int, timeout_ms: Int} -> Promise 'a
        context["__native_loom_spawn_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                ),
                "max_concurrent" to FunkType.IntType,
                "timeout_ms" to FunkType.IntType
            )),
            promiseType
        )

        // __native_loom_await: {p: Promise 'a} -> 'a
        context["__native_loom_await"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType
            )),
            FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        )

        // __native_loom_race: {ps: [Promise 'a]} -> 'a
        context["__native_loom_race"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType)
            )),
            FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        )

        // __native_loom_all: {ps: [Promise 'a]} -> ['a]
        context["__native_loom_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType)
            )),
            FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("a")))
        )

        // __native_loom_timeout: {ms: Int, p: Promise 'a} -> Option 'a
        val optionType = FunkType.TypeApplication("Option", listOf(FunkType.VarType(com.funk.language.ast.TypeVar("a"))))
        context["__native_loom_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ms" to FunkType.IntType,
                "p" to promiseType
            )),
            optionType
        )

        // __native_loom_cancel: {p: Promise 'a} -> {}
        context["__native_loom_cancel"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_sleep: {ms: Int} -> {}
        context["__native_loom_sleep"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ms" to FunkType.IntType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_is_done: {p: Promise 'a} -> Bool
        context["__native_loom_is_done"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType
            )),
            FunkType.BooleanType
        )

        // __native_loom_is_cancelled: {} -> Bool
        context["__native_loom_is_cancelled"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.BooleanType
        )

        // __native_loom_check: {} -> {}
        context["__native_loom_check"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.RecordType(emptyMap())
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

        // __native_promise_is_complete: {p: Promise} -> Bool
        context["__native_promise_is_complete"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType
            )),
            FunkType.BooleanType
        )
    }
}
