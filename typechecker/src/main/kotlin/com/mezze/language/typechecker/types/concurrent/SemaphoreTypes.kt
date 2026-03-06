package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Semaphore operations.
 *
 * Semaphores control access to resources with limited capacity.
 */
object SemaphoreTypes {

    /**
     * Register all semaphore native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        val semType = MezzeType.TypeApplication("Semaphore", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())

        // __native_semaphore_new: {permits: Int} -> Semaphore
        context["__native_semaphore_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("permits" to MezzeType.IntType)),
            semType
        )

        // __native_semaphore_acquire: {sem: Semaphore} -> {}
        context["__native_semaphore_acquire"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            unitType
        )

        // __native_semaphore_acquire_n: {sem: Semaphore, permits: Int} -> {}
        context["__native_semaphore_acquire_n"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sem" to semType,
                "permits" to MezzeType.IntType
            )),
            unitType
        )

        // __native_semaphore_release: {sem: Semaphore} -> {}
        context["__native_semaphore_release"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            unitType
        )

        // __native_semaphore_release_n: {sem: Semaphore, permits: Int} -> {}
        context["__native_semaphore_release_n"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sem" to semType,
                "permits" to MezzeType.IntType
            )),
            unitType
        )

        // __native_semaphore_try_acquire: {sem: Semaphore} -> Bool
        context["__native_semaphore_try_acquire"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            MezzeType.BoolType
        )

        // __native_semaphore_try_acquire_n: {sem: Semaphore, permits: Int} -> Bool
        context["__native_semaphore_try_acquire_n"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sem" to semType,
                "permits" to MezzeType.IntType
            )),
            MezzeType.BoolType
        )

        // __native_semaphore_available: {sem: Semaphore} -> Int
        context["__native_semaphore_available"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            MezzeType.IntType
        )

        // __native_semaphore_with_permit: {sem: Semaphore, f: {} -> 'a} -> 'a
        run {
            val resultType = MezzeType.VarType(TypeVar("a"))
            val funcType = MezzeType.FunctionType(unitType, resultType)
            context["__native_semaphore_with_permit"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "sem" to semType,
                    "f" to funcType
                )),
                resultType
            )
        }
    }
}
