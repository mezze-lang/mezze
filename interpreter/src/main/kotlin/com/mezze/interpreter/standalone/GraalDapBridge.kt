package com.mezze.interpreter.standalone

import com.oracle.truffle.api.debug.Breakpoint
import com.oracle.truffle.api.debug.Debugger
import com.oracle.truffle.api.debug.DebuggerSession
import com.oracle.truffle.api.debug.SuspendedEvent
import org.graalvm.polyglot.Engine
import java.io.File
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
 *   3. BLOCK the eval thread on a command queue, waiting for the next step
 *      command from the DAP thread (servicing `evaluate` tasks meanwhile —
 *      they must run on this thread too),
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

    /**
     * A per-suspension, thread-safe view of the stack + scopes + variables,
     * captured on the eval thread. `varsByRef` maps a DAP `variablesReference`
     * to the flat list of variables under it (a scope or a nested value's
     * properties) so `variables` requests answer from the snapshot.
     */
    private class SuspendSnapshot(
        val frames: List<FrameSnapshot>,
        /** frameId → its scopes (each with a variablesReference). */
        val scopesByFrame: Map<Int, List<ScopeSnapshot>>,
        /** variablesReference → the variables it contains. */
        val varsByRef: Map<Int, List<VarSnapshot>>,
    )
    private class FrameSnapshot(
        val id: Int,
        val name: String,
        val line: Int,
        val column: Int,
        val sourcePath: String?,
    )
    private class ScopeSnapshot(
        val name: String,
        val variablesReference: Int,
    )
    private class VarSnapshot(
        val name: String,
        val value: String,
        /** 0 = leaf; else a variablesReference into `varsByRef` for expansion. */
        val variablesReference: Int,
    )

    /**
     * Command queue from the DAP thread to the parked eval thread. While
     * suspended, the eval thread loops on this queue: `Eval` tasks (watch /
     * hover `evaluate`) run `frame.eval` ON the eval thread (frames are
     * thread-bound) and signal a latch; a `Step` command breaks the loop and
     * resumes execution. Using one queue keeps all frame access on the correct
     * thread.
     */
    private val cmdQueue = java.util.concurrent.LinkedBlockingQueue<ParkCommand>()

    private sealed interface ParkCommand {
        data class Step(val kind: DapDebuggerBridge.StepKind) : ParkCommand
        class Eval(
            val expression: String,
            val result: java.util.concurrent.CompletableFuture<String>,
        ) : ParkCommand
    }

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
        // Park until the DAP thread sends a Step command. Service Eval tasks
        // in the meantime (they must run on this thread — frames are
        // thread-bound). Loop exits only on a Step.
        var kind = DapDebuggerBridge.StepKind.CONTINUE
        loop@ while (true) {
            val cmd = try {
                cmdQueue.take()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break@loop
            }
            when (cmd) {
                is ParkCommand.Step -> { kind = cmd.kind; break@loop }
                is ParkCommand.Eval -> {
                    val out = try {
                        event.topStackFrame?.eval(cmd.expression)?.toDisplayString() ?: "<no frame>"
                    } catch (e: Exception) {
                        "<eval error: ${e.message}>"
                    }
                    cmd.result.complete(out)
                }
            }
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

    /**
     * Capture a thread-safe view of the suspended stack + scopes + variables ON
     * THE EVAL THREAD. Assigns `variablesReference` handles: each scope gets
     * one; each expandable value (record / list / object with properties) gets
     * one so the client can drill in. Values are rendered via
     * `DebugValue.toDisplayString()`. Depth is bounded so a cyclic/huge object
     * graph can't blow up capture.
     */
    private fun captureSnapshot(event: SuspendedEvent): SuspendSnapshot {
        val frames = mutableListOf<FrameSnapshot>()
        val scopesByFrame = LinkedHashMap<Int, List<ScopeSnapshot>>()
        val varsByRef = LinkedHashMap<Int, List<VarSnapshot>>()
        var nextRef = 1

        var frameId = 0
        for (frame in event.stackFrames) {
            val sec = frame.sourceSection
            val fname = try { frame.name ?: "<anonymous>" } catch (e: Exception) { "<frame>" }
            frames.add(FrameSnapshot(
                id = frameId,
                name = fname,
                line = sec?.startLine ?: 0,
                column = sec?.startColumn ?: 0,
                sourcePath = sec?.source?.path,
            ))

            // Walk the scope chain for this frame (innermost → outermost). We
            // fold arguments + declared values of each Truffle scope into one
            // DAP "Locals" scope for the top scope, and label parents by name.
            val frameScopes = mutableListOf<ScopeSnapshot>()
            try {
                var scope = frame.scope
                var depth = 0
                while (scope != null && depth < 8) {
                    val vars = mutableListOf<VarSnapshot>()
                    fun addValue(dv: com.oracle.truffle.api.debug.DebugValue) {
                        if (!dv.isReadable) return
                        val name = dv.name ?: return
                        val (display, childRef) = renderValue(dv, varsByRef, { nextRef++ }, 0)
                        vars.add(VarSnapshot(name, display, childRef))
                    }
                    try { scope.arguments?.forEach(::addValue) } catch (_: Exception) {}
                    try { scope.declaredValues?.forEach(::addValue) } catch (_: Exception) {}
                    if (vars.isNotEmpty()) {
                        val ref = nextRef++
                        varsByRef[ref] = vars
                        val scopeName = if (depth == 0) "Locals" else (scope.name ?: "Scope")
                        frameScopes.add(ScopeSnapshot(scopeName, ref))
                    }
                    scope = try { scope.parent } catch (e: Exception) { null }
                    depth++
                }
            } catch (e: Exception) {
                log("[dap] scope capture failed for frame $frameId: ${e.message}")
            }
            scopesByFrame[frameId] = frameScopes
            frameId++
        }
        return SuspendSnapshot(frames, scopesByFrame, varsByRef)
    }

    /**
     * Render a DebugValue to a (displayString, childRef) pair. If the value has
     * properties (a record/object), assign a fresh ref and recursively capture
     * its children into [varsByRef] (depth-bounded). Leaves get childRef 0.
     */
    private fun renderValue(
        dv: com.oracle.truffle.api.debug.DebugValue,
        varsByRef: MutableMap<Int, List<VarSnapshot>>,
        freshRef: () -> Int,
        depth: Int,
    ): Pair<String, Int> {
        val display = try { dv.toDisplayString() } catch (e: Exception) { "<error>" }
        if (depth >= 4) return display to 0
        val props: Collection<com.oracle.truffle.api.debug.DebugValue>? = try {
            if (dv.isReadable && dv.properties != null) dv.properties else null
        } catch (e: Exception) { null }
        if (props.isNullOrEmpty()) return display to 0
        val ref = freshRef()
        val children = props.mapNotNull { p ->
            if (!p.isReadable) return@mapNotNull null
            val pname = p.name ?: return@mapNotNull null
            val (pdisp, pref) = renderValue(p, varsByRef, freshRef, depth + 1)
            VarSnapshot(pname, pdisp, pref)
        }
        varsByRef[ref] = children
        return display to ref
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
        val snap = snapshot ?: return mapOf("scopes" to emptyList<Any>())
        val frameId = (args["frameId"] as? Number)?.toInt() ?: 0
        val scopes = (snap.scopesByFrame[frameId] ?: emptyList()).map { s ->
            mapOf(
                "name" to s.name,
                "variablesReference" to s.variablesReference,
                "expensive" to false,
            )
        }
        return mapOf("scopes" to scopes)
    }

    override fun variables(args: Map<String, Any?>): Map<String, Any?> {
        val snap = snapshot ?: return mapOf("variables" to emptyList<Any>())
        val ref = (args["variablesReference"] as? Number)?.toInt() ?: return mapOf("variables" to emptyList<Any>())
        val vars = (snap.varsByRef[ref] ?: emptyList()).map { v ->
            mapOf(
                "name" to v.name,
                "value" to v.value,
                "variablesReference" to v.variablesReference,
            )
        }
        return mapOf("variables" to vars)
    }

    override fun evaluate(args: Map<String, Any?>): Map<String, Any?> {
        val expr = args["expression"] as? String ?: return mapOf("result" to "", "variablesReference" to 0)
        // Only meaningful while suspended (watch / hover eval). Hand the
        // expression to the parked eval thread and wait for the result.
        if (currentEvent.get() == null) {
            return mapOf("result" to "<not paused>", "variablesReference" to 0)
        }
        val future = java.util.concurrent.CompletableFuture<String>()
        cmdQueue.put(ParkCommand.Eval(expr, future))
        val result = try {
            future.get(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            "<eval timeout>"
        }
        return mapOf("result" to result, "variablesReference" to 0)
    }

    override fun resume(kind: DapDebuggerBridge.StepKind) {
        // Hand the step to the parked eval thread. If nothing is parked (e.g. a
        // spurious continue before any stop), this just queues harmlessly.
        if (currentEvent.get() != null) {
            cmdQueue.put(ParkCommand.Step(kind))
        }
    }

    override fun disconnect() {
        // Let the program finish: if parked, continue; then close the session.
        if (currentEvent.get() != null) {
            try { cmdQueue.put(ParkCommand.Step(DapDebuggerBridge.StepKind.CONTINUE)) } catch (_: Exception) {}
        }
        try { session?.close() } catch (_: Exception) {}
    }
}
