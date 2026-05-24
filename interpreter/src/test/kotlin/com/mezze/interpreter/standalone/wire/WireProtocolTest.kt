package com.mezze.interpreter.standalone.wire

import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tests for [WireProtocol] payload schemas + [WireFrameCodec] framing.
 *
 * These are pure data tests — no subprocess, no I/O beyond in-memory
 * streams. Sanity checks the wire format before anything depends on it.
 */
@OptIn(ExperimentalSerializationApi::class)
class WireProtocolTest {

    // ─── Frame codec ─────────────────────────────────────────────────

    @Test
    fun `empty frame round-trips`() {
        val frame = WireFrame.empty(MessageType.PING)
        val bytes = encode(frame)
        // Framing: 4-byte length (= 1, just the tag) + 1 tag byte = 5 bytes total
        assertEquals(5, bytes.size)
        val decoded = WireFrameCodec.read(ByteArrayInputStream(bytes))
        assertEquals(frame, decoded)
    }

    @Test
    fun `frame with payload round-trips`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0xff.toByte())
        val frame = WireFrame(MessageType.STDOUT_DATA, payload)
        val decoded = decode(encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun `multiple frames round-trip sequentially`() {
        val frames = listOf(
            WireFrame.empty(MessageType.PING),
            WireFrame.of(MessageType.HELLO, Hello(client = "test", capabilities = listOf("core"))),
            WireFrame.empty(MessageType.PONG),
        )
        val out = ByteArrayOutputStream()
        frames.forEach { WireFrameCodec.write(out, it) }

        val input = ByteArrayInputStream(out.toByteArray())
        val read = generateSequence { WireFrameCodec.read(input) }.toList()
        assertEquals(frames, read)
    }

    @Test
    fun `clean EOF returns null`() {
        val decoded = WireFrameCodec.read(ByteArrayInputStream(ByteArray(0)))
        assertNull(decoded)
    }

    @Test
    fun `truncated length field throws PROTO_DECODE`() {
        // Only 3 bytes of the 4-byte length — partial read
        val input = ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x00))
        val ex = assertThrows(WireProtocolException::class.java) {
            WireFrameCodec.read(input)
        }
        assertEquals("PROTO_DECODE", ex.code)
    }

    @Test
    fun `zero-length frame throws PROTO_DECODE`() {
        // Length = 0 isn't valid; every frame needs at least a tag byte
        val input = ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        val ex = assertThrows(WireProtocolException::class.java) {
            WireFrameCodec.read(input)
        }
        assertEquals("PROTO_DECODE", ex.code)
        assertTrue(ex.message!!.contains("must be >= 1"))
    }

    @Test
    fun `payload exceeding cap throws PROTO_TOO_LARGE`() {
        // Encode a length one byte over the cap. Don't actually allocate
        // that buffer — readInt will reject before we try.
        val tooBig = WireProtocol.MAX_FRAME_PAYLOAD_BYTES + 2  // +1 for tag, +1 overflow
        val buf = ByteArrayOutputStream()
        java.io.DataOutputStream(buf).writeInt(tooBig)
        buf.write(MessageType.STDOUT_DATA.tag.toInt())  // tag byte before we error

        val ex = assertThrows(WireProtocolException::class.java) {
            WireFrameCodec.read(ByteArrayInputStream(buf.toByteArray()))
        }
        assertEquals("PROTO_TOO_LARGE", ex.code)
    }

    @Test
    fun `unknown tag throws PROTO_UNKNOWN_TYPE`() {
        // Length = 1 (just the tag), tag = 0x7F (unused). Future-reserved
        // for attach; not in v1 enum.
        val buf = ByteArrayOutputStream()
        java.io.DataOutputStream(buf).writeInt(1)
        buf.write(0x7F)  // ATTACH range, future
        // Actually 0x7F isn't claimed yet — wait, DETACH is 0x73. Pick
        // 0x7E which is genuinely unused.
        val input = ByteArrayInputStream(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x7E.toByte())
        )
        val ex = assertThrows(WireProtocolException::class.java) {
            WireFrameCodec.read(input)
        }
        assertEquals("PROTO_UNKNOWN_TYPE", ex.code)
    }

    @Test
    fun `writing a payload over cap throws PROTO_TOO_LARGE`() {
        val huge = ByteArray(WireProtocol.MAX_FRAME_PAYLOAD_BYTES + 1)
        val frame = WireFrame(MessageType.STDOUT_DATA, huge)
        val ex = assertThrows(WireProtocolException::class.java) {
            WireFrameCodec.write(ByteArrayOutputStream(), frame)
        }
        assertEquals("PROTO_TOO_LARGE", ex.code)
    }

    // ─── MessageType tag table ───────────────────────────────────────

    @Test
    fun `every MessageType has a unique tag`() {
        val tags = MessageType.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size, "duplicate tags: ${MessageType.entries.groupBy { it.tag }.filter { it.value.size > 1 }}")
    }

    @Test
    fun `fromTag round-trips every type`() {
        for (t in MessageType.entries) {
            assertEquals(t, MessageType.fromTag(t.tag), "round-trip failed for $t")
        }
    }

    @Test
    fun `fromTag returns null for unknown tag`() {
        assertNull(MessageType.fromTag(0x7E))
    }

    // ─── Payload CBOR round-trips ────────────────────────────────────

    @Test
    fun `Hello round-trips with all fields`() {
        val h = Hello(
            versionMajor = 1, versionMinor = 0,
            capabilities = listOf("core", "interrupt", "profile"),
            client = "mez-dev",
        )
        assertEquals(h, WireFrame.of(MessageType.HELLO, h).decode<Hello>())
    }

    @Test
    fun `Welcome round-trips with runtime info`() {
        val w = Welcome(
            versionMajor = 1, versionMinor = 0,
            capabilities = listOf("core", "interrupt"),
            agreedVersionMajor = 1, agreedVersionMinor = 0,
            agreedCapabilities = listOf("core"),
            runtimeInfo = RuntimeInfo(
                runtimeVersion = "0.1.0",
                jvmVendor = "Oracle GraalVM",
                jvmVersion = "24.0.2",
                languagesAvailable = listOf("mezze", "python"),
                jitEnabled = true,
            ),
        )
        assertEquals(w, WireFrame.of(MessageType.WELCOME, w).decode<Welcome>())
    }

    @Test
    fun `Load round-trips`() {
        val l = Load(fnsCborPath = "/tmp/fns.cbor", metaCborPath = "/tmp/meta.cbor", mode = "run")
        assertEquals(l, WireFrame.of(MessageType.LOAD, l).decode<Load>())
    }

    @Test
    fun `Exited round-trips with detail`() {
        val e = Exited(
            runId = 42,
            code = 1,
            reason = "error",
            detail = ExitDetail(
                message = "division by zero",
                stack = listOf(
                    StackFrame(function = "divide", file = "math.mz", line = 12, col = 5),
                    StackFrame(function = "main", file = "app.mz", line = 3),
                ),
                sourceFile = "math.mz",
                sourceLine = 12,
            ),
        )
        assertEquals(e, WireFrame.of(MessageType.EXITED, e).decode<Exited>())
    }

    @Test
    fun `Exited round-trips without detail`() {
        val e = Exited(runId = 1, code = 0, reason = "normal")
        assertEquals(e, WireFrame.of(MessageType.EXITED, e).decode<Exited>())
    }

    @Test
    fun `StdoutData round-trips with binary bytes`() {
        val s = StdoutData(runId = 1, bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0x7F))
        assertEquals(s, WireFrame.of(MessageType.STDOUT_DATA, s).decode<StdoutData>())
    }

    @Test
    fun `StdoutData round-trips with empty bytes`() {
        val s = StdoutData(runId = 1, bytes = ByteArray(0))
        assertEquals(s, WireFrame.of(MessageType.STDOUT_DATA, s).decode<StdoutData>())
    }

    @Test
    fun `Evict round-trips with hash list`() {
        val e = Evict(hashes = listOf("abc123", "def456", "0".repeat(64)))
        assertEquals(e, WireFrame.of(MessageType.EVICT, e).decode<Evict>())
    }

    @Test
    fun `ProtocolError round-trips with all fields`() {
        val p = ProtocolError(
            code = "PROTO_STATE_INVALID",
            message = "LOAD not allowed in INTERRUPTING state",
            inReplyTo = MessageType.LOAD.tag,
        )
        assertEquals(p, WireFrame.of(MessageType.ERROR, p).decode<ProtocolError>())
    }

    @Test
    fun `Config round-trips`() {
        val c = Config(keys = mapOf(
            "inspect" to "true",
            "inspect.port" to "4242",
            "profile.out" to "/tmp/profile.txt",
        ))
        assertEquals(c, WireFrame.of(MessageType.CONFIG, c).decode<Config>())
    }

    @Test
    fun `ConfigAck round-trips with mixed accept-reject`() {
        val a = ConfigAck(
            accepted = listOf("inspect", "profile.out"),
            rejected = mapOf("nonsense.key" to "unknown capability"),
        )
        assertEquals(a, WireFrame.of(MessageType.CONFIG_ACK, a).decode<ConfigAck>())
    }

    @Test
    fun `StatusReply round-trips`() {
        val s = StatusReply(
            state = "running",
            activeRunId = 7,
            scopeIds = listOf("default", "scratch"),
            handleCount = 12,
            memoryBytes = 128 * 1024 * 1024,
        )
        assertEquals(s, WireFrame.of(MessageType.STATUS_REPLY, s).decode<StatusReply>())
    }

    @Test
    fun `Log round-trips`() {
        val l = Log(level = "warn", source = "runtime", message = "polyglot context rebuilding")
        assertEquals(l, WireFrame.of(MessageType.LOG, l).decode<Log>())
    }

    @Test
    fun `Eval round-trips REPL-style input`() {
        val e = Eval(source = "let xs = [1,2,3]", scopeId = "default")
        assertEquals(e, WireFrame.of(MessageType.EVAL, e).decode<Eval>())
    }

    @Test
    fun `EvalResult round-trips with handle`() {
        val r = EvalResult(
            evalId = 1,
            handle = "h_42",
            rendered = "[1, 2, 3]",
            type = "Seq Int",
        )
        assertEquals(r, WireFrame.of(MessageType.EVAL_RESULT, r).decode<EvalResult>())
    }

    @Test
    fun `Interrupt round-trips with optional run_id`() {
        val noId = Interrupt()
        val withId = Interrupt(runId = 42)
        assertEquals(noId, WireFrame.of(MessageType.INTERRUPT, noId).decode<Interrupt>())
        assertEquals(withId, WireFrame.of(MessageType.INTERRUPT, withId).decode<Interrupt>())
    }

    @Test
    fun `Reject round-trips`() {
        val r = Reject(reason = "version_unsupported", detail = "client wants v2, server is v1")
        assertEquals(r, WireFrame.of(MessageType.REJECT, r).decode<Reject>())
    }

    // ─── Forward-compatibility: ignore unknown fields ────────────────

    @Test
    fun `decoding payload with extra fields succeeds`() {
        // CBOR map with an unknown key. Older client receiving a newer
        // server's payload should still decode the known fields.
        val knownThenExtra = mapOf(
            "code" to "PROTO_DECODE",
            "message" to "test",
            "future_field" to "ignored",
        )
        val bytes = WireProtocol.cbor.encodeToByteArray(
            kotlinx.serialization.serializer<Map<String, String>>(),
            knownThenExtra,
        )
        val decoded = WireProtocol.cbor.decodeFromByteArray(
            kotlinx.serialization.serializer<ProtocolError>(),
            bytes,
        )
        assertEquals("PROTO_DECODE", decoded.code)
        assertEquals("test", decoded.message)
        assertNull(decoded.inReplyTo)
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun encode(frame: WireFrame): ByteArray {
        val out = ByteArrayOutputStream()
        WireFrameCodec.write(out, frame)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): WireFrame {
        val read = WireFrameCodec.read(ByteArrayInputStream(bytes))
        assertNotNull(read, "expected a frame, got null (clean EOF)")
        return read!!
    }
}
