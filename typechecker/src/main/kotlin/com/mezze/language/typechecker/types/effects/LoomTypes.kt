package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for Loom virtual thread concurrency primitives.
 *
 * These native functions provide Project Loom virtual thread support:
 * - Spawning computations on virtual threads
 * - Awaiting promises
 * - Park/unpark for thread coordination
 * - Current thread handle
 */
object LoomTypes {

    /**
     * Register all Loom native function types.
     *
     * Each function gets its own fresh type variables for Promise, Thread, and result type.
     * This is important because these types are registered as polymorphic and get
     * fresh instantiation on each use. If we shared type variables across functions,
     * unifying one would affect all others.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // Helper: effect-polymorphic thunk {} -> 'a <...>
        fun effectPolymorphicThunk(returnType: MezzeType): MezzeType.FunctionType {
            return MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                returnType,
                effects = MezzeType.EffectRow.simple(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        // Helper: effect-polymorphic scoped callback {sc: Scope 'a} -> 'a <...>
        fun effectPolymorphicScopedCallback(scopeType: MezzeType, returnType: MezzeType): MezzeType.FunctionType {
            return MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("sc" to scopeType)),
                returnType,
                effects = MezzeType.EffectRow.simple(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        natives(context) {
            // __native_async_spawn: {f: {} -> 'a} -> Promise 'a
            fn("__native_async_spawn") {
                val a = tvar("a")
                param("f" to MezzeType.FunctionType(unit, a))
                returns(WellKnownTypeApp.of("Promise", listOf(a)))
            }

            // __native_promise_await: {p: Promise 'a} -> 'a
            fn("__native_promise_await") {
                val a = tvar("a")
                param("p" to WellKnownTypeApp.of("Promise", listOf(a)))
                returns(a)
            }

            // __native_async_sleep: {ms: Int} -> {}
            fn("__native_async_sleep") { param("ms" to int); returns(unit) }

            // __native_async_is_cancelled: {} -> Bool
            fn("__native_async_is_cancelled") { returns(bool) }

            // __native_async_check: {} -> {}
            fn("__native_async_check") { returns(unit) }

            // __native_promise_is_complete: {p: Promise 'a} -> Bool
            fn("__native_promise_is_complete") {
                val a = tvar("a")
                param("p" to WellKnownTypeApp.of("Promise", listOf(a)))
                returns(bool)
            }

            // Promise-value natives (#117/#118/#123)
            fn("__native_promise_inspect") {
                val a = tvar("a")
                param("p" to WellKnownTypeApp.of("Promise", listOf(a)))
                returns(MezzeType.VarType(TypeVar.freshWithName("PromiseState")))
            }

            fn("__native_promise_cancel") {
                val a = tvar("a")
                param("p" to WellKnownTypeApp.of("Promise", listOf(a)))
                returns(MezzeType.VarType(TypeVar.freshWithName("CancelOutcome")))
            }

            fn("__native_promise_succeed") {
                val a = tvar("a")
                param("val" to a)
                returns(WellKnownTypeApp.of("Promise", listOf(a)))
            }

            fn("__native_promise_fail") {
                val a = tvar("a")
                val asyncError = MezzeType.VarType(TypeVar.freshWithName("AsyncError"))
                param("err" to asyncError)
                returns(WellKnownTypeApp.of("Promise", listOf(a)))
            }

            fn("__native_promise_await_result") {
                val a = tvar("a")
                val asyncError = MezzeType.VarType(TypeVar.freshWithName("AsyncError"))
                param("p" to WellKnownTypeApp.of("Promise", listOf(a)))
                returns(result(a, asyncError))
            }

            // Scope lifecycle
            fn("__native_async_init_scope") {
                returns(MezzeType.VarType(TypeVar.freshWithName("tracker")))
            }

            fn("__native_async_finalize_scope") {
                param("tracker" to MezzeType.VarType(TypeVar.freshWithName("tracker")))
                returns(unit)
            }

            // __native_async_scope_is_cancelled: {sc: Scope 'a} -> Bool
            fn("__native_async_scope_is_cancelled") {
                val a = tvar("a")
                param("sc" to WellKnownTypeApp.of("Scope", listOf(a)))
                returns(bool)
            }
        }

        // Functions with effect-polymorphic callback params need raw registration

        // __native_async_park_until: {predicate: {} -> Bool <...>} -> {}
        context["__native_async_park_until"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "predicate" to effectPolymorphicThunk(MezzeType.BoolType)
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_async_scoped: {f: {sc: Scope 'a} -> 'a <...>} -> 'a
        val scopedA = MezzeType.VarType(TypeVar.freshWithName("a"))
        val scopeType = WellKnownTypeApp.of("Scope", listOf(scopedA))
        context["__native_async_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicScopedCallback(scopeType, scopedA)
            )),
            scopedA
        )

        // __native_async_scope_spawn: {sc: Scope 'a, f: {} -> 'b <...>} -> Promise 'b
        val scopeSpawnA = MezzeType.VarType(TypeVar.freshWithName("a"))
        val scopeSpawnB = MezzeType.VarType(TypeVar.freshWithName("b"))
        context["__native_async_scope_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to WellKnownTypeApp.of("Scope", listOf(scopeSpawnA)),
                "f" to effectPolymorphicThunk(scopeSpawnB)
            )),
            WellKnownTypeApp.of("Promise", listOf(scopeSpawnB))
        )

        // __native_async_scope_cancel_all: {sc: Scope 'a} -> {}
        val scopeCancelA = MezzeType.VarType(TypeVar.freshWithName("a"))
        context["__native_async_scope_cancel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to WellKnownTypeApp.of("Scope", listOf(scopeCancelA))
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_async_scope_get_token: {sc: Scope 'a} -> CancellationToken
        val scopeTokenA = MezzeType.VarType(TypeVar.freshWithName("a"))
        context["__native_async_scope_get_token"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to WellKnownTypeApp.of("Scope", listOf(scopeTokenA))
            )),
            WellKnownTypeApp.of("CancellationToken", emptyList())
        )

        // __native_async_spawn_tracked: {f: {} -> 'a <...>, max_concurrent: Int, tracker: 't} -> Promise 'a
        val spawnTrackedA = MezzeType.VarType(TypeVar.freshWithName("a"))
        val spawnTrackedTracker = MezzeType.VarType(TypeVar.freshWithName("tracker"))
        context["__native_async_spawn_tracked"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicThunk(spawnTrackedA),
                "max_concurrent" to MezzeType.IntType,
                "tracker" to spawnTrackedTracker
            )),
            WellKnownTypeApp.of("Promise", listOf(spawnTrackedA))
        )
    }
}
