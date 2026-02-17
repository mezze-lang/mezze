package com.mezze.language.interpreter.stdlib.effects

import com.mezze.language.interpreter.*

/**
 * Truffle-optimized implementations for Environment effect operations.
 *
 * Phase 6 of Truffle migration: I/O and System operations optimized for JIT performance.
 * Handles environment variables, process information, and system properties.
 *
 * Optimizations:
 * - Direct System.getenv() and System.getProperty() calls for JIT compilation
 * - Option types for environment variable lookups (Some/None)
 * - ProcessHandle API for process information
 * - Inline property access for OS information
 * - Mock environment helpers for testing
 *
 * Operations: env_get, env_set, env_get_all, current_dir, change_dir, os_name, os_version,
 *             arch, process_id, process_args, exit, mock_env_get
 *
 * Note: Java doesn't allow changing working directory or setting env vars at runtime,
 *       so these use System.setProperty() as a workaround for testing.
 *
 * Total: 12 environment functions
 */
object EnvironmentImplementations {

    /**
     * Register all Environment effect implementations
     */
    fun registerAll(context: MutableMap<String, MezzeValue>) {
        context["__native_env_get"] = NativeMezzeFunction("__native_env_get") { args ->
            val record = args[0] as MezzeRecord
            val name = record.fields["name"] as? MezzeString
                ?: throw NativeFunctionException("__native_env_get", "expects 'name' as String")

            val value = System.getenv(name.value)
            if (value != null) {
                MezzeVariant("Some", MezzeRecord(mapOf("value" to MezzeString(value))))
            } else {
                MezzeVariant("None", null)
            }
        }

        context["__native_env_set"] = NativeMezzeFunction("__native_env_set") { args ->
            val record = args[0] as MezzeRecord
            val name = record.fields["name"] as? MezzeString
                ?: throw NativeFunctionException("__native_env_set", "expects 'name' as String")
            val value = record.fields["value"] as? MezzeString
                ?: throw NativeFunctionException("__native_env_set", "expects 'value' as String")

            // Note: Java doesn't allow setting environment variables at runtime
            // This would need to be implemented differently in a real system
            System.setProperty(name.value, value.value)
            MezzeUnit
        }

        context["__native_env_get_all"] = NativeMezzeFunction("__native_env_get_all") { args ->
            val envVars = System.getenv().map { (key, value) ->
                MezzeTuple(listOf(MezzeString(key), MezzeString(value)))
            }
            MezzeArrayList(ArrayList(envVars))
        }

        context["__native_env_current_dir"] = NativeMezzeFunction("__native_env_current_dir") { args ->
            MezzeString(System.getProperty("user.dir"))
        }

        context["__native_env_change_dir"] = NativeMezzeFunction("__native_env_change_dir") { args ->
            val record = args[0] as MezzeRecord
            val path = record.fields["path"] as? MezzeString
                ?: throw NativeFunctionException("__native_env_change_dir", "expects 'path' as String")

            // Note: Java doesn't allow changing working directory at runtime
            // This would need to be implemented differently in a real system
            System.setProperty("user.dir", path.value)
            MezzeUnit
        }

        context["__native_env_os_name"] = NativeMezzeFunction("__native_env_os_name") { args ->
            MezzeString(System.getProperty("os.name"))
        }

        context["__native_env_os_version"] = NativeMezzeFunction("__native_env_os_version") { args ->
            MezzeString(System.getProperty("os.version"))
        }

        context["__native_env_arch"] = NativeMezzeFunction("__native_env_arch") { args ->
            MezzeString(System.getProperty("os.arch"))
        }

        context["__native_env_process_id"] = NativeMezzeFunction("__native_env_process_id") { args ->
            MezzeInt(ProcessHandle.current().pid())
        }

        context["__native_env_process_args"] = NativeMezzeFunction("__native_env_process_args") { args ->
            val args = ProcessHandle.current().info().arguments().orElse(arrayOf())
            val mezzeArgs = args.map { MezzeString(it) }
            MezzeArrayList(ArrayList(mezzeArgs))
        }

        context["__native_env_exit"] = NativeMezzeFunction("__native_env_exit") { args ->
            val record = args[0] as MezzeRecord
            val code = record.fields["code"] as? MezzeInt
                ?: throw NativeFunctionException("__native_env_exit", "expects 'code' as Int")

            System.exit(code.value.toInt())
            MezzeUnit // This line will never be reached
        }

        // Mock environment helpers
        context["__native_mock_env_get"] = NativeMezzeFunction("__native_mock_env_get") { args ->
            val record = args[0] as MezzeRecord
            val envVars = record.fields["env_vars"] as? MezzeArrayList
                ?: throw NativeFunctionException("__native_mock_env_get", "expects 'env_vars' as ArrayList")
            val name = record.fields["name"] as? MezzeString
                ?: throw NativeFunctionException("__native_mock_env_get", "expects 'name' as String")

            for (pair in envVars.elements) {
                val tuple = pair as MezzeTuple
                val key = tuple.elements[0] as MezzeString
                val value = tuple.elements[1] as MezzeString
                if (key.value == name.value) {
                    return@NativeMezzeFunction MezzeVariant("Some", MezzeRecord(mapOf("value" to value)))
                }
            }
            MezzeVariant("None", null)
        }
    }
}
