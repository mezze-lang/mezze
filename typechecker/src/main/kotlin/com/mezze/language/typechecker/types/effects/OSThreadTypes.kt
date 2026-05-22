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
                "ps" to MezzeType.SeqType(racePromise)
            )),
            raceResult
        )

        // __native_os_all: {ps: [Promise 'a]} -> ['a]
        val allPromise = MezzeType.VarType(TypeVar("Promise"))
        val allResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(allPromise)
            )),
            MezzeType.SeqType(allResult)
        )

        // __native_os_timeout: {ms: Int, p: Promise 'a} -> Option 'a
        val timeoutPromise = MezzeType.VarType(TypeVar("Promise"))
        val timeoutResult = MezzeType.VarType(TypeVar("a"))
        context["__native_os_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType,
                "p" to timeoutPromise
            )),
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(timeoutResult))
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
            MezzeType.BoolType
        )

        // __native_os_is_cancelled: {} -> Bool
        context["__native_os_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BoolType
        )

        // __native_os_check: {} -> {}
        context["__native_os_check"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // === Structured Concurrency ===

        // Result 'x AsyncError helper for join* return shapes.
        val osJoinAsyncErrorType = MezzeType.VarType(TypeVar("AsyncError"))
        fun osResultOf(elemType: MezzeType): MezzeType =
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(elemType, osJoinAsyncErrorType))

        // __native_os_join: {l: {} -> 'a, r: {} -> 'b}
        //   -> {l: Result 'a AsyncError, r: Result 'b AsyncError}
        context["__native_os_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "r" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.RecordType(mapOf(
                "l" to osResultOf(MezzeType.VarType(TypeVar("a"))),
                "r" to osResultOf(MezzeType.VarType(TypeVar("b")))
            ))
        )

        // __native_os_join3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c}
        //   -> {a: Result 'a AsyncError, b: Result 'b AsyncError, c: Result 'c AsyncError}
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
                "a" to osResultOf(MezzeType.VarType(TypeVar("a"))),
                "b" to osResultOf(MezzeType.VarType(TypeVar("b"))),
                "c" to osResultOf(MezzeType.VarType(TypeVar("c")))
            ))
        )

        // === Collection Operations ===

        // __native_os_map: {xs: ['a], f: {x: 'a} -> 'b} -> ['b]
        context["__native_os_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("b")))
        )

        // __native_os_for_each: {xs: ['a], f: {x: 'a} -> {}} -> {}
        context["__native_os_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
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
                "xs" to MezzeType.SeqType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BoolType
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
        )

        // === Error Handling ===

        // __native_os_any: {ps: [Promise 'a]} -> 'a
        val anyPromise = MezzeType.VarType(TypeVar("Promise"))
        context["__native_os_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(anyPromise)
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_os_settle: {ps: [Promise 'a]} -> [Result 'a AsyncError]
        val settlePromise = MezzeType.VarType(TypeVar("Promise"))
        val asyncErrorType = MezzeType.VarType(TypeVar("AsyncError"))
        val resultType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(
            MezzeType.VarType(TypeVar("a")),
            asyncErrorType
        ))
        context["__native_os_settle"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(settlePromise)
            )),
            MezzeType.SeqType(resultType)
        )

        // === Structured Concurrency Scopes ===

        // Scope type for OS threads
        val scopeType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Scope", listOf(MezzeType.VarType(TypeVar("a"))))

        // __native_os_scoped: {f: {sc: Scope 'a} -> 'a} -> 'a
        context["__native_os_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("sc" to scopeType)),
                    MezzeType.VarType(TypeVar("a"))
                )
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_os_scope_spawn: {sc: Scope 'a, f: {} -> 'b} -> Promise 'b
        val scopeSpawnPromiseType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Promise", listOf(MezzeType.VarType(TypeVar("b"))))
        context["__native_os_scope_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                )
            )),
            scopeSpawnPromiseType
        )

        // __native_os_scope_cancel_all: {sc: Scope 'a} -> {}
        context["__native_os_scope_cancel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            MezzeType.RecordType(emptyMap())
        )
    }
}
