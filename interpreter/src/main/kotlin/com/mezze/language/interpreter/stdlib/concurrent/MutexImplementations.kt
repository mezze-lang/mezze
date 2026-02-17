package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
import java.util.concurrent.locks.ReentrantLock

/**
 * Native implementations for Mutex operations.
 *
 * Mutexes provide mutual exclusion using Java's ReentrantLock.
 * Reentrant means the same thread can acquire the lock multiple times.
 */
object MutexImplementations {

    /**
     * Register all mutex-related native functions.
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        registerNew(context)
        registerWithLock(context)
        registerLock(context)
        registerUnlock(context)
        registerTryLock(context)
        registerIsHeldByCurrentThread(context)
    }

    /**
     * __native_mutex_new: {} -> Mutex
     * Creates a new mutex (unlocked).
     */
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_mutex_new"] = NativeMezzeFunction("__native_mutex_new") { _ ->
            MezzeMutex(ReentrantLock())
        }
    }

    /**
     * __native_mutex_with_lock: {mutex: Mutex, f: {} -> 'a} -> 'a
     * Acquires lock, runs function, releases lock (even on exception).
     */
    private fun registerWithLock(context: MutableMap<String, MezzeValue>) {
        context["__native_mutex_with_lock"] = NativeMezzeFunction("__native_mutex_with_lock") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_mutex_with_lock expects MezzeRecord")

            val mutex = record.fields["mutex"] as? MezzeMutex
                ?: throw MezzeException("__native_mutex_with_lock expects 'mutex' field of type Mutex")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_mutex_with_lock expects 'f' field")

            mutex.lock.lock()
            try {
                callFunction(f)
            } finally {
                mutex.lock.unlock()
            }
        }
    }

    /**
     * __native_mutex_lock: {mutex: Mutex} -> {}
     * Acquires lock (blocking). Reentrant - same thread can lock multiple times.
     */
    private fun registerLock(context: MutableMap<String, MezzeValue>) {
        context["__native_mutex_lock"] = NativeMezzeFunction("__native_mutex_lock") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_mutex_lock expects MezzeRecord")

            val mutex = record.fields["mutex"] as? MezzeMutex
                ?: throw MezzeException("__native_mutex_lock expects 'mutex' field of type Mutex")

            mutex.lock.lock()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_mutex_unlock: {mutex: Mutex} -> {}
     * Releases lock. Must be called by the thread holding the lock.
     */
    private fun registerUnlock(context: MutableMap<String, MezzeValue>) {
        context["__native_mutex_unlock"] = NativeMezzeFunction("__native_mutex_unlock") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_mutex_unlock expects MezzeRecord")

            val mutex = record.fields["mutex"] as? MezzeMutex
                ?: throw MezzeException("__native_mutex_unlock expects 'mutex' field of type Mutex")

            mutex.lock.unlock()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_mutex_try_lock: {mutex: Mutex} -> Bool
     * Tries to acquire lock without blocking.
     * Returns true if acquired, false if already held by another thread.
     */
    private fun registerTryLock(context: MutableMap<String, MezzeValue>) {
        context["__native_mutex_try_lock"] = NativeMezzeFunction("__native_mutex_try_lock") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_mutex_try_lock expects MezzeRecord")

            val mutex = record.fields["mutex"] as? MezzeMutex
                ?: throw MezzeException("__native_mutex_try_lock expects 'mutex' field of type Mutex")

            MezzeBool(mutex.lock.tryLock())
        }
    }

    /**
     * __native_mutex_is_held_by_current_thread: {mutex: Mutex} -> Bool
     * Checks if the current thread holds this mutex.
     */
    private fun registerIsHeldByCurrentThread(context: MutableMap<String, MezzeValue>) {
        context["__native_mutex_is_held_by_current_thread"] = NativeMezzeFunction("__native_mutex_is_held_by_current_thread") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_mutex_is_held_by_current_thread expects MezzeRecord")

            val mutex = record.fields["mutex"] as? MezzeMutex
                ?: throw MezzeException("__native_mutex_is_held_by_current_thread expects 'mutex' field of type Mutex")

            MezzeBool(mutex.lock.isHeldByCurrentThread)
        }
    }

    /**
     * Helper to call a Mezze function with no arguments.
     */
    private fun callFunction(f: MezzeValue): MezzeValue {
        val emptyArgs = MezzeRecord(emptyMap())
        return when (f) {
            is MezzeTruffleFunction -> {
                f.callTarget.call(
                    emptyArgs,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as MezzeValue
            }
            is NativeMezzeFunction -> {
                f.call(listOf(emptyArgs))
            }
            else -> throw MezzeException("Expected function, got: ${f::class.simpleName}")
        }
    }
}

/**
 * MezzeMutex - wraps a ReentrantLock for mutual exclusion.
 */
data class MezzeMutex(
    val lock: ReentrantLock
) : MezzeValue() {
    override fun getDisplayString(): String {
        val state = if (lock.isLocked) "locked" else "unlocked"
        val holdCount = lock.holdCount
        return "<mutex:$state" + (if (holdCount > 1) ":hold=$holdCount" else "") + ">"
    }
}
