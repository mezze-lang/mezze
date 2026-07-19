package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Minimal repro for the let-bound-match-shifts-slots bug blocking #239
 * four-policy semantics. See:
 *   memory/project_239_match_user_adt_bug.md
 */
@Execution(ExecutionMode.SAME_THREAD)
class MatchSlotBugTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `let-bound match on ADT inside scoped body keeps sc valid`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, all, scope_spawn, scoped }
            use std::result

            type AB = | A, B |

            let main = {} -> do
              scoped { f = { sc } -> do
                let dummy = match A is
                  A -> 1
                  B -> 2
                let p = scope_spawn { sc = sc, f = {} -> 7 }
                let results = all { ps = [p] }
                match results is
                  [Ok { val = v }] -> v + dummy
                  _ -> 0 - 1
              }

            perform LoomAsync in main {}
        """
        assertEquals(8L, eval(source))
    }
}
