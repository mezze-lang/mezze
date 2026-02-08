package com.funk.language.typechecker.types.collections

import com.funk.language.ast.FunkType

/**
 * Type signatures for ByteBuffer (mutable byte buffer) operations
 *
 * Corresponds to stdlib file:
 * - std/collections/buffer.funk
 *
 * ByteBuffer is a mutable byte buffer for efficient streaming/building.
 * It presents a functional API but mutates internally for O(1) amortized appends.
 */
object ByteBufferTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerByteBufferNatives(context)
    }

    private fun registerByteBufferNatives(context: MutableMap<String, FunkType>) {
        // __native_buffer_new: {capacity: Int} -> ByteBuffer
        context["__native_buffer_new"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("capacity" to FunkType.IntType)),
            FunkType.ByteBufferType
        )

        // __native_buffer_empty: {} -> ByteBuffer
        context["__native_buffer_empty"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.ByteBufferType
        )

        // __native_buffer_write: {buf: ByteBuffer, bytes: Bytes} -> ByteBuffer
        context["__native_buffer_write"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "buf" to FunkType.ByteBufferType,
                "bytes" to FunkType.BytesType
            )),
            FunkType.ByteBufferType
        )

        // __native_buffer_write_byte: {buf: ByteBuffer, b: Int} -> ByteBuffer
        context["__native_buffer_write_byte"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "buf" to FunkType.ByteBufferType,
                "b" to FunkType.IntType
            )),
            FunkType.ByteBufferType
        )

        // __native_buffer_write_string: {buf: ByteBuffer, s: String} -> ByteBuffer
        context["__native_buffer_write_string"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "buf" to FunkType.ByteBufferType,
                "s" to FunkType.StringType
            )),
            FunkType.ByteBufferType
        )

        // __native_buffer_to_bytes: {buf: ByteBuffer} -> Bytes
        context["__native_buffer_to_bytes"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("buf" to FunkType.ByteBufferType)),
            FunkType.BytesType
        )

        // __native_buffer_size: {buf: ByteBuffer} -> Int
        context["__native_buffer_size"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("buf" to FunkType.ByteBufferType)),
            FunkType.IntType
        )

        // __native_buffer_is_empty: {buf: ByteBuffer} -> Bool
        context["__native_buffer_is_empty"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("buf" to FunkType.ByteBufferType)),
            FunkType.BooleanType
        )

        // __native_buffer_clear: {buf: ByteBuffer} -> ByteBuffer
        context["__native_buffer_clear"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("buf" to FunkType.ByteBufferType)),
            FunkType.ByteBufferType
        )
    }
}
