package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for Coop (cooperative) async native functions.
 *
 * Provides single-threaded cooperative async - computation is stored
 * on spawn and executed on await.
 */
object CoopTypes {

    fun registerAll(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        // __native_coop_spawn: {f: {} -> 'a} -> CoopPromise 'a
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_spawn"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "f" to FunkType.FunctionType(unitType, resultType)
                )),
                promiseType
            )
        }

        // __native_coop_await: {p: CoopPromise 'a} -> 'a
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_await"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("p" to promiseType)),
                resultType
            )
        }

        // __native_coop_race: {ps: [CoopPromise 'a]} -> 'a
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_race"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("ps" to FunkType.ArrayType(promiseType))),
                resultType
            )
        }

        // __native_coop_all: {ps: [CoopPromise 'a]} -> ['a]
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_all"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("ps" to FunkType.ArrayType(promiseType))),
                FunkType.ArrayType(resultType)
            )
        }

        // __native_coop_timeout: {p: CoopPromise 'a, ms: Int} -> Option 'a
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            val optionType = FunkType.TypeApplication("Option", listOf(resultType))
            context["__native_coop_timeout"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "p" to promiseType,
                    "ms" to FunkType.IntType
                )),
                optionType
            )
        }

        // __native_coop_cancel: {p: CoopPromise 'a} -> {}
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_cancel"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("p" to promiseType)),
                unitType
            )
        }

        // __native_coop_sleep: {ms: Int} -> {}
        context["__native_coop_sleep"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("ms" to FunkType.IntType)),
            unitType
        )

        // __native_coop_is_done: {p: CoopPromise 'a} -> Bool
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_is_done"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("p" to promiseType)),
                FunkType.BooleanType
            )
        }

        // __native_coop_is_cancelled: {} -> Bool
        context["__native_coop_is_cancelled"] = FunkType.FunctionType(
            unitType,
            FunkType.BooleanType
        )

        // __native_coop_check: {} -> {}
        context["__native_coop_check"] = FunkType.FunctionType(
            unitType,
            unitType
        )

        // Legacy functions for compatibility
        // __native_coop_wrap_promise: {f: {} -> 'a} -> CoopPromise 'a
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_wrap_promise"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "f" to FunkType.FunctionType(unitType, resultType)
                )),
                promiseType
            )
        }

        // __native_coop_unwrap_promise: {p: CoopPromise 'a} -> {f: {} -> 'a}
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val promiseType = FunkType.TypeApplication("CoopPromise", listOf(resultType))
            context["__native_coop_unwrap_promise"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf("p" to promiseType)),
                FunkType.RecordType(mapOf(
                    "f" to FunkType.FunctionType(unitType, resultType)
                ))
            )
        }
    }
}
