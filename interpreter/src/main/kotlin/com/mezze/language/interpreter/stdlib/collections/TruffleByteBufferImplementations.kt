package com.mezze.language.interpreter.stdlib.collections

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for ByteBuffer operations.
 *
 * ByteBuffer is a mutable byte buffer for efficient streaming/building.
 * It presents a functional API but mutates internally for O(1) amortized appends.
 *
 * Performance characteristics:
 * - new: O(1)
 * - write/write_byte/write_string: O(1) amortized
 * - to_bytes: O(n) - copies to immutable Bytes
 * - size: O(1)
 * - clear: O(1)
 *
 * Memory: Uses ByteArrayOutputStream which doubles capacity when needed,
 * providing good amortized performance for streaming use cases.
 */
object TruffleByteBufferImplementations {

    fun registerAll(context: MutableMap<String, MezzeValue>) {

        // ============================================
        // Construction
        // ============================================

        // buffer::new { capacity: Int } -> ByteBuffer
        context["__native_buffer_new"] = NativeMezzeFunction("__native_buffer_new") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_new", "expects MezzeRecord")
            val capacity = (record.fields["capacity"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_buffer_new", "expects 'capacity' as Int")

            if (capacity < 0) {
                throw NativeFunctionException("__native_buffer_new", "capacity must be non-negative")
            }
            MezzeByteBuffer.withCapacity(capacity)
        }

        // buffer::empty {} -> ByteBuffer
        context["__native_buffer_empty"] = NativeMezzeFunction("__native_buffer_empty") { _ ->
            MezzeByteBuffer.empty()
        }

        // ============================================
        // Writing
        // ============================================

        // buffer::write { buf: ByteBuffer, bytes: Bytes } -> ByteBuffer
        context["__native_buffer_write"] = NativeMezzeFunction("__native_buffer_write") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_write", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_write", "expects 'buf' as ByteBuffer")
            val bytes = record.fields["bytes"] as? MezzeBytes
                ?: throw NativeFunctionException("__native_buffer_write", "expects 'bytes' as Bytes")

            buf.write(bytes)
        }

        // buffer::write_byte { buf: ByteBuffer, b: Int } -> ByteBuffer
        context["__native_buffer_write_byte"] = NativeMezzeFunction("__native_buffer_write_byte") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_write_byte", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_write_byte", "expects 'buf' as ByteBuffer")
            val b = (record.fields["b"] as? MezzeInt)?.value?.toInt()
                ?: throw NativeFunctionException("__native_buffer_write_byte", "expects 'b' as Int")

            if (b < 0 || b > 255) {
                throw NativeFunctionException("__native_buffer_write_byte", "byte value must be 0-255, got $b")
            }
            buf.writeByte(b)
        }

        // buffer::write_string { buf: ByteBuffer, s: String } -> ByteBuffer
        context["__native_buffer_write_string"] = NativeMezzeFunction("__native_buffer_write_string") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_write_string", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_write_string", "expects 'buf' as ByteBuffer")
            val s = (record.fields["s"] as? MezzeString)?.value
                ?: throw NativeFunctionException("__native_buffer_write_string", "expects 's' as String")

            buf.writeString(s)
        }

        // ============================================
        // Conversion
        // ============================================

        // buffer::to_bytes { buf: ByteBuffer } -> Bytes
        context["__native_buffer_to_bytes"] = NativeMezzeFunction("__native_buffer_to_bytes") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_to_bytes", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_to_bytes", "expects 'buf' as ByteBuffer")

            buf.toBytes()
        }

        // ============================================
        // Query
        // ============================================

        // buffer::size { buf: ByteBuffer } -> Int
        context["__native_buffer_size"] = NativeMezzeFunction("__native_buffer_size") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_size", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_size", "expects 'buf' as ByteBuffer")

            MezzeInt(buf.size().toLong())
        }

        // buffer::is_empty { buf: ByteBuffer } -> Bool
        context["__native_buffer_is_empty"] = NativeMezzeFunction("__native_buffer_is_empty") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_is_empty", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_is_empty", "expects 'buf' as ByteBuffer")

            MezzeBool(buf.size() == 0)
        }

        // ============================================
        // Mutation
        // ============================================

        // buffer::clear { buf: ByteBuffer } -> ByteBuffer
        context["__native_buffer_clear"] = NativeMezzeFunction("__native_buffer_clear") { args ->
            val record = args.firstOrNull() as? MezzeRecord
                ?: throw NativeFunctionException("__native_buffer_clear", "expects MezzeRecord")
            val buf = record.fields["buf"] as? MezzeByteBuffer
                ?: throw NativeFunctionException("__native_buffer_clear", "expects 'buf' as ByteBuffer")

            buf.clear()
        }
    }
}
