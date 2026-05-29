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
    const val PROTOCOL_VERSION_MINOR: Int = 0

    /** Maximum payload size (bytes). Tripwire for runaway frames. */
    const val MAX_FRAME_PAYLOAD_BYTES: Int = 16 * 1024 * 1024  // 16 MiB

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
    }
}

// ─── Frame type tags ──────────────────────────────────────────────────
//
// One byte per type. Reserve ranges:
//   0x00–0x0F  handshake + lifecycle
//   0x10–0x1F  code lifecycle (LOAD, EXITED, etc.)
//   0x20–0x2F  interaction (EVAL, REF_GET — REPL/attach)
//   0x30–0x3F  control (INTERRUPT, STATUS)
//   0x40–0x4F  configuration
//   0x50–0x5F  user I/O (STDOUT_DATA, STDIN_DATA)
//   0x60–0x6F  diagnostics (LOG, PING, ERROR)
//   0x70–0x7F  attach (future)
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

    // Interaction — REPL/attach (0x20–0x2F)
    EVAL(0x20),
    EVAL_RESULT(0x21),
    REF_GET(0x22),
    REF_RELEASE(0x23),
    BINDINGS(0x24),

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

    // Attach — future (0x70–0x7F)
    ATTACH_REQUEST(0x70),
    ATTACH_GRANTED(0x71),
    ATTACH_DENIED(0x72),
    DETACH(0x73),
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
    /** `"run"` to compile and execute; `"register"` to compile only (REPL warmup). */
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

// REPL / attach payloads — schemas defined for forward-compat. Servers
// reject these unless the corresponding capability was negotiated.

@Serializable
data class Eval(
    @SerialName("run_id") val runId: Long? = null,
    val source: String,
    @SerialName("scope_id") val scopeId: String? = null,
)

@Serializable
data class EvalResult(
    @SerialName("eval_id") val evalId: Long,
    /** Opaque handle; `REF_GET` / `REF_RELEASE` accept this. */
    val handle: String? = null,
    /** Pretty-printed form for display. */
    val rendered: String,
    /** Type name as Mezze would render it. */
    val type: String,
    val error: ExitDetail? = null,
)

@Serializable
data class RefGet(
    val handle: String,
    val depth: Int = 1,
    /** Optional path into structure: `.field`, `[0]`, etc. */
    val path: String? = null,
)

@Serializable
data class RefRelease(val handles: List<String>)

@Serializable
data class Bindings(
    @SerialName("scope_id") val scopeId: String,
    /** `"list"` or `"clear"`. */
    val action: String,
)

@Serializable
data class AttachRequest(
    val token: String? = null,
    /** `"read_only"` or `"interactive"`. */
    val mode: String,
)

@Serializable
data class AttachGranted(
    @SerialName("session_id") val sessionId: String,
    /** Free-form snapshot of current child state at attach time. */
    val snapshot: Map<String, String> = emptyMap(),
)

@Serializable
data class AttachDenied(val reason: String)

@Serializable
class Detach

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
