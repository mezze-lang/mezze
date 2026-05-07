package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for CountDownLatch operations.
 *
 * A latch allows threads to wait until a count reaches zero.
 */
object LatchTypes {

    /**
     * Register all latch native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        val latchType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Latch", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())

        // __native_latch_new: {count: Int} -> Latch
        context["__native_latch_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("count" to MezzeType.IntType)),
            latchType
        )

        // __native_latch_count_down: {latch: Latch} -> {}
        context["__native_latch_count_down"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("latch" to latchType)),
            unitType
        )

        // __native_latch_await: {latch: Latch} -> {}
        context["__native_latch_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("latch" to latchType)),
            unitType
        )

        // __native_latch_await_timeout: {latch: Latch, ms: Int} -> Bool
        context["__native_latch_await_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "latch" to latchType,
                "ms" to MezzeType.IntType
            )),
            MezzeType.BoolType
        )

        // __native_latch_get_count: {latch: Latch} -> Int
        context["__native_latch_get_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("latch" to latchType)),
            MezzeType.IntType
        )
    }
}
