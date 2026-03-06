package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType

/**
 * Type signatures for ByteBuffer (mutable byte buffer) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/buffer.mz
 *
 * ByteBuffer is a mutable byte buffer for efficient streaming/building.
 * It presents a functional API but mutates internally for O(1) amortized appends.
 */
object ByteBufferTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerByteBufferNatives(context)
    }

    private fun registerByteBufferNatives(context: MutableMap<String, MezzeType>) {
        // __native_buffer_new: {capacity: Int} -> ByteBuffer
        context["__native_buffer_new"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("capacity" to MezzeType.IntType)),
            MezzeType.ByteBufferType
        )

        // __native_buffer_empty: {} -> ByteBuffer
        context["__native_buffer_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.ByteBufferType
        )

        // __native_buffer_write: {buf: ByteBuffer, bytes: Bytes} -> ByteBuffer
        context["__native_buffer_write"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "buf" to MezzeType.ByteBufferType,
                "bytes" to MezzeType.BytesType
            )),
            MezzeType.ByteBufferType
        )

        // __native_buffer_write_byte: {buf: ByteBuffer, b: Int} -> ByteBuffer
        context["__native_buffer_write_byte"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "buf" to MezzeType.ByteBufferType,
                "b" to MezzeType.IntType
            )),
            MezzeType.ByteBufferType
        )

        // __native_buffer_write_string: {buf: ByteBuffer, s: String} -> ByteBuffer
        context["__native_buffer_write_string"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "buf" to MezzeType.ByteBufferType,
                "s" to MezzeType.StringType
            )),
            MezzeType.ByteBufferType
        )

        // __native_buffer_to_bytes: {buf: ByteBuffer} -> Bytes
        context["__native_buffer_to_bytes"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("buf" to MezzeType.ByteBufferType)),
            MezzeType.BytesType
        )

        // __native_buffer_size: {buf: ByteBuffer} -> Int
        context["__native_buffer_size"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("buf" to MezzeType.ByteBufferType)),
            MezzeType.IntType
        )

        // __native_buffer_is_empty: {buf: ByteBuffer} -> Bool
        context["__native_buffer_is_empty"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("buf" to MezzeType.ByteBufferType)),
            MezzeType.BoolType
        )

        // __native_buffer_clear: {buf: ByteBuffer} -> ByteBuffer
        context["__native_buffer_clear"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("buf" to MezzeType.ByteBufferType)),
            MezzeType.ByteBufferType
        )
    }
}
