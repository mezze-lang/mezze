package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Mutex operations.
 *
 * Every native carries `<Async>` because `Mutex` is an Async-family
 * primitive (see #170). `with_lock`'s callback is row-polymorphic so
 * the body can perform other Async-family ops (atom, etc.) inside.
 *
 * Mutexes provide mutual exclusion using Java's ReentrantLock.
 */
object MutexTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_mutex_new: {} -> Mutex <Async>
        run {
            val mutexType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Mutex", emptyList())
            context["__native_mutex_new"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                mutexType,
                effects = asyncEffect
            )
        }

        // __native_mutex_with_lock: {mutex: Mutex, f: {} -> 'a <...>} -> 'a <Async, ...>
        run {
            val mutexType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Mutex", emptyList())
            val resultType = MezzeType.VarType(TypeVar("a"))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                resultType,
                effects = MezzeType.EffectRow.simple(emptySet(), RowVar.fresh(), performed = false)
            )
            context["__native_mutex_with_lock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType,
                    "f" to funcType
                )),
                resultType,
                effects = asyncEffect
            )
        }

        // __native_mutex_lock: {mutex: Mutex} -> {} <Async>
        run {
            val mutexType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Mutex", emptyList())
            context["__native_mutex_lock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = asyncEffect
            )
        }

        // __native_mutex_unlock: {mutex: Mutex} -> {} <Async>
        run {
            val mutexType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Mutex", emptyList())
            context["__native_mutex_unlock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = asyncEffect
            )
        }

        // __native_mutex_try_lock: {mutex: Mutex} -> Bool <Async>
        run {
            val mutexType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Mutex", emptyList())
            context["__native_mutex_try_lock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.BoolType,
                effects = asyncEffect
            )
        }

        // __native_mutex_is_held_by_current_thread: {mutex: Mutex} -> Bool <Async>
        run {
            val mutexType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Mutex", emptyList())
            context["__native_mutex_is_held_by_current_thread"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.BoolType,
                effects = asyncEffect
            )
        }
    }
}
