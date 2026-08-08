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
    private val engine: Engine,
    private val log: (String) -> Unit = {},
) : DapDebuggerBridge {

    // #356 — the debug-instrumentation flip lives at the CLI/wire
    // callers (EmbeddedExec, RuntimeServer), NOT in this ctor. Flipping
    // here caused a static-volatile leak across tests: any test suite
    // that constructs GraalDapBridge (e.g. DapEndToEndTest) turns
    // BDSL debug ON for every subsequent test in the same JVM,
    // triggering ProbeNode.onReturnValue interop-type checks on
    // internal values like PromiseTracker (which isn't a valid
    // Truffle interop type). CLI + wire paths flip it explicitly
    // before Engine.build so real DAP sessions are still covered.

    // Resolved lazily in start(): the "debugger" instrument may not be
    // registered on a bare Engine until a Context has been created from it, so
    // Debugger.find(engine) at construction can NPE. By start() the RuntimeServer
    // has built its Context, so the instrument is present.
    private var debugger: Debugger? = null
    private var session: DebuggerSession? = null

    /**
     * DAP thread registry. Mezze's `Async.spawn` runs each task on its own
     * virtual `java.lang.Thread`, which GraalVM's debugger sees as a real
     * thread — so we can present them as distinct DAP threads. We assign a
     * stable DAP id per Thread (id 1 = the main/first thread) and remember the
     * name so `threads` can enumerate them.
     *
     * (Pure-continuation effect fibers — HandlerChainRegistry — are NOT threads and stay
     * invisible; that's the BDSL-gated follow-up #166.)
     */
    private val threadIds = java.util.concurrent.ConcurrentHashMap<Long, Int>()
    private val threadNames = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val nextThreadId = java.util.concurrent.atomic.AtomicInteger(1)

    /** DAP thread id for a JVM thread, assigning one on first sight. */
    private fun dapThreadId(t: Thread): Int {
        return threadIds.computeIfAbsent(t.threadId()) {
            val id = nextThreadId.getAndIncrement()
            val name = if (id == 1) "main" else (t.name.ifBlank { "fiber-$id" })
            threadNames[id] = name
            id
        }
    }

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
        var value: String,   // mutable so setVariable can patch it in place
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
        /** Run a task ON the parked eval thread, given the live SuspendedEvent
         *  (frame access is thread-bound). */
        class Task(
            val run: (SuspendedEvent) -> String,
            val result: java.util.concurrent.CompletableFuture<String>,
        ) : ParkCommand
    }

    /** Set by MezzeDapServer so the bridge can push `stopped`/`continued`/`output`. */
    @Volatile var server: MezzeDapServer? = null

    /** If true, suspend on the first statement (stopOnEntry). */
    @Volatile var stopOnEntry: Boolean = false

    /**
     * Logpoints: line → message template. A logpoint breakpoint doesn't stop;
     * on hit we emit the (interpolated) message as an `output` event and
     * auto-continue. Keyed by line — good enough for one source at a time.
     */
    private val logpointsByLine = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** The currently-installed exception breakpoint (replaced on each request). */
    private var exceptionBreakpoint: Breakpoint? = null

    /** Set by pause(); makes the next suspend report reason "pause". */
    @Volatile private var pauseRequested: Boolean = false

    private var started = false

    /** Resolve the debugger instrument, retrying briefly if the Context that
     *  registers it hasn't finished coming up yet. */
    private fun resolveDebugger(): Debugger {
        debugger?.let { return it }
        var lastErr: Exception? = null
        repeat(50) {
            try {
                val d = Debugger.find(engine)
                debugger = d
                return d
            } catch (e: Exception) {
                lastErr = e
                Thread.sleep(20)
            }
        }
        throw IllegalStateException("debugger instrument unavailable on this engine", lastErr)
    }

    /** Start the debug session. Call once, before the program runs. */
    @Synchronized
    override fun start() {
        if (started) return
        started = true
        val debugger = resolveDebugger()
        session = debugger.startSession { event -> onSuspend(event) }
        if (stopOnEntry) session?.suspendNextExecution()
        log("[dap] debugger session started (stopOnEntry=$stopOnEntry)")
    }

    private fun onSuspend(event: SuspendedEvent) {
        // #374 — under BDSL_ENTRY, tag-instrumented bytecode roots can fire
        // stale Breakpoints left over on the shared Truffle Engine from a
        // prior test's DapEndToEndTest run. Without a live DAP client the
        // cmdQueue.take() below would park the fiber forever. If there's no
        // connected server, treat this like an unhandled suspension and
        // continue instead of parking.
        if (server == null) {
            event.prepareContinue()
            return
        }
        val line = event.sourceSection?.startLine ?: -1
        // Logpoint: emit the (interpolated) message and continue WITHOUT
        // stopping. `{expr}` segments are evaluated in the top frame.
        val logTemplate = if (event.breakpoints.isNotEmpty()) logpointsByLine[line] else null
        if (logTemplate != null) {
            val msg = interpolateLog(logTemplate, event)
            server?.sendEvent("output", mapOf("category" to "console", "output" to msg + "\n"))
            event.prepareContinue()
            return
        }

        currentEvent.set(event)
        // Snapshot the stack ON THIS (eval) thread — the DAP thread can't touch
        // the live event's frames.
        snapshot = captureSnapshot(event)
        log("[dap] suspended at line $line")
        // Reason: exception (a thrown error hit an exception breakpoint) vs
        // line breakpoint vs step.
        val exception = try { event.exception } catch (e: Exception) { null }
        val wasPause = pauseRequested
        pauseRequested = false
        val reason = when {
            exception != null -> "exception"
            event.breakpoints.isNotEmpty() -> "breakpoint"
            wasPause -> "pause"
            else -> "step"
        }
        // Identify which JVM thread suspended (a spawned virtual thread gets its
        // own DAP thread id). `allThreadsStopped` = false: with single-threaded
        // suspend, only this thread is paused; others (if any) keep running.
        val threadId = dapThreadId(Thread.currentThread())
        val stoppedBody = linkedMapOf<String, Any?>(
            "reason" to reason,
            "threadId" to threadId,
            "allThreadsStopped" to false,
        )
        if (exception != null) {
            stoppedBody["description"] = "Paused on exception"
            stoppedBody["text"] = try { exception.message ?: exception.toString() } catch (e: Exception) { "error" }
        }
        server?.sendEvent("stopped", stoppedBody)
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
                is ParkCommand.Task -> {
                    val out = try { cmd.run(event) } catch (e: Exception) { "<error: ${e.message}>" }
                    cmd.result.complete(out)
                }
            }
        }
        // If this stop came from a pause (suspendAll), clear the global suspend
        // arm before resuming — otherwise the very next statement re-suspends and
        // the program can't make progress.
        if (wasPause) {
            try { session?.resumeAll() } catch (e: Exception) { log("[dap] resumeAll failed: ${e.message}") }
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
     * Interpolate a logpoint message: `{expr}` segments are evaluated in the
     * top frame (via frame.eval — safe here, we're on the eval thread inside
     * onSuspend) and substituted; other text is literal.
     */
    private fun interpolateLog(template: String, event: SuspendedEvent): String {
        val re = Regex("""\{([^}]*)\}""")
        return re.replace(template) { m ->
            val expr = m.groupValues[1].trim()
            if (expr.isEmpty()) return@replace ""
            try {
                event.topStackFrame?.eval(expr)?.toDisplayString() ?: ""
            } catch (e: Exception) {
                "{$expr}"  // leave the source visible if it can't be evaluated
            }
        }
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
        // Reset logpoints for this source (setBreakpoints is authoritative).
        logpointsByLine.clear()
        val results = bps.map { bp ->
            val line = (bp["line"] as? Number)?.toInt() ?: return@map mapOf("verified" to false)
            val condition = (bp["condition"] as? String)?.takeIf { it.isNotBlank() }
            val logMessage = (bp["logMessage"] as? String)?.takeIf { it.isNotBlank() }
            try {
                val breakpoint = Breakpoint.newBuilder(uri).lineIs(line).build()
                // Conditional breakpoint: a Mezze expression evaluated in the
                // frame; the debugger only stops when it's truthy.
                if (condition != null) breakpoint.setCondition(condition)
                session.install(breakpoint)
                // Logpoint: record the template; onSuspend emits `output` +
                // auto-continues instead of stopping.
                if (logMessage != null) logpointsByLine[line] = logMessage
                mapOf("verified" to breakpoint.isResolved, "line" to line)
            } catch (e: Exception) {
                log("[dap] setBreakpoint line $line failed: ${e.message}")
                mapOf("verified" to false, "line" to line)
            }
        }
        return mapOf("breakpoints" to results)
    }

    override fun setExceptionBreakpoints(args: Map<String, Any?>) {
        val session = this.session ?: run { start(); this.session!! }
        @Suppress("UNCHECKED_CAST")
        val filters = (args["filters"] as? List<String>) ?: emptyList()
        // Remove any previously-installed exception breakpoint (setExceptionBreakpoints
        // is authoritative — replaces the whole set each call).
        exceptionBreakpoint?.let { try { it.dispose() } catch (_: Exception) {} }
        exceptionBreakpoint = null
        if (filters.isEmpty()) return
        val caught = filters.contains("all")
        val uncaught = filters.contains("uncaught") || filters.contains("all")
        try {
            val bp = Breakpoint.newExceptionBuilder(caught, uncaught).build()
            session.install(bp)
            exceptionBreakpoint = bp
            log("[dap] exception breakpoints: caught=$caught uncaught=$uncaught")
        } catch (e: Exception) {
            log("[dap] setExceptionBreakpoints failed: ${e.message}")
        }
    }

    override fun threads(): List<Map<String, Any?>> {
        // Snapshot the registry (populated as threads suspend). Always include a
        // "main" (id 1) so `threads` is never empty once a session exists.
        if (threadNames.isEmpty()) threadNames[1] = "main"
        return threadNames.entries.sortedBy { it.key }.map { mapOf("id" to it.key, "name" to it.value) }
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

    override fun setVariable(args: Map<String, Any?>): Map<String, Any?> {
        val name = args["name"] as? String ?: return mapOf("value" to "")
        val valueStr = (args["value"] as? String)?.trim() ?: return mapOf("value" to "")
        val ref = (args["variablesReference"] as? Number)?.toInt()
        if (currentEvent.get() == null) return mapOf("value" to "<not paused>")
        // Write ON the eval thread (DebugValue/scope are thread-bound): find the
        // named value in the top frame's scope chain and set it. New value parsed
        // as int / bool / else raw string.
        val future = java.util.concurrent.CompletableFuture<String>()
        cmdQueue.put(ParkCommand.Task({ event ->
            val newVal: Any = valueStr.toLongOrNull()
                ?: valueStr.toDoubleOrNull()
                ?: when (valueStr) { "true", "True" -> true; "false", "False" -> false; else -> valueStr }
            var scope: com.oracle.truffle.api.debug.DebugScope? = event.topStackFrame?.scope
            var depth = 0
            while (scope != null && depth < 8) {
                for (v in scope.declaredValues) {
                    if (v.name == name) {
                        if (!v.isWritable) return@Task "<read-only>"
                        v.set(newVal)
                        val disp = v.toDisplayString()
                        // Patch the existing snapshot entry IN PLACE so a follow-up
                        // `variables` read (answered from the snapshot) sees the new
                        // value — re-capturing would renumber the variablesReferences.
                        if (ref != null) snapshot?.varsByRef?.get(ref)?.firstOrNull { it.name == name }?.value = disp
                        return@Task disp
                    }
                }
                scope = try { scope.parent } catch (e: Exception) { null }
                depth++
            }
            "<not found>"
        }, future))
        val applied = try { future.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { "<timeout>" }
        return mapOf("value" to applied)
    }

    override fun pause() {
        // Interrupt a running program: suspend at the next executed statement.
        // Only meaningful while running (not already parked).
        if (currentEvent.get() == null) {
            // The session is lazily started by setBreakpoints; a program with NO
            // breakpoints (pause-only) may not have one yet. Ensure it exists so
            // the suspend actually installs.
            if (session == null) start()
            pauseRequested = true
            // suspendNextExecution() arms the CALLING thread (the DAP thread),
            // which never runs guest code — so it never fires. suspendAll() arms
            // every executing guest thread, so the running eval thread stops at
            // its next statement. That's the correct "pause the program" action.
            try { session?.suspendAll() } catch (e: Exception) { log("[dap] pause failed: ${e.message}") }
        }
    }

    override fun setFunctionBreakpoints(args: Map<String, Any?>): Map<String, Any?> {
        // Function breakpoints by name aren't directly expressible via the line-
        // breakpoint API without a root-name filter, which Mezze's compiled
        // functions don't uniformly expose yet. Accept the request so VS Code
        // doesn't error, and report them unverified. (Line + exception + function-
        // ENTRY-via-line breakpoints cover the common cases today.)
        @Suppress("UNCHECKED_CAST")
        val bps = (args["breakpoints"] as? List<Map<String, Any?>>) ?: emptyList()
        return mapOf("breakpoints" to bps.map { mapOf("verified" to false) })
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

    /** Program finished — notify the client so VS Code closes the session. */
    fun onProgramExit(exitCode: Int) {
        server?.sendEvent("exited", mapOf("exitCode" to exitCode))
        server?.sendEvent("terminated", null)
        try { session?.close() } catch (_: Exception) {}
    }

    /**
     * Forward one chunk of program output to the DAP client's Debug Console.
     * [category] is "stdout" or "stderr". Called from the wire-output tee in
     * RuntimeServer so `println` etc. show up in VS Code while debugging.
     */
    fun onProgramOutput(text: String, category: String) {
        server?.sendEvent("output", mapOf("category" to category, "output" to text))
    }
}
