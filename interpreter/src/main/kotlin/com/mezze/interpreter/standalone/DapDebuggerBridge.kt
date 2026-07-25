package com.mezze.interpreter.standalone

/**
 * The seam between [MezzeDapServer] (DAP protocol) and the GraalVM `Debugger`
 * engine. The server translates DAP requests into calls on this bridge; the
 * bridge implementation (Phase 2+) drives `DebuggerSession` / `SuspendedEvent` /
 * `DebugStackFrame` / `DebugScope` / `DebugValue`.
 *
 * Keeping the bridge as an interface lets Phase 1 (framing + handshake) be
 * tested with a null bridge, and lets later phases be built + tested against a
 * fake bridge without a live Truffle Context.
 *
 * All request/reply bodies are plain DAP JSON shapes (Map / List / scalars) so
 * the server can forward them straight to the wire.
 */
interface DapDebuggerBridge {

    enum class StepKind { CONTINUE, OVER, INTO, OUT }

    /**
     * `setBreakpoints`: install line breakpoints for one source. `args` carries
     * `source.path` (or `source.name`) and `breakpoints: [{line}]`. Returns a
     * DAP body `{ breakpoints: [{ verified, line }] }` — `verified` reflects
     * `Breakpoint.isResolved`.
     */
    fun setBreakpoints(args: Map<String, Any?>): Map<String, Any?>

    /**
     * `setExceptionBreakpoints`: install/replace exception breakpoints from the
     * `filters` list (a subset of the advertised filters: "uncaught", "all").
     * Break at the raise site of a matching guest error.
     */
    fun setExceptionBreakpoints(args: Map<String, Any?>)

    /** `threads`: the JVM threads seen so far (main + Async.spawn'd fibers), as
     *  `[{ id, name }]`. Empty before anything has run. */
    fun threads(): List<Map<String, Any?>>

    /** `stackTrace`: `{ stackFrames: [{ id, name, line, column, source }], totalFrames }`. */
    fun stackTrace(): Map<String, Any?>

    /** `scopes`: `{ scopes: [{ name, variablesReference, expensive }] }` for a frame. */
    fun scopes(args: Map<String, Any?>): Map<String, Any?>

    /** `variables`: `{ variables: [{ name, value, variablesReference }] }` for a scope. */
    fun variables(args: Map<String, Any?>): Map<String, Any?>

    /** `evaluate`: `{ result, variablesReference }` — `frame.eval(expr)`. */
    fun evaluate(args: Map<String, Any?>): Map<String, Any?>

    /** `continue` / `next` / `stepIn` / `stepOut`: prepare the step + unpark the eval thread. */
    fun resume(kind: StepKind)

    /** `disconnect` / `terminate`: close the session and let the program finish. */
    fun disconnect()
}
