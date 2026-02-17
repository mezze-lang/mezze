package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

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
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerConsoleOperations(context)
        registerLoggingOperations(context)
    }

    private fun registerConsoleOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())

        context["__native_console_print"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )

        context["__native_console_println"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )
    }

    private fun registerLoggingOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())

        context["__native_log_info"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )

        context["__native_log_warn"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )

        context["__native_log_error"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )

        context["__native_log_debug"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )

        context["__native_log_trace"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("msg" to MezzeType.StringType)),
            unitType
        )
    }
}
