package com.mezze.wire

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed binary frame codec. One frame on the wire:
 *
 *   ┌───────────┬────────┬─────────────┐
 *   │ u32 len   │ u8 tag │ payload     │
 *   │ big-endian│        │ (len-1 B)   │
 *   └───────────┴────────┴─────────────┘
 *
 * `len` is the count of bytes after the length field, so frame total =
 * 4 + len. `len` must be ≥ 1 (every frame has at least a tag byte).
 *
 * No I/O wrappers, no buffering, no threading — caller owns lifecycle of
 * the streams. The codec validates `len` against
 * [WireProtocol.MAX_FRAME_PAYLOAD_BYTES] and throws
 * [WireProtocolException] on protocol violations.
 *
 * Thread-safety: a single instance is not thread-safe; callers must
 * serialize access. Typically: one [WireFrameCodec] for the reader-side
 * stream, another for the writer-side stream.
 */
object WireFrameCodec {

    /**
     * Read one frame from [input]. Returns null on clean EOF (read 0
     * bytes before the length field). Throws on partial frames or
     * protocol violations.
     */
    fun read(input: InputStream): WireFrame? {
        val data = DataInputStream(input)
        // Read the 4-byte length manually so we can distinguish "clean EOF
        // before any byte" (return null) from "partial length field"
        // (throw PROTO_DECODE). DataInputStream.readInt() conflates the
        // two by always throwing EOFException.
        val lenBytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(lenBytes, read, 4 - read)
            if (n < 0) {
                if (read == 0) return null  // clean EOF before any data
                throw WireProtocolException(
                    "PROTO_DECODE",
                    "partial length field: read $read of 4 bytes before EOF"
                )
            }
            read += n
        }
        val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                  ((lenBytes[1].toInt() and 0xFF) shl 16) or
                  ((lenBytes[2].toInt() and 0xFF) shl 8) or
                  (lenBytes[3].toInt() and 0xFF)
        if (len < 1) {
            throw WireProtocolException(
                "PROTO_DECODE",
                "frame length must be >= 1 (got $len)"
            )
        }
        if (len - 1 > WireProtocol.MAX_FRAME_PAYLOAD_BYTES) {
            throw WireProtocolException(
                "PROTO_TOO_LARGE",
                "frame payload ${len - 1} bytes exceeds cap ${WireProtocol.MAX_FRAME_PAYLOAD_BYTES}"
            )
        }
        val tag = try {
            data.readByte()
        } catch (e: EOFException) {
            throw WireProtocolException("PROTO_DECODE", "EOF after length field, before tag", e)
        }
        val payloadLen = len - 1
        val payload = ByteArray(payloadLen)
        if (payloadLen > 0) {
            try {
                data.readFully(payload)
            } catch (e: EOFException) {
                throw WireProtocolException(
                    "PROTO_DECODE",
                    "EOF reading $payloadLen-byte payload (read incomplete)",
                    e
                )
            }
        }
        val type = MessageType.fromTag(tag) ?: throw WireProtocolException(
            "PROTO_UNKNOWN_TYPE",
            "unknown message tag 0x${tag.toUByte().toString(16).padStart(2, '0')}"
        )
        return WireFrame(type, payload)
    }

    /**
     * Write [frame] to [output]. Does not flush; caller flushes when
     * appropriate (typically after a logical batch of frames).
     */
    fun write(output: OutputStream, frame: WireFrame) {
        val payloadLen = frame.payload.size
        if (payloadLen > WireProtocol.MAX_FRAME_PAYLOAD_BYTES) {
            throw WireProtocolException(
                "PROTO_TOO_LARGE",
                "payload $payloadLen bytes exceeds cap ${WireProtocol.MAX_FRAME_PAYLOAD_BYTES}"
            )
        }
        val data = DataOutputStream(output)
        data.writeInt(payloadLen + 1)  // tag byte counts toward len
        data.writeByte(frame.type.tag.toInt())
        if (payloadLen > 0) data.write(frame.payload)
    }

    /**
     * Convenience: write a frame and flush.
     */
    fun writeFlush(output: OutputStream, frame: WireFrame) {
        write(output, frame)
        output.flush()
    }
}

/**
 * Thrown when the wire is malformed at the framing layer. Carries a
 * stable code matching [ProtocolError.code] vocabulary so callers can
 * surface or report uniformly.
 */
class WireProtocolException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
