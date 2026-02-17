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

        // __native_loom_spawn_cfg: {f: {} -> 'a, max_concurrent: Int} -> Promise 'a
        context["__native_loom_spawn_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                ),
                "max_concurrent" to FunkType.IntType
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

        // === Structured Concurrency ===

        // __native_loom_join: {left: {} -> 'a, right: {} -> 'b} -> {left: 'a, right: 'b}
        context["__native_loom_join"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                ),
                "right" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("b"))
                )
            )),
            FunkType.RecordType(mapOf(
                "left" to FunkType.VarType(com.funk.language.ast.TypeVar("a")),
                "right" to FunkType.VarType(com.funk.language.ast.TypeVar("b"))
            ))
        )

        // __native_loom_join3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c} -> {a: 'a, b: 'b, c: 'c}
        context["__native_loom_join3"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                ),
                "b" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("b"))
                ),
                "c" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("c"))
                )
            )),
            FunkType.RecordType(mapOf(
                "a" to FunkType.VarType(com.funk.language.ast.TypeVar("a")),
                "b" to FunkType.VarType(com.funk.language.ast.TypeVar("b")),
                "c" to FunkType.VarType(com.funk.language.ast.TypeVar("c"))
            ))
        )

        // === Collection Operations ===

        // __native_loom_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        context["__native_loom_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(com.funk.language.ast.TypeVar("a")))),
                    FunkType.VarType(com.funk.language.ast.TypeVar("b"))
                )
            )),
            FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("b")))
        )

        // __native_loom_for_each: {xs: ['a], f: {x: 'a} -> {}} -> {}
        context["__native_loom_for_each"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(com.funk.language.ast.TypeVar("a")))),
                    FunkType.RecordType(emptyMap())
                )
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_filter: {xs: ['a], f: {x: 'a} -> Bool} -> ['a]
        context["__native_loom_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(com.funk.language.ast.TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.ArrayType(FunkType.VarType(com.funk.language.ast.TypeVar("a")))
        )

        // === Error Handling ===

        // __native_loom_any: {ps: [Promise 'a]} -> 'a
        context["__native_loom_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType)
            )),
            FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        )

        // __native_loom_settle: {ps: [Promise 'a]} -> [Result 'a AsyncError]
        // AsyncError is a variant: Failed {message: String} | Cancelled | TimedOut {ms: Int}
        val asyncErrorType = FunkType.VarType(com.funk.language.ast.TypeVar("AsyncError"))
        val resultType = FunkType.TypeApplication("Result", listOf(
            FunkType.VarType(com.funk.language.ast.TypeVar("a")),
            asyncErrorType
        ))
        context["__native_loom_settle"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType)
            )),
            FunkType.ArrayType(resultType)
        )

        // === Structured Concurrency Scopes ===

        // Scope type - opaque wrapper for structured concurrency scopes
        val scopeType = FunkType.TypeApplication("Scope", listOf(FunkType.VarType(com.funk.language.ast.TypeVar("a"))))

        // __native_loom_scoped: {f: {scope: Scope 'a} -> 'a} -> 'a
        context["__native_loom_scoped"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("scope" to scopeType)),
                    FunkType.VarType(com.funk.language.ast.TypeVar("a"))
                )
            )),
            FunkType.VarType(com.funk.language.ast.TypeVar("a"))
        )

        // __native_loom_scope_spawn: {scope: Scope 'a, f: {} -> 'b} -> Promise 'b
        val scopeSpawnPromiseType = FunkType.TypeApplication("Promise", listOf(FunkType.VarType(com.funk.language.ast.TypeVar("b"))))
        context["__native_loom_scope_spawn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "scope" to scopeType,
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(com.funk.language.ast.TypeVar("b"))
                )
            )),
            scopeSpawnPromiseType
        )

        // __native_loom_scope_cancel_all: {scope: Scope 'a} -> {}
        context["__native_loom_scope_cancel_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "scope" to scopeType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_loom_scope_get_token: {scope: Scope 'a} -> CancellationToken
        val cancellationTokenType = FunkType.TypeApplication("CancellationToken", emptyList())
        context["__native_loom_scope_get_token"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "scope" to scopeType
            )),
            cancellationTokenType
        )

        // __native_loom_scope_is_cancelled: {scope: Scope 'a} -> Bool
        context["__native_loom_scope_is_cancelled"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "scope" to scopeType
            )),
            FunkType.BooleanType
        )
    }
}
