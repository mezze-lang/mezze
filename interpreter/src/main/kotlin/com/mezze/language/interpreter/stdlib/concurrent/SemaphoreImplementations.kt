package com.mezze.language.interpreter.stdlib.concurrent

import com.mezze.language.interpreter.*
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
    fun registerAll(context: MutableMap<String, MezzeValue>) {
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
    private fun registerNew(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_new"] = NativeMezzeFunction("__native_semaphore_new") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_new expects MezzeRecord")

            val permits = (record.fields["permits"] as? MezzeInt)?.value?.toInt()
                ?: throw MezzeException("__native_semaphore_new expects 'permits' field of type Int")

            if (permits < 0) {
                throw MezzeException("Semaphore permits must be non-negative, got: $permits")
            }

            MezzeSemaphore(Semaphore(permits))
        }
    }

    /**
     * __native_semaphore_acquire: {sem: Semaphore} -> {}
     * Acquires one permit (blocks until available).
     */
    private fun registerAcquire(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_acquire"] = NativeMezzeFunction("__native_semaphore_acquire") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_acquire expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_acquire expects 'sem' field of type Semaphore")

            sem.semaphore.acquire()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_acquire_n: {sem: Semaphore, permits: Int} -> {}
     * Acquires multiple permits (blocks until all available).
     */
    private fun registerAcquireN(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_acquire_n"] = NativeMezzeFunction("__native_semaphore_acquire_n") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_acquire_n expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_acquire_n expects 'sem' field of type Semaphore")

            val permits = (record.fields["permits"] as? MezzeInt)?.value?.toInt()
                ?: throw MezzeException("__native_semaphore_acquire_n expects 'permits' field of type Int")

            sem.semaphore.acquire(permits)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_release: {sem: Semaphore} -> {}
     * Releases one permit.
     */
    private fun registerRelease(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_release"] = NativeMezzeFunction("__native_semaphore_release") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_release expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_release expects 'sem' field of type Semaphore")

            sem.semaphore.release()
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_release_n: {sem: Semaphore, permits: Int} -> {}
     * Releases multiple permits.
     */
    private fun registerReleaseN(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_release_n"] = NativeMezzeFunction("__native_semaphore_release_n") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_release_n expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_release_n expects 'sem' field of type Semaphore")

            val permits = (record.fields["permits"] as? MezzeInt)?.value?.toInt()
                ?: throw MezzeException("__native_semaphore_release_n expects 'permits' field of type Int")

            sem.semaphore.release(permits)
            MezzeRecord(emptyMap())
        }
    }

    /**
     * __native_semaphore_try_acquire: {sem: Semaphore} -> Bool
     * Tries to acquire one permit without blocking.
     */
    private fun registerTryAcquire(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_try_acquire"] = NativeMezzeFunction("__native_semaphore_try_acquire") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_try_acquire expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_try_acquire expects 'sem' field of type Semaphore")

            MezzeBool(sem.semaphore.tryAcquire())
        }
    }

    /**
     * __native_semaphore_try_acquire_n: {sem: Semaphore, permits: Int} -> Bool
     * Tries to acquire multiple permits without blocking.
     */
    private fun registerTryAcquireN(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_try_acquire_n"] = NativeMezzeFunction("__native_semaphore_try_acquire_n") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_try_acquire_n expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_try_acquire_n expects 'sem' field of type Semaphore")

            val permits = (record.fields["permits"] as? MezzeInt)?.value?.toInt()
                ?: throw MezzeException("__native_semaphore_try_acquire_n expects 'permits' field of type Int")

            MezzeBool(sem.semaphore.tryAcquire(permits))
        }
    }

    /**
     * __native_semaphore_available: {sem: Semaphore} -> Int
     * Gets the number of currently available permits.
     */
    private fun registerAvailable(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_available"] = NativeMezzeFunction("__native_semaphore_available") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_available expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_available expects 'sem' field of type Semaphore")

            MezzeInt(sem.semaphore.availablePermits().toLong())
        }
    }

    /**
     * __native_semaphore_with_permit: {sem: Semaphore, f: {} -> 'a} -> 'a
     * RAII-style: acquire permit, run function, release permit (even on exception).
     */
    private fun registerWithPermit(context: MutableMap<String, MezzeValue>) {
        context["__native_semaphore_with_permit"] = NativeMezzeFunction("__native_semaphore_with_permit") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw MezzeException("__native_semaphore_with_permit expects MezzeRecord")

            val sem = record.fields["sem"] as? MezzeSemaphore
                ?: throw MezzeException("__native_semaphore_with_permit expects 'sem' field of type Semaphore")

            val f = record.fields["f"]
                ?: throw MezzeException("__native_semaphore_with_permit expects 'f' field")

            sem.semaphore.acquire()
            try {
                callFunction(f)
            } finally {
                sem.semaphore.release()
            }
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
 * MezzeSemaphore - wraps a Java Semaphore.
 */
data class MezzeSemaphore(
    val semaphore: Semaphore
) : MezzeValue() {
    override fun getDisplayString(): String {
        return "<semaphore:${semaphore.availablePermits()} available>"
    }
}
