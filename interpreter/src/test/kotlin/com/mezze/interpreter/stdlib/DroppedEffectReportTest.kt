package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A spawned task's unhandled effect must not vanish in silence.
 *
 * A task that performs an operation no handler can discharge does not fail. It
 * is marked COMPLETED with the op stashed, and `await` re-raises it in the
 * awaiting context — which is a feature: a spawned task may perform the
 * parent's effects. The hole is what happens when nobody awaits. Nothing else
 * collects the op, so the effect simply never happens.
 *
 * `Async.spawn`'s callback row is absorbed at the spawn, so the types say
 * nothing either: a program with no `Time` performer can spawn `sleep` and be
 * accepted. That is how a `with_timeout` flake got built — the sleeper never
 * slept, and `is_done` called it finished.
 *
 * This does not close the hole. It makes it audible, which is the part that was
 * missing: the effect still does not happen, but you are told.
 */
class DroppedEffectReportTest {

    private fun stderrOf(body: () -> Unit): String {
        val captured = java.io.ByteArrayOutputStream()
        val real = System.err
        System.setErr(java.io.PrintStream(captured, true))
        try {
            body()
        } finally {
            System.setErr(real)
        }
        return captured.toString()
    }

    @Test
    fun `a spawned effect nobody awaits is reported when the context closes`() {
        val err = stderrOf {
            MezzeTestHelper.run(
                """
                use std::effects::async::{ Async, LoomAsync, sleep, yield_now }
                use std::collections::seq
                perform LoomAsync in do
                  let p = Async.spawn { f = {} -> sleep { ms = 1000 } }
                  # Give the task a turn. Without this the main body returns and
                  # the context closes before the spawned thread has reached the
                  # op at all, so there is nothing yet to report. `yield_now`
                  # needs only Async — anything that waited on the promise would
                  # wait forever, since a task parked on an unhandled op reads
                  # as Pending and never settles.
                  seq::for_each { xs = seq::range { start = 0, end = 200 }, f = { x } -> yield_now {} }
                  7
                """.trimIndent(),
            )
        }
        assertTrue(
            err.contains("performed an effect that no handler could discharge"),
            "the dropped effect must be reported; stderr was:\n$err",
        )
        assertTrue(
            err.contains("(effects "),
            "the report must identify the effect — by hash, since that is what a " +
                "suspended task carries — so it can be found; stderr was:\n$err",
        )
    }

    @Test
    fun `awaiting the task collects the effect and says nothing`() {
        // The counterpart. The same spawn, awaited under a performer that CAN
        // discharge it, is not a dropped effect and must not be reported — a
        // diagnostic that fires on correct programs is worse than none.
        val err = stderrOf {
            MezzeTestHelper.run(
                """
                use std::effects::async::{ Async, LoomAsync, await, sleep }
                use std::effects::time::{ Time, SystemTime }
                perform SystemTime in perform LoomAsync in do
                  let p = Async.spawn { f = {} -> sleep { ms = 1 } }
                  match await { p = p } is
                    Ok { val } -> 1
                    Err { error = _ } -> 0
                """.trimIndent(),
            )
        }
        assertTrue(
            !err.contains("performed an effect that no handler could discharge"),
            "an awaited task's effect was collected; nothing to report. stderr was:\n$err",
        )
    }
}
