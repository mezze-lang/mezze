package com.mezze.interpreter.standalone

import com.oracle.truffle.api.debug.Breakpoint
import com.oracle.truffle.api.debug.Debugger
import com.oracle.truffle.api.debug.DebuggerSession
import com.oracle.truffle.api.debug.SuspendedEvent
import org.graalvm.polyglot.Engine
import java.io.File
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * [DapDebuggerBridge] backed by GraalVM's `Debugger` engine. This is where all
 * real debugging happens — `session.install(breakpoint)`,
 * `event.prepareStepOver()`, stack frames / scopes / values off the parked
 * `SuspendedEvent`. The DAP protocol layer ([MezzeDapServer]) never touches the
 * Debugger directly; it goes through this bridge.
 *
 * ## Thread model (park/resume)
 *
 * The program runs on the eval thread (RuntimeServer's LOAD worker). GraalVM
 * invokes `onSuspend(event)` ON THAT THREAD when a breakpoint/step hits. We:
 *   1. stash the event (it's only valid inside the callback),
 *   2. send a DAP `stopped` event to the client,
 *   3. BLOCK the eval thread on a SynchronousQueue, waiting for the next step
 *      command from the DAP thread,
 *   4. when `resume(kind)` arrives from the DAP thread, apply the corresponding
 *      `prepareStep*`/`prepareContinue` on the event (still on the eval thread,
 *      via the queue handoff) and return from the callback — the program runs on.
 *
 * `SuspendedEvent` and its frames/scopes are only valid inside the callback, so
 * stackTrace/scopes/variables/evaluate must be answered while parked — which is
 * exactly when the DAP client asks for them (between `stopped` and the next
 * `continue`). Those bridge methods read the stashed event; they're called from
 * the DAP thread but only touch read-only snapshots we capture, plus the
 * event's frame APIs which GraalVM permits from another thread while suspended.
 */
class GraalDapBridge(
    engine: Engine,
    private val log: (String) -> Unit = {},
) : DapDebuggerBridge {

    private val debugger: Debugger = Debugger.find(engine)
    private var session: DebuggerSession? = null

    /** The event we're currently parked on (null when running). */
    private val currentEvent = AtomicReference<SuspendedEvent?>(null)

    /**
     * Snapshot of the current suspension, captured ON THE EVAL THREAD inside
     * onSuspend. `SuspendedEvent`/`DebugStackFrame`/`DebugScope`/`DebugValue`
     * are only valid on the event's own thread, so the DAP thread reads these
     * plain snapshots instead of touching the live event (→ "Illegal thread
     * access"). Null while running.
     */
    @Volatile private var snapshot: SuspendSnapshot? = null

    /** A per-suspension, thread-safe view of the stack + scopes + variables. */
    private class SuspendSnapshot(
        val frames: List<FrameSnapshot>,
    )
    private class FrameSnapshot(
        val id: Int,
        val name: String,
        val line: Int,
        val column: Int,
        val sourcePath: String?,
    )

    /** Handoff of the next step command from the DAP thread to the eval thread. */
    private val stepQueue = SynchronousQueue<DapDebuggerBridge.StepKind>()

    /** Set by MezzeDapServer so the bridge can push `stopped`/`continued`/`output`. */
    @Volatile var server: MezzeDapServer? = null

    /** If true, suspend on the first statement (stopOnEntry). */
    @Volatile var stopOnEntry: Boolean = false

    private var started = false

    /** Start the debug session. Call once, before the program runs. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        session = debugger.startSession { event -> onSuspend(event) }
        if (stopOnEntry) session?.suspendNextExecution()
        log("[dap] debugger session started (stopOnEntry=$stopOnEntry)")
    }

    private fun onSuspend(event: SuspendedEvent) {
        currentEvent.set(event)
        // Snapshot the stack ON THIS (eval) thread — the DAP thread can't touch
        // the live event's frames.
        snapshot = captureSnapshot(event)
        val line = event.sourceSection?.startLine ?: -1
        log("[dap] suspended at line $line")
        // Reason: entry vs breakpoint vs step. Good enough: report "breakpoint"
        // when a breakpoint matched, else "step"/"entry".
        val reason = when {
            event.breakpoints.isNotEmpty() -> "breakpoint"
            else -> "step"
        }
        server?.sendEvent("stopped", mapOf(
            "reason" to reason,
            "threadId" to 1,
            "allThreadsStopped" to true,
        ))
        // Park until the DAP thread hands us a step command.
        val kind = try {
            stepQueue.take()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            DapDebuggerBridge.StepKind.CONTINUE
        }
        // Apply the step ON THE EVAL THREAD (here, inside the callback).
        when (kind) {
            DapDebuggerBridge.StepKind.CONTINUE -> event.prepareContinue()
            DapDebuggerBridge.StepKind.OVER -> event.prepareStepOver(1)
            DapDebuggerBridge.StepKind.INTO -> event.prepareStepInto(1)
            DapDebuggerBridge.StepKind.OUT -> event.prepareStepOut(1)
        }
        currentEvent.set(null)
        snapshot = null
    }

    /** Capture a thread-safe view of the suspended stack (on the eval thread). */
    private fun captureSnapshot(event: SuspendedEvent): SuspendSnapshot {
        val frames = mutableListOf<FrameSnapshot>()
        var id = 0
        for (frame in event.stackFrames) {
            val sec = frame.sourceSection
            val name = try { frame.name ?: "<anonymous>" } catch (e: Exception) { "<frame>" }
            frames.add(FrameSnapshot(
                id = id,
                name = name,
                line = sec?.startLine ?: 0,
                column = sec?.startColumn ?: 0,
                sourcePath = sec?.source?.path,
            ))
            id++
        }
        return SuspendSnapshot(frames)
    }

    // ─── DapDebuggerBridge ───────────────────────────────────────────────

    override fun setBreakpoints(args: Map<String, Any?>): Map<String, Any?> {
        val session = this.session ?: run { start(); this.session!! }
        @Suppress("UNCHECKED_CAST")
        val source = args["source"] as? Map<String, Any?>
        val path = source?.get("path") as? String
        @Suppress("UNCHECKED_CAST")
        val bps = (args["breakpoints"] as? List<Map<String, Any?>>) ?: emptyList()
        if (path == null) return mapOf("breakpoints" to bps.map { mapOf("verified" to false) })

        // Canonicalize so the breakpoint URI matches GraalVM's Source URI.
        // GraalVM canonicalizes a file Source's path (resolving symlinks like
        // macOS /var → /private/var) and yields `file:///…`; a bare
        // File(path).toURI() gives `file:/…` on the un-resolved path, which
        // WON'T match and the breakpoint never resolves. canonicalFile fixes
        // both the symlink and the authority-slash form.
        val uri = try { File(path).canonicalFile.toURI() } catch (e: Exception) { File(path).toURI() }
        val results = bps.map { bp ->
            val line = (bp["line"] as? Number)?.toInt() ?: return@map mapOf("verified" to false)
            try {
                val breakpoint = Breakpoint.newBuilder(uri).lineIs(line).build()
                session.install(breakpoint)
                mapOf("verified" to breakpoint.isResolved, "line" to line)
            } catch (e: Exception) {
                log("[dap] setBreakpoint line $line failed: ${e.message}")
                mapOf("verified" to false, "line" to line)
            }
        }
        return mapOf("breakpoints" to results)
    }

    override fun stackTrace(): Map<String, Any?> {
        val snap = snapshot ?: return mapOf("stackFrames" to emptyList<Any>(), "totalFrames" to 0)
        val frames = snap.frames.map { f ->
            val entry = linkedMapOf<String, Any?>(
                "id" to f.id,
                "name" to f.name,
                "line" to f.line,
                "column" to f.column,
            )
            if (f.sourcePath != null) {
                entry["source"] = mapOf("path" to f.sourcePath, "name" to File(f.sourcePath).name)
            }
            entry
        }
        return mapOf("stackFrames" to frames, "totalFrames" to frames.size)
    }

    override fun scopes(args: Map<String, Any?>): Map<String, Any?> {
        // Phase 3 fills this in (frame.getScope chain). Handshake-safe stub.
        return mapOf("scopes" to emptyList<Any>())
    }

    override fun variables(args: Map<String, Any?>): Map<String, Any?> {
        return mapOf("variables" to emptyList<Any>())
    }

    override fun evaluate(args: Map<String, Any?>): Map<String, Any?> {
        return mapOf("result" to "", "variablesReference" to 0)
    }

    override fun resume(kind: DapDebuggerBridge.StepKind) {
        // Hand the step to the parked eval thread. If nothing is parked (e.g. a
        // spurious continue before any stop), this offer just no-ops.
        if (currentEvent.get() != null) {
            stepQueue.put(kind)
        }
    }

    override fun disconnect() {
        // Let the program finish: if parked, continue; then close the session.
        if (currentEvent.get() != null) {
            try { stepQueue.put(DapDebuggerBridge.StepKind.CONTINUE) } catch (_: Exception) {}
        }
        try { session?.close() } catch (_: Exception) {}
    }
}
