package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for OS platform thread concurrency primitives.
 *
 * Same API as Loom but uses OS-level platform threads.
 */
object OSThreadTypes {

    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_os_spawn: {f: {} -> 'a} -> Promise
        val spawnPromise = FunkType.VarType(TypeVar("Promise"))
        val spawnResult = FunkType.VarType(TypeVar("a"))
        context["__native_os_spawn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    spawnResult
                )
            )),
            spawnPromise
        )

        // __native_os_spawn_cfg: {f: {} -> 'a, pool_size: Int} -> Promise
        context["__native_os_spawn_cfg"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "pool_size" to FunkType.IntType
            )),
            FunkType.VarType(TypeVar("Promise"))
        )

        // __native_os_await: {p: Promise 'a} -> 'a
        val awaitPromise = FunkType.VarType(TypeVar("Promise"))
        val awaitResult = FunkType.VarType(TypeVar("a"))
        context["__native_os_await"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("p" to awaitPromise)),
            awaitResult
        )

        // __native_os_race: {ps: [Promise 'a]} -> 'a
        val racePromise = FunkType.VarType(TypeVar("Promise"))
        val raceResult = FunkType.VarType(TypeVar("a"))
        context["__native_os_race"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(racePromise)
            )),
            raceResult
        )

        // __native_os_all: {ps: [Promise 'a]} -> ['a]
        val allPromise = FunkType.VarType(TypeVar("Promise"))
        val allResult = FunkType.VarType(TypeVar("a"))
        context["__native_os_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(allPromise)
            )),
            FunkType.ArrayType(allResult)
        )

        // __native_os_timeout: {ms: Int, p: Promise 'a} -> Option 'a
        val timeoutPromise = FunkType.VarType(TypeVar("Promise"))
        val timeoutResult = FunkType.VarType(TypeVar("a"))
        context["__native_os_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ms" to FunkType.IntType,
                "p" to timeoutPromise
            )),
            FunkType.TypeApplication("Option", listOf(timeoutResult))
        )

        // __native_os_cancel: {p: Promise 'a} -> {}
        val cancelPromise = FunkType.VarType(TypeVar("Promise"))
        context["__native_os_cancel"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("p" to cancelPromise)),
            FunkType.RecordType(emptyMap())
        )

        // __native_os_sleep: {ms: Int} -> {}
        context["__native_os_sleep"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("ms" to FunkType.IntType)),
            FunkType.RecordType(emptyMap())
        )

        // __native_os_is_done: {p: Promise 'a} -> Bool
        val isDonePromise = FunkType.VarType(TypeVar("Promise"))
        context["__native_os_is_done"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("p" to isDonePromise)),
            FunkType.BooleanType
        )

        // __native_os_is_cancelled: {} -> Bool
        context["__native_os_is_cancelled"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.BooleanType
        )

        // __native_os_check: {} -> {}
        context["__native_os_check"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.RecordType(emptyMap())
        )

        // __native_os_current_thread: {} -> Thread
        val threadType = FunkType.VarType(TypeVar("Thread"))
        context["__native_os_current_thread"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            threadType
        )

        // __native_os_park: {} -> {}
        context["__native_os_park"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.RecordType(emptyMap())
        )

        // __native_os_unpark: {thread: Thread} -> {}
        val unparkThread = FunkType.VarType(TypeVar("Thread"))
        context["__native_os_unpark"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("thread" to unparkThread)),
            FunkType.RecordType(emptyMap())
        )

        // === Structured Concurrency ===

        // __native_os_join: {left: {} -> 'a, right: {} -> 'b} -> {left: 'a, right: 'b}
        context["__native_os_join"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "right" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                )
            )),
            FunkType.RecordType(mapOf(
                "left" to FunkType.VarType(TypeVar("a")),
                "right" to FunkType.VarType(TypeVar("b"))
            ))
        )

        // __native_os_join3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c} -> {a: 'a, b: 'b, c: 'c}
        context["__native_os_join3"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "b" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                ),
                "c" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("c"))
                )
            )),
            FunkType.RecordType(mapOf(
                "a" to FunkType.VarType(TypeVar("a")),
                "b" to FunkType.VarType(TypeVar("b")),
                "c" to FunkType.VarType(TypeVar("c"))
            ))
        )

        // === Collection Operations ===

        // __native_os_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        context["__native_os_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                )
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("b")))
        )

        // __native_os_for_each: {xs: ['a], f: {x: 'a} -> {}} -> {}
        context["__native_os_for_each"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.RecordType(emptyMap())
                )
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_os_filter: {xs: ['a], f: {x: 'a} -> Bool} -> ['a]
        context["__native_os_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                )
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        // === Error Handling ===

        // __native_os_any: {ps: [Promise 'a]} -> 'a
        val anyPromise = FunkType.VarType(TypeVar("Promise"))
        context["__native_os_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(anyPromise)
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_os_settle: {ps: [Promise 'a]} -> [Result 'a AsyncError]
        val settlePromise = FunkType.VarType(TypeVar("Promise"))
        val asyncErrorType = FunkType.VarType(TypeVar("AsyncError"))
        val resultType = FunkType.TypeApplication("Result", listOf(
            FunkType.VarType(TypeVar("a")),
            asyncErrorType
        ))
        context["__native_os_settle"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(settlePromise)
            )),
            FunkType.ArrayType(resultType)
        )

        // === Structured Concurrency Scopes ===

        // Scope type for OS threads
        val scopeType = FunkType.TypeApplication("Scope", listOf(FunkType.VarType(TypeVar("a"))))

        // __native_os_scoped: {f: {scope: Scope 'a} -> 'a} -> 'a
        context["__native_os_scoped"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("scope" to scopeType)),
                    FunkType.VarType(TypeVar("a"))
                )
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_os_scope_spawn: {scope: Scope 'a, f: {} -> 'b} -> Promise 'b
        val scopeSpawnPromiseType = FunkType.TypeApplication("Promise", listOf(FunkType.VarType(TypeVar("b"))))
        context["__native_os_scope_spawn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "scope" to scopeType,
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                )
            )),
            scopeSpawnPromiseType
        )

        // __native_os_scope_cancel_all: {scope: Scope 'a} -> {}
        context["__native_os_scope_cancel_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "scope" to scopeType
            )),
            FunkType.RecordType(emptyMap())
        )
    }
}
