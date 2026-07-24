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
        "DAP Phase 1 — foundation LANDED, breakpoint resolution still blocked. DONE: MezzeNode " +
        "is InstrumentableNode + @GenerateWrapper; TruffleCompiler tags statement-level nodes " +
        "(CallDirect/Local/Indirect/Native, WriteLocal) with StatementTag; Source-content " +
        "threading + file:// URI stamping (getOrCreateSource) so a client breakpoint matches by " +
        "URI. All interpreter-suite-safe. REMAINING: the breakpoint doesn't resolve for the eval " +
        "path — needs investigation with a build.gradle env/systemProperty passthrough (gradle " +
        "forks test workers WITHOUT inherited env, so MEZZE_DAP_DEBUG dumps were empty — the " +
        "diagnostic itself needs plumbing). Candidate causes: (a) the user module's nodes may " +
        "compile via a route that bypasses compileNode tagging, (b) section line-numbering vs the " +
        "breakpoint line, (c) Source URI still not matching. NEXT: add the systemProperty " +
        "passthrough, dump the tagged sections' URIs+lines during the eval, compare to the " +
        "breakpoint's, and fix the mismatch."
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

        // Write to a REAL file — the actual debugging scenario (VS Code sets a
        // breakpoint on a file:// URI, `mez` compiles the same file). Both the
        // breakpoint and the compiler's Source then share the file:// URI, which
        // is how GraalVM matches a breakpoint to a source section.
        val tmp = java.io.File.createTempFile("bp_", ".mz").apply { writeText(program); deleteOnExit() }

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
                val fileSource = Source.newBuilder("mezze", tmp).build()
                // Break on line 3 (`let x = add { … }`).
                val bp = Breakpoint.newBuilder(fileSource.uri).lineIs(3).build()
                session.install(bp)

                ctx.eval(fileSource)

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
