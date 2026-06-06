package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object ByteBufferTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_buffer_new") { param("capacity" to int); returns(byteBuffer) }
        fn("__native_buffer_empty") { returns(byteBuffer) }
        fn("__native_buffer_write") { param("buf" to byteBuffer, "bytes" to bytes); returns(byteBuffer) }
        fn("__native_buffer_write_byte") { param("buf" to byteBuffer, "b" to int); returns(byteBuffer) }
        fn("__native_buffer_write_string") { param("buf" to byteBuffer, "s" to str); returns(byteBuffer) }
        fn("__native_buffer_to_bytes") { param("buf" to byteBuffer); returns(bytes) }
        fn("__native_buffer_size") { param("buf" to byteBuffer); returns(int) }
        fn("__native_buffer_is_empty") { param("buf" to byteBuffer); returns(bool) }
        fn("__native_buffer_clear") { param("buf" to byteBuffer); returns(byteBuffer) }
    }
}
