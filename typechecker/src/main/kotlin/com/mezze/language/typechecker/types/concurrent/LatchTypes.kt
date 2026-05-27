package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for CountDownLatch operations.
 *
 * Every native carries `<Async>` (post-#170).
 *
 * A latch allows threads to wait until a count reaches zero.
 */
object LatchTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val latchType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Latch", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_latch_new: {count: Int} -> Latch <Async>
        context["__native_latch_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("count" to MezzeType.IntType)),
            latchType,
            effects = asyncEffect
        )

        // __native_latch_count_down: {latch: Latch} -> {} <Async>
        context["__native_latch_count_down"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("latch" to latchType)),
            unitType,
            effects = asyncEffect
        )

        // __native_latch_await: {latch: Latch} -> {} <Async>
        context["__native_latch_await"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("latch" to latchType)),
            unitType,
            effects = asyncEffect
        )

        // __native_latch_await_timeout: {latch: Latch, ms: Int} -> Bool <Async>
        context["__native_latch_await_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "latch" to latchType,
                "ms" to MezzeType.IntType
            )),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_latch_get_count: {latch: Latch} -> Int <Async>
        context["__native_latch_get_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("latch" to latchType)),
            MezzeType.IntType,
            effects = asyncEffect
        )
    }
}
