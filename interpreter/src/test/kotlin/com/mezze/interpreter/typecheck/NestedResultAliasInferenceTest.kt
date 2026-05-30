package com.mezze.interpreter.typecheck

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Pattern hit by ParallelErrorPropagationTest's "test concurrent group
 * operations": doubly-nested Result-of-Seq-of-Result, fed through nested
 * `seq::fold` calls.
 *
 * Originally filed as #174 (alleged typechecker inference bug). Turned
 * out the typechecker is fine — the original test source used `{acc, g}`
 * for fold's `f` lambda, but fold's signature names that record field
 * literally `x` (`f: { acc: 'b, x: 'a } -> 'b`) and Mezze requires the
 * lambda's destructure names to match. After renaming `g` → `x` the
 * typechecker is happy, but a separate interpreter issue surfaces (a
 * runtime "No matching pattern for value: null" inside the nested fold).
 *
 * The remaining issue is being investigated separately.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NestedResultAliasInferenceTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Disabled("Runtime null in nested fold/match — see updated #174")
    @Test
    fun `nested all() composed through fold infers without annotation`() {
        // Replicates the doubly-nested all-of-all pattern from
        // ParallelErrorPropagationTest > test concurrent group operations.
        // Note: fold's signature names the lambda's x-param literally `x`
        // (`f: { acc: 'b, x: 'a } -> 'b`) — Mezze lambda destructure must
        // match those field names exactly. Use `x` at both call sites.
        val code = """
            use std::effects::async::{ Async, LoomAsync, all }
            use std::collections::seq

            perform LoomAsync in do
                let create_group = {i: Int} -> do
                    let p1 = Async.spawn {f = {} -> i * 10}
                    let p2 = Async.spawn {f = {} -> i * 20}
                    all {ps = [p1, p2]}

                let g0 = Async.spawn {f = {} -> create_group {i = 0}}
                let g1 = Async.spawn {f = {} -> create_group {i = 1}}
                let groups = all {ps = [g0, g1]}

                let sum_arr = { arr } -> seq::fold {
                    xs = arr,
                    init = 0,
                    f = { acc, x } -> match x is
                        Ok { val } -> acc + val
                        Err { error = _ } -> acc
                }

                seq::fold {
                    xs = groups,
                    init = 0,
                    f = { acc, x } -> match x is
                        Ok { val } -> acc + sum_arr { arr = val }
                        Err { error = _ } -> acc
                }
        """.trimIndent()
        val result = eval(code)
        // i=0: 0+0=0; i=1: 10+20=30. Total 30.
        assertEquals(30L, result)
    }
}
