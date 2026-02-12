package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for Mutex operations.
 *
 * Mutexes provide mutual exclusion using Java's ReentrantLock.
 */
object MutexTypes {

    /**
     * Register all mutex native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_mutex_new: {} -> Mutex
        run {
            val mutexType = FunkType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_new"] = FunkType.FunctionType(
                FunkType.RecordType(emptyMap()),
                mutexType
            )
        }

        // __native_mutex_with_lock: {mutex: Mutex, f: {} -> 'a} -> 'a
        run {
            val mutexType = FunkType.TypeApplication("Mutex", emptyList())
            val resultType = FunkType.VarType(TypeVar("a"))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(emptyMap()),
                resultType
            )
            context["__native_mutex_with_lock"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "mutex" to mutexType,
                    "f" to funcType
                )),
                resultType
            )
        }

        // __native_mutex_lock: {mutex: Mutex} -> {}
        run {
            val mutexType = FunkType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_lock"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                FunkType.RecordType(emptyMap())
            )
        }

        // __native_mutex_unlock: {mutex: Mutex} -> {}
        run {
            val mutexType = FunkType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_unlock"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                FunkType.RecordType(emptyMap())
            )
        }

        // __native_mutex_try_lock: {mutex: Mutex} -> Bool
        run {
            val mutexType = FunkType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_try_lock"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                FunkType.BooleanType
            )
        }

        // __native_mutex_is_held_by_current_thread: {mutex: Mutex} -> Bool
        run {
            val mutexType = FunkType.TypeApplication("Mutex", emptyList())
            context["__native_mutex_is_held_by_current_thread"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "mutex" to mutexType
                )),
                FunkType.BooleanType
            )
        }
    }
}
