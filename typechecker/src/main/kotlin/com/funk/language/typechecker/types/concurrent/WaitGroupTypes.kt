package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType

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
    fun registerAll(context: MutableMap<String, FunkType>) {
        val wgType = FunkType.TypeApplication("WaitGroup", emptyList())
        val unitType = FunkType.RecordType(emptyMap())

        // __native_waitgroup_new: {} -> WaitGroup
        context["__native_waitgroup_new"] = FunkType.FunctionType(
            unitType,
            wgType
        )

        // __native_waitgroup_add: {wg: WaitGroup, delta: Int} -> {}
        context["__native_waitgroup_add"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "wg" to wgType,
                "delta" to FunkType.IntType
            )),
            unitType
        )

        // __native_waitgroup_done: {wg: WaitGroup} -> {}
        context["__native_waitgroup_done"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("wg" to wgType)),
            unitType
        )

        // __native_waitgroup_wait: {wg: WaitGroup} -> {}
        context["__native_waitgroup_wait"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("wg" to wgType)),
            unitType
        )

        // __native_waitgroup_wait_timeout: {wg: WaitGroup, ms: Int} -> Bool
        context["__native_waitgroup_wait_timeout"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "wg" to wgType,
                "ms" to FunkType.IntType
            )),
            FunkType.BooleanType
        )

        // __native_waitgroup_get_count: {wg: WaitGroup} -> Int
        context["__native_waitgroup_get_count"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("wg" to wgType)),
            FunkType.IntType
        )
    }
}
