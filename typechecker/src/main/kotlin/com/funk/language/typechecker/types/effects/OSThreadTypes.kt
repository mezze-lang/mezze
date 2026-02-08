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
    }
}
