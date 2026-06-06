package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

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

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        val mutexType = WellKnownTypeApp.of("Mutex", emptyList())

        // __native_mutex_new: {} -> Mutex <Async>
        fn("__native_mutex_new") {
            returns(mutexType)
            effects("Async")
        }

        // __native_mutex_with_lock: {mutex: Mutex, f: {} -> 'a <...>} -> 'a <Async, ...>
        fn("__native_mutex_with_lock") {
            val a = tvar("a")
            val funcType = MezzeType.FunctionType(
                unit,
                a,
                effects = MezzeType.EffectRow.simple(emptySet(), RowVar.fresh(), performed = false)
            )
            param("mutex" to mutexType, "f" to funcType)
            returns(a)
            effects("Async")
        }

        // __native_mutex_lock: {mutex: Mutex} -> {} <Async>
        fn("__native_mutex_lock") {
            param("mutex" to mutexType)
            returns(unit)
            effects("Async")
        }

        // __native_mutex_unlock: {mutex: Mutex} -> {} <Async>
        fn("__native_mutex_unlock") {
            param("mutex" to mutexType)
            returns(unit)
            effects("Async")
        }

        // __native_mutex_try_lock: {mutex: Mutex} -> Bool <Async>
        fn("__native_mutex_try_lock") {
            param("mutex" to mutexType)
            returns(bool)
            effects("Async")
        }

        // __native_mutex_is_held_by_current_thread: {mutex: Mutex} -> Bool <Async>
        fn("__native_mutex_is_held_by_current_thread") {
            param("mutex" to mutexType)
            returns(bool)
            effects("Async")
        }
    }
}
