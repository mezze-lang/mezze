package com.mezze.interpreter.typecheck

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Minimal reproducer for the typechecker inference limitation hit by
 * ParallelErrorPropagationTest's "test concurrent group operations".
 *
 * Shape: `seq::fold` over a `Seq (Result (Seq (Result Int AsyncError)) AsyncError)`
 * — doubly-nested Result-of-Seq-of-Result with a structural variant alias
 * (`AsyncError = | AsyncFailed | AsyncCancelled | AsyncTimedOut |`) at both
 * nesting levels.
 *
 * The typechecker fails to unify the outer `xs: Seq 'a` parameter of `seq::fold`
 * against the inferred type, reporting both Result variants expanded as the
 * full alias carrier set.
 *
 * Expected once fixed: both tests below pass without annotation.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NestedResultAliasInferenceTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Disabled("Typechecker inference bug — tracked as #174")
    @Test
    fun `nested all() composed through fold infers without annotation`() {
        // Replicates the doubly-nested all-of-all pattern from
        // ParallelErrorPropagationTest > test concurrent group operations.
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
                    f = { acc, g } -> match g is
                        Ok { val } -> acc + sum_arr { arr = val }
                        Err { error = _ } -> acc
                }
        """.trimIndent()
        val result = eval(code)
        // i=0: 0+0=0; i=1: 10+20=30. Total 30.
        assertEquals(30L, result)
    }
}
