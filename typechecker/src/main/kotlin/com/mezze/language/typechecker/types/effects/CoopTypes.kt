package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Coop (cooperative) async native functions.
 *
 * Provides the same types as Loom but for cooperative scheduling.
 * All functions take a 'cont' parameter for workflow orchestration support.
 */
object CoopTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val promiseType = MezzeType.TypeApplication("Promise", listOf(MezzeType.VarType(TypeVar("a"))))
        val contType = MezzeType.VarType(TypeVar("Continuation"))

        // __native_coop_spawn: { f: {} -> 'a, cont: Continuation } -> Promise 'a
        context["__native_coop_spawn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "cont" to contType
            )),
            promiseType
        )

        // __native_coop_await: { p: Promise 'a, cont: Continuation } -> 'a
        context["__native_coop_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType,
                "cont" to contType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_coop_race: { ps: [Promise 'a], cont: Continuation } -> 'a
        context["__native_coop_race"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType),
                "cont" to contType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_coop_all: { ps: [Promise 'a], cont: Continuation } -> ['a]
        context["__native_coop_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType),
                "cont" to contType
            )),
            MezzeType.ArrayType(MezzeType.VarType(TypeVar("a")))
        )

        // __native_coop_timeout: { ms: Int, p: Promise 'a, cont: Continuation } -> Option 'a
        val optionType = MezzeType.TypeApplication("Option", listOf(MezzeType.VarType(TypeVar("a"))))
        context["__native_coop_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType,
                "p" to promiseType,
                "cont" to contType
            )),
            optionType
        )

        // __native_coop_sleep: { ms: Int, cont: Continuation } -> {}
        context["__native_coop_sleep"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ms" to MezzeType.IntType,
                "cont" to contType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_coop_is_done: { p: Promise 'a } -> Bool
        context["__native_coop_is_done"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "p" to promiseType
            )),
            MezzeType.BooleanType
        )

        // === Structured Concurrency ===

        // __native_coop_join: {left: {} -> 'a, right: {} -> 'b, cont: Continuation} -> {left: 'a, right: 'b}
        context["__native_coop_join"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "left" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "right" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "cont" to contType
            )),
            MezzeType.RecordType(mapOf(
                "left" to MezzeType.VarType(TypeVar("a")),
                "right" to MezzeType.VarType(TypeVar("b"))
            ))
        )

        // __native_coop_join3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c, cont: Continuation} -> {a: 'a, b: 'b, c: 'c}
        context["__native_coop_join3"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "b" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "c" to MezzeType.FunctionType(
                    MezzeType.RecordType(emptyMap()),
                    MezzeType.VarType(TypeVar("c"))
                ),
                "cont" to contType
            )),
            MezzeType.RecordType(mapOf(
                "a" to MezzeType.VarType(TypeVar("a")),
                "b" to MezzeType.VarType(TypeVar("b")),
                "c" to MezzeType.VarType(TypeVar("c"))
            ))
        )

        // === Collection Operations ===

        // __native_coop_map: {xs: ['a], f: {x: 'a} -> 'b, cont: Continuation} -> ['b]
        context["__native_coop_map"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.VarType(TypeVar("b"))
                ),
                "cont" to contType
            )),
            MezzeType.ArrayType(MezzeType.VarType(TypeVar("b")))
        )

        // __native_coop_for_each: {xs: ['a], f: {x: 'a} -> {}, cont: Continuation} -> {}
        context["__native_coop_for_each"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.RecordType(emptyMap())
                ),
                "cont" to contType
            )),
            MezzeType.RecordType(emptyMap())
        )

        // __native_coop_filter: {xs: ['a], f: {x: 'a} -> Bool, cont: Continuation} -> ['a]
        context["__native_coop_filter"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "xs" to MezzeType.ArrayType(MezzeType.VarType(TypeVar("a"))),
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("x" to MezzeType.VarType(TypeVar("a")))),
                    MezzeType.BooleanType
                ),
                "cont" to contType
            )),
            MezzeType.ArrayType(MezzeType.VarType(TypeVar("a")))
        )

        // === Error Handling ===

        // __native_coop_any: {ps: [Promise 'a], cont: Continuation} -> 'a
        context["__native_coop_any"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType),
                "cont" to contType
            )),
            MezzeType.VarType(TypeVar("a"))
        )

        // __native_coop_settle: {ps: [Promise 'a], cont: Continuation} -> [Result 'a AsyncError]
        val asyncErrorType = MezzeType.VarType(TypeVar("AsyncError"))
        val resultType = MezzeType.TypeApplication("Result", listOf(
            MezzeType.VarType(TypeVar("a")),
            asyncErrorType
        ))
        context["__native_coop_settle"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "ps" to MezzeType.ArrayType(promiseType),
                "cont" to contType
            )),
            MezzeType.ArrayType(resultType)
        )

        // === Structured Concurrency Scopes ===

        // Scope type for Coop
        val scopeType = MezzeType.TypeApplication("Scope", listOf(MezzeType.VarType(TypeVar("a"))))

        // __native_coop_scoped: {f: {scope: Scope 'a} -> 'a, cont: Continuation} -> 'a
        context["__native_coop_scoped"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "f" to MezzeType.FunctionType(
                    MezzeType.RecordType(mapOf("scope" to scopeType)),
                    MezzeType.VarType(TypeVar("a"))
                ),
                "cont" to contType
            )),
            MezzeType.VarType(TypeVar("a"))
        )
    }
}
