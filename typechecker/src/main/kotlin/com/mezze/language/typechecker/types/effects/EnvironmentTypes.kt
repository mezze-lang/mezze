package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return com.mezze.language.typechecker.util.WellKnownTypeApp.of("Option", listOf(elementType))
}

/**
 * Type signatures for the five OS-surface native functions that back the
 * Env / Args / Cwd / Os / Process effects (in stdlib/std/effects/{env,
 * args, cwd, os, process}.mz). The natives share a common __native_env_*
 * prefix for historical reasons — they all live in EnvironmentNatives.java
 * — even though they're surfaced as distinct effects to user code.
 *
 * Categories:
 * - Env vars: __native_env_get, __native_env_set, __native_env_get_all,
 *   __native_mock_env_get
 * - Cwd:      __native_env_current_dir, __native_env_change_dir
 * - Os:       __native_env_os_name, __native_env_os_version, __native_env_arch
 * - Process:  __native_env_process_id, __native_env_process_args,
 *   __native_env_exit
 */
object EnvironmentTypes {
    fun registerAll(context: MutableMap<String, MezzeType>) {
        registerEnvironmentVariableOperations(context)
        registerDirectoryOperations(context)
        registerSystemInformationOperations(context)
        registerProcessInformationOperations(context)
        registerProcessControlOperations(context)
        registerMockEnvironmentOperations(context)
    }

    private fun registerEnvironmentVariableOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())

        context["__native_env_get"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("name" to MezzeType.StringType)),
            optionType(MezzeType.StringType)
        )

        context["__native_env_set"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "name" to MezzeType.StringType,
                "value" to MezzeType.StringType
            )),
            unitType
        )

        context["__native_env_get_all"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.SeqType(MezzeType.RecordType(mapOf("k" to MezzeType.StringType, "v" to MezzeType.StringType)))
        )
    }

    private fun registerDirectoryOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())

        context["__native_env_current_dir"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.StringType
        )

        context["__native_env_change_dir"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("path" to MezzeType.StringType)),
            unitType
        )
    }

    private fun registerSystemInformationOperations(context: MutableMap<String, MezzeType>) {
        context["__native_env_os_name"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.StringType
        )

        context["__native_env_os_version"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.StringType
        )

        context["__native_env_arch"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.StringType
        )
    }

    private fun registerProcessInformationOperations(context: MutableMap<String, MezzeType>) {
        context["__native_env_process_id"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.IntType
        )

        context["__native_env_process_args"] = MezzeType.FunctionType(
            MezzeType.RecordType(emptyMap()),
            MezzeType.SeqType(MezzeType.StringType)
        )
    }

    private fun registerProcessControlOperations(context: MutableMap<String, MezzeType>) {
        val unitType = MezzeType.RecordType(emptyMap())

        context["__native_env_exit"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf("code" to MezzeType.IntType)),
            unitType
        )
    }

    private fun registerMockEnvironmentOperations(context: MutableMap<String, MezzeType>) {
        context["__native_mock_env_get"] = MezzeType.FunctionType(
            MezzeType.RecordType(mapOf(
                "env_vars" to MezzeType.SeqType(MezzeType.RecordType(mapOf("k" to MezzeType.StringType, "v" to MezzeType.StringType))),
                "name" to MezzeType.StringType
            )),
            optionType(MezzeType.StringType)
        )
    }
}
