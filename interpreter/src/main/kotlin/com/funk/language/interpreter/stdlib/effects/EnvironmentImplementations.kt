package com.funk.language.interpreter.stdlib.effects

import com.funk.language.interpreter.*

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
    fun registerAll(context: MutableMap<String, FunkValue>) {
        context["__native_env_get"] = NativeFunkFunction("__native_env_get") { args ->
            val record = args[0] as FunkRecord
            val name = record.fields["name"] as? FunkString
                ?: throw NativeFunctionException("__native_env_get", "expects 'name' as String")

            val value = System.getenv(name.value)
            if (value != null) {
                FunkVariant("Some", FunkRecord(mapOf("value" to FunkString(value))))
            } else {
                FunkVariant("None", null)
            }
        }

        context["__native_env_set"] = NativeFunkFunction("__native_env_set") { args ->
            val record = args[0] as FunkRecord
            val name = record.fields["name"] as? FunkString
                ?: throw NativeFunctionException("__native_env_set", "expects 'name' as String")
            val value = record.fields["value"] as? FunkString
                ?: throw NativeFunctionException("__native_env_set", "expects 'value' as String")

            // Note: Java doesn't allow setting environment variables at runtime
            // This would need to be implemented differently in a real system
            System.setProperty(name.value, value.value)
            FunkUnit
        }

        context["__native_env_get_all"] = NativeFunkFunction("__native_env_get_all") { args ->
            val envVars = System.getenv().map { (key, value) ->
                FunkTuple(listOf(FunkString(key), FunkString(value)))
            }
            FunkArrayList(ArrayList(envVars))
        }

        context["__native_env_current_dir"] = NativeFunkFunction("__native_env_current_dir") { args ->
            FunkString(System.getProperty("user.dir"))
        }

        context["__native_env_change_dir"] = NativeFunkFunction("__native_env_change_dir") { args ->
            val record = args[0] as FunkRecord
            val path = record.fields["path"] as? FunkString
                ?: throw NativeFunctionException("__native_env_change_dir", "expects 'path' as String")

            // Note: Java doesn't allow changing working directory at runtime
            // This would need to be implemented differently in a real system
            System.setProperty("user.dir", path.value)
            FunkUnit
        }

        context["__native_env_os_name"] = NativeFunkFunction("__native_env_os_name") { args ->
            FunkString(System.getProperty("os.name"))
        }

        context["__native_env_os_version"] = NativeFunkFunction("__native_env_os_version") { args ->
            FunkString(System.getProperty("os.version"))
        }

        context["__native_env_arch"] = NativeFunkFunction("__native_env_arch") { args ->
            FunkString(System.getProperty("os.arch"))
        }

        context["__native_env_process_id"] = NativeFunkFunction("__native_env_process_id") { args ->
            FunkInt(ProcessHandle.current().pid())
        }

        context["__native_env_process_args"] = NativeFunkFunction("__native_env_process_args") { args ->
            val args = ProcessHandle.current().info().arguments().orElse(arrayOf())
            val funkArgs = args.map { FunkString(it) }
            FunkArrayList(ArrayList(funkArgs))
        }

        context["__native_env_exit"] = NativeFunkFunction("__native_env_exit") { args ->
            val record = args[0] as FunkRecord
            val code = record.fields["code"] as? FunkInt
                ?: throw NativeFunctionException("__native_env_exit", "expects 'code' as Int")

            System.exit(code.value.toInt())
            FunkUnit // This line will never be reached
        }

        // Mock environment helpers
        context["__native_mock_env_get"] = NativeFunkFunction("__native_mock_env_get") { args ->
            val record = args[0] as FunkRecord
            val envVars = record.fields["env_vars"] as? FunkArrayList
                ?: throw NativeFunctionException("__native_mock_env_get", "expects 'env_vars' as ArrayList")
            val name = record.fields["name"] as? FunkString
                ?: throw NativeFunctionException("__native_mock_env_get", "expects 'name' as String")

            for (pair in envVars.elements) {
                val tuple = pair as FunkTuple
                val key = tuple.elements[0] as FunkString
                val value = tuple.elements[1] as FunkString
                if (key.value == name.value) {
                    return@NativeFunkFunction FunkVariant("Some", FunkRecord(mapOf("value" to value)))
                }
            }
            FunkVariant("None", null)
        }
    }
}
