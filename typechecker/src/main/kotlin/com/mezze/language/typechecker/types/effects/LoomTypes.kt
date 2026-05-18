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

        // __native_loom_spawn: {f: {} -> 'a} -> Promise 'a
        context["__native_loom_spawn"] = MezzeType.FunctionType(
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

        // __native_loom_spawn_cfg: {f: {} -> 'a <...>, max_concurrent: Int} -> Promise 'a
        // The f parameter accepts any effects (<...>)
        context["__native_loom_spawn_cfg"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "max_concurrent" to MezzeType.IntType
            )),
            promiseType
        )

        // __native_loom_await: {p: Promise 'a} -> 'a
        context["__native_loom_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_spawn_all: {fs: [{} -> 'a <...>]} -> ['a] <Concurrency>
        // Bare-call wrapper from `concurrency.all` declares <Concurrency>, so the
        // native must too — same pattern as STM's read_all/write_all (#310).
        context["__native_loom_spawn_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "fs" to MezzeType.SeqType(effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))
            )),
            MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
            MezzeType.EffectRow(effects = setOf("Concurrency"))
        )

        // __native_loom_spawn_race: {fs: [{} -> 'a <...>]} -> 'a
        // Spawns all computations on virtual threads and returns first result
        // The fs parameter accepts functions with any effects
        context["__native_loom_spawn_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "fs" to MezzeType.SeqType(effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_race: {ps: [Promise 'a]} -> 'a
        context["__native_loom_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(promiseType)
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_all: {ps: [Promise 'a]} -> ['a]
        context["__native_loom_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(promiseType)
            )),
            MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // __native_loom_timeout: {ms: Int, p: Promise 'a} -> Option 'a
        val optionType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))
        context["__native_loom_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType,
                "p" to promiseType
            )),
            optionType
        )

        // __native_loom_cancel: {p: Promise 'a} -> {}
        context["__native_loom_cancel"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_sleep: {ms: Int} -> {}
        context["__native_loom_sleep"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_is_done: {p: Promise 'a} -> Bool
        context["__native_loom_is_done"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.BoolType
        )

        // __native_loom_is_cancelled: {} -> Bool
        context["__native_loom_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BoolType
        )

        // __native_loom_check: {} -> {}
        context["__native_loom_check"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_park_until: {predicate: {} -> Bool <...>} -> {}
        context["__native_loom_park_until"] = MezzeType.FunctionType(
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

        // === Structured Concurrency ===

        // __native_loom_join: {l: {} -> 'a <...>, r: {} -> 'b <...>} -> {l: 'a, r: 'b}
        // Callback parameters accept any effects (<...>)
        context["__native_loom_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "l" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "r" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
            )),
            MezzeType.RecordType(mapOf(
                "l" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                "r" to MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
            ))
        )

        // __native_loom_join3: {a: {} -> 'a <...>, b: {} -> 'b <...>, c: {} -> 'c <...>} -> {a: 'a, b: 'b, c: 'c}
        // Callback parameters accept any effects (<...>)
        context["__native_loom_join3"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "a" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "b" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))),
                "c" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("c")))
            )),
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                "b" to MezzeType.VarType(com.mezze.language.ast.TypeVar("b")),
                "c" to MezzeType.VarType(com.mezze.language.ast.TypeVar("c"))
            ))
        )

        // === Collection Operations ===

        // Helper: Create an effect-polymorphic function type {x: 'a} -> 'b <...>
        fun effectPolymorphicMapper(inputType: MezzeType, outputType: MezzeType): MezzeType.FunctionType {
            return MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to inputType)),
                outputType,
                effects = MezzeType.EffectRow.simple(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        // __native_loom_map: {xs: ['a], f: {x: 'a} -> 'b <...>} -> ['b]
        // The f parameter accepts any effects (<...>)
        context["__native_loom_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "f" to effectPolymorphicMapper(
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
        )

        // __native_loom_for_each: {xs: ['a], f: {x: 'a} -> {} <...>} -> {}
        // The f parameter accepts any effects (<...>)
        context["__native_loom_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "f" to effectPolymorphicMapper(
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                    MezzeType.RecordType(emptyMap())
                )
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_filter: {xs: ['a], f: {x: 'a} -> Bool <...>} -> ['a]
        // The f parameter accepts any effects (<...>)
        context["__native_loom_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "f" to effectPolymorphicMapper(
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                    MezzeType.BoolType
                )
            )),
            MezzeType.SeqType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // === Error Handling ===

        // __native_loom_any: {ps: [Promise 'a]} -> 'a
        context["__native_loom_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(promiseType)
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_settle: {ps: [Promise 'a]} -> [Result 'a AsyncError]
        // AsyncError is a variant: Failed {message: String} | Cancelled | TimedOut {ms: Int}
        val asyncErrorType = MezzeType.VarType(com.mezze.language.ast.TypeVar("AsyncError"))
        val resultType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Result", listOf(
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
            asyncErrorType
        ))
        context["__native_loom_settle"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.SeqType(promiseType)
            )),
            MezzeType.SeqType(resultType)
        )

        // === Promise-value natives (#117/#118/#123) ===
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

        // __native_loom_scoped: {f: {sc: Scope 'a} -> 'a <...>} -> 'a
        // The callback f can have any effects (<...>)
        context["__native_loom_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicScopedCallback(scopeType, MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_scope_spawn: {sc: Scope 'a, f: {} -> 'b <...>} -> Promise 'b
        // The f parameter can have any effects (<...>)
        val scopeSpawnPromiseType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Promise", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))))
        context["__native_loom_scope_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType,
                "f" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
            )),
            scopeSpawnPromiseType
        )

        // __native_loom_scope_cancel_all: {sc: Scope 'a} -> {}
        context["__native_loom_scope_cancel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_scope_get_token: {sc: Scope 'a} -> CancellationToken
        val cancellationTokenType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("CancellationToken", emptyList())
        context["__native_loom_scope_get_token"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            cancellationTokenType
        )

        // __native_loom_scope_is_cancelled: {sc: Scope 'a} -> Bool
        context["__native_loom_scope_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "sc" to scopeType
            )),
            MezzeType.BoolType
        )

        // === Effect Scope Lifecycle (for scoped effects) ===
        // These natives support initialize_scope/finalize_scope in effect handlers
        // The tracker type is polymorphic - it can be any type returned by initialize_scope

        // __native_loom_init_scope: {} -> 't (polymorphic - returns opaque tracker)
        // Creates a new promise tracker for structured concurrency
        val scopeTrackerVar = MezzeType.VarType(com.mezze.language.ast.TypeVar("tracker"))
        context["__native_loom_init_scope"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            scopeTrackerVar
        )

        // __native_loom_finalize_scope: {tracker: 't} -> {}
        // Awaits all unawaited promises registered with the tracker
        context["__native_loom_finalize_scope"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "tracker" to MezzeType.VarType(com.mezze.language.ast.TypeVar("tracker"))
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_spawn_tracked: {f: {} -> 'a <...>, max_concurrent: Int, tracker: 't} -> Promise 'a
        // Spawns a computation and registers the promise with the tracker
        context["__native_loom_spawn_tracked"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "max_concurrent" to MezzeType.IntType,
                "tracker" to MezzeType.VarType(com.mezze.language.ast.TypeVar("tracker"))
            )),
            promiseType
        )
    }
}
