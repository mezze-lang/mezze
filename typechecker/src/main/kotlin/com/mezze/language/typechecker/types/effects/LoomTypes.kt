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
        val promiseType = MezzeType.TypeApplication("Promise", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))

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
                effects = MezzeType.EffectRow(emptySet<String>(), RowVar.fresh(), performed = false)
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

        // __native_loom_spawn_all: {fs: [{} -> 'a <...>]} -> ['a]
        // Spawns all computations on virtual threads and waits for all to complete
        // The fs parameter accepts functions with any effects
        context["__native_loom_spawn_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "fs" to MezzeType.ArrayType(effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))
            )),
            MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // __native_loom_spawn_race: {fs: [{} -> 'a <...>]} -> 'a
        // Spawns all computations on virtual threads and returns first result
        // The fs parameter accepts functions with any effects
        context["__native_loom_spawn_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "fs" to MezzeType.ArrayType(effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_race: {ps: [Promise 'a]} -> 'a
        context["__native_loom_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType)
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_all: {ps: [Promise 'a]} -> ['a]
        context["__native_loom_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType)
            )),
            MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // __native_loom_timeout: {ms: Int, p: Promise 'a} -> Option 'a
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))
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
            MezzeType.BooleanType
        )

        // __native_loom_is_cancelled: {} -> Bool
        context["__native_loom_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.BooleanType
        )

        // __native_loom_check: {} -> {}
        context["__native_loom_check"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_park: {} -> {}
        context["__native_loom_park"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_unpark: {thread: Thread} -> {}
        val unparkThread = MezzeType.VarType(com.mezze.language.ast.TypeVar("Thread"))
        context["__native_loom_unpark"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "thread" to unparkThread
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_current_thread: {} -> Thread
        val currentThread = MezzeType.VarType(com.mezze.language.ast.TypeVar("Thread"))
        context["__native_loom_current_thread"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            currentThread
        )

        // __native_promise_is_complete: {p: Promise} -> Bool
        context["__native_promise_is_complete"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.BooleanType
        )

        // === Structured Concurrency ===

        // __native_loom_join: {left: {} -> 'a <...>, right: {} -> 'b <...>} -> {left: 'a, right: 'b}
        // Callback parameters accept any effects (<...>)
        context["__native_loom_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "left" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "right" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
            )),
            MezzeType.RecordType(mapOf(
                "left" to MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                "right" to MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
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
                effects = MezzeType.EffectRow(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        // __native_loom_map: {xs: ['a], f: {x: 'a} -> 'b <...>} -> ['b]
        // The f parameter accepts any effects (<...>)
        context["__native_loom_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "f" to effectPolymorphicMapper(
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))
                )
            )),
            MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
        )

        // __native_loom_for_each: {xs: ['a], f: {x: 'a} -> {} <...>} -> {}
        // The f parameter accepts any effects (<...>)
        context["__native_loom_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
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
                "xs" to MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))),
                "f" to effectPolymorphicMapper(
                    MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
                    MezzeType.BooleanType
                )
            )),
            MezzeType.ArrayType(MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
        )

        // === Error Handling ===

        // __native_loom_any: {ps: [Promise 'a]} -> 'a
        context["__native_loom_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType)
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_settle: {ps: [Promise 'a]} -> [Result 'a AsyncError]
        // AsyncError is a variant: Failed {message: String} | Cancelled | TimedOut {ms: Int}
        val asyncErrorType = MezzeType.VarType(com.mezze.language.ast.TypeVar("AsyncError"))
        val resultType = MezzeType.TypeApplication("Result", listOf(
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a")),
            asyncErrorType
        ))
        context["__native_loom_settle"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType)
            )),
            MezzeType.ArrayType(resultType)
        )

        // === Structured Concurrency Scopes ===

        // Scope type - opaque wrapper for structured concurrency scopes
        val scopeType = MezzeType.TypeApplication("Scope", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))))

        // Helper: Create an effect-polymorphic function type {scope: Scope 'a} -> 'a <...>
        fun effectPolymorphicScopedCallback(scopeType: MezzeType, returnType: MezzeType): MezzeType.FunctionType {
            return MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("scope" to scopeType)),
                returnType,
                effects = MezzeType.EffectRow(emptySet<String>(), RowVar.fresh(), performed = false)
            )
        }

        // __native_loom_scoped: {f: {scope: Scope 'a} -> 'a <...>} -> 'a
        // The callback f can have any effects (<...>)
        context["__native_loom_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to effectPolymorphicScopedCallback(scopeType, MezzeType.VarType(com.mezze.language.ast.TypeVar("a")))
            )),
            MezzeType.VarType(com.mezze.language.ast.TypeVar("a"))
        )

        // __native_loom_scope_spawn: {scope: Scope 'a, f: {} -> 'b <...>} -> Promise 'b
        // The f parameter can have any effects (<...>)
        val scopeSpawnPromiseType = MezzeType.TypeApplication("Promise", listOf(MezzeType.VarType(com.mezze.language.ast.TypeVar("b"))))
        context["__native_loom_scope_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "scope" to scopeType,
                "f" to effectPolymorphicThunk(MezzeType.VarType(com.mezze.language.ast.TypeVar("b")))
            )),
            scopeSpawnPromiseType
        )

        // __native_loom_scope_cancel_all: {scope: Scope 'a} -> {}
        context["__native_loom_scope_cancel_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "scope" to scopeType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_loom_scope_get_token: {scope: Scope 'a} -> CancellationToken
        val cancellationTokenType = MezzeType.TypeApplication("CancellationToken", emptyList())
        context["__native_loom_scope_get_token"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "scope" to scopeType
            )),
            cancellationTokenType
        )

        // __native_loom_scope_is_cancelled: {scope: Scope 'a} -> Bool
        context["__native_loom_scope_is_cancelled"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "scope" to scopeType
            )),
            MezzeType.BooleanType
        )
    }
}
