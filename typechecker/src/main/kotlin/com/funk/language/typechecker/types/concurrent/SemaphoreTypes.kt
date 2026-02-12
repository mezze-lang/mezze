package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for Semaphore operations.
 *
 * Semaphores control access to resources with limited capacity.
 */
object SemaphoreTypes {

    /**
     * Register all semaphore native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        val semType = FunkType.TypeApplication("Semaphore", emptyList())
        val unitType = FunkType.RecordType(emptyMap())

        // __native_semaphore_new: {permits: Int} -> Semaphore
        context["__native_semaphore_new"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("permits" to FunkType.IntType)),
            semType
        )

        // __native_semaphore_acquire: {sem: Semaphore} -> {}
        context["__native_semaphore_acquire"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("sem" to semType)),
            unitType
        )

        // __native_semaphore_acquire_n: {sem: Semaphore, permits: Int} -> {}
        context["__native_semaphore_acquire_n"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "sem" to semType,
                "permits" to FunkType.IntType
            )),
            unitType
        )

        // __native_semaphore_release: {sem: Semaphore} -> {}
        context["__native_semaphore_release"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("sem" to semType)),
            unitType
        )

        // __native_semaphore_release_n: {sem: Semaphore, permits: Int} -> {}
        context["__native_semaphore_release_n"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "sem" to semType,
                "permits" to FunkType.IntType
            )),
            unitType
        )

        // __native_semaphore_try_acquire: {sem: Semaphore} -> Bool
        context["__native_semaphore_try_acquire"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("sem" to semType)),
            FunkType.BooleanType
        )

        // __native_semaphore_try_acquire_n: {sem: Semaphore, permits: Int} -> Bool
        context["__native_semaphore_try_acquire_n"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "sem" to semType,
                "permits" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        // __native_semaphore_available: {sem: Semaphore} -> Int
        context["__native_semaphore_available"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("sem" to semType)),
            FunkType.IntType
        )

        // __native_semaphore_with_permit: {sem: Semaphore, f: {} -> 'a} -> 'a
        run {
            val resultType = FunkType.VarType(TypeVar("a"))
            val funcType = FunkType.FunctionType(unitType, resultType)
            context["__native_semaphore_with_permit"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "sem" to semType,
                    "f" to funcType
                )),
                resultType
            )
        }
    }
}
