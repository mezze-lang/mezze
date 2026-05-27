package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for WaitGroup operations.
 *
 * Every native carries `<Async>` (post-#170).
 *
 * A WaitGroup allows waiting on a dynamic collection of tasks,
 * where the count can be incremented as new tasks are added.
 */
object WaitGroupTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val wgType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("WaitGroup", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_waitgroup_new: {} -> WaitGroup <Async>
        context["__native_waitgroup_new"] = MezzeType.FunctionType(
            unitType,
            wgType,
            effects = asyncEffect
        )

        // __native_waitgroup_add: {wg: WaitGroup, delta: Int} -> {} <Async>
        context["__native_waitgroup_add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "wg" to wgType,
                "delta" to MezzeType.IntType
            )),
            unitType,
            effects = asyncEffect
        )

        // __native_waitgroup_done: {wg: WaitGroup} -> {} <Async>
        context["__native_waitgroup_done"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("wg" to wgType)),
            unitType,
            effects = asyncEffect
        )

        // __native_waitgroup_wait: {wg: WaitGroup} -> {} <Async>
        context["__native_waitgroup_wait"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("wg" to wgType)),
            unitType,
            effects = asyncEffect
        )

        // __native_waitgroup_wait_timeout: {wg: WaitGroup, ms: Int} -> Bool <Async>
        context["__native_waitgroup_wait_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "wg" to wgType,
                "ms" to MezzeType.IntType
            )),
            MezzeType.BoolType,
            effects = asyncEffect
        )

        // __native_waitgroup_get_count: {wg: WaitGroup} -> Int <Async>
        context["__native_waitgroup_get_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("wg" to wgType)),
            MezzeType.IntType,
            effects = asyncEffect
        )
    }
}
