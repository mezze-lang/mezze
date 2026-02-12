package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType

/**
 * Type definitions for CountDownLatch operations.
 *
 * A latch allows threads to wait until a count reaches zero.
 */
object LatchTypes {

    /**
     * Register all latch native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        val latchType = FunkType.TypeApplication("Latch", emptyList())
        val unitType = FunkType.RecordType(emptyMap())

        // __native_latch_new: {count: Int} -> Latch
        context["__native_latch_new"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("count" to FunkType.IntType)),
            latchType
        )

        // __native_latch_count_down: {latch: Latch} -> {}
        context["__native_latch_count_down"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("latch" to latchType)),
            unitType
        )

        // __native_latch_await: {latch: Latch} -> {}
        context["__native_latch_await"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("latch" to latchType)),
            unitType
        )

        // __native_latch_await_timeout: {latch: Latch, ms: Int} -> Bool
        context["__native_latch_await_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "latch" to latchType,
                "ms" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        // __native_latch_get_count: {latch: Latch} -> Int
        context["__native_latch_get_count"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("latch" to latchType)),
            FunkType.IntType
        )
    }
}
