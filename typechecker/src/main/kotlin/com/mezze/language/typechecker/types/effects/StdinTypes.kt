package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

/**
 * Type signatures for stdin effect natives.
 *
 * Corresponds to stdlib file: std/effects/stdin.mz
 */
object StdinTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        fn("__native_stdin_read_line") { returns(option(str)) }
        fn("__native_stdin_read_char") { returns(option(char)) }
        fn("__native_stdin_read_bytes") { param("max" to int); returns(bytes) }
        fn("__native_stdin_is_tty") { returns(bool) }
        fn("__native_stdin_read_password") { returns(option(str)) }
        fn("__native_stdin_term_size") { returns(rec("rows" to int, "cols" to int)) }
        fn("__native_stdin_set_raw_mode") { returns(unit) }
        fn("__native_stdin_unset_raw_mode") { returns(unit) }

        // Mock stdin
        fn("__native_mock_stdin_read_line") { param("lines" to seq(str)); returns(option(str)) }
        fn("__native_mock_stdin_read_char") { param("lines" to seq(str)); returns(option(char)) }
        fn("__native_mock_stdin_read_bytes") { param("lines" to seq(str), "max" to int); returns(bytes) }
        fn("__native_mock_stdin_read_password") { param("lines" to seq(str)); returns(option(str)) }
    }
}
