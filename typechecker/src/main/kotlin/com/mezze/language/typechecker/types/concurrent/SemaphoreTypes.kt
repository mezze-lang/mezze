package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for Semaphore operations.
 *
 * Every native carries `<Async>` (post-#170). `with_permit`'s callback
 * is row-polymorphic so the body can perform other Async-family ops.
 *
 * Semaphores control access to resources with limited capacity.
 */
object SemaphoreTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        val semType = WellKnownTypeApp.of("Semaphore", emptyList())

        // __native_semaphore_new: {permits: Int} -> Semaphore <Async>
        fn("__native_semaphore_new") {
            param("permits" to int)
            returns(semType)
            effects("Async")
        }

        // __native_semaphore_acquire: {sem: Semaphore} -> {} <Async>
        fn("__native_semaphore_acquire") {
            param("sem" to semType)
            returns(unit)
            effects("Async")
        }

        // __native_semaphore_acquire_n: {sem: Semaphore, permits: Int} -> {} <Async>
        fn("__native_semaphore_acquire_n") {
            param("sem" to semType, "permits" to int)
            returns(unit)
            effects("Async")
        }

        // __native_semaphore_release: {sem: Semaphore} -> {} <Async>
        fn("__native_semaphore_release") {
            param("sem" to semType)
            returns(unit)
            effects("Async")
        }

        // __native_semaphore_release_n: {sem: Semaphore, permits: Int} -> {} <Async>
        fn("__native_semaphore_release_n") {
            param("sem" to semType, "permits" to int)
            returns(unit)
            effects("Async")
        }

        // __native_semaphore_try_acquire: {sem: Semaphore} -> Bool <Async>
        fn("__native_semaphore_try_acquire") {
            param("sem" to semType)
            returns(bool)
            effects("Async")
        }

        // __native_semaphore_try_acquire_n: {sem: Semaphore, permits: Int} -> Bool <Async>
        fn("__native_semaphore_try_acquire_n") {
            param("sem" to semType, "permits" to int)
            returns(bool)
            effects("Async")
        }

        // __native_semaphore_available: {sem: Semaphore} -> Int <Async>
        fn("__native_semaphore_available") {
            param("sem" to semType)
            returns(int)
            effects("Async")
        }

        // __native_semaphore_with_permit: {sem: Semaphore, f: {} -> 'a <...>} -> 'a <Async, ...>
        fn("__native_semaphore_with_permit") {
            val a = tvar("a")
            val funcType = MezzeType.FunctionType(
                unit,
                a,
                effects = MezzeType.EffectRow.simple(emptySet(), RowVar.fresh(), performed = false)
            )
            param("sem" to semType, "f" to funcType)
            returns(a)
            effects("Async")
        }
    }
}
