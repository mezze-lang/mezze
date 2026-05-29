package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.util.WellKnownTypeApp

private fun optionType(elementType: MezzeType): MezzeType =
    WellKnownTypeApp.of("Option", listOf(elementType))

/**
 * Type signatures for stdin effect natives.
 *
 * Corresponds to stdlib file: std/effects/stdin.mz
 */
object StdinTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        val unitArg = MezzeType.RecordType(emptyMap())

        context["__native_stdin_read_line"] = MezzeType.FunctionType(
            unitArg,
            optionType(MezzeType.StringType)
        )

        context["__native_stdin_read_char"] = MezzeType.FunctionType(
            unitArg,
            optionType(MezzeType.CharType)
        )

        context["__native_stdin_read_bytes"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("max" to MezzeType.IntType)),
            MezzeType.BytesType
        )

        context["__native_stdin_is_tty"] = MezzeType.FunctionType(
            unitArg,
            MezzeType.BoolType
        )

        context["__native_stdin_read_password"] = MezzeType.FunctionType(
            unitArg,
            optionType(MezzeType.StringType)
        )

        val linesArg = MezzeType.RecordType(mapOf("lines" to MezzeType.SeqType(MezzeType.StringType)))

        context["__native_mock_stdin_read_line"] = MezzeType.FunctionType(
            linesArg,
            optionType(MezzeType.StringType)
        )

        context["__native_mock_stdin_read_char"] = MezzeType.FunctionType(
            linesArg,
            optionType(MezzeType.CharType)
        )

        context["__native_mock_stdin_read_bytes"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "lines" to MezzeType.SeqType(MezzeType.StringType),
                "max" to MezzeType.IntType,
            )),
            MezzeType.BytesType
        )

        context["__native_mock_stdin_read_password"] = MezzeType.FunctionType(
            linesArg,
            optionType(MezzeType.StringType)
        )
    }
}
