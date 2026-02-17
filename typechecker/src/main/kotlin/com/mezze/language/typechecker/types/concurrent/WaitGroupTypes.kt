package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType

/**
 * Type definitions for WaitGroup operations.
 *
 * A WaitGroup allows waiting on a dynamic collection of tasks,
 * where the count can be incremented as new tasks are added.
 */
object WaitGroupTypes {

    /**
     * Register all wait group native function types.
     */
    fun registerAll(context: MutableMap<String, MezzeType>) {
        val wgType = MezzeType.TypeApplication("WaitGroup", emptyList())
        val unitType = MezzeType.RecordType(emptyMap())

        // __native_waitgroup_new: {} -> WaitGroup
        context["__native_waitgroup_new"] = MezzeType.FunctionType(
            unitType,
            wgType
        )

        // __native_waitgroup_add: {wg: WaitGroup, delta: Int} -> {}
        context["__native_waitgroup_add"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "wg" to wgType,
                "delta" to MezzeType.IntType
            )),
            unitType
        )

        // __native_waitgroup_done: {wg: WaitGroup} -> {}
        context["__native_waitgroup_done"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("wg" to wgType)),
            unitType
        )

        // __native_waitgroup_wait: {wg: WaitGroup} -> {}
        context["__native_waitgroup_wait"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("wg" to wgType)),
            unitType
        )

        // __native_waitgroup_wait_timeout: {wg: WaitGroup, ms: Int} -> Bool
        context["__native_waitgroup_wait_timeout"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "wg" to wgType,
                "ms" to MezzeType.IntType
            )),
            MezzeType.BooleanType
        )

        // __native_waitgroup_get_count: {wg: WaitGroup} -> Int
        context["__native_waitgroup_get_count"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("wg" to wgType)),
            MezzeType.IntType
        )
    }
}
