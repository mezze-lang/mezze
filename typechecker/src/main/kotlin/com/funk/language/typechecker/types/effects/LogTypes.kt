package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType

/**
 * Type signatures for console and logging effect operations
 *
 * Corresponds to stdlib file: std/effects/log.funk
 *
 * Provides types for:
 * - Console output (print, println)
 * - Structured logging (info, warn, error, debug, trace)
 */
object LogTypes {
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerConsoleOperations(context)
        registerLoggingOperations(context)
    }

    private fun registerConsoleOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        context["__native_console_print"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )

        context["__native_console_println"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )
    }

    private fun registerLoggingOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        context["__native_log_info"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )

        context["__native_log_warn"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )

        context["__native_log_error"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )

        context["__native_log_debug"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )

        context["__native_log_trace"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("msg" to FunkType.StringType)),
            unitType
        )
    }
}
