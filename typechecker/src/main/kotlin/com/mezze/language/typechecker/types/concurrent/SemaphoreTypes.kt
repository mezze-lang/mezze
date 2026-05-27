package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Semaphore operations.
 *
 * Every native carries `<Async>` (post-#170). `with_permit`'s callback
 * is row-polymorphic so the body can perform other Async-family ops.
 *
 * Semaphores control access to resources with limited capacity.
 */
object SemaphoreTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val semType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Semaphore", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_semaphore_new: {permits: Int} -> Semaphore <Async>
        context["__native_semaphore_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("permits" to MezzeType.IntType)),
            semType,
            effects = asyncEffect
        )

        // __native_semaphore_acquire: {sem: Semaphore} -> {} <Async>
        context["__native_semaphore_acquire"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            unitType,
            effects = asyncEffect
        )

        // __native_semaphore_acquire_n: {sem: Semaphore, permits: Int} -> {} <Async>
        context["__native_semaphore_acquire_n"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sem" to semType,
                "permits" to MezzeType.IntType
            )),
            unitType,
            effects = asyncEffect
        )

        // __native_semaphore_release: {sem: Semaphore} -> {} <Async>
        context["__native_semaphore_release"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            unitType,
            effects = asyncEffect
        )

        // __native_semaphore_release_n: {sem: Semaphore, permits: Int} -> {} <Async>
        context["__native_semaphore_release_n"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sem" to semType,
                "permits" to MezzeType.IntType
            )),
            unitType,
            effects = asyncEffect
        )

        // __native_semaphore_try_acquire: {sem: Semaphore} -> Bool <Async>
        context["__native_semaphore_try_acquire"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_semaphore_try_acquire_n: {sem: Semaphore, permits: Int} -> Bool <Async>
        context["__native_semaphore_try_acquire_n"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sem" to semType,
                "permits" to MezzeType.IntType
            )),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_semaphore_available: {sem: Semaphore} -> Int <Async>
        context["__native_semaphore_available"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("sem" to semType)),
            MezzeType.IntType,
            effects = asyncEffect
        )

        // __native_semaphore_with_permit: {sem: Semaphore, f: {} -> 'a <...>} -> 'a <Async, ...>
        run {
            val resultType = MezzeType.VarType(TypeVar("a"))
            val funcType = MezzeType.FunctionType(
                unitType,
                resultType,
                effects = MezzeType.EffectRow.simple(emptySet(), RowVar.fresh(), performed = false)
            )
            context["__native_semaphore_with_permit"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "sem" to semType,
                    "f" to funcType
                )),
                resultType,
                effects = asyncEffect
            )
        }
    }
}
