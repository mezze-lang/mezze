package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for Coop (cooperative) async native functions.
 *
 * Provides the same types as Loom but for cooperative scheduling.
 * All functions take a 'cont' parameter for workflow orchestration support.
 */
object CoopTypes {

    fun registerAll(context: MutableMap<String, FunkType>) {
        val promiseType = FunkType.TypeApplication("Promise", listOf(FunkType.VarType(TypeVar("a"))))
        val contType = FunkType.VarType(TypeVar("Continuation"))

        // __native_coop_spawn: { f: {} -> 'a, cont: Continuation } -> Promise 'a
        context["__native_coop_spawn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "cont" to contType
            )),
            promiseType
        )

        // __native_coop_await: { p: Promise 'a, cont: Continuation } -> 'a
        context["__native_coop_await"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType,
                "cont" to contType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_coop_race: { ps: [Promise 'a], cont: Continuation } -> 'a
        context["__native_coop_race"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType),
                "cont" to contType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_coop_all: { ps: [Promise 'a], cont: Continuation } -> ['a]
        context["__native_coop_all"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType),
                "cont" to contType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        // __native_coop_timeout: { ms: Int, p: Promise 'a, cont: Continuation } -> Option 'a
        val optionType = FunkType.TypeApplication("Option", listOf(FunkType.VarType(TypeVar("a"))))
        context["__native_coop_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ms" to FunkType.IntType,
                "p" to promiseType,
                "cont" to contType
            )),
            optionType
        )

        // __native_coop_sleep: { ms: Int, cont: Continuation } -> {}
        context["__native_coop_sleep"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ms" to FunkType.IntType,
                "cont" to contType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_coop_is_done: { p: Promise 'a } -> Bool
        context["__native_coop_is_done"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType
            )),
            FunkType.BooleanType
        )

        // Legacy functions for compatibility
        // __native_coop_wrap_promise: { f: {} -> 'a } -> Promise 'a
        context["__native_coop_wrap_promise"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                )
            )),
            promiseType
        )

        // __native_coop_unwrap_promise: { p: Promise 'a } -> { f: {} -> 'a }
        context["__native_coop_unwrap_promise"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "p" to promiseType
            )),
            FunkType.RecordType(mapOf(
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                )
            ))
        )

        // === Structured Concurrency ===

        // __native_coop_join: {left: {} -> 'a, right: {} -> 'b, cont: Continuation} -> {left: 'a, right: 'b}
        context["__native_coop_join"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "left" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "right" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                ),
                "cont" to contType
            )),
            FunkType.RecordType(mapOf(
                "left" to FunkType.VarType(TypeVar("a")),
                "right" to FunkType.VarType(TypeVar("b"))
            ))
        )

        // __native_coop_join3: {a: {} -> 'a, b: {} -> 'b, c: {} -> 'c, cont: Continuation} -> {a: 'a, b: 'b, c: 'c}
        context["__native_coop_join3"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "a" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("a"))
                ),
                "b" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("b"))
                ),
                "c" to FunkType.FunctionType(
                    FunkType.RecordType(emptyMap()),
                    FunkType.VarType(TypeVar("c"))
                ),
                "cont" to contType
            )),
            FunkType.RecordType(mapOf(
                "a" to FunkType.VarType(TypeVar("a")),
                "b" to FunkType.VarType(TypeVar("b")),
                "c" to FunkType.VarType(TypeVar("c"))
            ))
        )

        // === Collection Operations ===

        // __native_coop_map: {xs: ['a], f: {x: 'a} -> 'b, cont: Continuation} -> ['b]
        context["__native_coop_map"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.VarType(TypeVar("b"))
                ),
                "cont" to contType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("b")))
        )

        // __native_coop_for_each: {xs: ['a], f: {x: 'a} -> {}, cont: Continuation} -> {}
        context["__native_coop_for_each"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.RecordType(emptyMap())
                ),
                "cont" to contType
            )),
            FunkType.RecordType(emptyMap())
        )

        // __native_coop_filter: {xs: ['a], f: {x: 'a} -> Bool, cont: Continuation} -> ['a]
        context["__native_coop_filter"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "xs" to FunkType.ArrayType(FunkType.VarType(TypeVar("a"))),
                "f" to FunkType.FunctionType(
                    FunkType.RecordType(mapOf("x" to FunkType.VarType(TypeVar("a")))),
                    FunkType.BooleanType
                ),
                "cont" to contType
            )),
            FunkType.ArrayType(FunkType.VarType(TypeVar("a")))
        )

        // === Error Handling ===

        // __native_coop_any: {ps: [Promise 'a], cont: Continuation} -> 'a
        context["__native_coop_any"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType),
                "cont" to contType
            )),
            FunkType.VarType(TypeVar("a"))
        )

        // __native_coop_settle: {ps: [Promise 'a], cont: Continuation} -> [Result 'a AsyncError]
        val asyncErrorType = FunkType.VarType(TypeVar("AsyncError"))
        val resultType = FunkType.TypeApplication("Result", listOf(
            FunkType.VarType(TypeVar("a")),
            asyncErrorType
        ))
        context["__native_coop_settle"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "ps" to FunkType.ArrayType(promiseType),
                "cont" to contType
            )),
            FunkType.ArrayType(resultType)
        )
    }
}
