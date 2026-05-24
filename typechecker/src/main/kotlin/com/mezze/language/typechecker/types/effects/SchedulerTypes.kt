package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for cooperative scheduler native functions.
 *
 * Used by std/control/scheduler.mz for real continuation-based cooperative async.
 */
object SchedulerTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // Opaque scheduler type (native object)
        val schedulerType = MezzeType.VarType(TypeVar("__NativeScheduler"))

        // Opaque promise type (native object) - same as used by async Promise
        val nativePromiseType = MezzeType.VarType(TypeVar("__NativePromise"))

        // __native_scheduler_new: {} -> __NativeScheduler
        context["__native_scheduler_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            schedulerType
        )

        // __native_scheduler_spawn: {sched: __NativeScheduler, f: {} -> 'a} -> __NativePromise
        context["__native_scheduler_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                )
            )),
            nativePromiseType
        )

        // __native_scheduler_await: {sched: __NativeScheduler, p: __NativePromise} -> 'a
        context["__native_scheduler_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "p" to nativePromiseType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_scheduler_await_result: {sched, p} -> Result 'a AsyncError
        // (the non-throwing counterpart used by CoopAsync.await to keep the
        //  Result contract honest under cooperative scheduling).
        val asyncErrorType = MezzeType.VarType(TypeVar("AsyncError"))
        val schedulerAwaitResultType = com.mezze.language.typechecker.util.WellKnownTypeApp.of(
            "Result",
            listOf(MezzeType.VarType(TypeVar("a")), asyncErrorType)
        )
        context["__native_scheduler_await_result"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "p" to nativePromiseType
            )),
            schedulerAwaitResultType
        )

        // __native_scheduler_drain: {sched: __NativeScheduler} -> {}
        context["__native_scheduler_drain"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_scheduler_is_done: {p: __NativePromise} -> Bool
        context["__native_scheduler_is_done"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to nativePromiseType
            )),
            MezzeType.BoolType
        )

        // __native_scheduler_pending_count: {sched: __NativeScheduler} -> Int
        context["__native_scheduler_pending_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType
            )),
            MezzeType.IntType
        )

        // __native_scheduler_race: {sched: __NativeScheduler, ps: [__NativePromise]} -> Result 'a AsyncError
        val schedRaceAsyncErrorType = MezzeType.VarType(TypeVar("AsyncError"))
        val schedRaceResultType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(
            MezzeType.VarType(TypeVar("a")),
            schedRaceAsyncErrorType
        ))
        context["__native_scheduler_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "ps" to MezzeType.SeqType(nativePromiseType)
            )),
            schedRaceResultType
        )

        // __native_scheduler_all: {sched: __NativeScheduler, ps: [__NativePromise]} -> ['a]
        context["__native_scheduler_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "ps" to MezzeType.SeqType(nativePromiseType)
            )),
            MezzeType.SeqType(MezzeType.VarType(TypeVar("a")))
        )

        // __native_scheduler_sleep: {sched: __NativeScheduler, ms: Int} -> {}
        context["__native_scheduler_sleep"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "ms" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_scheduler_park_until: {sched, predicate: {} -> Bool <...>} -> {}
        context["__native_scheduler_park_until"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sched" to schedulerType,
                "predicate" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.BoolType,
                    effects = MezzeType.EffectRow.simple(emptySet<String>(), RowVar.fresh(), performed = false)
                )
            )),
            MezzeType.RecordType(emptyMap())
        )

    }
}
