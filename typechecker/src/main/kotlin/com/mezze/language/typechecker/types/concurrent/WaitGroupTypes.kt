package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*
import com.mezze.language.typechecker.util.WellKnownTypeApp

/**
 * Type definitions for WaitGroup operations.
 *
 * Every native carries `<Async>` (post-#170).
 *
 * A WaitGroup allows waiting on a dynamic collection of tasks,
 * where the count can be incremented as new tasks are added.
 */
object WaitGroupTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        val wgType = WellKnownTypeApp.of("WaitGroup", emptyList())

        // __native_waitgroup_new: {} -> WaitGroup <Async>
        fn("__native_waitgroup_new") {
            returns(wgType)
            effects("Async")
        }

        // __native_waitgroup_add: {wg: WaitGroup, delta: Int} -> {} <Async>
        fn("__native_waitgroup_add") {
            param("wg" to wgType, "delta" to int)
            returns(unit)
            effects("Async")
        }

        // __native_waitgroup_done: {wg: WaitGroup} -> {} <Async>
        fn("__native_waitgroup_done") {
            param("wg" to wgType)
            returns(unit)
            effects("Async")
        }

        // __native_waitgroup_wait: {wg: WaitGroup} -> {} <Async>
        fn("__native_waitgroup_wait") {
            param("wg" to wgType)
            returns(unit)
            effects("Async")
        }

        // __native_waitgroup_wait_timeout: {wg: WaitGroup, ms: Int} -> Bool <Async>
        fn("__native_waitgroup_wait_timeout") {
            param("wg" to wgType, "ms" to int)
            returns(bool)
            effects("Async")
        }

        // __native_waitgroup_get_count: {wg: WaitGroup} -> Int <Async>
        fn("__native_waitgroup_get_count") {
            param("wg" to wgType)
            returns(int)
            effects("Async")
        }
    }
}
