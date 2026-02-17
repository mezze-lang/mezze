package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for OS platform thread concurrency primitives.
 *
 * Same API as Loom but uses OS-level platform threads.
 */
object OSThreadTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_os_spawn: {f: {} -> 'a} -> Promise
        val spawnPromise = MezzeType.VarType(TypeVar("Promise"))
        val spawnResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    spawnResult
                )
            )),
            spawnPromise
        )

        // __native_os_spawn_cfg: {f: {} -> 'a, pool_size: Int} -> Promise
        context["__native_os_spawn_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "pool_size" to MezzeType.IntType
            )),
            MezzeType.VarType(TypeVar("Promise"))
        )

        // __native_os_await: {p: Promise 'a} -> 'a
        val awaitPromise = MezzeType.VarType(TypeVar("Promise"))
        val awaitResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("p" to awaitPromise)),
            awaitResult
        )

        // __native_os_race: {ps: [Promise 'a]} -> 'a
        val racePromise = MezzeType.VarType(TypeVar("Promise"))
        val raceResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(racePromise)
            )),
            raceResult
        )

        // __native_os_all: {ps: [Promise 'a]} -> ['a]
        val allPromise = MezzeType.VarType(TypeVar("Promise"))
        val allResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(allPromise)
            )),
            MezzeType.ArrayType(allResult)
        )

        // __native_os_timeout: {ms: Int, p: Promise 'a} -> Option 'a
        val timeoutPromise = MezzeType.VarType(TypeVar("Promise"))
        val timeoutResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType,
                "p" to timeoutPromise
            )),
            MezzeType.TypeApplication("Option", listOf(timeoutResult))
        )

        // __native_os_cancel: {p: Promise 'a} -> {}
        val cancelPromise = MezzeType.VarType(TypeVar("Promise"))
        context["__native_os_cancel"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("p" to cancelPromise)),
            MezzeType.RecordType(emptyMap())
        )

        // __native_os_sleep: {ms: Int} -> {}
        context["__native_os_sleep"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("ms" to MezzeType.IntType)),
            MezzeType.RecordType(emptyMap())
        )

        // __native_os_is_done: {p: Promise 'a} -> Bool
        val isDonePromise = MezzeType.VarType(TypeVar("Promise"))
        context["__native_os_is_done"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("p" to isDonePromise)),
            MezzeType.BooleanType
        )

        // __native_os_is_cancelled: {} -> Bool
        context["__native_os_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BooleanType
        )

        // __native_os_check: {} -> {}
        context["__native_os_check"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // __native_os_current_thread: {} -> Thread
        val threadType = MezzeType.VarType(TypeVar("Thread"))
        context["__native_os_current_thread"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            threadType
        )

        // __native_os_park: {} -> {}
        context["__native_os_park"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // __native_os_unpark: {thread: Thread} -> {}
        val unparkThread = MezzeType.VarType(TypeVar("Thread"))
        context["__native_os_unpark"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("thread" to unparkThread)),
            MezzeType.RecordType(emptyMap())
        )

        // === Structured Concurrency ===

        // __native_os_join: {left: {} -> 'a, right: {} -> 'b} -> {left: 'a, right: 'b}
        context["__native_os_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "left" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "right" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.RecordType(mapOf(
                "left" to MezzeType.VarType(TypeVar("a")),
                "right" to MezzeType.VarType(TypeVar("b"))
            ))
        )

        // __native_os_join3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c} -> {a: 'a, b: 'b, c: 'c}
        context["__native_os_join3"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "b" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "c" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("c"))
                )
            )),
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.VarType(TypeVar("a")),
                "b" to MezzeType.VarType(TypeVar("b")),
                "c" to MezzeType.VarType(TypeVar("c"))
            ))
        )

        // === Collection Operations ===

        // __native_os_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        context["__native_os_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.ArrayType(MezzeType.VarType(TypeVar("b")))
        )

        // __native_os_for_each: {xs: ['a], f: {x: 'a} -> {}} -> {}
        context["__native_os_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.RecordType(emptyMap())
                )
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_os_filter: {xs: ['a], f: {x: 'a} -> Bool} -> ['a]
        context["__native_os_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BooleanType
                )
            )),
            MezzeType.ArrayType(MezzeType.VarType(TypeVar("a")))
        )

        // === Error Handling ===

        // __native_os_any: {ps: [Promise 'a]} -> 'a
        val anyPromise = MezzeType.VarType(TypeVar("Promise"))
        context["__native_os_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(anyPromise)
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_os_settle: {ps: [Promise 'a]} -> [Result 'a AsyncError]
        val settlePromise = MezzeType.VarType(TypeVar("Promise"))
        val asyncErrorType = MezzeType.VarType(TypeVar("AsyncError"))
        val resultType = MezzeType.TypeApplication("Result", listOf(
            MezzeType.VarType(TypeVar("a")),
            asyncErrorType
        ))
        context["__native_os_settle"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(settlePromise)
            )),
            MezzeType.ArrayType(resultType)
        )

        // === Structured Concurrency Scopes ===

        // Scope type for OS threads
        val scopeType = MezzeType.TypeApplication("Scope", listOf(MezzeType.VarType(TypeVar("a"))))

        // __native_os_scoped: {f: {scope: Scope 'a} -> 'a} -> 'a
        context["__native_os_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("scope" to scopeType)),
                    MezzeType.VarType(TypeVar("a"))
                )
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_os_scope_spawn: {scope: Scope 'a, f: {} -> 'b} -> Promise 'b
        val scopeSpawnPromiseType = MezzeType.TypeApplication("Promise", listOf(MezzeType.VarType(TypeVar("b"))))
        context["__native_os_scope_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "scope" to scopeType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            scopeSpawnPromiseType
        )

        // __native_os_scope_cancel_all: {scope: Scope 'a} -> {}
        context["__native_os_scope_cancel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "scope" to scopeType
            )),
            MezzeType.RecordType(emptyMap())
        )
    }
}
