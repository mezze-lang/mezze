package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

/**
 * Type signatures for console and logging effect operations
 *
 * Corresponds to stdlib file: std/effects/log.mz
 *
 * Provides types for:
 * - Console output (print, println)
 * - Structured logging (info, warn, error, debug, trace)
 */
object LogTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Console operations
        fn("__native_console_print") { param("msg" to str); returns(unit) }
        fn("__native_console_println") { param("msg" to str); returns(unit) }

        // Logging operations
        fn("__native_log_info") { param("msg" to str); returns(unit) }
        fn("__native_log_warn") { param("msg" to str); returns(unit) }
        fn("__native_log_error") { param("msg" to str); returns(unit) }
        fn("__native_log_debug") { param("msg" to str); returns(unit) }
        fn("__native_log_trace") { param("msg" to str); returns(unit) }
    }
}
