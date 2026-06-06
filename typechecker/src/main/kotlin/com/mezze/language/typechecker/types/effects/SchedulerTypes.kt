package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for cooperative scheduler native functions.
 *
 * Used by std/control/scheduler.mz for real continuation-based cooperative async.
 */
object SchedulerTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        // Opaque scheduler type (native object)
        val schedulerType = MezzeType.VarType(TypeVar("__NativeScheduler"))

        // Opaque promise type (native object)
        val nativePromiseType = MezzeType.VarType(TypeVar("__NativePromise"))

        natives(context) {
            fn("__native_scheduler_new") { returns(schedulerType) }

            fn("__native_scheduler_spawn") {
                val a = tvar("a")
                param("sched" to schedulerType, "f" to MezzeType.FunctionType(unit, a))
                returns(nativePromiseType)
            }

            fn("__native_scheduler_await") {
                val a = tvar("a")
                param("sched" to schedulerType, "p" to nativePromiseType)
                returns(a)
            }

            fn("__native_scheduler_await_result") {
                val a = tvar("a")
                val asyncError = MezzeType.VarType(TypeVar("AsyncError"))
                param("sched" to schedulerType, "p" to nativePromiseType)
                returns(result(a, asyncError))
            }

            fn("__native_scheduler_drain") {
                param("sched" to schedulerType)
                returns(unit)
            }

            fn("__native_scheduler_is_done") {
                param("p" to nativePromiseType)
                returns(bool)
            }

            fn("__native_scheduler_pending_count") {
                param("sched" to schedulerType)
                returns(int)
            }

            fn("__native_scheduler_race") {
                val a = tvar("a")
                val asyncError = MezzeType.VarType(TypeVar("AsyncError"))
                param("sched" to schedulerType, "ps" to seq(nativePromiseType))
                returns(result(a, asyncError))
            }

            fn("__native_scheduler_all") {
                val a = tvar("a")
                param("sched" to schedulerType, "ps" to seq(nativePromiseType))
                returns(seq(a))
            }

            fn("__native_scheduler_sleep") {
                param("sched" to schedulerType, "ms" to int)
                returns(unit)
            }
        }

        // __native_scheduler_park_until: effect-polymorphic predicate needs raw registration
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
