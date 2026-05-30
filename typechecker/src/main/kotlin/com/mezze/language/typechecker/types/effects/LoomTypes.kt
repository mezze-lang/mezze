package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar

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
        val promiseType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Promise", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))

        // __native_async_spawn: {f: {} -> 'a} -> Promise 'a
        context["__native_async_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
                )
            )),
            promiseType
        )

        // Helper: Create an effect-polymorphic function type {} -> 'a <...>
        // The RowVar makes it accept any effects
        fun effectPolymorphicThunk(returnType: MezzeType): MezzeType.FunctionType {
            return MezzeType.FunctionType(
                MezzeType.RecordType(emptyMap()),
                returnType,
                effects = MezzeType.EffectRow.simple(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        // __native_promise_await: {p: Promise 'a} -> 'a
        context["__native_promise_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_async_sleep: {ms: Int} -> {}
        context["__native_async_sleep"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_async_is_cancelled: {} -> Bool
        context["__native_async_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BoolType
        )

        // __native_async_check: {} -> {}
        context["__native_async_check"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // __native_async_park_until: {predicate: {} -> Bool <...>} -> {}
        context["__native_async_park_until"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "predicate" to effectPolymorphicThunk(MezzeType.BoolType)
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_promise_is_complete: {p: Promise} -> Bool
        context["__native_promise_is_complete"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.BoolType
        )

        // === Promise-value natives (#117/#118/#123) ===
        val asyncErrorType = MezzeType.VarType(com.mezze.language.ast.TypeVar("AsyncError"))
        val resultType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
            asyncErrorType
        ))
        // PromiseState 'a — variant: Pending | Completed{val: 'a} | Failed{err: AsyncError}
        val promiseStateType = MezzeType.VarType(com.mezze.language.ast.TypeVar("PromiseState"))
        // CancelOutcome — Cancelled | AlreadyCompleted | AlreadyFailed | NotCancellable
        val cancelOutcomeType = MezzeType.VarType(com.mezze.language.ast.TypeVar("CancelOutcome"))

        // __native_promise_inspect: { p: Promise 'a } -> PromiseState 'a
        context["__native_promise_inspect"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("p" to promiseType)),
            promiseStateType
        )

        // __native_promise_cancel: { p: Promise 'a } -> CancelOutcome
        context["__native_promise_cancel"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("p" to promiseType)),
            cancelOutcomeType
        )

        // __native_promise_succeed: { val: 'a } -> Promise 'a
        context["__native_promise_succeed"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "val" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
            )),
            promiseType
        )

        // __native_promise_fail: { err: AsyncError } -> Promise 'a
        context["__native_promise_fail"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("err" to asyncErrorType)),
            promiseType
        )

        // __native_promise_await_result: { p: Promise 'a } -> Result 'a AsyncError
        context["__native_promise_await_result"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("p" to promiseType)),
            resultType
        )

        // === Structured Concurrency Scopes ===

        // Scope type - opaque wrapper for structured concurrency scopes
        val scopeType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Scope", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))

        // Helper: Create an effect-polymorphic function type {sc: Scope 'a} -> 'a <...>
        fun effectPolymorphicScopedCallback(scopeType: MezzeType, returnType: MezzeType): MezzeType.FunctionType {
            return MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("sc" to scopeType)),
                returnType,
                effects = MezzeType.EffectRow.simple(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        // __native_async_scoped: {f: {sc: Scope 'a} -> 'a <...>} -> 'a
        // The callback f can have any effects (<...>)
        context["__native_async_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicScopedCallback(scopeType, MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_async_scope_spawn: {sc: Scope 'a, f: {} -> 'b <...>} -> Promise 'b
        // The f parameter can have any effects (<...>)
        val scopeSpawnPromiseType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Promise", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))))
        context["__native_async_scope_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType,
                "f" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
            )),
            scopeSpawnPromiseType
        )

        // __native_async_scope_cancel_all: {sc: Scope 'a} -> {}
        context["__native_async_scope_cancel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_async_scope_get_token: {sc: Scope 'a} -> CancellationToken
        val cancellationTokenType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("CancellationToken", emptyList())
        context["__native_async_scope_get_token"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            cancellationTokenType
        )

        // __native_async_scope_is_cancelled: {sc: Scope 'a} -> Bool
        context["__native_async_scope_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            MezzeType.BoolType
        )

        // === Effect Scope Lifecycle (for scoped effects) ===
        // These natives support initialize_scope/finalize_scope in effect handlers
        // The tracker type is polymorphic - it can be any type returned by initialize_scope

        // __native_async_init_scope: {} -> 't (polymorphic - returns opaque tracker)
        // Creates a new promise tracker for structured concurrency
        val scopeTrackerVar = MezzeType.VarType(com.mezze.language.ast.TypeVar("tracker"))
        context["__native_async_init_scope"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            scopeTrackerVar
        )

        // __native_async_finalize_scope: {tracker: 't} -> {}
        // Awaits all unawaited promises registered with the tracker
        context["__native_async_finalize_scope"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "tracker" to MezzeType.VarType(com.mezze.language.ast.TypeVar("tracker"))
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_async_spawn_tracked: {f: {} -> 'a <...>, max_concurrent: Int, tracker: 't} -> Promise 'a
        // Spawns a computation and registers the promise with the tracker
        context["__native_async_spawn_tracked"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "max_concurrent" to MezzeType.IntType,
                "tracker" to MezzeType.VarType(com.mezze.language.ast.TypeVar("tracker"))
            )),
            promiseType
        )
    }
}
