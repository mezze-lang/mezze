package com.mezze.language.typechecker.types.collections

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

object BytesTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_bytes_empty") { returns(bytes) }
        fn("__native_bytes_from_string") { param("s" to str); returns(bytes) }
        fn("__native_bytes_from_seq") { param("arr" to seq(int)); returns(bytes) }
        fn("__native_bytes_from_hex") { param("s" to str); returns(option(bytes)) }
        fn("__native_bytes_from_base64") { param("s" to str); returns(option(bytes)) }
        fn("__native_bytes_repeat") { param("b" to bytes, "n" to int); returns(bytes) }
        fn("__native_bytes_size") { param("b" to bytes); returns(int) }
        fn("__native_bytes_get") { param("b" to bytes, "i" to int); returns(option(int)) }
        fn("__native_bytes_is_empty") { param("b" to bytes); returns(bool) }
        fn("__native_bytes_slice") { param("b" to bytes, "start" to int, "end" to int); returns(bytes) }
        fn("__native_bytes_take") { param("b" to bytes, "n" to int); returns(bytes) }
        fn("__native_bytes_drop") { param("b" to bytes, "n" to int); returns(bytes) }
        fn("__native_bytes_concat") { param("b1" to bytes, "b2" to bytes); returns(bytes) }
        fn("__native_bytes_concat_all") { param("bs" to seq(bytes)); returns(bytes) }
        fn("__native_bytes_index_of") { param("b" to bytes, "pattern" to bytes); returns(option(int)) }
        fn("__native_bytes_contains") { param("b" to bytes, "pattern" to bytes); returns(bool) }
        fn("__native_bytes_starts_with") { param("b" to bytes, "prefix" to bytes); returns(bool) }
        fn("__native_bytes_ends_with") { param("b" to bytes, "suffix" to bytes); returns(bool) }
        fn("__native_bytes_equals") { param("b1" to bytes, "b2" to bytes); returns(bool) }
        fn("__native_bytes_compare") { param("b1" to bytes, "b2" to bytes); returns(int) }
        fn("__native_bytes_to_str") { param("b" to bytes); returns(result(str, str)) }
        fn("__native_bytes_to_seq") { param("b" to bytes); returns(seq(int)) }
        fn("__native_bytes_to_hex") { param("b" to bytes); returns(str) }
        fn("__native_bytes_to_base64") { param("b" to bytes); returns(str) }
        fn("__native_bytes_reverse") { param("b" to bytes); returns(bytes) }
        fn("__native_bytes_map") {
            param("b" to bytes, "f" to MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("x" to int)),
                int
            ))
            returns(bytes)
        }
        fn("__native_bytes_fold") {
            val a = tvar("a")
            param("b" to bytes, "init" to a, "f" to MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("acc" to a, "byte" to int)),
                a
            ))
            returns(a)
        }
    }
}
