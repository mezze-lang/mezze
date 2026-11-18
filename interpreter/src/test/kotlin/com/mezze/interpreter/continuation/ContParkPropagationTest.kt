package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The first vertical slice of continuations-and-swappable-async, end to end in
 * real compiled Mezze: a performer op body calls `cont.park`, the op body's root
 * suspends, the suspension propagates through every non-tail frame between the
 * park and the perform boundary, the boundary resumes the op body at the park
 * site with a `ParkOutcome`, and every suspended frame finishes its own work.
 *
 * Before this, `BdslYieldComposesAcrossRootsTest` pinned the failure: the outer
 * frame received the inner continuation AS A NUMBER. Here `inner` adds 1 and
 * `outer` adds 100 AFTER their calls, so both tails must run for 108.
 *
 * The park's predicate is `{} -> True`, so the machine answers it on its next
 * turn; this test is about the mechanism, not policy. (An EMPTY wait set,
 * `fs = []`, is unsatisfiable and is a deadlock — asserted in
 * `CoopAsyncSchedulerTest`.)
 */
class ContParkPropagationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val parker = """
        use std::effects::async::{ ParkOn, WaitSet, ParkOutcome }
        effect Wait where { wait: {} -> Int <Wait>, }
        impl Wait for Parker where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          wait = {} -> match cont.park { on = OnPredicates { fs = [{} -> True] }, set = WaitDefault } is
            ParkReady { which } -> 7 + which
            ParkCancelled { reason } -> 0 - 1,
        }
    """

    @Test
    fun `a park suspends through two non-tail frames and both tails run`() = assertEquals(
        108L,
        eval(
            """
            $parker
            let inner = {} -> Wait.wait {} + 1
            let outer = {} -> inner {} + 100
            perform Parker in outer {}
            """,
        ),
    )

    @Test
    fun `the op body continues after the park with the outcome`() = assertEquals(
        7L,
        eval(
            """
            $parker
            perform Parker in Wait.wait {}
            """,
        ),
    )

    @Test
    fun `two parks in one body each resume in order`() = assertEquals(
        "7,7",
        eval(
            """
            $parker
            perform Parker in do
              let a = Wait.wait {}
              let b = Wait.wait {}
              "${'$'}(a.to_str {}),${'$'}(b.to_str {})"
            """,
        ),
    )

    @Test
    fun `a performer that never mentions cont is untouched`() = assertEquals(
        43L,
        eval(
            """
            effect Ask where { ask: {} -> Int <Ask>, }
            impl Ask for Fixed where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              ask = {} -> 42,
            }
            let f = {} -> Ask.ask {} + 1
            perform Fixed in f {}
            """,
        ),
    )
}
