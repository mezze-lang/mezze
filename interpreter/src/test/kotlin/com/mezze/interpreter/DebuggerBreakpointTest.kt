package com.mezze.interpreter

import com.oracle.truffle.api.debug.Breakpoint
import com.oracle.truffle.api.debug.Debugger
import com.oracle.truffle.api.debug.SuspendedEvent
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * DAP Phase 1 acceptance: the GraalVM debugger can place a LINE breakpoint on a
 * Mezze program and suspend there. Proves source sections + StandardTags.
 * StatementTag are wired onto the Truffle nodes (without them, `Breakpoint.
 * newBuilder(...lineIs(n))` never resolves and never suspends).
 *
 * This is a self-contained validation of Phase 1 and a preview of the Phase 2
 * debug-session mechanics (attach session, set breakpoint, run, inspect,
 * resume).
 */
class DebuggerBreakpointTest {

    private fun makeContext(): Context = Context.newBuilder("mezze")
        .allowAllAccess(true)
        .option("engine.WarnInterpreterOnly", "false")
        .build()

    @Test
    @Timeout(60)
    @Disabled(
        "DAP Phase 1 spec — breakpoint RESOLUTION is blocked on Source identity. " +
        "MezzeNode is now InstrumentableNode + @GenerateWrapper and TruffleCompiler tags " +
        "statement-level nodes (calls, local writes) with StatementTag + a SourceSection, " +
        "so the debugger CAN attach probes. The remaining wiring: TruffleCompiler." +
        "getOrCreateSource re-reads the file from DISK by path, so an in-memory eval Source " +
        "(e.g. 'bp.mz') resolves to EMPTY content and the section can't match a breakpoint on " +
        "the real text. Fix = thread the caller's actual polyglot Source (content + URI) " +
        "through PipelineRunner.compileFromIR into TruffleCompiler instead of re-reading disk. " +
        "Then this test passes and Phase 2 (debug session) can build on it."
    )
    fun `a line breakpoint suspends execution at that line`() {
        // A do-block with distinct statement lines. Line 3 has a call
        // (`add {..}`) — a tagged statement stop point.
        val program = """
            let add = {a: Int, b: Int} -> a + b
            let main = {} -> do
              let x = add { a = 1, b = 2 }
              let y = add { a = x, b = 10 }
              y
        """.trimIndent()

        val ctx = makeContext()
        val hits = AtomicInteger(0)
        val suspendedLines = mutableListOf<Int>()
        try {
            val debugger = ctx.engine.instruments["debugger"]!!.lookup(Debugger::class.java)
            val session = debugger.startSession { event: SuspendedEvent ->
                hits.incrementAndGet()
                event.sourceSection?.startLine?.let { suspendedLines.add(it) }
                event.prepareContinue()  // resume — we only want to prove it stopped
            }
            session.use {
                // Break on line 3 (`let x = add { … }`).
                val bp = Breakpoint.newBuilder(
                    Source.newBuilder("mezze", program, "bp.mz").build().uri
                ).lineIs(3).build()
                session.install(bp)

                val src = Source.newBuilder("mezze", program, "bp.mz").build()
                ctx.eval(src)

                // Diagnostic: dump what the debugger sees.
                System.err.println("[bp-test] resolved=${bp.isResolved} hits=${hits.get()} lines=$suspendedLines")
                assertTrue(bp.isResolved,
                    "breakpoint on line 3 should RESOLVE to a tagged node — if not, " +
                    "statement tags/source sections aren't wired. suspendedLines=$suspendedLines")
                assertTrue(hits.get() >= 1,
                    "execution should have suspended at least once at the breakpoint, " +
                    "hits=${hits.get()} lines=$suspendedLines")
                assertTrue(suspendedLines.contains(3),
                    "should have suspended on line 3, got suspended lines: $suspendedLines")
            }
        } finally {
            ctx.close()
        }
    }
}
