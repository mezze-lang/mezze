package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Mutex operations.
 *
 * Mutexes provide mutual exclusion using Java's ReentrantLock.
 */
object MutexTypes {

    /**
     * Register all mutex native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_mutex_new: {} -> Mutex
        run {
            val mutexType = MezzeType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_new"] = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                mutexType
            )
        }

        // __native_mutex_with_lock: {mutex: Mutex, f: {} -> 'a} -> 'a
        run {
            val mutexType = MezzeType.TypeApplication("Mutex", emptyList())
            val resultType = MezzeType.VarType(TypeVar("a"))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                resultType
            )
            context["__native_mutex_with_lock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType,
                    "f" to funcType
                )),
                resultType
            )
        }

        // __native_mutex_lock: {mutex: Mutex} -> {}
        run {
            val mutexType = MezzeType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_lock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mutex_unlock: {mutex: Mutex} -> {}
        run {
            val mutexType = MezzeType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_unlock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_mutex_try_lock: {mutex: Mutex} -> Bool
        run {
            val mutexType = MezzeType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_try_lock"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.BooleanType
            )
        }

        // __native_mutex_is_held_by_current_thread: {mutex: Mutex} -> Bool
        run {
            val mutexType = MezzeType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_is_held_by_current_thread"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                MezzeType.BooleanType
            )
        }
    }
}
