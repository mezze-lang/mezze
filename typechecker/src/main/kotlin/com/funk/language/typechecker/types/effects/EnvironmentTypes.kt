package com.funk.language.typechecker.types.effects

import com.funk.language.ast.FunkType

/**
 * Helper function to create Option type (as TypeApplication)
 */
private fun optionType(elementType: FunkType): FunkType {
    return FunkType.TypeApplication("Option", listOf(elementType))
}

/**
 * Type signatures for environment effect operations
 *
 * Corresponds to stdlib file: std/effects/environment.funk
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
    fun registerAll(context: MutableMap<String, FunkType>) {
        registerEnvironmentVariableOperations(context)
        registerDirectoryOperations(context)
        registerSystemInformationOperations(context)
        registerProcessInformationOperations(context)
        registerProcessControlOperations(context)
        registerMockEnvironmentOperations(context)
    }

    private fun registerEnvironmentVariableOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        context["__native_env_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("name" to FunkType.StringType)),
            optionType(FunkType.StringType)
        )

        context["__native_env_set"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "name" to FunkType.StringType,
                "value" to FunkType.StringType
            )),
            unitType
        )

        context["__native_env_get_all"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.ArrayType(FunkType.RecordType(mapOf("key" to FunkType.StringType, "value" to FunkType.StringType)))
        )
    }

    private fun registerDirectoryOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        context["__native_env_current_dir"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_change_dir"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("path" to FunkType.StringType)),
            unitType
        )
    }

    private fun registerSystemInformationOperations(context: MutableMap<String, FunkType>) {
        context["__native_env_os_name"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_os_version"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )

        context["__native_env_arch"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.StringType
        )
    }

    private fun registerProcessInformationOperations(context: MutableMap<String, FunkType>) {
        context["__native_env_process_id"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.IntType
        )

        context["__native_env_process_args"] = FunkType.FunctionType(
            FunkType.RecordType(emptyMap()),
            FunkType.ArrayType(FunkType.StringType)
        )
    }

    private fun registerProcessControlOperations(context: MutableMap<String, FunkType>) {
        val unitType = FunkType.RecordType(emptyMap())

        context["__native_env_exit"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("code" to FunkType.IntType)),
            unitType
        )
    }

    private fun registerMockEnvironmentOperations(context: MutableMap<String, FunkType>) {
        context["__native_mock_env_get"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "env_vars" to FunkType.ArrayType(FunkType.RecordType(mapOf("key" to FunkType.StringType, "value" to FunkType.StringType))),
                "name" to FunkType.StringType
            )),
            optionType(FunkType.StringType)
        )
    }
}
