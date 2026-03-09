package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: MezzeType): MezzeType {
    return MezzeType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for environment effect operations
 *
 * Corresponds to stdlib file: std/effects/environment.mz
 *
 * Provides types for:
 * - Environment variable access (get, set, get_all)
 * - Directory operations (current_dir, change_dir)
 * - System information (os_name, os_version, arch)
 * - Process information (process_id, process_args)
 * - Process control (exit)
 * - Mock environment operations (for testing)
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
            MezzeType.SeqType(MezzeType.RecordType(mapOf("key" to MezzeType.StringType, "value" to MezzeType.StringType)))
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
                "env_vars" to MezzeType.SeqType(MezzeType.RecordType(mapOf("key" to MezzeType.StringType, "value" to MezzeType.StringType))),
                "name" to MezzeType.StringType
            )),
            optionType(MezzeType.StringType)
        )
    }
}
