package com.mezze.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer

/**
 * Mezze wire-protocol v1 — pure data layer.
 *
 * See `docs/wire-protocol/00-design.md` for the design.
 *
 * This file contains:
 *   - Frame format constants (length-prefixed binary frames)
 *   - Message type tags (one byte each)
 *   - CBOR-serializable payload classes for every message type
 *   - Encode/decode helpers ([WireFrame.encode] / [WireFrame.decode])
 *
 * It contains no I/O. The codec ([WireFrameCodec]) lives in a separate
 * file and depends on this. State-machine logic lives in
 * [com.mezze.interpreter.standalone.wire.WireSession]-shaped classes
 * (parent and child variants), built on top of this layer.
 *
 * Versioning: the constants [PROTOCOL_VERSION_MAJOR] /
 * [PROTOCOL_VERSION_MINOR] are part of the on-the-wire HELLO/WELCOME
 * payload. Bumping major implies breaking framing or core flow. Bumping
 * minor implies adding messages or fields backward-compatibly.
 */
@OptIn(ExperimentalSerializationApi::class)
object WireProtocol {

    const val PROTOCOL_VERSION_MAJOR: Int = 1

    /**
     * 1 — added the expression-evaluation messages (0x70–0x7F) and the `eval`
     * capability. Minor, not major: every added message is optional and gated on
     * a negotiated capability, so a peer that never heard of them is unaffected.
     */
    const val PROTOCOL_VERSION_MINOR: Int = 1

    /** Maximum payload size (bytes). Tripwire for runaway frames. */
    const val MAX_FRAME_PAYLOAD_BYTES: Int = 16 * 1024 * 1024  // 16 MiB

    /**
     * Depth at which [Eval] stops walking a value and emits [WireValue.Elided].
     *
     * A bound is not an optimisation, it is a requirement: a value tree is walked
     * from a live runtime value, and a deep or wide structure would otherwise
     * serialise megabytes into a frame to answer "what is x" — or not terminate at
     * all on a cyclic structure. Callers that want more ask for it, or expand a
     * specific [WireValue.Elided] path.
     */
    const val DEFAULT_EVAL_MAX_DEPTH: Int = 6

    /** Elements/entries per collection before the rest become an omitted count. */
    const val DEFAULT_EVAL_MAX_ITEMS: Int = 100

    /** Configured by [WireProtocol] for CBOR encode/decode. */
    val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true   // forward-compat: peers may send fields we don't know
        encodeDefaults = false     // smaller frames; missing => default
    }

    /**
     * Canonical capability strings. Both sides should use these constants
     * rather than literal strings so typos surface at compile time.
     *
     * Capability semantics — see `docs/wire-protocol/00-design.md` §8.
     */
    object Capability {
        const val CORE = "core"
        const val EXIT_CODE = "exit_code"
        const val INTERRUPT = "interrupt"
        const val STRUCTURED_ERRORS = "structured_errors"

        // I/O extensions
        const val STDIN = "stdin"                  // STDIN_DATA/STDIN_EOF/STDIN_REQUEST/STDIN_PAUSE flow
        const val STDERR_FRAMES = "stderr_frames"  // STDERR_DATA frames vs inherited fd 2
        const val PASSWORD = "password"            // PASSWORD_REQUEST / PASSWORD_REPLY round-trip

        // Process surface
        const val ARGV = "argv"                    // Load carries argv tail
        const val CWD = "cwd"                      // Load carries cwd
        const val ENV = "env"                      // Load carries env snapshot

        // Terminal
        const val TERM_SIZE = "term_size"          // RuntimeInfo carries rows/cols; WINCH frames flow
        const val RAW_STDIN = "raw_stdin"          // RAW_MODE_SET / RAW_MODE_UNSET frames

        // Signals
        const val SIGNALS = "signals"              // SIGNAL frames flow (non-SIGINT)

        // Dev instrumentation (existing)
        const val INSPECT = "inspect"
        const val PROFILE = "profile"
        const val COVERAGE = "coverage"
        const val ALLOCATIONS = "allocations"
        const val INSIGHT = "insight"
        const val TRACE_JIT = "trace_jit"

        /** EVAL / EVAL_RESULT / EXPAND / EXPAND_RESULT / CANCEL flow. */
        const val EVAL = "eval"
    }
}

// ─── Frame type tags ──────────────────────────────────────────────────
//
// One byte per type. Reserve ranges:
//   0x00–0x0F  handshake + lifecycle
//   0x10–0x1F  code lifecycle (LOAD, EXITED, etc.)
//   0x30–0x3F  control (INTERRUPT, STATUS)
//   0x40–0x4F  configuration
//   0x50–0x5F  user I/O (STDOUT_DATA, STDIN_DATA)
//   0x60–0x6F  diagnostics (LOG, PING, ERROR)
//   0x70–0x7F  expression evaluation (EVAL, EXPAND, CANCEL)
// Range 0x20–0x2F is still intentionally unused — reserved for if/when
// multi-client attach gets a real design driver.
//
// Gaps in numbering are intentional — leaves room within each range to
// add related messages without renumbering. Once published, type bytes
// MUST NOT be reused for a different message; deprecated types stay
// reserved.

enum class MessageType(val tag: Byte) {
    // Handshake + lifecycle (0x00–0x0F)
    HELLO(0x01),
    WELCOME(0x02),
    REJECT(0x03),
    QUIT(0x04),
    BYE(0x05),

    // Code lifecycle (0x10–0x1F)
    EVICT(0x10),
    LOAD(0x11),
    LOADED(0x12),
    STARTED(0x13),
    EXITED(0x14),

    // Control (0x30–0x3F)
    INTERRUPT(0x30),
    STATUS(0x31),
    STATUS_REPLY(0x32),

    // Configuration (0x40–0x4F)
    CONFIG(0x40),
    CONFIG_ACK(0x41),

    // User I/O (0x50–0x5F)
    STDOUT_DATA(0x50),
    STDERR_DATA(0x51),
    STDIN_DATA(0x52),
    STDIN_EOF(0x53),
    STDIN_REQUEST(0x54),
    STDIN_PAUSE(0x55),
    PASSWORD_REQUEST(0x56),
    PASSWORD_REPLY(0x57),
    WINCH(0x58),
    SIGNAL(0x59),
    RAW_MODE_SET(0x5A),
    RAW_MODE_UNSET(0x5B),

    // Diagnostics (0x60–0x6F)
    LOG(0x60),
    PING(0x61),
    PONG(0x62),
    ERROR(0x63),

    // Expression evaluation (0x70–0x7F)
    EVAL(0x70),
    EVAL_RESULT(0x71),
    EXPAND(0x72),
    EXPAND_RESULT(0x73),
    CANCEL(0x74),
    ;

    constructor(tag: Int) : this(tag.toByte())

    companion object {
        private val byTag: Map<Byte, MessageType> = entries.associateBy { it.tag }

        /** Decode a tag byte to a [MessageType], or null if unknown. */
        fun fromTag(tag: Byte): MessageType? = byTag[tag]
    }
}

// ─── Payload schemas ──────────────────────────────────────────────────
//
// One @Serializable data class per [MessageType] that carries a payload.
// Empty messages (BYE, QUIT body-less, PING, etc.) still get a class so
// the dispatcher has a uniform shape, but they're zero-sized after CBOR
// encoding.

// Handshake

@Serializable
data class Hello(
    @SerialName("version_major") val versionMajor: Int = WireProtocol.PROTOCOL_VERSION_MAJOR,
    @SerialName("version_minor") val versionMinor: Int = WireProtocol.PROTOCOL_VERSION_MINOR,
    val capabilities: List<String> = emptyList(),
    /** Identifies the parent: `mez-dev`, `mez-exec`, `mez-repl`, etc. */
    val client: String,
    /**
     * Parent's TTY/terminal snapshot. The child has no direct OS view
     * of the user's terminal once the wire replaces fd 0; without this
     * info `is_tty` etc. would always return false in the child.
     */
    @SerialName("isatty_stdin") val isattyStdin: Boolean = false,
    @SerialName("isatty_stdout") val isattyStdout: Boolean = false,
    @SerialName("isatty_stderr") val isattyStderr: Boolean = false,
    @SerialName("term_rows") val termRows: Int = 0,
    @SerialName("term_cols") val termCols: Int = 0,
    @SerialName("term") val term: String = "",
    /**
     * Shared secret, required ONLY when the runtime is listening on a socket.
     *
     * A spawned child inherits its parent's pipes, and the OS decides who may
     * write to them — so the stdio transport needs no secret. A listening socket
     * has no such guarantee: any local process could connect, and this protocol can
     * LOAD and RUN code. The token is what keeps "reachable on loopback" from
     * meaning "anyone on this machine can execute code in your project".
     *
     * Null on the stdio transport, where it is neither sent nor checked. Optional
     * and default-null, so an older peer that never heard of it still handshakes
     * (the codec sets `ignoreUnknownKeys` and `encodeDefaults = false`).
     */
    @SerialName("token") val token: String? = null,
)

@Serializable
data class Welcome(
    @SerialName("version_major") val versionMajor: Int,
    @SerialName("version_minor") val versionMinor: Int,
    val capabilities: List<String> = emptyList(),
    @SerialName("agreed_version_major") val agreedVersionMajor: Int,
    @SerialName("agreed_version_minor") val agreedVersionMinor: Int,
    @SerialName("agreed_capabilities") val agreedCapabilities: List<String> = emptyList(),
    @SerialName("runtime_info") val runtimeInfo: RuntimeInfo,
)

@Serializable
data class RuntimeInfo(
    @SerialName("runtime_version") val runtimeVersion: String,
    @SerialName("runtime_git_sha") val runtimeGitSha: String = "",
    @SerialName("jvm_vendor") val jvmVendor: String,
    @SerialName("jvm_version") val jvmVersion: String,
    @SerialName("truffle_version") val truffleVersion: String = "",
    @SerialName("languages_available") val languagesAvailable: List<String> = emptyList(),
    @SerialName("jit_enabled") val jitEnabled: Boolean = false,
    /**
     * Parent-reported TTY/terminal state. The child has no direct OS
     * connection to the user's terminal once wire framing replaces fd 0,
     * so it must rely on what the parent tells it here at handshake time.
     */
    @SerialName("isatty_stdin") val isattyStdin: Boolean = false,
    @SerialName("isatty_stdout") val isattyStdout: Boolean = false,
    @SerialName("isatty_stderr") val isattyStderr: Boolean = false,
    @SerialName("term_rows") val termRows: Int = 0,
    @SerialName("term_cols") val termCols: Int = 0,
    @SerialName("term") val term: String = "",
    // NOTE: deliberately NO `token` field here. The session secret travels
    // client → server in [Hello] and never comes back: this class is part of
    // [Welcome], and WELCOME is the frame most likely to be logged or traced
    // when someone debugs a handshake, since it carries the versions and
    // capabilities. A secret that rides along in it ends up in log files. The
    // client already knows the token — it just sent it — so echoing it is pure
    // exposure with no use.
)

@Serializable
data class Reject(val reason: String, val detail: String = "")

@Serializable
data class Quit(val reason: String = "done")

@Serializable
class Bye

// Code lifecycle

@Serializable
data class Evict(val hashes: List<String>)

@Serializable
data class Load(
    @SerialName("fns_cbor_path") val fnsCborPath: String,
    @SerialName("meta_cbor_path") val metaCborPath: String,
    /** `"run"` to compile and execute. Reserved for future compile-only mode. */
    val mode: String = "run",
    /**
     * Parent-supplied argv tail (everything the user passed after `--` to
     * `mez exec` / `mez dev`). Null = parent does not support `argv`
     * capability; child should fall back to ProcessHandle's view.
     */
    val argv: List<String>? = null,
    /**
     * Parent's current working directory at LOAD time. Refreshed on every
     * LOAD so `cd` between reloads in dev mode is reflected.
     */
    val cwd: String? = null,
    /**
     * Parent's environment snapshot. Null = parent does not support `env`
     * capability; child uses JVM-inherited env.
     */
    val env: Map<String, String>? = null,
)

@Serializable
data class Loaded(
    @SerialName("run_id") val runId: Long,
    @SerialName("fn_count") val fnCount: Int,
)

@Serializable
data class Started(@SerialName("run_id") val runId: Long)

@Serializable
data class Exited(
    @SerialName("run_id") val runId: Long,
    val code: Int,
    /** `"normal"` / `"error"` / `"exit_called"` / `"interrupted"` / `"timeout"` / `"crashed"`. */
    val reason: String,
    val detail: ExitDetail? = null,
)

@Serializable
data class ExitDetail(
    val message: String = "",
    val stack: List<StackFrame> = emptyList(),
    @SerialName("source_file") val sourceFile: String? = null,
    @SerialName("source_line") val sourceLine: Int? = null,
    @SerialName("source_col") val sourceCol: Int? = null,
)

@Serializable
data class StackFrame(
    val function: String,
    val file: String? = null,
    val line: Int? = null,
    val col: Int? = null,
)

// Control

@Serializable
data class Interrupt(@SerialName("run_id") val runId: Long? = null)

@Serializable
class StatusReq

@Serializable
data class StatusReply(
    /** `"idle"` / `"loading"` / `"running"` / `"interrupting"` / `"shutting_down"`. */
    val state: String,
    @SerialName("active_run_id") val activeRunId: Long? = null,
    @SerialName("scope_ids") val scopeIds: List<String> = emptyList(),
    @SerialName("handle_count") val handleCount: Int = 0,
    @SerialName("memory_bytes") val memoryBytes: Long = 0,
)

// Configuration

@Serializable
data class Config(val keys: Map<String, String>)

@Serializable
data class ConfigAck(
    val accepted: List<String> = emptyList(),
    val rejected: Map<String, String> = emptyMap(),
)

// User I/O

@Serializable
data class StdoutData(
    @SerialName("run_id") val runId: Long,
    val bytes: ByteArray,
) {
    // Generated equals/hashCode skipped — ByteArray identity-equality breaks
    // tests but we don't compare wire frames structurally; CBOR round-trip
    // tests check encoded bytes instead.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StdoutData) return false
        return runId == other.runId && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * runId.hashCode() + bytes.contentHashCode()
}

@Serializable
data class StderrData(
    @SerialName("run_id") val runId: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StderrData) return false
        return runId == other.runId && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * runId.hashCode() + bytes.contentHashCode()
}

@Serializable
data class StdinData(
    @SerialName("run_id") val runId: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StdinData) return false
        return runId == other.runId && bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int = 31 * runId.hashCode() + bytes.contentHashCode()
}

@Serializable
data class StdinEof(@SerialName("run_id") val runId: Long)

/**
 * Child → parent: "I need stdin bytes for this run, start forwarding from
 * your stdin". Sent on first stdin read in a run. Parent spawns a reader
 * thread until it sees EOF and ships [StdinData] frames.
 */
@Serializable
data class StdinRequest(@SerialName("run_id") val runId: Long)

/**
 * Child → parent: "stop forwarding, I have enough". Parent stops its
 * stdin reader. Re-armable: child can issue another STDIN_REQUEST later.
 */
@Serializable
data class StdinPause(@SerialName("run_id") val runId: Long)

/**
 * Child → parent: "ask the user for a password, with this prompt, then
 * send the result back via [PasswordReply]". Parent owns the real TTY,
 * does the no-echo input locally, sends the result.
 */
@Serializable
data class PasswordRequest(
    @SerialName("run_id") val runId: Long,
    val prompt: String = "",
)

/**
 * Parent → child: the password the user entered. Null `value` =
 * EOF / cancelled / no TTY available.
 */
@Serializable
data class PasswordReply(
    @SerialName("run_id") val runId: Long,
    val value: String? = null,
)

/** Parent → child: terminal resize event. Both sides start at 0×0 if unknown. */
@Serializable
data class Winch(val rows: Int, val cols: Int)

/**
 * Parent → child: a signal arrived at the parent that the user-program
 * might want to react to. Examples: `"sigterm"`, `"sighup"`,
 * `"sigusr1"`, `"sigusr2"`. `"sigint"` is handled by the separate
 * INTERRUPT frame, not here.
 */
@Serializable
data class Signal(val name: String)

/** Child → parent: switch terminal to raw mode (echo off, line-buffer off). */
@Serializable
class RawModeSet

/** Child → parent: restore the saved terminal mode. */
@Serializable
class RawModeUnset

// Diagnostics

@Serializable
data class Log(
    /** `"debug"` / `"info"` / `"warn"` / `"error"`. */
    val level: String,
    /** Origin: `"runtime"` / `"truffle"` / `"polyglot"` / etc. */
    val source: String,
    val message: String,
)

@Serializable
class Ping

@Serializable
class Pong

@Serializable
data class ProtocolError(
    /**
     * Stable error codes for protocol-level failures:
     *   `PROTO_DECODE`         malformed CBOR
     *   `PROTO_UNKNOWN_TYPE`   tag byte not recognized
     *   `PROTO_STATE_INVALID`  command not allowed in current state
     *   `PROTO_TOO_LARGE`      frame exceeded MAX_FRAME_PAYLOAD_BYTES
     *   `PROTO_VERSION`        version negotiation failed
     */
    val code: String,
    val message: String,
    /** Optional: the tag byte of the message we're complaining about. */
    @SerialName("in_reply_to") val inReplyTo: Byte? = null,
)

// ─── Expression evaluation ────────────────────────────────────────────
//
// LOAD/EVICT are module-granularity — "here is a program, run it" — and the
// answer comes back as console bytes and an exit code. "Evaluate this
// expression and give me the VALUE" is a different interaction: request /
// response over values, many of them possibly in flight at once, from
// different callers (an editor's inline hints, a REPL, a doc browser).
//
// WHY NO SOURCE TEXT IN [Eval]: the runtime cannot compile. `:interpreter`
// depends on `:parser` and `:typechecker` only as TEST dependencies — at
// runtime it has neither, and that is deliberate, because the runtime is what
// ships as the slim jar and the native image. So the split is the one LOAD
// already embodies: the client compiles, the runtime executes IR. An
// expression's type errors are therefore diagnosed on the CLIENT side of the
// wire and never travel as a protocol message at all.
//
// WHY NO SHAPE FIELD EITHER: rendering a value needs its static shape (Nat vs
// Int are both Long, a newtype is erased to its payload, a one-char Str is not
// a Char). That shape does not need a wire field — the compiler already bakes a
// PrintType into the IR as a constant for `to_dbg`/`to_str`, and the same
// mechanism carries it here. Keeping it out of the protocol also keeps the
// published contract independent of a compiler-internal type.
//
// WHY NO `module_hash` STALENESS GUARD: it would check nothing. The IR closure
// LOAD/EVAL ships is self-contained ("all modules are already included in the
// FunctionIR list"), and every cache inside the runtime is keyed by content
// hash — so cached code for hash X *is* X. The staleness that does exist is
// between an editor's unsaved buffer and the store, which is decided before
// compilation and therefore before this protocol is involved. A field that
// cannot detect the failure it names is worse than no field.

/**
 * Client → runtime: run this compiled expression and reply with its value.
 *
 * [requestId] is the client's own counter and is echoed in [EvalResult]. It is
 * NOT a `run_id`: run ids identify programs, of which the runtime models one at
 * a time (`StatusReply.activeRunId` is singular), whereas eval requests are
 * many and concurrent. That is also why cancellation gets its own [Cancel]
 * message rather than reusing INTERRUPT — INTERRUPT means "stop the running
 * program", and there is no way to say with it "drop the doc browser's request
 * but keep the editor's".
 */
@Serializable
data class Eval(
    @SerialName("request_id") val requestId: Long,
    /** Compiled IR closure for the expression, as for [Load]. */
    @SerialName("fns_cbor_path") val fnsCborPath: String,
    /** Metadata sidecar, as for [Load]; its `entryHash` names the expression. */
    @SerialName("meta_cbor_path") val metaCborPath: String,
    @SerialName("max_depth") val maxDepth: Int = WireProtocol.DEFAULT_EVAL_MAX_DEPTH,
    @SerialName("max_items") val maxItems: Int = WireProtocol.DEFAULT_EVAL_MAX_ITEMS,
    /** Wall-clock budget for the expression. 0 = no limit. */
    @SerialName("timeout_ms") val timeoutMs: Long = 0,
)

/**
 * Runtime → client: the value, or why there isn't one.
 *
 * Exactly one of [value] / [error] is set. There is deliberately no "was it
 * truncated" flag — [WireValue.Elided] nodes in the tree say so precisely, and
 * a derived boolean alongside them is one more thing that can disagree.
 */
@Serializable
data class EvalResult(
    @SerialName("request_id") val requestId: Long,
    val value: WireValue? = null,
    val error: EvalError? = null,
    @SerialName("elapsed_micros") val elapsedMicros: Long = 0,
)

/**
 * Why an evaluation produced no value.
 *
 * [detail] is the same [ExitDetail] a program failure carries, reused rather
 * than re-declared: a client that already renders a Mezze stack trace for a
 * crashed program renders one for a failed expression with the same code.
 */
@Serializable
data class EvalError(
    /**
     * One of [Kind]. A string rather than an enum, matching `Exited.reason` and
     * `Log.level`: an unknown enum value is a hard decode failure, so a client
     * built today would refuse a frame naming a kind added tomorrow.
     */
    val kind: String,
    val detail: ExitDetail,
) {
    object Kind {
        /** A path in [Eval] does not exist. */
        const val NOT_FOUND = "not_found"
        /** The IR or metadata sidecar would not decode. */
        const val DECODE_FAILED = "decode_failed"
        /** The metadata named no entry, so there is no expression to run. */
        const val NO_ENTRY = "no_entry"
        /** The expression ran and failed. [EvalError.detail] carries the stack. */
        const val FAILED = "failed"
        /** [Cancel] arrived for this request. */
        const val CANCELLED = "cancelled"
        /** `timeout_ms` elapsed. */
        const val TIMEOUT = "timeout"
        /** The value could not be walked — a shape/value mismatch, i.e. a compiler bug. */
        const val UNRENDERABLE = "unrenderable"
        /** An [Expand] path does not address a node in that result. */
        const val NO_SUCH_PATH = "no_such_path"
        /** The referenced result is no longer held, so the path cannot be resolved. */
        const val EXPIRED = "expired"
    }
}

/**
 * Client → runtime: expand one [WireValue.Elided] node from an earlier result.
 *
 * This is the laziness half of the value tree: a first reply is bounded, and a
 * client that wants to look inside an elided branch asks for exactly that
 * branch rather than re-requesting the whole value at a larger depth.
 */
@Serializable
data class Expand(
    @SerialName("request_id") val requestId: Long,
    /** The [EvalResult.requestId] whose tree [path] came from. */
    @SerialName("from_request") val fromRequest: Long,
    /** The [WireValue.Elided.path] to expand. */
    val path: String,
    @SerialName("max_depth") val maxDepth: Int = WireProtocol.DEFAULT_EVAL_MAX_DEPTH,
    @SerialName("max_items") val maxItems: Int = WireProtocol.DEFAULT_EVAL_MAX_ITEMS,
)

/** Runtime → client: the subtree at [path], or why it could not be produced. */
@Serializable
data class ExpandResult(
    @SerialName("request_id") val requestId: Long,
    val path: String,
    val value: WireValue? = null,
    val error: EvalError? = null,
)

/**
 * Client → runtime: abandon one in-flight [Eval] or [Expand].
 *
 * Answered with an [EvalError.Kind.CANCELLED] result for that request, so a
 * client always gets exactly one reply per request and never has to time out
 * waiting to learn a cancel landed. Cancelling an unknown or finished request
 * is not an error — the reply may already have been sent.
 */
@Serializable
data class Cancel(@SerialName("request_id") val requestId: Long)

// ─── Frame: type + payload pair ───────────────────────────────────────

/**
 * A decoded frame. Use [encode] to get bytes ready for the wire; use
 * [WireFrameCodec] to read/write multiple frames on a stream.
 *
 * The class is intentionally a sealed-ish interface implemented via a
 * `type + payload` pair rather than a polymorphic discriminator field.
 * Reason: we want explicit control over byte layout, not CBOR's union
 * tagging.
 */
data class WireFrame(val type: MessageType, val payload: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireFrame) return false
        return type == other.type && payload.contentEquals(other.payload)
    }
    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()

    companion object {
        /** Encode a typed payload into a frame (CBOR-serialized). */
        @OptIn(ExperimentalSerializationApi::class)
        inline fun <reified T : Any> of(type: MessageType, value: T): WireFrame =
            WireFrame(type, WireProtocol.cbor.encodeToByteArray(serializer<T>(), value))

        /** For empty-payload messages (PING, PONG, BYE, etc.). */
        fun empty(type: MessageType): WireFrame = WireFrame(type, ByteArray(0))
    }

    /** Decode the payload to type [T] (CBOR-deserialized). */
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T : Any> decode(): T =
        WireProtocol.cbor.decodeFromByteArray(serializer<T>(), payload)
}
