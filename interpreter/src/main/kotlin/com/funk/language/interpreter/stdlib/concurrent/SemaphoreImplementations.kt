package com.funk.language.interpreter.stdlib.concurrent

import com.funk.language.interpreter.*
import java.util.concurrent.Semaphore

/**
 * Native implementations for Semaphore operations.
 *
 * Semaphores control access to resources with limited capacity,
 * allowing N concurrent accessors (unlike mutex which allows only 1).
 */
object SemaphoreImplementations {

    /**
     * Register all semaphore-related native functions.
     */
    fun registerAll(context: MutableMap<String, FunkValue>) {
        registerNew(context)
        registerAcquire(context)
        registerAcquireN(context)
        registerRelease(context)
        registerReleaseN(context)
        registerTryAcquire(context)
        registerTryAcquireN(context)
        registerAvailable(context)
        registerWithPermit(context)
    }

    /**
     * __native_semaphore_new: {permits: Int} -> Semaphore
     * Creates a new semaphore with specified permits.
     */
    private fun registerNew(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_new"] = NativeFunkFunction("__native_semaphore_new") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_new expects FunkRecord")

            val permits = (record.fields["permits"] as? FunkInt)?.value?.toInt()
                ?: throw FunkException("__native_semaphore_new expects 'permits' field of type Int")

            if (permits < 0) {
                throw FunkException("Semaphore permits must be non-negative, got: $permits")
            }

            FunkSemaphore(Semaphore(permits))
        }
    }

    /**
     * __native_semaphore_acquire: {sem: Semaphore} -> {}
     * Acquires one permit (blocks until available).
     */
    private fun registerAcquire(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_acquire"] = NativeFunkFunction("__native_semaphore_acquire") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_acquire expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_acquire expects 'sem' field of type Semaphore")

            sem.semaphore.acquire()
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_acquire_n: {sem: Semaphore, permits: Int} -> {}
     * Acquires multiple permits (blocks until all available).
     */
    private fun registerAcquireN(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_acquire_n"] = NativeFunkFunction("__native_semaphore_acquire_n") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_acquire_n expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_acquire_n expects 'sem' field of type Semaphore")

            val permits = (record.fields["permits"] as? FunkInt)?.value?.toInt()
                ?: throw FunkException("__native_semaphore_acquire_n expects 'permits' field of type Int")

            sem.semaphore.acquire(permits)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_release: {sem: Semaphore} -> {}
     * Releases one permit.
     */
    private fun registerRelease(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_release"] = NativeFunkFunction("__native_semaphore_release") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_release expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_release expects 'sem' field of type Semaphore")

            sem.semaphore.release()
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_release_n: {sem: Semaphore, permits: Int} -> {}
     * Releases multiple permits.
     */
    private fun registerReleaseN(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_release_n"] = NativeFunkFunction("__native_semaphore_release_n") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_release_n expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_release_n expects 'sem' field of type Semaphore")

            val permits = (record.fields["permits"] as? FunkInt)?.value?.toInt()
                ?: throw FunkException("__native_semaphore_release_n expects 'permits' field of type Int")

            sem.semaphore.release(permits)
            FunkRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_try_acquire: {sem: Semaphore} -> Bool
     * Tries to acquire one permit without blocking.
     */
    private fun registerTryAcquire(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_try_acquire"] = NativeFunkFunction("__native_semaphore_try_acquire") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_try_acquire expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_try_acquire expects 'sem' field of type Semaphore")

            FunkBool(sem.semaphore.tryAcquire())
        }
    }

    /**
     * __native_semaphore_try_acquire_n: {sem: Semaphore, permits: Int} -> Bool
     * Tries to acquire multiple permits without blocking.
     */
    private fun registerTryAcquireN(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_try_acquire_n"] = NativeFunkFunction("__native_semaphore_try_acquire_n") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_try_acquire_n expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_try_acquire_n expects 'sem' field of type Semaphore")

            val permits = (record.fields["permits"] as? FunkInt)?.value?.toInt()
                ?: throw FunkException("__native_semaphore_try_acquire_n expects 'permits' field of type Int")

            FunkBool(sem.semaphore.tryAcquire(permits))
        }
    }

    /**
     * __native_semaphore_available: {sem: Semaphore} -> Int
     * Gets the number of currently available permits.
     */
    private fun registerAvailable(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_available"] = NativeFunkFunction("__native_semaphore_available") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_available expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_available expects 'sem' field of type Semaphore")

            FunkInt(sem.semaphore.availablePermits().toLong())
        }
    }

    /**
     * __native_semaphore_with_permit: {sem: Semaphore, f: {} -> 'a} -> 'a
     * RAII-style: acquire permit, run function, release permit (even on exception).
     */
    private fun registerWithPermit(context: MutableMap<String, FunkValue>) {
        context["__native_semaphore_with_permit"] = NativeFunkFunction("__native_semaphore_with_permit") { args ->
            val record = args.firstOrNull() as? FunkRecord
                ?: throw FunkException("__native_semaphore_with_permit expects FunkRecord")

            val sem = record.fields["sem"] as? FunkSemaphore
                ?: throw FunkException("__native_semaphore_with_permit expects 'sem' field of type Semaphore")

            val f = record.fields["f"]
                ?: throw FunkException("__native_semaphore_with_permit expects 'f' field")

            sem.semaphore.acquire()
            try {
                callFunction(f)
            } finally {
                sem.semaphore.release()
            }
        }
    }

    /**
     * Helper to call a Funk function with no arguments.
     */
    private fun callFunction(f: FunkValue): FunkValue {
        val emptyArgs = FunkRecord(emptyMap())
        return when (f) {
            is FunkTruffleFunction -> {
                f.callTarget.call(
                    emptyArgs,
                    f.closureVariables,
                    f.lexicalScope,
                    f.lexicalScopeSlotMap
                ) as FunkValue
            }
            is NativeFunkFunction -> {
                f.call(listOf(emptyArgs))
            }
            else -> throw FunkException("Expected function, got: ${f::class.simpleName}")
        }
    }
}

/**
 * FunkSemaphore - wraps a Java Semaphore.
 */
data class FunkSemaphore(
    val semaphore: Semaphore
) : FunkValue() {
    override fun getDisplayString(): String {
        return "<semaphore:${semaphore.availablePermits()} available>"
    }
}
