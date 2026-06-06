package com.mezze.language.typechecker.types.effects

import com.mezze.language.ast.MezzeType
import com.mezze.language.typechecker.types.*

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
    fun registerAll(context: MutableMap<String, MezzeType>) = natives(context) {
        // Environment variable operations
        fn("__native_env_get") { param("name" to str); returns(option(str)) }
        fn("__native_env_set") { param("name" to str, "value" to str); returns(unit) }
        fn("__native_env_get_all") {
            returns(seq(rec("k" to str, "v" to str)))
        }

        // Directory operations
        fn("__native_env_current_dir") { returns(str) }
        fn("__native_env_change_dir") { param("path" to str); returns(unit) }

        // System information
        fn("__native_env_os_name") { returns(str) }
        fn("__native_env_os_version") { returns(str) }
        fn("__native_env_arch") { returns(str) }

        // Process information
        fn("__native_env_process_id") { returns(int) }
        fn("__native_env_process_args") { returns(seq(str)) }

        // Process control
        fn("__native_env_exit") { param("code" to int); returns(unit) }

        // Mock environment
        fn("__native_mock_env_get") {
            param(
                "env_vars" to seq(rec("k" to str, "v" to str)),
                "name" to str
            )
            returns(option(str))
        }
    }
}
